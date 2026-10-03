"""Snapshot the actual BAAS checkout for the Android runtime probe.

Usage: python3 tools/prepare_probe.py --baas ../blue_archive_auto_script --fixture IMAGE
Outputs are generated build assets, never a second maintained BAAS codebase.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--baas", type=Path, required=True)
    parser.add_argument("--fixture", type=Path, required=True)
    args = parser.parse_args()
    source = args.baas.resolve()
    output = Path(__file__).resolve().parents[1] / "app/build/generated/probe-assets"
    output.mkdir(parents=True, exist_ok=True)
    paths = [p for root in ("core", "module", "gui")
             for p in (source / root).rglob("*.py") if "__pycache__" not in p.parts]
    paths += [p for p in (source / "src/images/JP").rglob("*") if p.suffix in (".py", ".png")]
    paths += [source / "config/static.json", source / "src/rgb_feature/JP.json",
              source / "src/images/JP/mail/menu.png", source / "src/images/JP/shop/list-type-name-feature.png"]
    manifest = {"head": subprocess.check_output(
        ["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip(),
        "dirty": subprocess.check_output(["git", "-C", str(source), "status", "--porcelain"], text=True),
        "files": {}}
    with zipfile.ZipFile(output / "baas-source.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(set(paths)):
            name = path.relative_to(source).as_posix()
            data = path.read_bytes()
            archive.writestr(name, data)
            manifest["files"][name] = hashlib.sha256(data).hexdigest()
        fixture = args.fixture.read_bytes()
        archive.writestr("fixtures/shop.png", fixture)
        manifest["fixture_sha256"] = hashlib.sha256(fixture).hexdigest()
        archive.writestr("source-manifest.json", json.dumps(manifest, indent=2))
    print(f"Prepared {len(paths)} source/resource files from {manifest['head']}")


if __name__ == "__main__":
    main()
