"""Replay one normal/hard mission using the original complete exploration flow."""
import json
from pathlib import Path
import re
import shutil


def run(files_dir, bridge, native, gate, cycles=0, task="explore_probe", profile_path=None,
        options_json="{}"):
    options = json.loads(options_json)
    if not isinstance(options, dict) or set(options) != {"mission"}:
        raise ValueError("Specify one mission, for example 24-1 or H15-3")
    mission = str(options["mission"]).strip().upper()
    match = re.fullmatch(r"(H)?([1-9][0-9]*)-([1-5])", mission)
    if match is None or (match[1] and int(match[3]) > 3):
        raise ValueError("Invalid mission: " + mission)
    hard = bool(match[1])
    stage = f"{match[2]}-{match[3]}"

    from core_loader import prepare
    prepare(files_dir)
    from platform_bindings import install
    install()
    from core.Baas_thread import func_dict
    from module.explore_tasks import explore_task
    import core_task

    profile = Path(files_dir) / "explore-probe" / "profile"
    profile.mkdir(parents=True, exist_ok=True)
    for name in ("config.json", "event.json", "switch.json"):
        source = Path(profile_path) / name
        if source.exists():
            shutil.copyfile(source, profile / name)
    config_path = profile / "config.json"
    config = json.loads(config_path.read_text())
    key = "explore_hard_task_list" if hard else "explore_normal_task_list"
    config.update({key: stage, "explore_task_use_simple_mode": False})
    config_path.write_text(json.dumps(config, ensure_ascii=False))
    task_name = "explore_hard_task" if hard else "explore_normal_task"
    original = func_dict[task_name]
    explore = explore_task.explore_hard_task if hard else explore_task.explore_normal_task

    def probe(engine):
        engine.logger.info(f"Debug replay {mission}: full route, including completed stages")
        ok = explore(engine, force=True)
        if ok:
            engine.to_main_page()
        return ok

    try:
        func_dict[task_name] = probe
        result = json.loads(core_task.run(files_dir, bridge, native, gate, task="core_task",
                                         profile_path=str(profile), manual_task=task_name))
        result["exploreProbe"] = {"mission": mission, "force": True, "simpleMode": False}
        return json.dumps(result, ensure_ascii=False)
    finally:
        func_dict[task_name] = original
