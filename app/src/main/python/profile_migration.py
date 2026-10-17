"""Add tasks and fields introduced by a newer core to existing event.json files, never removing anything."""
import copy
import json
import os
from pathlib import Path
import sys
import time

CN_SERVERS = ("官服", "B服")


def _lock():
    try:
        from java import jclass
    except ImportError:
        return None
    return jclass("moe.baas.aio.ProfileStore")


def merge_events(events, defaults, server, enable_state="default"):
    """Return a merged copy of events; the input list is not modified."""
    if not isinstance(events, list) or not all(isinstance(e, dict) and isinstance(e.get("func_name"), str) for e in events):
        raise ValueError("Unexpected event.json structure")
    merged = copy.deepcopy(events)
    defaults = copy.deepcopy(defaults)
    if server not in CN_SERVERS:
        for event in defaults:
            for reset in event.get("daily_reset", []):
                reset[0] = (reset[0] + 23) % 24
    present = {e["func_name"]: e for e in merged}
    for index, default in enumerate(defaults):
        current = present.get(default["func_name"])
        if current is None:
            new = dict(default)
            if enable_state in ("on", "off"):
                new["enabled"] = enable_state == "on"
            merged.insert(min(index, len(merged)), new)
            present[new["func_name"]] = new
            continue
        for key, value in default.items():
            current.setdefault(key, value)
    return merged


def _write(path, text):
    partial = path.with_name(path.name + ".partial")
    with open(partial, "w", encoding="utf-8") as stream:
        stream.write(text); stream.flush(); os.fsync(stream.fileno())
    os.replace(partial, path)


def merge_profile(directory, defaults):
    directory = Path(directory)
    event_path = directory / "event.json"
    original = event_path.read_text(encoding="utf-8")
    config = json.loads((directory / "config.json").read_text(encoding="utf-8"))
    server = config.get("server", "日服")
    events = json.loads(original)
    merged = merge_events(events, defaults, server, config.get("new_event_enable_state", "default"))
    if merged == events:
        return
    backups = directory / ".core-backup"
    backups.mkdir(exist_ok=True)
    _write(backups / f"event-{int(time.time())}.json", original)
    for old in sorted(backups.glob("event-*.json"))[:-2]:
        old.unlink()
    _write(event_path, json.dumps(merged, ensure_ascii=False, indent=2))


def merge_profiles(profiles_dir, defaults):
    store = _lock()
    if store is not None:
        store.lockEvents()
    try:
        for event_path in sorted(Path(profiles_dir).glob("*/event.json")):
            try:
                merge_profile(event_path.parent, defaults)
            except Exception as error:
                print("Event migration skipped", event_path.parent.name, f"{type(error).__name__}: {error}", file=sys.stderr)
    finally:
        if store is not None:
            store.unlockEvents()
