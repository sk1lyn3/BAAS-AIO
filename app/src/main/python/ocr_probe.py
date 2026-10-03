"""Verify unmodified BAAS OCR inference and Python API on Android."""
import hashlib
import json
from pathlib import Path
import socket
import time
import traceback
import zipfile
from types import SimpleNamespace

import cv2
import requests


def run(files_dir, native, bridge=None):
    report = {"backend": "existing BAAS Android arm64 OCR library", "checks": {}, "cases": []}
    started = False
    device = None
    owned_display = False
    root = Path(files_dir) / "ocr-probe"
    try:
        root.mkdir(exist_ok=True)
        with zipfile.ZipFile(Path(files_dir) / "ocr-resources.zip") as archive:
            archive.extractall(root)
        manifest = json.loads((root / "manifest.json").read_text())
        for name, digest in manifest["files"].items():
            if name.startswith("resource/"):
                assert hashlib.sha256((root / name).read_bytes()).hexdigest() == digest, name
        report["prebuild_revision"] = manifest["revision"]
        with socket.socket() as port_socket:
            port_socket.bind(("127.0.0.1", 0))
            port = port_socket.getsockname()[1]
        config = json.loads((root / "resource/global_setting.json").read_text())
        config["ocr"]["server"] = {"host": "127.0.0.1", "port": port}
        config["ocr"]["num_thread"] = 2
        (root / "config").mkdir(exist_ok=True)
        (root / "config/global_setting.json").write_text(json.dumps(config))
        launch_time = time.monotonic()
        report["service"] = str(native.start(str(root)))
        started = True
        base = f"http://127.0.0.1:{port}"
        deadline = time.monotonic() + 10
        while True:
            try:
                requests.post(base + "/test", timeout=1).raise_for_status()
                break
            except requests.RequestException:
                if time.monotonic() > deadline: raise
                time.sleep(.1)
        report["checks"]["local_native_server"] = {"ok": True, "port": port,
                                                   "startup_seconds": time.monotonic() - launch_time}
        # Import and use the original desktop API, replacing only executable startup.
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
        ocr.enable_thread_pool(2)
        expected = json.loads((Path(files_dir) / "ocr-reference.json").read_text())
        frame = cv2.imread(str(Path(files_dir) / "baas-probe/fixtures/shop.png"))
        if bridge is not None:
            from companion_device import CompanionDevice
            from core import image as baas_image, position, color
            device = CompanionDevice(bridge)
            state = device.command("status")["state"]
            if state["displayId"] == -1:
                device.display_id = device.command("start", width=1280, height=720, dpi=240)["displayId"]
                owned_display = True
            else:
                assert state["alive"] and (state["width"], state["height"]) == (1280, 720), state
            device._input("launch", package="moe.baas.aio", activity="moe.baas.aio.RecognitionProbeActivity")
            deadline = time.monotonic() + 10
            while True:
                captured = device.screenshot()
                difference = float(cv2.absdiff(frame, captured).mean())
                if difference < .1:
                    break
                assert time.monotonic() < deadline, ("Fixture render mismatch", difference)
                time.sleep(.1)
            frame = captured
            cv2.imwrite(str(Path(files_dir) / "recognition-frame.png"), frame)
            baas = SimpleNamespace(identifier="JP", ratio=1, latest_img_array=frame)
            matches = baas_image.get_image_all_appear_position(
                baas, "shop_list-type-name-feature", (0, 102, 25, 569), .8)
            assert len(matches) == 24, matches
            assert color.rgb_in_range(baas, 1, 266, 245, 255, 245, 255, 245, 255)
            assert not color.rgb_in_range(baas, 1, 251, 245, 255, 245, 255, 245, 255)
            report["checks"]["companion_capture_and_baas_vision"] = {
                "ok": True, "displayId": device.display_id, "created_display": owned_display,
                "matched_positions": len(matches), "pixel_mean_difference": difference,
                "frame": device.last_metadata,
                "target": "real-game screenshot fixture rendered by RecognitionProbeActivity"}
        for case in expected["cases"]:
            lang = case["language"]
            init_time = time.monotonic()
            ocr.init_model([lang], num_thread=2)
            initialization = time.monotonic() - init_time
            if case["region"] is None:
                image = cv2.imread(str(root / "resource/ocr_models/test_images" / (lang + ".png")))
            else:
                image = ocr.get_area_img(frame, case["region"])
            timings = []
            baas = SimpleNamespace(latest_img_array=frame, ratio=1, ocr_img_pass_method=1,
                                   shared_memory_name="", logger=ocr.logger)
            for repetition in range(3):
                before = time.monotonic()
                if case["region"] is None:
                    text = ocr.ocr_for_single_line(lang, origin_image=image, candidates=case["candidates"], pass_method=1)
                else:
                    text = ocr.get_region_res(baas, case["region"], language=lang, candidates=case["candidates"])
                timings.append(time.monotonic() - before)
                assert text == case["expected"], (case["name"], text, case["expected"])
            report["cases"].append({"name": case["name"], "language": lang, "text": text,
                                    "ok": True, "init_seconds": initialization, "seconds": timings})
            ocr.client.release_all().raise_for_status()
        report["checks"]["original_baas_ocr_api"] = {"ok": True, "cases": len(report["cases"])}
        ocr.init_model(["en-us"], num_thread=2)
        assert ocr.recognize_int(baas, [928, 8, 1034, 38]) == 19530
        report["checks"]["baas_integer_wrapper"] = {"ok": True, "value": 19530}
        ocr.client.release_all().raise_for_status()
        ocr.init_model(["ja-jp"], num_thread=2)
        multi_image = ocr.get_area_img(frame, expected["multiline"]["region"])
        actual = json.loads(ocr.ocr("ja-jp", origin_image=multi_image, pass_method=1, ret_options=7))
        reference_lines = expected["multiline"]["raw"]["text_list"]
        assert [x["text"] for x in actual["text_list"]] == [x["text"] for x in reference_lines], actual
        for actual_line, reference_line in zip(actual["text_list"], reference_lines):
            for point, reference_point in zip(actual_line["position"], reference_line["position"]):
                assert all(abs(a - b) <= 2 for a, b in zip(point, reference_point)), (actual_line, reference_line)
        report["checks"]["multiline_detection_and_recognition"] = {"ok": True, "result": actual}
        report["ok"] = True
    except Exception:
        report["ok"] = False
        report["error"] = traceback.format_exc()
    finally:
        if owned_display:
            try:
                assert device.command("status")["displayId"] == device.display_id
                device.command("stop")
                assert device.command("status")["displayId"] == -1
                report["checks"]["display_cleanup"] = {"ok": True}
            except Exception:
                report["ok"] = False
                report["display_cleanup_error"] = traceback.format_exc()
        if started:
            try:
                native.stop()
                # Native stop must join the server and release the listening port.
                with socket.socket() as check_socket:
                    check_socket.bind(("127.0.0.1", port))
                report["checks"]["stop_releases_port"] = {"ok": True}
            except Exception:
                report["ok"] = False
                report["cleanup_error"] = traceback.format_exc()
    return json.dumps(report, ensure_ascii=False, indent=2)
