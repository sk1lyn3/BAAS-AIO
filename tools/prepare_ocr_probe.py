"""Stage existing BAAS Android OCR binaries/models without changing its checkout."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import shutil
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--prebuild", type=Path, required=True)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--reference", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    revision = subprocess.check_output(
        ["git", "-C", str(args.prebuild), "rev-parse", "origin/android-arm64-v8a"], text=True).strip()
    paths = subprocess.check_output(
        ["git", "-C", str(args.prebuild), "ls-tree", "-r", "--name-only", revision], text=True).splitlines()
    output = root / "app/build/generated"
    libs = output / "ocr-jni/arm64-v8a"
    assets = output / "probe-assets"
    libs.mkdir(parents=True, exist_ok=True)
    assets.mkdir(parents=True, exist_ok=True)
    if args.reference.exists():
        shutil.copyfile(args.reference, assets / "ocr-reference.json")
    else:
        (assets / "ocr-reference.json").write_text("{}")
    manifest = {"revision": revision, "files": {}}
    with zipfile.ZipFile(assets / "ocr-resources.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        for name in paths:
            if not name.startswith(("resource/", "lib/arm64-v8a/")):
                continue
            data = subprocess.check_output(["git", "-C", str(args.prebuild), "show", f"{revision}:{name}"])
            manifest["files"][name] = hashlib.sha256(data).hexdigest()
            if name.startswith("lib/"):
                (libs / Path(name).name).write_bytes(data)
            else:
                archive.writestr(name, data)
        archive.writestr("manifest.json", json.dumps(manifest, indent=2))
    compiler = args.ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang++"
    subprocess.run([str(compiler), "-std=c++17", "-shared", "-fPIC", "-Os", "-Wl,--build-id=sha1",
                    str(root / "app/src/main/cpp/ocr_bridge.cpp"), "-ldl", "-o",
                    str(libs / "libbaas_aio_ocr.so")], check=True)
    print(f"Staged existing BAAS Android OCR {revision}")


if __name__ == "__main__":
    main()
