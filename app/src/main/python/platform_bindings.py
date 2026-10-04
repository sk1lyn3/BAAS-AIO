"""Adapt desktop IO imports, never rewrite BAAS business source for Android."""
import sys
import types


class RawConfigValues:
    @staticmethod
    def tr(context, value): return value
    @staticmethod
    def undo(value): return value


class DesktopOnly:
    def __init__(self, *args, **kwargs):
        raise RuntimeError("Desktop IO is unavailable; use the Android device binding")
    @staticmethod
    def get_instance(*args, **kwargs):
        raise RuntimeError("Desktop IO is unavailable; use the Android device binding")


def install(notify=None):
    boundaries = {
        "core.device.Control": {"Control": DesktopOnly},
        "core.device.Screenshot": {"Screenshot": DesktopOnly},
        "core.device.connection": {"Connection": DesktopOnly},
        "core.device.uiautomator2_client": {"U2Client": DesktopOnly, "BAAS_U2_Initer": DesktopOnly, "__atx_agent_version__": "android-local"},
        "gui.util.translator": {"baasTranslator": RawConfigValues()},
        "gui.util.customized_ui": {"BoundComponent": DesktopOnly},
        "multiprocessing.shared_memory": {"SharedMemory": DesktopOnly},
    }
    if notify is not None:
        boundaries["core.notification"] = {"notify": notify, "toast": notify}
    for name, exports in boundaries.items():
        module = types.ModuleType(name); module.__dict__.update(exports)
        sys.modules[name] = module
