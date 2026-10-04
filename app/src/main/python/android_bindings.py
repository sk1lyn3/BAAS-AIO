"""Android IO bindings for unmodified BAAS classes; no task selection logic."""
import json
from pathlib import Path
import socket
import time
from types import SimpleNamespace
import zipfile

import requests
from java import jclass


class AndroidOcr:
    def __init__(self, files_dir, native):
        self.native = native
        self.root = Path(files_dir) / "workflow-ocr"
        self.running = False
        if not (self.root / "resource").exists():
            with zipfile.ZipFile(Path(files_dir) / "ocr-resources.zip") as bundle:
                bundle.extractall(self.root)

    def start(self):
        if self.running:
            return self.ocr
        from core.ocr.baas_ocr_client.Client import BaasOcrClient
        from core.ocr.ocr import Baas_ocr
        with socket.socket() as temporary:
            temporary.bind(("127.0.0.1", 0))
            port = temporary.getsockname()[1]
        config = json.loads((self.root / "resource/global_setting.json").read_text())
        config["ocr"]["server"] = {"host": "127.0.0.1", "port": port}
        config["ocr"]["num_thread"] = 2
        (self.root / "config").mkdir(exist_ok=True)
        (self.root / "config/global_setting.json").write_text(json.dumps(config))
        self.info = str(self.native.start(str(self.root)))
        self.running = True
        base = f"http://127.0.0.1:{port}"
        class LocalClient(BaasOcrClient):
            def __init__(self):
                self.config = SimpleNamespace(base_url=base)
        class LocalOcr(Baas_ocr):
            def __init__(self):
                self.client = LocalClient()
                self.logger = SimpleNamespace(info=lambda *args: None, error=lambda *args: None)
        self.ocr = LocalOcr()
        deadline = time.monotonic() + 10
        while True:
            try:
                requests.get(base + "/", timeout=.5)
                break
            except requests.RequestException:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(.1)
        self.ocr.init_model(["en-us", "ja-jp"], num_thread=2)
        return self.ocr

    def stop(self):
        if self.running:
            self.native.stop()
            self.running = False


def bind_schedule_file(scheduler, on_commit):
    """Serialize file output with the UI, preserving live checkbox changes."""
    store = jclass("moe.baas.aio.ProfileStore")
    original = scheduler._commit_change
    def commit():
        store.lockEvents()
        try:
            current = json.loads(Path(scheduler.event_config_path).read_text())
            enabled = {event["func_name"]: event["enabled"] for event in current}
            for event in scheduler._event_config:
                if event["func_name"] in enabled:
                    event["enabled"] = enabled[event["func_name"]]
            original()
        finally:
            store.unlockEvents()
        on_commit(scheduler._current_task)
    scheduler._commit_change = commit
