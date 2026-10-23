"""First-start self-test of a newly installed core: imports and schedule parsing only, no device or OCR."""
import inspect
import json
import shutil
import tempfile
from pathlib import Path

CONTRACT = {
    "core.Baas_thread": {"Baas_thread": ("init_all_data", "thread_starter", "solve", "init_config", "init_device",
                                         "handle_then", "deal_with_func_call_timeout", "deal_with_package_incorrect")},
    "core.scheduler": {"Scheduler": ("heartbeat", "systole", "_commit_change", "_read_config",
                                     "update_valid_task_queue", "convert_to_seconds")},
    "core.ocr.ocr": {"Baas_ocr": ("get_region_res",)},
}


def run(files_dir):
    from platform_bindings import install
    install()
    import importlib
    for name, classes in CONTRACT.items():
        module = importlib.import_module(name)
        for class_name, methods in classes.items():
            cls = getattr(module, class_name)
            missing = [m for m in methods if not callable(getattr(cls, m, None))]
            if missing:
                raise RuntimeError(f"{name}.{class_name} is missing {missing}")
    from core.Baas_thread import func_dict
    from core.config.config_set import ConfigSet
    from core.config.default_config import EVENT_DEFAULT_CONFIG
    from core.exception import RequestHumanTakeOver, PackageIncorrect  # noqa: F401
    from core import position
    from core.device.swipe_compat import apply  # noqa: F401
    from core.ocr.baas_ocr_client.Client import BaasOcrClient
    from core.scheduler import Scheduler
    from profile_migration import merge_profile
    for attribute in ("initialized_image", "image_dic", "image_x_y_range"):
        if not hasattr(position, attribute):
            raise RuntimeError("core.position is missing " + attribute)
    if "num_thread" not in inspect.signature(BaasOcrClient.init_model).parameters:
        raise RuntimeError("BaasOcrClient.init_model signature changed")
    for task in ("restart", "de_clothes"):
        if task not in func_dict:
            raise RuntimeError("func_dict is missing " + task)
    # ConfigSet may migrate shop lists; only touch disposable copies so a failed
    # package activation cannot rewrite the user's real config.json.
    with tempfile.TemporaryDirectory(prefix="baas-core-check-") as staging:
        defaults = json.loads(EVENT_DEFAULT_CONFIG)
        for profile in sorted((Path(files_dir) / "profiles").glob("*/event.json")):
            source = profile.parent
            copy = Path(staging) / source.name
            copy.mkdir()
            for name in ("config.json", "event.json"):
                src = source / name
                if src.is_file():
                    shutil.copy2(src, copy / name)
            if not (copy / "config.json").is_file():
                continue
            merge_profile(copy, defaults)
            ConfigSet(str(copy))
            Scheduler(None, str(copy)).update_valid_task_queue()
