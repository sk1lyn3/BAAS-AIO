"""Execute real BAAS code offline on Android and report unsupported boundaries."""
import importlib
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import sys
import time
import traceback
from types import SimpleNamespace
import zipfile


def run(files_dir):
    from platform_bindings import install
    install()
    root = Path(files_dir) / "baas-probe"
    if root.exists():
        shutil.rmtree(root)
    root.mkdir(exist_ok=True)
    with zipfile.ZipFile(Path(files_dir) / "baas-source.zip") as archive:
        archive.extractall(root)
    with zipfile.ZipFile(Path(files_dir) / "probe-fixtures.zip") as archive:
        archive.extractall(root)
    sys.path.insert(0, str(root))
    os.chdir(root)
    manifest = json.loads((root / "source-manifest.json").read_text())
    for name, digest in manifest["files"].items():
        assert hashlib.sha256((root / name).read_bytes()).hexdigest() == digest, name
    manifest.update(json.loads((root / "fixture-manifest.json").read_text()))
    if "fixture_sha256" not in manifest:
        raise RuntimeError("Offline diagnostic fixture missing; build with -PprobeFixture=IMAGE")
    assert hashlib.sha256((root / "fixtures/shop.png").read_bytes()).hexdigest() == manifest["fixture_sha256"]
    report = {"python": sys.version, "machine": platform.machine(),
              "source": {"head": manifest["head"], "dirty": manifest.get("dirty", ""),
                         "files": len(manifest["files"]),
                         "fixture_sha256": manifest["fixture_sha256"]}, "checks": {}}

    def check(name, operation):
        started = time.monotonic()
        try:
            report["checks"][name] = {"ok": True, "result": operation()}
        except Exception:
            report["checks"][name] = {"ok": False, "error": traceback.format_exc()}
        report["checks"][name]["seconds"] = time.monotonic() - started

    def vision():
        import cv2
        import numpy as np
        from core import image, position, color
        frame = cv2.imread(str(root / "fixtures/shop.png"))
        assert frame is not None and frame.shape == (720, 1280, 3)
        position.image_dic["JP"] = {"shop_list-type-name-feature": cv2.imread(
            str(root / "src/images/JP/shop/list-type-name-feature.png"))}
        device = SimpleNamespace(identifier="JP", ratio=1, latest_img_array=frame)
        matches = image.get_image_all_appear_position(
            device, "shop_list-type-name-feature", (0, 102, 25, 569), 0.8)
        assert any(abs(y - 252) <= 2 for x, y in matches), matches
        assert not color.rgb_in_range(device, 1, 251, 245, 255, 245, 255, 245, 255)
        assert color.rgb_in_range(device, 1, 266, 245, 255, 245, 255, 245, 255)
        return {"opencv": cv2.__version__, "numpy": np.__version__,
                "shape": list(frame.shape), "matched_positions": len(matches)}

    check("baas_vision", vision)
    for name in ("core.config.config_set", "module.mail", "core.scheduler", "core.Baas_thread"):
        check("import:" + name, lambda name=name: {"file": importlib.import_module(name).__file__})
    check("baas_config", lambda: config_probe(root))
    check("baas_mail_workflow", lambda: mail_probe(root))
    report["ok"] = all(item["ok"] for item in report["checks"].values())
    return json.dumps(report, ensure_ascii=False, indent=2)


def config_probe(root):
    from core.config.config_set import ConfigSet
    from core.config.default_config import DEFAULT_CONFIG
    config_dir = root / "config/Probe"
    config_dir.mkdir(exist_ok=True)
    (config_dir / "config.json").write_text(DEFAULT_CONFIG)
    config = ConfigSet(str(config_dir))
    config.set("ap", 123)
    assert config.get("ap") == 123
    assert json.loads((config_dir / "config.json").read_text())["ap"] == 123
    return {"saved_and_reloaded_ap": 123}


def mail_probe(root):
    import cv2
    import numpy as np
    from core import position
    from core.Baas_thread import Baas_thread
    from core.config.config_set import ConfigSet
    from core.scheduler import Scheduler
    from src.images.JP.x_y_range.mail import x_y_range

    rgb_features = json.loads((root / "src/rgb_feature/JP.json").read_text())["rgb_feature"]
    position.image_x_y_range["JP"] = {"mail": x_y_range}
    position.image_dic["JP"]["mail_menu"] = cv2.imread(str(root / "src/images/JP/mail/menu.png"))
    main_frame = np.zeros((720, 1280, 3), dtype=np.uint8)
    for (x, y), limits in zip(*rgb_features["main_page"]):
        main_frame[y, x] = [(limits[4] + limits[5]) // 2,
                            (limits[2] + limits[3]) // 2, (limits[0] + limits[1]) // 2]
    results = []
    for collected in (False, True):
        mail_frame = np.zeros_like(main_frame)
        x1, y1, x2, y2 = x_y_range["menu"]
        mail_frame[y1:y2, x1:x2] = position.image_dic["JP"]["mail_menu"]
        mail_frame[646, 1142] = (218, 217, 216) if collected else (75, 233, 245)
        config = ConfigSet(str(root / "config/Probe"))
        device = Baas_thread(config, logger_signal=SimpleNamespace(emit=lambda *args: None))
        device.config = config.config
        device.identifier, device.server, device.ratio = "JP", "JP", 1
        device.flag_run, device.is_android_device = True, True
        device.rgb_feature = rgb_features
        device.resolution = (1280, 720)
        device.latest_img_array = main_frame.copy()
        device.screenshot_interval = 0.01
        state = {"frame": main_frame, "clicks": []}

        def click(x, y, **options):
            state["clicks"].append([x, y])
            if abs(x - 1141) <= 5 and abs(y - 43) <= 5:
                state["frame"] = mail_frame

        device.control = SimpleNamespace(method="android_companion", click=click)
        device.screenshot = SimpleNamespace(screenshot=lambda: state["frame"].copy())
        events = [{"func_name": "mail", "event_name": "Mail", "enabled": True,
                   "next_tick": 0, "priority": 1, "interval": 60, "daily_reset": [],
                   "disabled_time_range": [], "pre_task": [], "post_task": []}]
        (root / "config/Probe/event.json").write_text(json.dumps(events))
        device.scheduler = Scheduler(None, str(root / "config/Probe"))
        task = device.scheduler.heartbeat()
        assert task["current_task"] == "mail"
        assert device.solve(task["current_task"])
        expected = [[1141, 43], [1236, 39] if collected else [1142, 670]]
        assert len(state["clicks"]) == len(expected), state["clicks"]
        assert all(abs(x - ex) <= 5 and abs(y - ey) <= 5
                   for (x, y), (ex, ey) in zip(state["clicks"], expected)), state["clicks"]
        device.scheduler.systole("mail", next_time=60)
        assert device.scheduler.heartbeat() is None
        next_tick = json.loads((root / "config/Probe/event.json").read_text())[0]["next_tick"]
        assert next_tick > time.time()
        results.append({"collected": collected, "clicks": state["clicks"], "next_tick": next_tick})
    return {"engine": "Baas_thread.solve", "device": "offline screenshot/input fixture", "cases": results}
