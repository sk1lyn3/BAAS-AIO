"""Build a signed BAAS core package and channel index that AIO installs without a new APK.

The package holds the same baas-source.zip content as the APK (under source/), the generated JSON
and the settings catalog that Kotlin reads (under data/), bundle-manifest.json with every file's sha256, and an ECDSA P-256
signature of that manifest. The channel index carries the zip's sha256 and is signed with the same key.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
import prepare_baas_core  # noqa: E402

FORMAT = 1
DATA_FILES = ("profile-defaults.json", "static-config.json", "activities-info.json")
CATALOG = Path(__file__).resolve().parents[1] / "app/src/main/assets/config-catalog.json"


def check_catalog(catalog, defaults, static):
    """Fail the build on what the app rejects at install: catalog keys without a default,
    defaults outside their field's options, missing shop tables."""
    config = defaults["config"]
    for group in catalog["groups"]:
        for field in group["fields"]:
            key = field["key"]
            if key not in config:
                raise SystemExit(f"config-catalog.json: {key} has no default in this core")
            values = {str(option["value"]) for option in field.get("options") or []}
            default = config[key]
            items = [i for row in default for i in (row if isinstance(row, list) else [row])] \
                if isinstance(default, list) and field.get("kind") in ("teams", "slots") else [default]
            if values and not all(str(i) in values for i in items):
                raise SystemExit(f"config-catalog.json: default of {key} is not one of its options")
            if field.get("goods") and not all(m in static.get(field["goods"], {}) for m in ("CN", "Global", "JP")):
                raise SystemExit(f"config-catalog.json: {field['goods']} is missing from static data")


def sign(key, payload):
    with tempfile.NamedTemporaryFile() as body:
        body.write(payload); body.flush()
        return subprocess.check_output(["openssl", "dgst", "-sha256", "-sign", str(key), body.name])


def build(baas, revision, notes, key, output, min_version_code, max_version_code):
    with tempfile.TemporaryDirectory() as work:
        assets = Path(work) / "assets"
        sys.argv = ["prepare_baas_core.py", "--baas", str(baas), "--revision", revision,
                    "--output", str(assets), "--debug-output", str(Path(work) / "debug")]
        prepare_baas_core.main()
        entries = {}
        with zipfile.ZipFile(assets / "baas-source.zip") as source:
            for name in source.namelist():
                entries["source/" + name] = source.read(name)
        for name in DATA_FILES:
            entries["data/" + name] = (assets / name).read_bytes()
        entries["data/config-catalog.json"] = CATALOG.read_bytes()
        check_catalog(json.loads(CATALOG.read_text(encoding="utf-8")), json.loads(entries["data/profile-defaults.json"]),
                      json.loads(entries["data/static-config.json"]))
    source_manifest = json.loads(entries["source/source-manifest.json"])
    manifest = {
        "format": FORMAT, "coreApi": source_manifest["coreApi"],
        "core": {"commit": source_manifest["head"], "commitTime": source_manifest["commitTime"]},
        "minVersionCode": min_version_code, "maxVersionCode": max_version_code,
        "files": {name: hashlib.sha256(body).hexdigest() for name, body in sorted(entries.items())},
    }
    manifest_bytes = json.dumps(manifest, ensure_ascii=False, indent=2).encode()
    output.mkdir(parents=True, exist_ok=True)
    name = f"core-{source_manifest['head'][:12]}.zip"
    with zipfile.ZipFile(output / name, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("bundle-manifest.json", manifest_bytes)
        archive.writestr("bundle-manifest.json.sig", sign(key, manifest_bytes))
        for entry, body in sorted(entries.items()):
            archive.writestr(entry, body)
    body = (output / name).read_bytes()
    return {"coreApi": manifest["coreApi"], "commit": source_manifest["head"],
            "commitTime": source_manifest["commitTime"], "file": name,
            "zipSha256": hashlib.sha256(body).hexdigest(), "size": len(body), "notes": notes,
            "minVersionCode": min_version_code, "maxVersionCode": max_version_code}


def write_channel(output, channel, package, key, url_base):
    """Keep the newest package per API/version range, retaining a choice for older APKs."""
    path = output / f"{channel}.json"
    packages = json.loads(path.read_text())["packages"] if path.exists() else []
    entry = dict(package, url=url_base.rstrip("/") + "/" + package.pop("file"))
    def compatibility(item):
        return item["coreApi"], item.get("minVersionCode") or 0, item.get("maxVersionCode")
    packages = [p for p in packages if compatibility(p) != compatibility(entry)] + [entry]
    body = json.dumps({"format": FORMAT, "packages": sorted(packages, key=lambda p: (p["coreApi"], p.get("minVersionCode") or 0))},
                      ensure_ascii=False, indent=2).encode()
    path.write_bytes(body)
    (output / f"{channel}.json.sig").write_bytes(sign(key, body))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--baas", type=Path, required=True)
    parser.add_argument("--revision", required=True, help="Core commit to package")
    parser.add_argument("--channel", default="stable", choices=("stable", "dev"))
    parser.add_argument("--notes", default="")
    parser.add_argument("--key", type=Path, required=True, help="ECDSA P-256 private key (PEM)")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--url-base", required=True, help="Download URL prefix of the package asset")
    parser.add_argument("--min-version-code", type=int, help="Only set when the core requires an app fix within the same coreApi")
    parser.add_argument("--max-version-code", type=int)
    args = parser.parse_args()
    package = build(args.baas, args.revision, args.notes, args.key, args.output,
                    args.min_version_code, args.max_version_code)
    print(json.dumps(package, indent=2))
    write_channel(args.output, args.channel, package, args.key, args.url_base)


if __name__ == "__main__":
    main()
