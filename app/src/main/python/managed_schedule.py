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
        or batch.get("failureCause") or "Managed profile failed")[-6000:]


def failure_context(batch):
    tasks = batch.get("tasks", [])
    logs = batch.get("failureLog") or batch.get("workflowLog") or batch.get("log", [])
    errors = [i for i, e in enumerate(logs) if e.get("level", 0) >= 3]
    if errors and not batch.get("failureLog"):
        tracebacks = [i for i in errors if str(logs[i].get("message", "")).lstrip().startswith("Traceback")]
        logs = logs[:tracebacks[-1] if tracebacks else errors[0]]
    # Keep ordinary workflow context; detailed diagnostics remain in Debug logs.
    lines = []
    for e in logs:
        message = str(e.get("message", "")).strip()
        if not (1 <= e.get("level", 0) < 3) or not message or message.startswith(("Traceback", "File ")):
            continue
        stamp = datetime.fromtimestamp(e["time"]).strftime("%H:%M:%S") if e.get("time") else ""
        count = e.get("count", 1)
        if count > 1 and e.get("lastTime"):
            stamp += "–" + datetime.fromtimestamp(e["lastTime"]).strftime("%H:%M:%S")
        lines.append((f"[{stamp}] " if stamp else "") + message[:500] + (f" ×{count}" if count > 1 else ""))
    context = {"task": tasks[-1].get("task", "") if tasks else "",
               "phase": "restoring" if batch.get("failurePhase") == "restoring" else ("executing" if tasks else "initializing"),
               "context": "\n".join(lines[-10:])[-3000:]}
    for key in ("startedAt", "failedAt", "elapsedSeconds"):
        if key in batch:
            context[key] = batch[key]
    if tasks:
        for source, target in (("startedAt", "taskStartedAt"), ("elapsedSeconds", "taskElapsedSeconds")):
            if source in tasks[-1]:
                context[target] = tasks[-1][source]
    context["frame"] = batch.get("frame", {"status": "not_captured"})
    return context


def defer_profile(mode, path, reason, token, context=None):
    previous = json.loads(str(mode.profileRecovery(path)))
    failures = previous.get("failures", 0) + 1
    state = {"failures": failures, "retryAt": time.time() + RETRY_DELAY_SECONDS if failures == 1 else None,
             "error": reason}
    state.update(context or {"task": "", "phase": "planning", "context": ""})
    state.setdefault("frame", {"status": "not_captured"})
    if not mode.saveRecovery(path, json.dumps(state, ensure_ascii=False), token):
        return None  # Stopped, deselected, or a newer managed session now owns the state.
    return state


def next_allowed(event, timestamp, scheduler):
    """Move a wake-up past BAAS disabled periods, including overlaps and midnight."""
    periods = event["disabled_time_range"]
    for _ in range(max(1, len(periods)) * 8 + 1):
        date = datetime.fromtimestamp(timestamp)
        seconds = scheduler.convert_to_seconds(date.hour, date.minute, date.second)
        ends = []
        for start_hms, end_hms in periods:
            start = scheduler.convert_to_seconds(*start_hms)
            end = scheduler.convert_to_seconds(*end_hms)
            if start > end:
                if seconds >= start:
                    ends.append(end + 86400)
                elif seconds <= end:
                    ends.append(end)
            elif start <= seconds <= end:
                ends.append(end)
        if not ends:
            return timestamp
        midnight = date.replace(hour=0, minute=0, second=0, microsecond=0)
        timestamp = (midnight + timedelta(seconds=max(ends) + 1)).timestamp()
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


def prepare_device(bridge, gate, mode):
    bridge.reconnect()
    if mode.shouldWakeBeforeRun():
        was_asleep = not mode.deviceInteractive()
        if not mode.deviceReady():
            gate.log(1, "Wake/unlock requested by the global managed setting")
            bridge.command('{"cmd":"wake"}')
            deadline = time.monotonic() + 5
            while not mode.deviceReady():
                if time.monotonic() >= deadline:
                    raise RuntimeError("Automatic unlock failed; unlock the device or disable wake-before-managed")
                if not gate.waitForNext(100):
                    return False
        if was_asleep:
            bridge.beginManagedScreen()
    # The default path uses the unlocked virtual display without changing the physical screen.
    gate.log(0, "Preparing virtual display; wakeBeforeRun=" + str(mode.shouldWakeBeforeRun()) +
             "; " + str(bridge.debugState(False)))
    return gate.checkpoint("Managed: preparing virtual display")


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
            planning_started = time.time()
            planning_clock = time.monotonic()
            try:
                item = plan(files_dir, [path])[0]
            except Exception as error:
                failed_at = time.time()
                state = defer_profile(mode, path, str(error), token,
                                      {"task": "", "phase": "planning", "context": "",
                                       "startedAt": planning_started, "failedAt": failed_at,
                                       "elapsedSeconds": time.monotonic() - planning_clock})
                if state is not None:
                    report["profileFailures"].append({"profile": path, **state})
                continue
            gate.log(0, "Managed schedule: " + json.dumps(item, ensure_ascii=False))
            if not item["due"]:
                continue
            report.pop("failureContext", None)
            profile_started = time.time()
            profile_clock = time.monotonic()
            gate.log(1, "Managed profile: " + Path(item["profile"]).name)
            if not prepare_device(bridge, gate, mode):
                report["state"] = "stopped"; break
            mode.setExecuting(True)
            from core_task import run as run_core
            preparation_elapsed = time.monotonic() - profile_clock
            batch = json.loads(run_core(files_dir, bridge, native, gate, 0, "core_once", item["profile"]))
            batch["startedAt"] = profile_started
            if "elapsedSeconds" in batch:
                batch["elapsedSeconds"] += preparation_elapsed
            gate.log(0, "Managed profile finished: " + path + " state=" + batch["state"])
            report["profiles"].append(batch)
            report["tasks"].extend(batch.get("tasks", []))
            mode.setExecuting(False)
            if batch["state"] == "stopped" or not mode.isEnabled() or mode.sessionToken() != token:
                report["state"] = "stopped"
                break
            if batch["state"] == "failed":
                report["failureContext"] = failure_context(batch)
            if batch.get("cleanupErrors"):
                raise RuntimeError("Managed resource cleanup failed: " + "\n".join(batch["cleanupErrors"]))
            if batch["state"] == "completed":
                mode.saveRecovery(path, "{}", token)
            else:
                # Verify shared device access before treating an engine failure as profile-local.
                bridge.reconnect()
                bridge.command('{"cmd":"ping"}')
                reason = failure_reason(batch)
                state = defer_profile(mode, path, reason, token, failure_context(batch))
                if state is None and batch.get("frame", {}).get("file"):
                    try:
                        (Path(files_dir) / "managed-failure-frames" / batch["frame"]["file"]).unlink(missing_ok=True)
                    except OSError:
                        pass
                if state is not None:
                    report["profileFailures"].append({"profile": path, **state})
                    gate.log(2, "Managed profile " + Path(path).name +
                             (" suspended after retry" if state["failures"] >= 2 else " deferred for 5 minutes") +
                             ": " + reason)
                report.pop("failureContext", None)
        # Optional one-shot requests use the same worker, after all currently due tasks.
        while report["state"] == "completed" and mode.isEnabled() and mode.sessionToken() == token:
            path = mode.nextResourceRefresh()
            if path is None:
                break
            if any(p["due"] for p in plan(files_dir, json.loads(str(mode.selectedPaths())))):
                break  # Replan due work first; finish() keeps the pending request scheduled.
            path = str(path)
            if not gate.checkpoint("Managed: refreshing resources") or not prepare_device(bridge, gate, mode):
                report["state"] = "stopped"; break
            mode.setExecuting(True)
            try:
                from core_task import run as run_core
                batch = json.loads(run_core(files_dir, bridge, native, gate, 0, "core_once", path,
                                            "refresh_resources"))
                report["profiles"].append(batch)
                report["tasks"].extend(batch.get("tasks", []))
                if batch.get("cleanupErrors"):
                    raise RuntimeError("Resource refresh cleanup failed: " + "\n".join(batch["cleanupErrors"]))
                if batch["state"] == "stopped":
                    report["state"] = "stopped"
                elif batch["state"] != "completed":
                    gate.log(2, "Resource refresh failed: " + failure_reason(batch))
            finally:
                mode.finishResourceRefresh(path, token)
                mode.setExecuting(False)
        if not mode.isEnabled() or mode.sessionToken() != token:
            report["state"] = "stopped"
        if report["state"] == "completed":
            plans = plan(files_dir, json.loads(str(mode.selectedPaths())))
            next_plan = min((p for p in plans if p["nextWakeAt"] is not None),
                            key=lambda p: p["nextWakeAt"], default=None)
            report["nextWakeAt"] = next_plan["nextWakeAt"] if next_plan else None
            report["nextProfile"] = next_plan["profile"] if next_plan else ""
    except Exception:
        import traceback
        report.update(state="failed", error=traceback.format_exc())
    finally:
        gate.log(0, "Managed batch finished: " + report["state"] + " nextWakeAt=" + str(report.get("nextWakeAt")))
        mode.setExecuting(False)
    return json.dumps(report, ensure_ascii=False)
