"""Android IO bindings for unmodified BAAS classes; no task selection logic."""
import json
from pathlib import Path
import shutil
import socket
import time
from types import SimpleNamespace
import zipfile

import requests
from java import jclass


def local_ocr(base_url):
    """Use the original BAAS OCR API with an already running local service."""
    import json as json_module
    from core.ocr.baas_ocr_client.Client import BaasOcrClient
    from core.ocr.ocr import Baas_ocr

    class LocalClient(BaasOcrClient):
        # Fail fast on connect; allow slow model load / recognition without hanging stop.
        TIMEOUT = (3, 120)

        def __init__(self):
            self.config = SimpleNamespace(base_url=base_url)

        def _post(self, url, **kwargs):
            kwargs.setdefault("timeout", self.TIMEOUT)
            return requests.post(url, **kwargs)

        def init_model(self, language, gpu_id=-1, num_thread=4, EnableCpuMemoryArena=False):
            return self._post(self.config.base_url + "/init_model", json={
                "language": language,
                "gpu_id": gpu_id,
                "num_thread": num_thread,
                "EnableCpuMemoryArena": EnableCpuMemoryArena,
            })

        def release_model(self, language):
            return self._post(self.config.base_url + "/release_model", json={"language": language})

        def release_all(self):
            return requests.get(self.config.base_url + "/release_all", timeout=self.TIMEOUT)

        def ocr(self, language, origin_image=None, candidates="", pass_method=0, local_path="",
                ret_options=0b100, shared_memory_name=""):
            url = self.config.base_url + "/ocr"
            data = {
                "language": language,
                "candidates": candidates,
                "image": {"pass_method": pass_method},
                "ret_options": ret_options,
            }
            return self._image_post(url, data, pass_method, origin_image, local_path, shared_memory_name)

        def get_text_boxes(self, language, origin_image=None, pass_method=0, local_path="",
                           shared_memory_name=""):
            url = self.config.base_url + "/get_text_boxes"
            data = {"language": language, "image": {"pass_method": pass_method}}
            return self._image_post(url, data, pass_method, origin_image, local_path, shared_memory_name)

        def ocr_for_single_line(self, language, origin_image=None, candidates="", pass_method=0,
                                local_path="", shared_memory_name=""):
            url = self.config.base_url + "/ocr_for_single_line"
            data = {
                "language": language,
                "candidates": candidates,
                "image": {"pass_method": pass_method},
            }
            return self._image_post(url, data, pass_method, origin_image, local_path, shared_memory_name)

        def _image_post(self, url, data, pass_method, origin_image, local_path, shared_memory_name):
            self.get_request_data(data, pass_method, origin_image, local_path, shared_memory_name)
            if pass_method in (0, 2):
                return self._post(url, json=data)
            if pass_method == 1:
                files = {
                    "data": (None, json_module.dumps(data), "application/json"),
                    "image": ("image.png", self.get_image_bytes(origin_image), "image/png"),
                }
                return self._post(url, files=files)
            raise ValueError(f"Invalid pass_method {pass_method}")

    class LocalOcr(Baas_ocr):
        def __init__(self):
            self.client = LocalClient()
            self.logger = SimpleNamespace(info=lambda *args: None, error=lambda *args: None)

    return LocalOcr()


def prepare_ocr_resources(files_dir):
    root = Path(files_dir) / "workflow-ocr"
    resource = root / "resource"
    marker = resource / ".bundle-index.json"
    with zipfile.ZipFile(Path(files_dir) / "ocr-resources.zip") as bundle:
        # Only resource/ is installed; top-level metadata is discarded with staging.
        # The central directory is small; no model data is read on a cache hit.
        index = sorted((entry.filename, entry.CRC, entry.file_size)
                       for entry in bundle.infolist()
                       if not entry.is_dir() and entry.filename.startswith("resource/"))
        signature = json.dumps(index)
        if (marker.is_file() and marker.read_text() == signature
                and all((root / name).is_file() and (root / name).stat().st_size == size
                        for name, _, size in index)):
            return root
        staging = root.with_name("workflow-ocr.staging")
        if staging.exists():
            shutil.rmtree(staging)
        try:
            bundle.extractall(staging)
            if not (staging / "resource/global_setting.json").is_file():
                raise RuntimeError("OCR resource bundle is missing global_setting.json")
            (staging / "resource/.bundle-index.json").write_text(signature)
            root.mkdir(parents=True, exist_ok=True)
            if resource.exists():
                shutil.rmtree(resource)
            (staging / "resource").rename(resource)
        finally:
            if staging.exists():
                shutil.rmtree(staging)
    return root


class AndroidOcr:
    def __init__(self, files_dir, native):
        self.native = native
        self.root = prepare_ocr_resources(files_dir)
        self.running = False

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
    """Preserve live UI edits when BAAS writes a task's next execution time."""
    store = jclass("moe.baas.aio.ProfileStore")
    original = scheduler._commit_change
    original_read = scheduler._read_config
    ticks = {e["func_name"]: e["next_tick"] for e in scheduler._event_config}
    def read():
        original_read()
        ticks.clear()
        ticks.update((e["func_name"], e["next_tick"]) for e in scheduler._event_config)
    scheduler._read_config = read
    def commit():
        store.lockEvents()
        try:
            current = json.loads(Path(scheduler.event_config_path).read_text())
            edits = {event["func_name"]: event for event in current}
            for event in scheduler._event_config:
                saved = edits.get(event["func_name"])
                if saved is not None:
                    tick = event["next_tick"]
                    previous = ticks.get(event["func_name"])
                    event.update(saved)
                    # A newer UI edit wins if it changed the same execution time.
                    if tick != previous and saved["next_tick"] == previous:
                        event["next_tick"] = tick
            original()
            ticks.clear()
            ticks.update((e["func_name"], e["next_tick"]) for e in scheduler._event_config)
        finally:
            store.unlockEvents()
        on_commit(scheduler._current_task)
    scheduler._commit_change = commit


def bind_task_progress(scheduler, on_update):
    """Observe main tasks in the upstream queue without changing scheduling."""
    heartbeat, systole = scheduler.heartbeat, scheduler.systole
    completed, current, idle = 0, None, True

    def next_task():
        nonlocal completed, current, idle
        task = heartbeat()
        if task is None:
            current = None
            idle = True
        else:
            if idle:
                completed = 0
            idle = False
            current = task["current_task"]
            on_update(completed, completed + 1 + len(scheduler._valid_task_queue))
        return task

    def finish(task_name, next_time=0, force_task=False):
        nonlocal completed, current
        result = systole(task_name, next_time, force_task)
        if result is not None and not force_task and task_name == current:
            completed += 1
            current = None
            on_update(completed, completed + len(scheduler._valid_task_queue))
        return result

    scheduler.heartbeat, scheduler.systole = next_task, finish
