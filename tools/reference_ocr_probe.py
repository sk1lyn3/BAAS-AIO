"""Produce OCR comparison data in an isolated desktop server process."""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time

import cv2
import requests


def cases(models, fixture):
    result = []
    for language in ("zh-cn", "zh-cn_v3", "zh-tw", "ja-jp", "en-us", "ko-kr", "ru-ru"):
        result.append((language + ":model-test", language,
                       cv2.imread(str(models / "test_images" / (language + ".png"))), "", None))
    frame = cv2.imread(str(fixture))
    for name, lang, region, candidates in [
        ("shop-title", "ja-jp", (104, 5, 221, 37), ""),
        ("tactical-shop", "ja-jp", (35, 267, 198, 304), ""),
        ("shop-currency", "en-us", (928, 8, 1034, 38), "0123456789"),
        ("shop-price", "en-us", (681, 307, 741, 333), "0123456789"),
    ]:
        x1, y1, x2, y2 = region
        result.append((name, lang, frame[y1:y2, x1:x2], candidates, region))
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--baas", type=Path, required=True)
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    binary = (args.baas / "core/ocr/baas_ocr_client/bin").resolve()
    with tempfile.TemporaryDirectory(prefix="baas-ocr-reference-") as temporary:
        root = Path(temporary)
        (root / "resource").symlink_to(binary / "resource", target_is_directory=True)
        (root / "config").mkdir()
        with socket.socket() as port_socket:
            port_socket.bind(("127.0.0.1", 0))
            port = port_socket.getsockname()[1]
        config = json.loads((binary / "resource/global_setting.json").read_text())
        config["ocr"]["server"] = {"host": "127.0.0.1", "port": port}
        config["ocr"]["num_thread"] = 2
        (root / "config/global_setting.json").write_text(json.dumps(config))
        env = os.environ.copy()
        env["LD_LIBRARY_PATH"] = str(binary)
        process = subprocess.Popen([str(binary / "BAAS_ocr_server")], cwd=root, env=env,
                                   stdin=subprocess.PIPE, stdout=subprocess.DEVNULL)
        report = {"cases": [], "server": "isolated desktop BAAS OCR"}
        try:
            base = f"http://127.0.0.1:{port}"
            deadline = time.monotonic() + 10
            while True:
                assert process.poll() is None, "Reference server exited"
                try:
                    requests.post(base + "/test", timeout=1).raise_for_status()
                    break
                except requests.RequestException:
                    if time.monotonic() > deadline: raise
                    time.sleep(.1)
            for name, lang, image, candidates, region in cases(binary / "resource/ocr_models", args.fixture):
                assert image is not None, name
                result = requests.post(base + "/init_model", json={"language": [lang], "num_thread": 2,
                                        "gpu_id": -1, "EnableCpuMemoryArena": False}, timeout=60)
                result.raise_for_status()
                _, png = cv2.imencode(".png", image)
                data = {"language": lang, "candidates": candidates, "image": {"pass_method": 1}}
                result = requests.post(base + "/ocr_for_single_line", files={
                    "data": (None, json.dumps(data), "application/json"),
                    "image": ("image.png", png.tobytes(), "image/png")}, timeout=30)
                result.raise_for_status()
                raw = result.json()
                text = ''.join(c for c, score in zip(raw['text'], raw['char_scores']) if score > .2)
                assert text, (name, raw)
                report["cases"].append({"name": name, "language": lang, "region": region,
                                        "candidates": candidates, "expected": text, "raw": raw})
                requests.get(base + "/release_all", timeout=30).raise_for_status()
                print(name, repr(text), flush=True)
            requests.post(base + "/init_model", json={"language": ["ja-jp"], "num_thread": 2}, timeout=60).raise_for_status()
            region = [28, 109, 209, 552]
            frame = cv2.imread(str(args.fixture))
            _, png = cv2.imencode(".png", frame[109:552, 28:209])
            data = {"language": "ja-jp", "image": {"pass_method": 1}, "ret_options": 7}
            result = requests.post(base + "/ocr", files={
                "data": (None, json.dumps(data), "application/json"),
                "image": ("image.png", png.tobytes(), "image/png")}, timeout=30)
            result.raise_for_status()
            report["multiline"] = {"region": region, "raw": result.json()}
            assert len(report["multiline"]["raw"]["text_list"]) == 6
            print('multiline:', [x['text'] for x in result.json()['text_list']], flush=True)
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
        finally:
            if process.poll() is None:
                process.stdin.write(b"exit\n")
                process.stdin.flush()
                try: process.wait(10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


if __name__ == "__main__":
    main()
