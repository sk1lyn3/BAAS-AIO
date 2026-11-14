"""Resolve and validate one exported Android OCR runtime for the APK build."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys


def resolve(directory):
    root = Path(directory).resolve()
    bundle = json.loads((root / "runtime.json").read_text())
    if bundle["format"] != 1 or bundle["abi"] != "arm64-v8a":
        raise ValueError("Unsupported OCR runtime format or ABI")

    def member(path):
        result = (root / path).resolve()
        if not result.is_relative_to(root):
            raise ValueError(f"OCR runtime file must be inside the bundle: {path}")
        return result

    prebuild = member(bundle["prebuild"]["path"])
    revision = bundle["prebuild"]["revision"]
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("OCR runtime must pin a full prebuild commit")

    def snapshot(name):
        return subprocess.check_output(["git", "-C", str(prebuild), "show", f"{revision}:{name}"], text=True)

    source = json.loads(snapshot("source.json"))
    if source["commit"] != bundle["sourceCommit"]:
        raise ValueError("OCR source revision does not match the prebuild")
    files = {}
    for name in ("wheel", "reference"):
        path = member(bundle[name]["path"])
        if hashlib.sha256(path.read_bytes()).hexdigest() != bundle[name]["sha256"]:
            raise ValueError(f"OCR {name} checksum mismatch: {path.name}")
        files[name] = str(path)
    languages = json.loads(snapshot("resource/static.json"))["ocr_model_name"]
    cases = json.loads(Path(files["reference"]).read_text())["cases"]
    if not cases or any(case["language"] not in languages for case in cases):
        raise ValueError("OCR reference cases do not match the packaged languages")
    return {"prebuild": str(prebuild), "revision": revision, **files,
            "sourceCommit": source["commit"], "python": source["python"], "numpy": source["numpy"]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory")
    parser.add_argument("--revision", action="store_true")
    args = parser.parse_args()
    try:
        runtime = resolve(args.directory)
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(f"Invalid OCR runtime: {error}")
    print(runtime["revision"] if args.revision else json.dumps(runtime))
