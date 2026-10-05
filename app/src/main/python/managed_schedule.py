"""Run selected profiles in finite BAAS batches, without a second task scheduler."""
import json
import time
from datetime import datetime, timedelta
from pathlib import Path

from java import jclass


def next_allowed(event, timestamp, scheduler):
    """Move a wake-up past BAAS disabled periods, including overlapping ranges."""
    periods = event["disabled_time_range"]
    for _ in range(max(1, len(periods)) * 8 + 1):
        date = datetime.fromtimestamp(timestamp)
        seconds = scheduler.convert_to_seconds(date.hour, date.minute, date.second)
        blocked = [p for p in periods if scheduler.convert_to_seconds(*p[0]) <= seconds <= scheduler.convert_to_seconds(*p[1])]
        if not blocked:
            return timestamp
        end = max(scheduler.convert_to_seconds(*p[1]) for p in blocked)
        midnight = date.replace(hour=0, minute=0, second=0, microsecond=0)
        timestamp = (midnight + timedelta(seconds=end + 1)).timestamp()
    return None  # Entire days can be disabled: do not spin or wake the game.


def plan(files_dir, paths):
    from core_loader import prepare
    prepare(files_dir)
    from core.scheduler import Scheduler
    store = jclass("moe.baas.aio.ProfileStore")
    now = time.time()
    result = []
    store.lockEvents()
    try:
        for path in paths:
            scheduler = Scheduler(None, path)
            scheduler.update_valid_task_queue()
            ticks = [next_allowed(e, max(now, e["next_tick"]), scheduler)
                     for e in scheduler._event_config if e["enabled"]]
            ticks = [t for t in ticks if t is not None]
            result.append({"profile": path, "due": bool(scheduler._valid_task_queue),
                           "nextWakeAt": min(ticks, default=None)})
    finally:
        store.unlockEvents()
    return result


def run(files_dir, bridge, native, gate, *_args):
    mode = jclass("moe.baas.aio.ManagedMode")
    report = {"state": "completed", "profiles": [], "tasks": []}
    try:
        from platform_bindings import install
        install()
        paths = json.loads(str(mode.selectedPaths()))
        gate.log(0, "Managed batch profiles: " + json.dumps(paths, ensure_ascii=False))
        for path in paths:
            item = plan(files_dir, [path])[0]
            gate.log(0, "Managed schedule: " + json.dumps(item, ensure_ascii=False))
            if not mode.isEnabled() or not gate.checkpoint("Managed: checking profiles"):
                report["state"] = "stopped"; break
            if item["profile"] not in json.loads(str(mode.selectedPaths())) or not item["due"]:
                continue
            gate.log(1, "Managed profile: " + Path(item["profile"]).name)
            bridge.reconnect()
            was_asleep = not mode.deviceInteractive()
            if not mode.deviceReady():
                gate.log(0, "Wake/unlock requested; " + str(bridge.debugState(False)))
                wake = json.loads(str(bridge.command('{"cmd":"wake"}')))
                if not wake.get("ok"):
                    raise RuntimeError(wake.get("error", "Cannot wake device"))
                deadline = time.monotonic() + 5
                while not mode.deviceReady():
                    if time.monotonic() >= deadline:
                        raise RuntimeError("Device is still locked; unlock it before managed execution")
                    if not gate.waitForNext(100):
                        report["state"] = "stopped"; break
                if report["state"] == "stopped": break
            if was_asleep:
                bridge.beginManagedScreen()
            gate.log(0, "Wake/unlock ready; " + str(bridge.debugState(False)))
            mode.setExecuting(True)
            from core_task import run as run_core
            batch = json.loads(run_core(files_dir, bridge, native, gate, 0, "core_once", item["profile"]))
            gate.log(0, "Managed profile finished: " + path + " state=" + batch["state"])
            report["profiles"].append(batch)
            report["tasks"].extend(batch.get("tasks", []))
            if batch["state"] != "completed":
                report.update(state=batch["state"], error=batch.get("error") or "\n".join(e["message"] for e in batch.get("log", []) if e.get("level", 0) >= 3)[-6000:] or "Managed profile failed")
                break
        if report["state"] == "completed":
            plans = plan(files_dir, json.loads(str(mode.selectedPaths())))
            report["nextWakeAt"] = min((p["nextWakeAt"] for p in plans if p["nextWakeAt"] is not None), default=None)
    except Exception:
        import traceback
        report.update(state="failed", error=traceback.format_exc())
    finally:
        gate.log(0, "Managed batch finished: " + report["state"] + " nextWakeAt=" + str(report.get("nextWakeAt")))
        mode.setExecuting(False)
    return json.dumps(report, ensure_ascii=False)
