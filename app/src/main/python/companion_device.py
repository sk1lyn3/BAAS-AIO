"""Device adapter for the Android-local Kotlin Companion bridge."""
import json
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

    def command(self, cmd, **args):
        return json.loads(str(self.bridge.command(json.dumps({"cmd": cmd, **args}))))

    def _input(self, cmd, **args):
        result = self.command(cmd, **args)
        with self.state_lock:
            self.completed_ns = result["completedNs"]
            self.dirty = True
        return result

    def click(self, x, y):
        return self._input("click", x=int(x), y=int(y))

    def swipe(self, x1, y1, x2, y2, duration=None):
        return self._input("swipe", x1=int(x1), y1=int(y1), x2=int(x2), y2=int(y2),
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
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                try:
                    frame = self.bridge.screenshot()
                except CompanionCommandException as error:
                    if not str(error.getMessage()).endswith(": No frame available yet"):
                        raise
                    time.sleep(0.05)
                    continue
                metadata = json.loads(str(frame.getMetadata()))
                if metadata["displayId"] != self.display_id:
                    raise RuntimeError("Companion display changed")
                if metadata["ageMs"] <= 1000 and (not fence or (
                        metadata["frameId"] > frame_id and metadata["timestampNs"] > fence)):
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
                time.sleep(0.05)
            raise TimeoutError("No fresh Companion frame")

    def probe_state(self):
        return json.loads(str(self.bridge.probeState()))
