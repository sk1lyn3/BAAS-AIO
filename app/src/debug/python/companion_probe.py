"""On-device input/capture verification against Companion's real debug Activity."""
import json
from pathlib import Path
import threading
import time
import traceback
from types import SimpleNamespace

import cv2

from companion_device import CompanionDevice, CompanionCommandException


def run(bridge, files_dir):
    report = {"broker": str(bridge.brokerInfo()), "transport": "Shizuku FD + local Unix socket",
              "desktop_required": False, "checks": {}}
    device = CompanionDevice(bridge)
    owned = False
    try:
        state = device.command("status")["state"]
        if state["displayId"] == -1:
            device.display_id = device.command("start", width=1280, height=720, dpi=240)["displayId"]
            owned = True
        else:
            assert state["alive"] and (state["width"], state["height"]) == (1280, 720)
        report["displayId"] = device.display_id
        report["created_display"] = owned
        # A valid negative reply must leave the framed connection usable.
        try:
            device.command("aio_probe_invalid_command")
            raise AssertionError("Unknown command was accepted")
        except CompanionCommandException as error:
            assert "Unknown cmd: aio_probe_invalid_command" in str(error.getMessage()), error
        assert device.command("status")["displayId"] == device.display_id
        report["checks"]["command_error_preserves_connection"] = {"ok": True}
        device._input("launch", package="moe.baas.aio", activity="moe.baas.aio.ProbeActivity")
        deadline = time.monotonic() + 10
        while True:
            image = device.screenshot()
            if tuple(int(v) for v in image[400, 1000]) == (64, 40, 24):
                break
            assert time.monotonic() < deadline, "Probe did not render"
            time.sleep(0.1)

        # Exercise the original BAAS click dispatcher and actual screenshot adapter.
        from core.Baas_thread import Baas_thread
        from core.config.config_set import ConfigSet
        from core import color
        from runtime_probe import RawConfigValues
        config = ConfigSet(str(Path(files_dir) / "baas-probe/config/Probe"))
        engine = Baas_thread(config, logger_signal=SimpleNamespace(emit=lambda *args: None))
        engine.config = config.config
        engine.control, engine.screenshot = device, device
        engine.flag_run, engine.is_android_device = True, True
        engine.ratio, engine.resolution = 1, (1280, 720)
        engine.click(640, 360)
        deadline = time.monotonic() + 3
        while True:
            actual = device.probe_state()
            if actual["displayId"] == device.display_id and actual["downs"] == actual["ups"] == 1:
                break
            assert time.monotonic() < deadline, actual
            time.sleep(0.05)
        engine.update_screenshot_array()
        assert color.rgb_in_range(engine, 1000, 400, 24, 24, 40, 40, 64, 64)
        report["checks"]["baas_click_and_capture"] = {"ok": True, "events": actual}

        errors = []
        entered, finished = threading.Event(), threading.Event()
        def drag():
            try:
                entered.set()
                engine.swipe(640, 600, 640, 220, 2)
            except Exception:
                errors.append(traceback.format_exc())
            finally:
                finished.set()
        worker = threading.Thread(target=drag)
        worker.start()
        entered.wait()
        frames_during_drag = 0
        durations = []
        frame_ids = []
        while not finished.is_set():
            started = time.monotonic()
            device.screenshot()
            durations.append(time.monotonic() - started)
            if not finished.is_set():
                frames_during_drag += 1
                frame_ids.append(device.last_metadata["frameId"])
        worker.join()
        assert not errors, errors
        actual = device.probe_state()
        assert frames_during_drag >= 2, frames_during_drag
        assert len(set(frame_ids)) >= 2, frame_ids
        assert actual["cancels"] == 0 and actual["downs"] == actual["ups"] == 2, actual
        assert 1950 <= actual["gestureDurationMs"] <= 2200, actual
        assert actual["moves"] >= 80, actual
        report["checks"]["independent_capture_during_drag"] = {
            "ok": True, "frames": frames_during_drag, "frame_ids": frame_ids,
            "capture_seconds": durations, "events": actual}

        device.pinch(640, 360, 300, 50)
        actual = device.probe_state()
        assert actual["pointerDowns"] == actual["pointerUps"] == 1 and actual["maxPointers"] == 2, actual
        assert actual["cancels"] == 0, actual
        # Companion's start/end spread is the radius on each side of the center.
        assert abs(actual["firstSeparation"] - 600) <= 1 and abs(actual["lastSeparation"] - 100) <= 1, actual
        report["checks"]["pinch"] = {"ok": True, "events": actual}
        device.key(62)
        actual = device.probe_state()
        assert actual["lastKey"] == 62 and actual["keys"] == 1, actual
        report["checks"]["key"] = {"ok": True, "events": actual}
        engine.swipe(640, 360, 640, 350)
        actual = device.probe_state()
        assert actual["downs"] == actual["ups"] == 4 and actual["cancels"] == 0, actual
        assert 300 <= actual["gestureDurationMs"] <= 550, actual
        report["checks"]["baas_default_swipe"] = {"ok": True, "events": actual}
        device.long_click(640, 360, 0.4)
        actual = device.probe_state()
        assert actual["downs"] == actual["ups"] == 5 and actual["cancels"] == 0, actual
        assert 400 <= actual["gestureDurationMs"] <= 650, actual
        report["checks"]["long_click"] = {"ok": True, "events": actual}
        image = device.screenshot()
        assert device.last_metadata["timestampNs"] > device.completed_ns
        cv2.imwrite(str(Path(files_dir) / "companion-frame.png"), image)
        report["frame"] = device.last_metadata
        report["last_input_completed_ns"] = device.completed_ns
        report["ok"] = True
    except Exception:
        report["ok"] = False
        report["error"] = traceback.format_exc()
    finally:
        if owned:
            try:
                device.command("stop")
                report["cleanup"] = device.command("status")["state"]
                assert report["cleanup"]["displayId"] == -1
            except Exception:
                report["ok"] = False
                report["cleanup_error"] = traceback.format_exc()
    return json.dumps(report, ensure_ascii=False, indent=2)
