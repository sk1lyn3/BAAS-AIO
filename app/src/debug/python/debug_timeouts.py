"""Debug-only managed task limit, editable with adb run-as without rebuilding."""
from pathlib import Path


def managed_task_timeout(files_dir):
    path = Path(files_dir) / "debug-managed-task-timeout-seconds"
    seconds = int(path.read_text().strip()) if path.exists() else 600
    if not 0 <= seconds <= 86400:
        raise ValueError("Debug managed task timeout must be 0..86400 seconds")
    return seconds
