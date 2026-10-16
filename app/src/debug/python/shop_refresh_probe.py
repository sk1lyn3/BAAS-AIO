"""Verify both shop refresh dialogs; never purchase or confirm a refresh."""
import json
from pathlib import Path
import shutil
import time


def run(files_dir, bridge, native, gate, cycles=0, task="shop_refresh_probe", profile_path=None):
    from core_loader import prepare
    prepare(files_dir)
    from platform_bindings import install
    install()
    from core.Baas_thread import func_dict
    from core import image
    from module.shop import common_shop, tactical_challenge_shop
    from module.shop.shop_utils import goto_shop_by_name
    import core_task

    root = Path(files_dir) / "shop-refresh-probe"
    profile = root / "profile"
    profile.mkdir(parents=True, exist_ok=True)
    for name in ("config.json", "event.json", "switch.json"):
        source = Path(profile_path) / name
        if source.exists():
            shutil.copyfile(source, profile / name)
    evidence = {"dryRun": True, "purchased": False, "refreshConfirmed": False, "shops": []}
    deadline = time.monotonic() + 180

    class ProbeGate:
        def __getattr__(self, name):
            return getattr(gate, name)

        def checkpoint(self, phase):
            if time.monotonic() > deadline:
                raise RuntimeError("Shop refresh probe exceeded 180 seconds")
            return gate.checkpoint(phase)

    def probe(engine):
        import cv2

        def wait_for(feature):
            deadline = time.monotonic() + 12
            while time.monotonic() < deadline:
                if not gate.checkpoint("Verify shop refresh"):
                    raise RuntimeError("Shop refresh probe stopped")
                engine.update_screenshot_array()
                if image.compare_image(engine, feature):
                    return
                time.sleep(.2)
            raise RuntimeError("Shop refresh probe could not recognize " + feature)

        engine.to_main_page()
        evidence["identifier"] = engine.identifier
        tactical_index = 6 if engine.server == "JP" else 5
        for name, index, shop in (("common_shop", 0, common_shop),
                                  ("tactical_challenge_shop", tactical_index, tactical_challenge_shop)):
            entry = {"shop": name, "recognized": False, "returnedToShop": False}
            evidence["shops"].append(entry)
            common_shop.to_common_shop(engine, True)
            if name == "tactical_challenge_shop":
                goto_shop_by_name(engine, index)
            wait_for("shop_menu")
            shop.to_refresh(engine)
            wait_for("shop_refresh-notice")
            screenshot = root / (name + "-refresh-dialog.png")
            cv2.imwrite(str(screenshot), engine.latest_img_array)
            entry.update(recognized=True, screenshot=str(screenshot))
            engine.logger.info(name + " refresh dialog recognized; cancel without spending currency")
            engine.click(511, 480, wait_over=True)
            wait_for("shop_menu")
            entry["returnedToShop"] = True
            engine.to_main_page()
        return True

    original = func_dict["common_shop"]
    try:
        func_dict["common_shop"] = probe
        result = json.loads(core_task.run(files_dir, bridge, native, ProbeGate(), task="core_task",
                                         profile_path=str(profile), manual_task="common_shop"))
        result["shopRefreshProbe"] = evidence
        return json.dumps(result, ensure_ascii=False)
    finally:
        func_dict["common_shop"] = original
