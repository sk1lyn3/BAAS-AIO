"""Foreground Android host for the original BAAS Scheduler and task modules."""
import json
from pathlib import Path
import time
import traceback

from companion_device import CompanionDevice
from workflow_task import run as run_batch, TaskStopped


def run(files_dir, bridge, native, gate, cycles=0, task="jp_managed"):
    report = {"state": "failed", "mode": task, "batches": [], "idleEntries": 0}
    owned = False
    device = None
    try:
        import runtime_probe
        assert json.loads(runtime_probe.run(files_dir))["ok"]
        from core.scheduler import Scheduler
        from core.config.default_config import DEFAULT_CONFIG
        source = json.loads((Path(files_dir) / "baas-probe/fixtures/daily-config.json").read_text())
        testing = task == "jp_managed_test"
        profile = Path(files_dir) / ("managed-test-JP_Phone" if testing else "daily-JP_Phone")
        profile.mkdir(exist_ok=True)
        if not (profile / "config.json").exists():
            values = {**json.loads(DEFAULT_CONFIG), **source["config"], "push_after_error": False,
                      "push_after_completion": False, "then": "无动作"}
            (profile / "config.json").write_text(json.dumps(values, ensure_ascii=False))
        if testing:
            # Dedicated profile: real mail module, enough idle time to expire socket sessions.
            event = next(e.copy() for e in source["events"] if e["func_name"] == "mail")
            event.update(next_tick=0, interval=65, daily_reset=[], pre_task=[], post_task=[])
            (profile / "event.json").write_text(json.dumps([event], ensure_ascii=False))
        elif not (profile / "event.json").exists():
            (profile / "event.json").write_text(json.dumps(source["events"], ensure_ascii=False))
        scheduler = Scheduler(None, str(profile))
        device = CompanionDevice(bridge)
        status = device.command("status")["state"]
        if not status["alive"]:
            device.display_id = device.command("start", width=1280, height=720, dpi=240)["displayId"]
            owned = True
        report.update(profile=str(profile), displayId=device.display_id, createdDisplay=owned)
        idle = False
        while True:
            if not gate.checkpoint("Managed: checking due tasks"):
                raise TaskStopped()
            due = scheduler.heartbeat()
            if due is not None:
                bridge.reconnect()
                idle = False
                gate.progress(json.dumps({"mode": task, "phase": "executing", "totalBatches": report.get("totalBatches", 0)}))
                batch = json.loads(run_batch(files_dir, bridge, native, gate, task="jp_managed_batch",
                                             managed_profile=str(profile)))
                report["batches"].append(batch)
                if len(report["batches"]) > 20:
                    del report["batches"][:-20]
                report["totalBatches"] = report.get("totalBatches", 0) + 1
                if batch["state"] != "completed":
                    report["state"] = batch["state"]
                    break
                if testing and report["totalBatches"] >= (cycles or 2):
                    report["state"] = "completed"
                    break
                scheduler = Scheduler(None, str(profile))
            if not idle:
                bridge.reconnect()
                if str(bridge.currentPackage()) == "com.YostarJP.BlueArchive":
                    bridge.stopGame("com.YostarJP.BlueArchive")
                bridge.close()
                idle = True
                report["idleEntries"] += 1
            scheduler.update_valid_task_queue()
            enabled = [e for e in scheduler._event_config if e["enabled"]]
            next_tick = min((e["next_tick"] for e in enabled), default=None)
            gate.progress(json.dumps({"mode": task, "phase": "idle", "nextWakeAt": next_tick,
                                      "displayId": device.display_id, "totalBatches": report.get("totalBatches", 0),
                                      "idleEntries": report["idleEntries"], "ocrRunning": False}))
            delay = min(60000, max(1000, round((next_tick - time.time()) * 1000))) if next_tick else 60000
            if not gate.waitForNext(delay):
                raise TaskStopped()
    except TaskStopped:
        report["state"] = "stopped"
    except Exception:
        report["error"] = traceback.format_exc()
    finally:
        try:
            bridge.stopGame("com.YostarJP.BlueArchive")
            if owned:
                bridge.reconnect()
                if device.command("status")["displayId"] == device.display_id:
                    device.command("stop")
                    report["displayReleased"] = True
        except Exception:
            report["cleanupError"] = traceback.format_exc()
            report["state"] = "failed"
    return json.dumps(report, ensure_ascii=False, indent=2)
