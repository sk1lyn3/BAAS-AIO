"""Run selected profiles in finite BAAS batches, without a second task scheduler."""
import json
import time
from datetime import datetime, timedelta
from pathlib import Path

from java import jclass

RETRY_DELAY_SECONDS = 300


def failure_reason(batch):
    return (batch.get("error") or "\n".join(
        e["message"] for e in batch.get("log", []) if e.get("level", 0) >= 3)
        or "Managed profile failed")[-6000:]


def failure_context(batch):
    tasks = batch.get("tasks", [])
    logs = batch.get("log", [])
    errors = [i for i, e in enumerate(logs) if e.get("level", 0) >= 3]
    if errors:
        tracebacks = [i for i in errors if str(logs[i].get("message", "")).lstrip().startswith("Traceback")]
        logs = logs[:tracebacks[-1] if tracebacks else errors[0]]
    # Keep ordinary workflow context; detailed diagnostics remain in Debug logs.
    lines = [str(e.get("message", "")).strip()[:500] for e in logs
             if 1 <= e.get("level", 0) < 3 and str(e.get("message", "")).strip()
             and not str(e.get("message", "")).lstrip().startswith(("Traceback", "File "))]
    return {"task": tasks[-1].get("task", "") if tasks else "",
            "phase": "restoring" if batch.get("failurePhase") == "restoring" else ("executing" if tasks else "initializing"),
            "context": "\n".join(lines[-10:])[-3000:]}


def defer_profile(mode, path, reason, token, context=None):
    previous = json.loads(str(mode.profileRecovery(path)))
    failures = previous.get("failures", 0) + 1
    state = {"failures": failures, "retryAt": time.time() + RETRY_DELAY_SECONDS if failures == 1 else None,
             "error": reason}
    state.update(context or {})
    if not mode.saveRecovery(path, json.dumps(state, ensure_ascii=False), token):
        return None  # Stopped, deselected, or a newer managed session now owns the state.
    return state


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
    mode = jclass("moe.baas.aio.ManagedMode")
    now = time.time()
    result = []
    store.lockEvents()
    try:
        for path in paths:
            recovery = json.loads(str(mode.profileRecovery(path)))
            if recovery.get("failures", 0) >= 2:
                result.append({"profile": path, "due": False, "nextWakeAt": None})
                continue
            retry_at = recovery.get("retryAt") or 0
            try:
                scheduler = Scheduler(None, path)
                scheduler.update_valid_task_queue()
            except Exception:
                if retry_at <= now:
                    raise
                result.append({"profile": path, "due": False, "nextWakeAt": retry_at})
                continue
            ticks = [next_allowed(e, max(now, e["next_tick"], retry_at), scheduler)
                     for e in scheduler._event_config if e["enabled"]]
            ticks = [t for t in ticks if t is not None]
            result.append({"profile": path, "due": retry_at <= now and bool(scheduler._valid_task_queue),
                           "nextWakeAt": min(ticks, default=None)})
    finally:
        store.unlockEvents()
    return result


def run(files_dir, bridge, native, gate, *_args):
    mode = jclass("moe.baas.aio.ManagedMode")
    report = {"state": "completed", "profiles": [], "tasks": [], "profileFailures": []}
    token = mode.sessionToken()
    try:
        from platform_bindings import install
        install()
        paths = json.loads(str(mode.selectedPaths()))
        gate.log(0, "Managed batch profiles: " + json.dumps(paths, ensure_ascii=False))
        for path in paths:
            if not mode.isEnabled() or mode.sessionToken() != token or not gate.checkpoint("Managed: checking profiles"):
                report["state"] = "stopped"; break
            if path not in json.loads(str(mode.selectedPaths())):
                continue
            try:
                item = plan(files_dir, [path])[0]
            except Exception as error:
                state = defer_profile(mode, path, str(error), token)
                if state is not None:
                    report["profileFailures"].append({"profile": path, **state})
                continue
            gate.log(0, "Managed schedule: " + json.dumps(item, ensure_ascii=False))
            if not item["due"]:
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
            mode.setExecuting(False)
            if batch["state"] == "stopped" or not mode.isEnabled() or mode.sessionToken() != token:
                report["state"] = "stopped"
                break
            if batch.get("cleanupErrors"):
                raise RuntimeError("Managed resource cleanup failed: " + "\n".join(batch["cleanupErrors"]))
            if batch["state"] == "completed":
                mode.saveRecovery(path, "{}", token)
            else:
                # Verify shared device access before treating an engine failure as profile-local.
                bridge.reconnect()
                bridge.command('{"cmd":"ping"}')
                if not mode.deviceReady():
                    raise RuntimeError("Device is locked or asleep after managed task failure")
                reason = failure_reason(batch)
                state = defer_profile(mode, path, reason, token, failure_context(batch))
                if state is not None:
                    report["profileFailures"].append({"profile": path, **state})
                    gate.log(2, "Managed profile " + Path(path).name +
                             (" suspended after retry" if state["failures"] >= 2 else " deferred for 5 minutes") +
                             ": " + reason)
        if not mode.isEnabled() or mode.sessionToken() != token:
            report["state"] = "stopped"
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
