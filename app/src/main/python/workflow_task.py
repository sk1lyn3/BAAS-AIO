"""A repeatable on-device task through real BAAS vision, OCR and device adapters."""
import json
from pathlib import Path
import socket
import time
import traceback
from types import SimpleNamespace
import zipfile

import requests

from companion_device import CompanionDevice


class TaskStopped(Exception):
    pass


class RecheckTarget(Exception):
    pass


def run(files_dir, bridge, native, gate, cycles=0):
    report = {"state": "failed", "cycles": [], "target": "WorkflowTargetActivity: real shop fixture plus OCR-visible counter"}
    device = None
    owned_display = False
    ocr_started = False
    generation = gate.generation()

    def checkpoint(phase):
        nonlocal generation
        if not gate.checkpoint(phase):
            raise TaskStopped()
        resumed = gate.generation()
        if resumed != generation:
            # The daemon expires idle sockets after 30 s. Renew before new work;
            # no input command is replayed.
            bridge.reconnect()
            generation = resumed

    class GatedDevice(CompanionDevice):
        input_generation = None

        def _input(self, command, **args):
            checkpoint("Input: " + command)
            if command != "launch" and self.input_generation != generation:
                raise RecheckTarget()
            return super()._input(command, **args)

        def screenshot(self):
            checkpoint("Capture")
            return super().screenshot()

    try:
        checkpoint("Preparing Python runtime")
        import runtime_probe
        runtime_report = json.loads(runtime_probe.run(files_dir))
        assert runtime_report["ok"], runtime_report
        report["source"] = runtime_report["source"]
        checkpoint("Preparing OCR")
        root = Path(files_dir) / "workflow-ocr"
        root.mkdir(exist_ok=True)
        with zipfile.ZipFile(Path(files_dir) / "ocr-resources.zip") as bundle:
            bundle.extractall(root)
        with socket.socket() as temporary:
            temporary.bind(("127.0.0.1", 0))
            port = temporary.getsockname()[1]
        config = json.loads((root / "resource/global_setting.json").read_text())
        config["ocr"]["server"] = {"host": "127.0.0.1", "port": port}
        config["ocr"]["num_thread"] = 2
        (root / "config").mkdir(exist_ok=True)
        (root / "config/global_setting.json").write_text(json.dumps(config))
        report["ocr_service"] = str(native.start(str(root)))
        ocr_started = True
        base = f"http://127.0.0.1:{port}"
        deadline = time.monotonic() + 10
        while True:
            checkpoint("Starting OCR service")
            try:
                requests.post(base + "/test", timeout=1).raise_for_status()
                break
            except requests.RequestException:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(.1)
        from core.ocr.baas_ocr_client.Client import BaasOcrClient
        from core.ocr.ocr import Baas_ocr
        class LocalClient(BaasOcrClient):
            def __init__(self):
                self.config = SimpleNamespace(base_url=base)
        class LocalOcr(Baas_ocr):
            def __init__(self):
                self.client = LocalClient()
                self.logger = SimpleNamespace(info=lambda *args: None, error=lambda *args: None)
        ocr = LocalOcr()
        ocr.init_model(["en-us"], num_thread=2)
        device = GatedDevice(bridge)
        state = device.command("status")["state"]
        if not state["alive"]:
            device.display_id = device.command("start", width=1280, height=720, dpi=240)["displayId"]
            owned_display = True
        else:
            assert (state["width"], state["height"]) == (1280, 720), state
        report["displayId"] = device.display_id
        report["created_display"] = owned_display
        device._input("launch", package="moe.baas.aio", activity="moe.baas.aio.WorkflowTargetActivity")
        from core.Baas_thread import Baas_thread
        from core.config.config_set import ConfigSet
        from core import image as baas_image
        from runtime_probe import RawConfigValues
        engine = Baas_thread(ConfigSet(str(Path(files_dir) / "baas-probe/config/Probe"), translator=RawConfigValues()),
                             logger_signal=SimpleNamespace(emit=lambda *args: None))
        engine.config = engine.config_set.config
        engine.control, engine.screenshot, engine.ocr = device, device, ocr
        engine.flag_run, engine.is_android_device = True, True
        engine.ratio, engine.resolution, engine.identifier = 1, (1280, 720), "JP"
        engine.ocr_img_pass_method, engine.shared_memory_name = 1, ""
        counter_region = [885, 120, 1100, 202]
        iteration = 0
        while not cycles or iteration < cycles:
            checkpoint("Recognizing target")
            cycle_generation = generation
            ready_deadline = time.monotonic() + 10
            while True:
                engine.update_screenshot_array()
                checkpoint("Recognizing target")
                matches = baas_image.get_image_all_appear_position(engine, "shop_list-type-name-feature", (0, 102, 25, 569), .8)
                currency = ocr.recognize_int(engine, [928, 8, 1034, 38])
                if len(matches) == 24 and currency == 19530:
                    break
                if time.monotonic() >= ready_deadline:
                    import cv2
                    cv2.imwrite(str(Path(files_dir) / "workflow-last-frame.png"), engine.latest_img_array)
                    raise RuntimeError(("Target did not become ready", len(matches), currency))
            before_text = ocr.get_region_res(engine, counter_region, language="en-us", candidates="0123456789")
            assert before_text.isdigit(), ("Counter OCR was empty or invalid", before_text)
            before = int(before_text)
            checkpoint("Clicking recognized target")
            if generation != cycle_generation:
                continue
            device.input_generation = generation
            try:
                engine.click(650, 635)
            except RecheckTarget:
                continue
            deadline = time.monotonic() + 5
            while True:
                checkpoint("Verifying input result")
                if generation != cycle_generation:
                    break
                engine.update_screenshot_array()
                after = ocr.recognize_int(engine, counter_region)
                if after == before + 1:
                    break
                assert time.monotonic() < deadline, (before, after)
            if generation != cycle_generation:
                continue
            iteration += 1
            progress = {"iteration": iteration, "counterBefore": before, "counterAfter": after,
                        "currency": currency, "templateMatches": len(matches), "frameId": device.frame_id,
                        "displayId": device.display_id}
            report["cycles"].append(progress)
            if len(report["cycles"]) > 100:
                del report["cycles"][0]
            report["totalCycles"] = iteration
            gate.progress(json.dumps(progress))
            if (not cycles or iteration < cycles) and not gate.waitForNext(1000):
                raise TaskStopped()
        report["state"] = "completed"
    except TaskStopped:
        report["state"] = "stopped"
    except Exception:
        report["error"] = traceback.format_exc()
    finally:
        cleanup = {}
        if owned_display:
            try:
                bridge.reconnect()
                state = device.command("status")["state"]
                if state["displayId"] == device.display_id:
                    device.command("stop")
                    cleanup["displayReleased"] = not device.command("status")["state"]["alive"]
                else:
                    cleanup["displayReplaced"] = True
            except Exception:
                cleanup["displayError"] = traceback.format_exc()
        if ocr_started:
            try:
                native.stop()
                cleanup["ocrStopped"] = True
            except Exception:
                cleanup["ocrError"] = traceback.format_exc()
        report["cleanup"] = cleanup
        if any(name.endswith("Error") for name in cleanup):
            report["state"] = "failed"
    return json.dumps(report, ensure_ascii=False, indent=2)
