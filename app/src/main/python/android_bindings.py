"""Android IO bindings for unmodified BAAS classes; no task selection logic."""
import json
from pathlib import Path
import socket
import time
from types import SimpleNamespace
import zipfile

import requests
from java import jclass


def local_ocr(base_url):
    """Use the original BAAS OCR API with an already running local service."""
    from core.ocr.baas_ocr_client.Client import BaasOcrClient
    from core.ocr.ocr import Baas_ocr

    class LocalClient(BaasOcrClient):
        def __init__(self):
            self.config = SimpleNamespace(base_url=base_url)

    class LocalOcr(Baas_ocr):
        def __init__(self):
            self.client = LocalClient()
            self.logger = SimpleNamespace(info=lambda *args: None, error=lambda *args: None)

    return LocalOcr()


class AndroidOcr:
    def __init__(self, files_dir, native):
        self.native = native
        self.root = Path(files_dir) / "workflow-ocr"
        self.running = False
        if not (self.root / "resource").exists():
            with zipfile.ZipFile(Path(files_dir) / "ocr-resources.zip") as bundle:
                bundle.extractall(self.root)

    def start(self, languages=None):
        if self.running:
            return self.ocr
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
        self.ocr = local_ocr(base)
        deadline = time.monotonic() + 10
        while True:
            try:
                requests.get(base + "/", timeout=.5)
                break
            except requests.RequestException:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(.1)
        self.ocr.init_model(list(dict.fromkeys(["en-us"] + (languages or ["ja-jp"]))), num_thread=2)
        return self.ocr

    def stop(self):
        if self.running:
            self.native.stop()
            self.running = False


def bind_schedule_file(scheduler, on_commit):
    """Serialize file output with the UI, preserving live toggles and ordering."""
    store = jclass("moe.baas.aio.ProfileStore")
    original = scheduler._commit_change
    def commit():
        store.lockEvents()
        try:
            current = json.loads(Path(scheduler.event_config_path).read_text())
            edits = {event["func_name"]: event for event in current}
            for event in scheduler._event_config:
                saved = edits.get(event["func_name"])
                if saved is not None:
                    for key in ("enabled", "priority"):
                        event[key] = saved[key]
            original()
        finally:
            store.unlockEvents()
        on_commit(scheduler._current_task)
    scheduler._commit_change = commit
