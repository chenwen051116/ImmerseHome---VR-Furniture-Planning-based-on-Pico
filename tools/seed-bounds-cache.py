#!/usr/bin/env python3
"""Seed the app's measured-bounds cache (.bounds-cache.json) for a device/emulator.

The Room Planner app measures each model's bounding box natively (slow and, on the
x86 emulator, crash-prone for huge models). This tool computes the same world-space
bounds offline from the LOCAL copies and writes the cache file with the DEVICE files'
mtimes, so the app's catalog builds instantly.

Run AFTER pushing the models to the device:

  python tools/seed-bounds-cache.py --models-dir models --package com.example.testfull [--push]

--push also pushes the resulting cache to the device (needs adb on PATH or ADB env var).
"""

import argparse
import importlib.util
import json
import subprocess
import sys
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "gr", str(Path(__file__).with_name("glb-rescale.py"))
)
gr = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gr)

MODEL_EXTS = {".glb", ".gltf", ".usda", ".usdc", ".usdz"}


def find_adb():
    import os

    for candidate in [
        os.environ.get("ADB"),
        r"C:\Users\Taven\AppData\Local\Android\Sdk\platform-tools\adb.exe",
        "adb",
    ]:
        if not candidate:
            continue
        try:
            subprocess.run(
                [candidate, "version"],
                capture_output=True,
                check=True,
                timeout=10,
            )
            return candidate
        except Exception:
            continue
    raise SystemExit("adb not found — set the ADB env var or add it to PATH")


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--models-dir", default="models")
    parser.add_argument("--package", default="com.example.testfull")
    parser.add_argument("--out", default="build/bounds-cache.json")
    parser.add_argument("--push", action="store_true")
    args = parser.parse_args()

    models_dir = Path(args.models_dir)
    files = sorted(
        p for p in models_dir.iterdir() if p.suffix.lower() in MODEL_EXTS
    )
    if not files:
        raise SystemExit(f"no model files in {models_dir}")

    remote_root = f"/storage/emulated/0/Android/data/{args.package}/files/models"
    adb = find_adb() if args.push else None

    cache = {}
    for model in files:
        doc, bin_chunk = gr.read_glb(str(model))
        lo, hi = gr.world_aabb(doc, bin_chunk)
        entry = {
            "c": [round((lo[k] + hi[k]) / 2, 6) for k in range(3)],
            "h": [round((hi[k] - lo[k]) / 2, 6) for k in range(3)],
            "b": round(max(0.0, -lo[1]), 6),
            "m": 0,
        }
        if args.push:
            result = subprocess.run(
                [
                    adb,
                    "shell",
                    "stat",
                    "-c",
                    "%Y",
                    f"/sdcard/Android/data/{args.package}/files/models/{model.name}",
                ],
                capture_output=True,
                text=True,
                check=True,
            )
            entry["m"] = int(result.stdout.strip()) * 1000
        cache[f"{remote_root}/{model.name}"] = entry
        size = [entry["h"][k] * 2 for k in range(3)]
        print(f"{model.name}: {size[0]:.3f} x {size[1]:.3f} x {size[2]:.3f}")

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(cache), encoding="utf-8")
    print(f"written {out} ({len(cache)} entries)")

    if args.push:
        subprocess.run(
            [
                adb,
                "push",
                str(out),
                f"/sdcard/Android/data/{args.package}/files/models/.bounds-cache.json",
            ],
            check=True,
        )
        print("pushed .bounds-cache.json to device")
    return 0


if __name__ == "__main__":
    sys.exit(main())
