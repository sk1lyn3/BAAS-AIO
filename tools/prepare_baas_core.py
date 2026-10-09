"""Package BAAS source/resources unchanged; --revision can select an upstream commit."""
import argparse
import ast
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import zipfile


def wanted(name):
    parts = Path(name).parts
    if any(part in ("__pycache__", ".git", "output") for part in parts):
        return False
    if name.startswith(("core/ocr/baas_ocr_client/bin/", "src/scrcpy_bin/", "src/atx_app/")):
        return False
    if name.startswith(("core/", "module/")):
        return Path(name).suffix in (".py", ".json", ".txt", ".csv", ".yaml", ".yml")
    return name.startswith(("gui/", "src/")) or name in ("window.py", "LICENSE")


def constants(data):
    return {node.targets[0].id: ast.literal_eval(node.value)
            for node in ast.parse(data).body if isinstance(node, ast.Assign)
            and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name)}


def check_contract(data):
    required = {"core/Baas_thread.py": ("Baas_thread", {"init_all_data", "thread_starter", "solve", "init_config", "init_device", "handle_then"}),
                "core/scheduler.py": ("Scheduler", {"heartbeat", "systole", "_commit_change", "_read_config"})}
    for name, (class_name, methods) in required.items():
        tree = ast.parse(data[name]); cls = next(n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == class_name)
        found = {n.name for n in cls.body if isinstance(n, ast.FunctionDef)}
        if missing := methods - found:
            raise RuntimeError(f"BAAS binding contract changed in {name}: missing {sorted(missing)}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--baas", type=Path, required=True)
    parser.add_argument("--revision", help="Git revision; omitted uses the supplied source tree")
    parser.add_argument("--fixture", type=Path, help="Optional offline diagnostic screenshot")
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[1] / "app/build/generated/runtime-assets")
    parser.add_argument("--debug-output", type=Path, default=Path(__file__).resolve().parents[1] / "app/build/generated/debug-assets")
    args = parser.parse_args(); source = args.baas.resolve(); output = args.output; output.mkdir(parents=True, exist_ok=True)
    if args.revision:
        raw = subprocess.check_output(["git", "-C", str(source), "archive", args.revision])
        with tarfile.open(fileobj=io.BytesIO(raw)) as archive:
            data = {entry.name: archive.extractfile(entry).read() for entry in archive if entry.isfile() and wanted(entry.name)}
    else:
        data = {p.relative_to(source).as_posix(): p.read_bytes()
                for prefix in ("core", "module", "gui", "src") for p in (source / prefix).rglob("*")
                if p.is_file() and wanted(p.relative_to(source).as_posix())}
        for name in ("window.py", "LICENSE"):
            if (source / name).exists(): data[name] = (source / name).read_bytes()
    check_contract(data)
    defaults = constants(data["core/config/default_config.py"])
    (output / "profile-defaults.json").write_text(json.dumps({
        "config": json.loads(defaults["DEFAULT_CONFIG"]),
        "events": json.loads(defaults["EVENT_DEFAULT_CONFIG"]),
        "switch": json.loads(defaults["SWITCH_DEFAULT_CONFIG"])}, ensure_ascii=False))
    (output / "static-config.json").write_text(defaults["STATIC_DEFAULT_CONFIG"])
    activities = {}
    for mode, name in json.loads(defaults["STATIC_DEFAULT_CONFIG"])["current_game_activity"].items():
        activity_data = data.get(f"src/explore_task_data/activities/{name}.json")
        activities[mode] = {"name": name, "stages": json.loads(activity_data) if activity_data else {}} if name else None
    (output / "activities-info.json").write_text(json.dumps(activities, ensure_ascii=False))
    def sample(name, fallback):
        if args.revision:
            return json.loads(defaults[fallback])
        path = source / "config/JP_Phone" / name
        return json.loads(path.read_text()) if path.exists() else json.loads(defaults[fallback])
    phone = sample("config.json", "DEFAULT_CONFIG"); phone["server"] = "日服"
    excluded = {"program_address", "adbIP", "adbPort", "hotkey_run", "push_json", "push_serverchan", "push_feishu", "push_wecom", "open_emulator_stat", "autostart", "then"}
    config = {key: value for key, value in phone.items() if key not in excluded}
    events = sample("event.json", "EVENT_DEFAULT_CONFIG"); layout = sample("switch.json", "SWITCH_DEFAULT_CONFIG")
    (output / "profile-template.json").write_text(json.dumps({"config": config, "events": events, "switch": layout}, ensure_ascii=False))
    static = source / "config/static.json"
    data["config/static.json"] = (static.read_bytes() if static.exists() and not args.revision else defaults["STATIC_DEFAULT_CONFIG"].encode())
    def git(*arguments):
        result = subprocess.run(["git", "-C", str(source), *arguments], capture_output=True, text=True)
        return result.stdout.strip() if result.returncode == 0 else "unversioned"
    manifest = {"head": git("rev-parse", args.revision or "HEAD"), "revision": args.revision or "working-tree",
                "dirty": "" if args.revision else git("status", "--porcelain"), "files": {}}
    with zipfile.ZipFile(output / "baas-source.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        for name, body in sorted(data.items()):
            archive.writestr(name, body); manifest["files"][name] = hashlib.sha256(body).hexdigest()
        archive.writestr("source-manifest.json", json.dumps(manifest, indent=2))
    args.debug_output.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.debug_output / "probe-fixtures.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        enabled = sorted((e for e in events if e["enabled"]), key=lambda e: e["priority"])
        archive.writestr("fixtures/daily-config.json", json.dumps({"profile": "JP_Phone", "config": config, "events": enabled}))
        archive.writestr("fixtures/cafe-config.json", json.dumps({"config": {k: v for k, v in phone.items() if k.startswith("cafe_reward_")},
                            "interval": next((e["interval"] for e in events if e["func_name"] == "cafe_reward"), 10800)}))
        fixture_manifest = {}
        if args.fixture and args.fixture.exists():
            fixture = args.fixture.read_bytes(); archive.writestr("fixtures/shop.png", fixture)
            fixture_manifest["fixture_sha256"] = hashlib.sha256(fixture).hexdigest()
        archive.writestr("fixture-manifest.json", json.dumps(fixture_manifest))
    print(f"Packaged {len(data)} unchanged BAAS source/resource files from {manifest['head']} ({manifest['revision']})")


if __name__ == "__main__":
    main()
