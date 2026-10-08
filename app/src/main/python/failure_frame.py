"""Keep the last accepted frame without requesting device IO during failure handling."""
from pathlib import Path
from types import SimpleNamespace
from contextlib import nullcontext
from uuid import uuid4


def freeze_failure_frame(device):
    # PNG bytes are immutable; retain them before recovery replaces the cache.
    with getattr(device, "state_lock", nullcontext()):
        return SimpleNamespace(
            last_frame_png=getattr(device, "last_frame_png", None),
            last_frame_time=getattr(device, "last_frame_time", None),
            last_metadata=dict(getattr(device, "last_metadata", None) or {}),
            last_capture_error=getattr(device, "last_capture_error", None))


def save_failure_frame(files_dir, device):
    error = getattr(device, "last_capture_error", None)
    png = getattr(device, "last_frame_png", None)
    frame = {"status": "capture_failed" if error else ("available" if png else "not_captured")}
    if error:
        frame["error"] = error
    if not png:
        return frame
    frame["time"] = device.last_frame_time
    metadata = getattr(device, "last_metadata", None) or {}
    if "ageMs" in metadata:
        frame["ageMs"] = metadata["ageMs"]
    try:
        directory = Path(files_dir) / "managed-failure-frames"
        directory.mkdir(exist_ok=True)
        name = str(uuid4()) + ".png"
        (directory / name).write_bytes(png)
        frame["file"] = name
    except Exception as error:
        # Diagnostics must never replace the original workflow failure.
        frame["saveError"] = str(error)[-1000:]
    return frame
