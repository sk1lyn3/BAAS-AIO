"""Load the build snapshot of BAAS without running diagnostic workflows."""
import hashlib
import json
import os
import shutil
from pathlib import Path
import sys
import zipfile


def prepare(files_dir):
    root = Path(files_dir) / "baas-core"
    with zipfile.ZipFile(Path(files_dir) / "baas-source.zip") as archive:
        manifest = json.loads(archive.read("source-manifest.json"))
        marker = root / "source-manifest.json"
        if not marker.exists() or json.loads(marker.read_text()) != manifest:
            staging = root.with_name("baas-core.staging")
            if staging.exists():
                shutil.rmtree(staging)
            try:
                archive.extractall(staging)
                for name, digest in manifest["files"].items():
                    if hashlib.sha256((staging / name).read_bytes()).hexdigest() != digest:
                        raise RuntimeError("BAAS source verification failed: " + name)
                if root.exists():
                    shutil.rmtree(root)
                staging.rename(root)
            finally:
                if staging.exists():
                    shutil.rmtree(staging)
    if str(root) not in sys.path:
        sys.path.insert(0, str(root))
    os.chdir(root)
    return manifest
