"""Restore the lobby, then repeatedly check entry templates without entering the event."""
import json
from pathlib import Path
import shutil


def run(files_dir, bridge, native, gate, cycles=0, task="activity_entry_probe", profile_path=None):
    from core_loader import prepare
    manifest = prepare(files_dir)
    from platform_bindings import install
    install()
    import cv2
    import numpy as np
    from core import image, position
    from core.Baas_thread import func_dict
    import core_task

    profile = Path(files_dir) / "activity-entry-probe" / "profile"
    profile.mkdir(parents=True, exist_ok=True)
    for name in ("config.json", "event.json", "switch.json"):
        source = Path(profile_path) / name
        if source.exists():
            shutil.copyfile(source, profile / name)
    evidence = {"samples": 0, "clickedEntry": False}

    def detect(engine, name):
        template = position.image_dic[engine.identifier].get(name)
        item = {"name": name, "available": template is not None, "matched": False}
        if template is None:
            item["reason"] = "template missing"
        else:
            area = position.get_area(engine.identifier, name)
            if not area:
                item["reason"] = "recognition area missing"
            else:
                crop = image.screenshot_cut(engine, area)
                score = float(cv2.matchTemplate(cv2.resize(crop, (template.shape[1], template.shape[0]),
                    interpolation=cv2.INTER_AREA), template, cv2.TM_CCOEFF_NORMED)[0][0])
                delta = np.abs(np.mean(template, axis=(0, 1)) - np.mean(crop, axis=(0, 1)))
                item.update(area=list(area), score=round(score, 4),
                            colorDifference=[round(float(x), 1) for x in delta],
                            colorPassed=bool(image.compare_image_rgb(template, crop, 20)),
                            matched=bool(image.compare_image(engine, name)))
        return item

    def probe(engine):
        engine.to_main_page()
        evidence.update(identifier=engine.identifier, activity=engine.current_game_activity, source=manifest["head"])
        gate.log(1, f"Activity entry probe: {engine.identifier} / {engine.current_game_activity}; Core {manifest['head'][:7]}")
        gate.log(1, "Checking every second until stopped; threshold >0.8, color difference <=20; no entry clicks")
        if getattr(engine.config, "activity_sweep_use_generic_entry", False):
            gate.log(1, "Generic entry is enabled: normal navigation bypasses enter1/2/3 templates")
        for name in ("activity_enter1", "activity_enter2", "activity_enter3"):
            present = name in position.image_dic[engine.identifier]
            area = position.get_area(engine.identifier, name) if present else None
            gate.log(1, f"{name}: template={'available' if present else 'missing'}, area={area}")
        while engine.flag_run:
            # Capture runs the existing task checkpoint, including pause/resume/stop.
            engine.update_screenshot_array()
            results = [detect(engine, name) for name in ("activity_enter1", "activity_enter2", "activity_enter3")]
            evidence["samples"] += 1
            evidence["results"] = results
            evidence["firstMatched"] = next((item["name"] for item in results if item["matched"]), None)
            for item in results:
                details = item.get("reason") or (f"score={item['score']:.4f}, colorDiffBGR={item['colorDifference']}, colorOK={item['colorPassed']}")
                gate.log(1, f"Entry #{evidence['samples']} {item['name']}: {'MATCH' if item['matched'] else 'MISS'}; {details}")
            gate.waitForNext(1000)
        return True

    original = func_dict["activity_sweep"]
    try:
        func_dict["activity_sweep"] = probe
        result = json.loads(core_task.run(files_dir, bridge, native, gate, task="core_task",
                                         profile_path=str(profile), manual_task="activity_sweep"))
        result["activityEntryProbe"] = evidence
        return json.dumps(result, ensure_ascii=False)
    finally:
        func_dict["activity_sweep"] = original
