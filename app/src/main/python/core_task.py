"""Run the original BAAS entry points with Android device/output bindings."""
import json
from pathlib import Path
import threading
import time
import traceback
from types import SimpleNamespace

from java import jclass
from companion_device import CompanionDevice
from core_loader import prepare
from android_bindings import AndroidOcr, bind_schedule_file


def run(files_dir, bridge, native, gate, cycles=0, task="core_managed", profile_path=None):
    report = {"state": "failed", "entrypoint": "Baas_thread.init_all_data + thread_starter",
              "profile": profile_path, "tasks": [], "log": [], "idleEntries": 0}
    device = None
    ocr = None
    engine = None
    owned = False
    stopping = False
    generation = gate.generation()
    worker = threading.current_thread()
    try:
        manifest = prepare(files_dir)
        report["source"] = {"head": manifest["head"], "files": len(manifest["files"])}
        from platform_bindings import install
        install(notify=lambda title=None, body=None, **extra: gate.log(2, str(body or title or "")))
        from core.Baas_thread import Baas_thread
        from core.config.config_set import ConfigSet
        from core.exception import RequestHumanTakeOver, PackageIncorrect
        from core import position
        from core.device.swipe_compat import apply
        from core.virtual_scrcpy_compat import _patch_color
        import android_bindings
        if not getattr(android_bindings, "compat_installed", False):
            apply(); _patch_color()
            android_bindings.compat_installed = True
        config = ConfigSet(profile_path)

        def checkpoint(phase):
            nonlocal stopping, generation
            if not gate.checkpoint(phase):
                stopping = True
                if engine is not None:
                    engine.flag_run = False
                raise RequestHumanTakeOver("Stopped from Android")
            if gate.generation() != generation:
                bridge.reconnect()
                generation = gate.generation()

        def output(level, message):
            line = str(message)
            report["log"].append({"level": level, "message": line})
            del report["log"][:-200]
            jclass("android.util.Log").i("BaasCore", line)
            gate.log(level, line)

        class Device(CompanionDevice):
            frame_generation = None
            def _input(self, command, **args):
                checkpoint("Input: " + command)
                if (threading.current_thread() == worker and command != "launch" and
                        self.frame_generation != generation):
                    raise PackageIncorrect("Recheck game after manual control")
                return super()._input(command, **args)
            def screenshot(self):
                checkpoint("Capture")
                image = super().screenshot()
                if threading.current_thread() == worker:
                    self.frame_generation = generation
                return image

        device = Device(bridge)
        state = device.command("status")["state"]
        if not state["alive"]:
            device.display_id = device.command("start", width=1280, height=720, dpi=240)["displayId"]
            owned = True
        elif (state["width"], state["height"]) != (1280, 720):
            raise ValueError("Companion display must be 1280x720")
        report.update(displayId=device.display_id, createdDisplay=owned)
        ocr = AndroidOcr(files_dir, native)

        class Capture:
            interval = .3
            last = 0
            def set_screenshot_interval(self, value):
                self.interval = max(.3, float(value))
                return self.interval
            def screenshot(self):
                remaining = self.interval - (time.monotonic() - self.last)
                if remaining > 0 and not gate.waitForNext(round(remaining * 1000)):
                    checkpoint("Capture")
                image = device.screenshot()
                self.last = time.monotonic()
                return image

        class AndroidBaas(Baas_thread):
            active = False
            current = None
            def init_config(self):
                ok = super().init_config()
                if ok:
                    self.config.control_method = self.config.screenshot_method = "android_companion"
                    self.config.push_after_error = self.config.push_after_completion = False
                return ok
            def init_device(self):
                self.is_android_device = True
                self.server = self.identifier = self.config_set.server_mode
                self.package_name = self.static_config.package_name[self.config.server]
                self.activity_name = self.static_config.activity_name[self.config.server]
                if self.server == "Global":
                    options = Path(files_dir) / "global-device-option.json"
                    options.write_text(str(bridge.gameOptions(self.package_name)))
                    self._read_DeviceOption_ocr_language(str(options))
                    self.identifier += "_" + self.ocr_language
                else:
                    self.ocr_language = "ja-jp" if self.server == "JP" else "zh-cn"
                self.current_game_activity = self.static_config.current_game_activity[self.server]
                self.serial = "android-local"
                self.control = self.u2 = device
                self.screenshot = Capture()
                self.connection = SimpleNamespace(
                    get_current_package=lambda: str(bridge.currentPackage()),
                    close_current_app=device.app_stop)
                self.ratio, self.resolution = 1, (1280, 720)
                self.ocr_img_pass_method, self.shared_memory_name = 1, ""
                self.set_screenshot_interval(self.config.screenshot_interval)
                self.last_refresh_u2_time = time.time()
                position.initialized_image[self.identifier] = False
                position.image_dic[self.identifier] = {}; position.image_x_y_range[self.identifier] = {}
                return True
            def u2_get_screenshot(self):
                return device.screenshot()
            def pinch(self, cx, cy, start, end, steps=30):
                return device.pinch(cx, cy, start, end, steps)
            def solve(self, activity):
                checkpoint("Core: " + activity)
                if not self.active:
                    gate.progress(json.dumps({"phase": "restoring", "profile": profile_path,
                                              "currentTask": activity, "tasks": report["tasks"]}))
                    bridge.reconnect()
                    self.ocr = ocr.start([self.ocr_language])
                    self.active = True
                    report.setdefault("ocrSessions", []).append(ocr.info)
                    del report["ocrSessions"][:-20]
                    if activity != "restart" and not super().solve("restart"):
                        report["state"] = "failed"; self.flag_run = False
                        return False
                entry = {"task": activity, "state": "running"}
                report["tasks"].append(entry); del report["tasks"][:-200]
                self.current = activity
                gate.progress(json.dumps({"phase": "executing", "profile": profile_path,
                                          "currentTask": activity, "tasks": report["tasks"]}))
                ok = super().solve(activity)
                entry["state"] = "stopped" if stopping else ("completed" if ok else "failed")
                if not ok:
                    report["state"] = "failed"
                return ok
            def handle_then(self):
                # Android owns idle resources; upstream owns when a task is due.
                checkpoint("Core: waiting for tasks")
                if self.active and task == "core_managed":
                    bridge.stopGame(self.package_name)
                    ocr.stop(); bridge.close(); self.active = False
                report["idleEntries"] += 1
                enabled = [e for e in self.scheduler._event_config if e["enabled"]]
                gate.progress(json.dumps({"phase": "idle", "profile": profile_path,
                                          "currentTask": None, "idleEntries": report["idleEntries"],
                                          "nextWakeAt": min((e["next_tick"] for e in enabled), default=None)}))
                if task == "core_once" or (cycles and report.get("scheduledTasks", 0) >= cycles):
                    report["state"] = "completed"; self.flag_run = False

        engine = AndroidBaas(config, logger_signal=SimpleNamespace(emit=output),
                             update_signal=SimpleNamespace(emit=lambda value: gate.queue(json.dumps(value))))
        checkpoint("Core: initializing")
        if not engine.init_all_data():
            raise RuntimeError("Original BAAS initialization failed")
        def committed(current):
            report["scheduledTasks"] = report.get("scheduledTasks", 0) + 1
        bind_schedule_file(engine.scheduler, committed)
        # These two methods are the upstream implementation, never reimplemented here.
        report["upstreamEntryUnchanged"] = AndroidBaas.thread_starter is Baas_thread.thread_starter
        engine.thread_starter()
        if stopping:
            report["state"] = "stopped"
    except Exception:
        if stopping:
            report["state"] = "stopped"
        else:
            report["error"] = traceback.format_exc()
    finally:
        if engine is not None:
            engine.flag_run = False
        cleanup = []
        if task != "core_profile" and engine is not None and engine.package_name:
            cleanup.append(lambda: bridge.stopGame(engine.package_name))
        if ocr is not None:
            cleanup.append(ocr.stop)
        def release_display():
            bridge.reconnect()
            if device.command("status")["displayId"] == device.display_id:
                device.command("stop")
                report["displayReleased"] = True
        if owned and task != "core_profile":
            cleanup.append(release_display)
        for action in cleanup:
            try:
                action()
            except Exception:
                report.setdefault("cleanupErrors", []).append(traceback.format_exc())
                report["state"] = "failed"
    return json.dumps(report, ensure_ascii=False, indent=2)
