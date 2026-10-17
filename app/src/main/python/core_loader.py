"""Select and load one BAAS core per process: the APK snapshot or a verified downloaded package."""
import ast
from datetime import datetime
import hashlib
import json
import os
import shutil
from pathlib import Path
import sys
import threading
import types
import zipfile

# Bump whenever the glue's use of core symbols or the generated data schema changes.
CORE_API = 1
BUNDLED = "bundled"
MAX_BOOT_ATTEMPTS = 2
_PACKAGES = ("core", "module", "gui")
_selected = None
_lock = threading.Lock()


def prepare(files_dir):
    """Return the source manifest of the selected core; the choice never changes within a process."""
    global _selected
    with _lock:
        if _selected is None:
            files_dir = Path(files_dir)
            _selected = _select(files_dir)
            _migrate_profiles(files_dir, _selected[0])
        root, manifest = _selected
    _enter(root)
    return manifest


def _enter(root):
    if str(root) not in sys.path:
        sys.path.insert(0, str(root))
    os.chdir(root)


def _state_path(files_dir):
    return Path(files_dir) / "core" / "state.json"


def read_state(files_dir):
    try:
        state = json.loads(_state_path(files_dir).read_text(encoding="utf-8"))
        if isinstance(state, dict):
            return state
    except (OSError, ValueError):
        pass
    return {"active": BUNDLED}


def write_state(files_dir, state):
    path = _state_path(files_dir)
    path.parent.mkdir(parents=True, exist_ok=True)
    partial = path.with_name(path.name + ".partial")
    with open(partial, "w", encoding="utf-8") as stream:
        json.dump(state, stream, ensure_ascii=False, indent=2)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(partial, path)


def _fail(state, message, detail=None):
    """message is shown to the user; detail only goes to the log and state for diagnostics."""
    state["lastError"] = message
    state["lastErrorDetail"] = detail
    print("Core selection:", message, detail or "", file=sys.stderr)


def _extract_bundled(files_dir):
    root = files_dir / "baas-core"
    with zipfile.ZipFile(files_dir / "baas-source.zip") as archive:
        manifest = json.loads(archive.read("source-manifest.json"))
        marker = root / "source-manifest.json"
        if not marker.exists() or json.loads(marker.read_text()) != manifest:
            staging = root.with_name("baas-core.staging")
            if staging.exists():
                shutil.rmtree(staging)
            try:
                archive.extractall(staging)
                for name, digest in manifest["files"].items():
                    if hashlib.sha256((staging / name).read_bytes()).hexdigest() != digest:
                        raise RuntimeError("BAAS source verification failed: " + name)
                if root.exists():
                    shutil.rmtree(root)
                staging.rename(root)
            finally:
                if staging.exists():
                    shutil.rmtree(staging)
    return root, manifest


def _load_package(files_dir, package_id, bundled_manifest):
    """Re-check an installed package before trusting it; Kotlin verified its signature at install."""
    directory = files_dir / "core" / "ext" / package_id
    bundle = json.loads((directory / "bundle-manifest.json").read_text(encoding="utf-8"))
    if bundle.get("coreApi") != CORE_API:
        raise RuntimeError(f"Core package needs coreApi {bundle.get('coreApi')}, this app provides {CORE_API}")
    if not is_newer(bundle["core"]["commit"], bundle["core"]["commitTime"],
                    bundled_manifest["head"], bundled_manifest["commitTime"]):
        raise RuntimeError("The built-in core is newer than the downloaded package")
    for name, digest in bundle["files"].items():
        if name.startswith("source/"):
            if hashlib.sha256((directory / name).read_bytes()).hexdigest() != digest:
                raise RuntimeError("Core package file changed: " + name)
    root = directory / "source"
    return root, json.loads((root / "source-manifest.json").read_text(encoding="utf-8"))


def commit_instant(value):
    """Committer time with offset (git %cI) as a comparable UTC instant."""
    return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()


def is_newer(commit, commit_time, bundled_commit, bundled_time):
    return commit != bundled_commit and commit_instant(commit_time) > commit_instant(bundled_time)


def _select(files_dir):
    bundled = _extract_bundled(files_dir)
    state = read_state(files_dir)
    pending = state.get("pending")
    if pending:
        attempts = int(state.get("bootAttempts") or 0)
        if attempts >= MAX_BOOT_ATTEMPTS:
            state["pending"] = None; state["bootAttempts"] = 0
            _fail(state, "新版本未能完成启动检查，已回退更新前版本", "self-test did not finish twice")
            write_state(files_dir, state)
        else:
            # Persist the attempt before importing anything that could kill the process.
            state["bootAttempts"] = attempts + 1
            write_state(files_dir, state)
            saved = _boundary_modules()
            try:
                selected = _load_package(files_dir, pending, bundled[1])
                _enter(selected[0])
                # Only adds tasks and fields, so it is safe before the test and keeps old profiles parseable.
                _migrate_profiles(files_dir, selected[0])
                from core_check import run as self_test
                self_test(files_dir)
            except Exception as error:
                _unload(selected_root=files_dir / "core" / "ext" / pending / "source", saved=saved)
                state = read_state(files_dir)
                state["pending"] = None; state["bootAttempts"] = 0
                _fail(state, "新版本无法在当前应用上运行，已回退更新前版本", _describe(error))
                write_state(files_dir, state)
            else:
                state = read_state(files_dir)
                state.update(active=pending, pending=None, bootAttempts=0, lastError=None, lastErrorDetail=None)
                write_state(files_dir, state)
                return selected
    active = state.get("active") or BUNDLED
    if active != BUNDLED:
        try:
            return _load_package(files_dir, active, bundled[1])
        except Exception as error:
            state["active"] = BUNDLED
            _fail(state, "已下载的版本无法使用，已改用内置版本", _describe(error))
            write_state(files_dir, state)
    return bundled


def _describe(error):
    return f"{type(error).__name__}: {error}"[:600]


def _boundary_modules():
    """platform_bindings installs file-less stand-ins that a failed self-test must not take away."""
    return {name: module for name, module in sys.modules.items()
            if name.split(".")[0] in _PACKAGES and isinstance(module, types.ModuleType)
            and getattr(module, "__file__", None) is None and getattr(module, "__path__", None) is None}


def _unload(selected_root, saved):
    for name in [n for n in sys.modules if n.split(".")[0] in _PACKAGES]:
        del sys.modules[name]
    sys.modules.update(saved)
    while str(selected_root) in sys.path:
        sys.path.remove(str(selected_root))


def _default_events(root):
    tree = ast.parse((root / "core/config/default_config.py").read_text(encoding="utf-8"))
    for node in tree.body:
        if (isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name)
                and node.targets[0].id == "EVENT_DEFAULT_CONFIG"):
            return json.loads(ast.literal_eval(node.value))
    raise RuntimeError("EVENT_DEFAULT_CONFIG not found")


def _migrate_profiles(files_dir, root):
    from profile_migration import merge_profiles
    try:
        merge_profiles(files_dir / "profiles", _default_events(root))
    except Exception as error:
        # Migration only adds tasks; failing it must never block running the existing schedule.
        print("Event migration skipped:", _describe(error), file=sys.stderr)
