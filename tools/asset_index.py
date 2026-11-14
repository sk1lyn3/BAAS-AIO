"""Small APK-side indexes for reusing extracted bundles without retaining their ZIPs."""
import hashlib
import json
from pathlib import Path
import zipfile


def write_asset_index(path, manifest_name):
    path = Path(path)
    with zipfile.ZipFile(path) as archive:
        hashes = json.loads(archive.read(manifest_name))["files"]
        entries = [[entry.filename, entry.file_size,
                    hashes.get(entry.filename) or hashlib.sha256(archive.read(entry)).hexdigest()]
                   for entry in archive.infolist() if not entry.is_dir()]
    path.with_suffix(".index.json").write_text(json.dumps(entries, separators=(",", ":")))
