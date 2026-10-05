"""Device adapter for the Android-local Kotlin Companion bridge."""
import json
from pathlib import Path
import re
import threading
import time

import cv2
import numpy as np
from java import jclass

CompanionCommandException = jclass("moe.baas.aio.CompanionCommandException")


class CompanionDevice:
    method = "android_companion"

    def __init__(self, bridge):
        self.bridge = bridge
        self.display_id = self.command("status")["displayId"]
        self.frame_id = 0
        self.completed_ns = 0
        self.dirty = False
        self.last_metadata = None
        self.state_lock = threading.Lock()
        self.capture_lock = threading.Lock()
        self.active_package = None

    def command(self, cmd, **args):
        return json.loads(str(self.bridge.command(json.dumps({"cmd": cmd, **args}))))

    def _input(self, cmd, **args):
        for attempt in range(3):
            try:
                result = self.command(cmd, **args)
                break
            except CompanionCommandException as error:
                failure = json.loads(str(error.getReplyJson()))
                # Match the desktop client: retry only a known pre-injection rejection.
                if (cmd != "click" or attempt == 2 or failure.get("delivery") != "not_sent"
                        or failure.get("retryable") is not True):
                    raise
                state = self.command("status")
                if state["displayId"] != self.display_id or not state["state"]["alive"]:
                    raise RuntimeError("Click recovery aborted: display session changed") from error
                if self.active_package and self.app_current()["package"] != self.active_package:
                    raise RuntimeError("Click recovery aborted: target game is not in foreground") from error
                time.sleep(.3)
        with self.state_lock:
            self.completed_ns = result["completedNs"]
            self.dirty = True
        return result

    def app_current(self):
        return {"package": str(self.bridge.currentPackage())}

    def app_start(self, package_name, activity=None):
        result = self._input("launch", package=package_name, **({"activity": activity} if activity else {}))
        self.active_package = package_name
        return result

    def app_stop(self, package_name):
        self.bridge.stopGame(package_name)

    def push(self, source, destination):
        # The upstream de_clothes task uses u2.push; expose only its fixed file target.
        source = Path(source)
        match = re.fullmatch(
            r"/sdcard/Android/data/(com\.RoamingStar\.BlueArchive(?:\.bilibili)?)/files/?",
            destination)
        if source.name != "LocalizeConfig.txt" or match is None:
            raise ValueError("Unsupported Companion file transfer")
        self.bridge.writeLocalizeConfig(match.group(1), source.read_text(encoding="utf-8"))

    def click(self, x, y):
        x, y = self._point(x, y)
        return self._input("click", x=x, y=y)

    @staticmethod
    def _point(x, y):
        return max(0, min(1279, round(x))), max(0, min(719, round(y)))

    def swipe(self, x1, y1, x2, y2, duration=None):
        x1, y1 = self._point(x1, y1)
        x2, y2 = self._point(x2, y2)
        return self._input("swipe", x1=x1, y1=y1, x2=x2, y2=y2,
                           duration=max(80, min(10000, round((duration or 0.3) * 1000))))

    def long_click(self, x, y, duration):
        return self.swipe(x, y, x, y, duration)

    def key(self, key_code):
        return self._input("key", keyCode=key_code)

    def pinch(self, cx, cy, start, end, steps=30):
        return self._input("pinch", cx=cx, cy=cy, start=start, end=end,
                           duration=max(80, min(10000, steps * 16)))

    def screenshot(self):
        with self.capture_lock:
            with self.state_lock:
                completed_ns = self.completed_ns
                fence = completed_ns if self.dirty else 0
                frame_id = self.frame_id
            deadline = time.monotonic() + 30
            last_frame = "No frame received"
            while True:
                try:
                    frame = self.bridge.screenshot()
                except CompanionCommandException as error:
                    if not str(error.getMessage()).endswith(": No frame available yet"):
                        raise
                    last_frame = str(error.getMessage())
                else:
                    metadata = json.loads(str(frame.getMetadata()))
                    last_frame = metadata
                    if metadata["displayId"] != self.display_id:
                        raise RuntimeError("Companion display changed")
                    # Match desktop BAAS: a static frame is valid without pending input;
                    # after input require a newer frame and a timestamp past completion.
                    if not fence or (metadata["frameId"] > frame_id and metadata["timestampNs"] > fence):
                        png = bytes(frame.getPng())
                        image = cv2.imdecode(np.frombuffer(png, np.uint8), cv2.IMREAD_COLOR)
                        if image is None or image.shape != (720, 1280, 3):
                            raise RuntimeError("Invalid Companion frame")
                        with self.state_lock:
                            self.frame_id = metadata["frameId"]
                            self.last_metadata = metadata
                            if self.completed_ns == completed_ns:
                                self.dirty = False
                        return image
                if time.monotonic() >= deadline:
                    raise TimeoutError(
                        f"No fresh Companion frame after input: displayId={self.display_id}, "
                        f"previousFrameId={frame_id}, inputCompletedNs={fence}, lastFrame={last_frame}")
                time.sleep(.1)

    def probe_state(self):
        return json.loads(str(self.bridge.probeState()))
