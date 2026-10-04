"""A repeatable on-device task through real BAAS vision, OCR and device adapters."""
import json
from pathlib import Path
import socket
import time
import traceback
import threading
from types import SimpleNamespace
import zipfile

import requests

from companion_device import CompanionDevice


class TaskStopped(Exception):
    pass


class RecheckTarget(Exception):
    pass


_gesture_compat_applied = False


def run(files_dir, bridge, native, gate, cycles=0, task="fixture", managed_profile=None):
    global _gesture_compat_applied
    report = {"state": "failed", "cycles": [], "target": "WorkflowTargetActivity: real shop fixture plus OCR-visible counter"}
    device = None
    owned_display = False
    ocr_started = False
    generation = gate.generation()
    assert task in ("fixture", "jp_login", "jp_cafe", "jp_daily", "jp_managed_batch"), task
    capture_workers = set()
    restore_cafe_capture = None
    result = None
    selected_profile = Path(managed_profile) if managed_profile else None

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

        def app_stop(self, package_name):
            checkpoint("Stopping game: " + package_name)
            return super().app_stop(package_name)

        def screenshot(self):
            checkpoint("Capture")
            if task != "fixture":
                if not gate.waitForNext(max(1, round(engine.screenshot_interval * 1000))):
                    raise TaskStopped()
                checkpoint("JP game: recognizing screen")
            image = super().screenshot()
            self.input_generation = generation
            return image

    try:
        checkpoint("Preparing Python runtime")
        import runtime_probe
        runtime_report = json.loads(runtime_probe.run(files_dir))
        assert runtime_report["ok"], runtime_report
        report["source"] = runtime_report["source"]
        if selected_profile is not None:
            values = json.loads((selected_profile / "config.json").read_text())
            if values.get("server") != "日服":
                raise ValueError("This runtime currently supports JP configurations only")
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
        from android_bindings import local_ocr
        ocr = local_ocr(base)
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
        if task == "fixture":
            device._input("launch", package="moe.baas.aio", activity="moe.baas.aio.WorkflowTargetActivity")
        else:
            report["target"] = "JP game: existing BAAS to_main_page plus resource OCR"
            device._input("launch", package="com.YostarJP.BlueArchive")
        from core.Baas_thread import Baas_thread
        from core.config.config_set import ConfigSet
        from core import image as baas_image
        engine = Baas_thread(ConfigSet(str(selected_profile or (Path(files_dir) / "baas-probe/config/Probe"))),
                             logger_signal=SimpleNamespace(emit=lambda *args: None))
        engine.config = engine.config_set.config
        if selected_profile is not None:
            assert engine.init_config(), "Configuration initialization failed"
        engine.control, engine.screenshot, engine.ocr = device, device, ocr
        engine.flag_run, engine.is_android_device = True, True
        engine.ratio, engine.resolution, engine.identifier = 1, (1280, 720), "JP"
        engine.ocr_img_pass_method, engine.shared_memory_name = 1, ""
        if task != "fixture":
            from core import color, position
            from java import jclass
            logs = []
            report["log"] = logs
            def log(*args):
                line = str(args[-1])
                logs.append(line)
                del logs[:-100]
                jclass("android.util.Log").i("BaasGameTask", line)
            engine.logger.logger_signal = SimpleNamespace(emit=log)
            engine.ocr_language = "ja-jp"
            if task in ("jp_daily", "jp_managed_batch"):
                ocr.init_model(["ja-jp"], num_thread=2)
            engine.server, engine.package_name = "JP", "com.YostarJP.BlueArchive"
            engine.config.control_method = engine.config.screenshot_method = "android_companion"
            engine.screenshot_interval = float(engine.config.screenshot_interval)
            engine.connection = SimpleNamespace(get_current_package=lambda: str(bridge.currentPackage()))
            # The offline probe installs synthetic subsets in global registries.
            # Reload the actual JP resources before invoking the real workflow.
            position.initialized_image["JP"] = False
            position.image_dic["JP"] = {}
            position.image_x_y_range["JP"] = {}
            assert engine.init_image_resource() and engine.init_rgb()
            launch_deadline = time.monotonic() + 10
            while True:
                checkpoint("JP login: waiting for game foreground")
                package = str(bridge.currentPackage())
                if package == engine.package_name:
                    break
                if time.monotonic() > launch_deadline:
                    raise RuntimeError(("Game did not become foreground", package))
                if not gate.waitForNext(100):
                    raise TaskStopped()
            while True:
                checkpoint("JP login: returning to home")
                try:
                    engine.to_main_page()
                    checkpoint("JP login: verifying home")
                    engine.update_screenshot_array()
                    if not color.match_rgb_feature(engine, "main_page"):
                        continue
                    report["mainPageRecognized"] = True
                    report["credits"] = engine.get_creditpoints(is_main_page=True)
                    report["package"] = str(bridge.currentPackage())
                    report["frameId"] = device.frame_id
                    import cv2
                    cv2.imwrite(str(Path(files_dir) / "game-home.png"), engine.latest_img_array)
                    checkpoint("JP login: completed")
                    break
                except RecheckTarget:
                    continue
            if task in ("jp_cafe", "jp_daily", "jp_managed_batch"):
                from module import cafe_reward
                report["target"] = "JP game: existing BAAS cafe_reward"
                settings = json.loads((Path(files_dir) / "baas-probe/fixtures/cafe-config.json").read_text())
                if selected_profile is not None:
                    events = json.loads((selected_profile / "event.json").read_text())
                    settings = {"config": {key: value for key, value in vars(engine.config).items()
                                           if key.startswith("cafe_reward_")},
                                "interval": next((e["interval"] for e in events if e["func_name"] == "cafe_reward"), 10800)}
                for key, value in settings["config"].items():
                    setattr(engine.config, key, value)
                report["cafeConfig"] = settings
                engine.scheduler = SimpleNamespace(get_interval=lambda _: settings["interval"])
                if not _gesture_compat_applied:
                    from core.device.swipe_compat import apply
                    from core.virtual_scrcpy_compat import _patch_color
                    apply(); _patch_color()
                    _gesture_compat_applied = True
                engine.u2 = device
                engine.pinch = lambda cx, cy, start, end, steps=30: device.pinch(cx, cy, start, end, steps)
                def legacy_capture():
                    # Original cafe captures during the swipe on its own worker.
                    # It must not change the main worker's pause generation.
                    return CompanionDevice.screenshot(device)
                engine.u2_get_screenshot = legacy_capture
                original_capture_swipe = cafe_reward.swipe_gift_and_screenshot
                def capture_swipe(instance):
                    errors = []
                    def capture():
                        try:
                            cafe_reward.screenshot_thread(instance, max(.6, instance.config.cafe_reward_interaction_shot_delay))
                        except Exception as error:
                            errors.append(error)
                    worker = threading.Thread(target=capture, name="baas-cafe-capture")
                    capture_workers.add(worker)
                    worker.start()
                    started = time.monotonic()
                    instance.u2_swipe(131, 660, 1280, 660, duration=.5)
                    elapsed = round(time.monotonic() - started, 3)
                    worker.join(25)
                    if worker.is_alive():
                        raise TimeoutError("Cafe capture did not finish")
                    if errors:
                        raise errors[0]
                    return elapsed
                cafe_reward.swipe_gift_and_screenshot = capture_swipe
                restore_cafe_capture = lambda: setattr(cafe_reward, "swipe_gift_and_screenshot", original_capture_swipe)
                if task in ("jp_daily", "jp_managed_batch"):
                    daily = json.loads((Path(files_dir) / "baas-probe/fixtures/daily-config.json").read_text())
                    from core.config.default_config import DEFAULT_CONFIG
                    from core.scheduler import Scheduler
                    profile = Path(managed_profile) if managed_profile else Path(files_dir) / "daily-JP_Phone"
                    profile.mkdir(exist_ok=True)
                    config_path = profile / "config.json"
                    if not config_path.exists():
                        values = {**json.loads(DEFAULT_CONFIG), **daily["config"],
                                  "control_method": "android_companion", "screenshot_method": "android_companion",
                                  "push_after_error": False, "push_after_completion": False, "then": "无动作"}
                        config_path.write_text(json.dumps(values, ensure_ascii=False))
                    engine.config_set = ConfigSet(str(profile))
                    assert engine.init_config(), "Daily config initialization failed"
                    # Device selection is local; preserve the imported transport fields on disk.
                    engine.config.control_method = engine.config.screenshot_method = "android_companion"
                    engine.config_path = str(profile)
                    engine.daily_config_refresh()
                    if not (profile / "event.json").exists():
                        (profile / "event.json").write_text(json.dumps(daily["events"], ensure_ascii=False))
                    engine.scheduler = Scheduler(None, str(profile))
                    report["target"] = "JP single daily test: all enabled JP_Phone tasks"
                    report["profile"] = str(profile)
                    plan = sorted((event for event in json.loads((profile / "event.json").read_text())
                                   if event["enabled"]), key=lambda event: event["priority"])
                    report["taskPlan"] = [event["func_name"] for event in plan]
                else:
                    plan = [{"func_name": "cafe_reward", "interval": settings["interval"]}]
                report["tasks"] = []
                from core.Baas_thread import func_dict
                pending = [(event, 0) for event in plan]
                while pending or task == "jp_managed_batch":
                    if task == "jp_managed_batch":
                        next_task = engine.scheduler.heartbeat()
                        if next_task is None:
                            break
                        event = {"func_name": next_task["current_task"], "pre_task": next_task["pre_task"], "post_task": next_task["post_task"]}
                        ready_at = 0
                    else:
                        event, ready_at = pending.pop(0)
                    if ready_at > time.monotonic():
                        checkpoint("JP daily: waiting for arena cooldown")
                        if not gate.waitForNext(round((ready_at - time.monotonic()) * 1000)):
                            raise TaskStopped()
                    activity = event["func_name"]
                    if task in ("jp_daily", "jp_managed_batch"):
                        engine.scheduler._current_task = {"current_task": activity}
                    else:
                        engine.scheduler = SimpleNamespace(get_interval=lambda _, event=event: event["interval"])
                    engine.next_time = 0
                    started = time.monotonic()
                    result = {"task": activity, "state": "running"}
                    report["tasks"].append(result)
                    gate.progress(json.dumps({"taskPlan": report.get("taskPlan", [activity]), "tasks": report["tasks"]}))
                    while True:
                        checkpoint("JP daily: " + activity)
                        try:
                            for name in event.get("pre_task", []) + [activity] + event.get("post_task", []):
                                if not func_dict[name](engine):
                                    raise RuntimeError("BAAS task returned failure: " + name)
                            checkpoint("JP daily: verifying " + activity)
                            engine.update_screenshot_array()
                            if activity == "cafe_reward":
                                report["cafeRecognized"] = color.match_rgb_feature(engine, "cafe") or bool(baas_image.compare_image(engine, "cafe_menu"))
                                assert report["cafeRecognized"], "Cafe task ended outside cafe"
                                cv2.imwrite(str(Path(files_dir) / "game-cafe.png"), engine.latest_img_array)
                            result.update(state="completed", seconds=round(time.monotonic() - started, 2),
                                          nextTime=engine.next_time)
                            if task in ("jp_daily", "jp_managed_batch"):
                                result["nextTick"] = str(engine.scheduler.systole(activity, engine.next_time))
                                if task == "jp_daily" and activity == "arena" and 0 < engine.next_time <= 60:
                                    pending.append((event, time.monotonic() + engine.next_time))
                            gate.progress(json.dumps({"tasks": report["tasks"]}))
                            break
                        except RecheckTarget:
                            continue
                if task in ("jp_daily", "jp_managed_batch"):
                    while True:
                        try:
                            checkpoint("JP daily: returning to home")
                            engine.to_main_page()
                            engine.update_screenshot_array()
                            assert color.match_rgb_feature(engine, "main_page"), "Daily test did not return home"
                            report["returnedHome"] = True
                            cv2.imwrite(str(Path(files_dir) / "game-daily-home.png"), engine.latest_img_array)
                            checkpoint("JP daily: exiting game")
                            bridge.stopGame(engine.package_name)
                            deadline = time.monotonic() + 5
                            while str(bridge.currentPackage()) == engine.package_name:
                                if time.monotonic() > deadline:
                                    raise TimeoutError("Game remained foreground after exit")
                                if not gate.waitForNext(100):
                                    raise TaskStopped()
                            report["gameExited"] = True
                            break
                        except RecheckTarget:
                            continue
        counter_region = [885, 120, 1100, 202]
        iteration = 0
        while task == "fixture" and (not cycles or iteration < cycles):
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
        if result is not None and result["state"] == "running":
            result["state"] = "stopped"
        report["state"] = "stopped"
    except Exception:
        if result is not None and result["state"] == "running":
            result["state"] = "failed"
        report["error"] = traceback.format_exc()
    finally:
        cleanup = {}
        if restore_cafe_capture:
            restore_cafe_capture()
        for worker in capture_workers:
            worker.join(25)
            if worker.is_alive():
                cleanup["captureError"] = "Cafe capture worker did not finish"
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
