"""One AIO operation using the core's existing home-page resource readers."""
from types import SimpleNamespace


def run(engine):
    current = engine.u2.app_current()["package"]
    if current != engine.package_name:
        if current in engine.static_config.package_name.values():
            engine.u2.app_stop(current)
        # Resolve the game's launcher, as the normal Companion preview does.
        engine.u2.app_start(engine.package_name)
    engine.to_main_page()
    engine.update_screenshot_array()
    original = engine.ocr

    def read(*args, **kwargs):
        value = original.get_region_res(*args, **kwargs)
        # The upstream currency readers otherwise turn an empty OCR result into 0.
        if args[3] in ("Credit Points", "Pyroxene") and not any(c.isdigit() for c in value):
            raise ValueError("Resource OCR returned no digits: " + args[3])
        return value

    engine.ocr = SimpleNamespace(get_region_res=read)
    try:
        engine.main_page_update_data()
    finally:
        engine.ocr = original
