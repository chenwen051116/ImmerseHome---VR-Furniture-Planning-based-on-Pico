#!/usr/bin/env python3
"""Refine the Stage immersion variant on a `pico-cli project create` project.

`pico-cli project create --template <planar|volumetric|stage>` owns the base
project: package layout, entry chain (`Main.kt`), and a fully-populated
`AndroidManifest.xml` (the WindowContainer / Stage meta-data is already there).

The only thing the three CLI templates cannot express is which Stage immersion
variant is wanted, since `STAGE_MIXED`, `STAGE_PROGRESSIVE`, and `STAGE_FULL`
all map to `--template stage`. This script adjusts the *already-present*
`pico.spatial.stage.*` meta-data values in the launcher activity to match the
chosen variant. It does not insert meta-data, rename files, or rewrite Kotlin.

For `ON_PLAIN` / `IN_VOLUME` the CLI output is already correct and this is a
no-op.

Usage:
    python3 -m scripts.inject_container \
        --output ./generated-spatial-app \
        --container STAGE_FULL
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path


STAGE_META_VALUES = {
    "STAGE_MIXED": {
        "pico.spatial.stage.style": "1",
        "pico.spatial.stage.immersion": "0",
        "pico.spatial.stage.immersion_min": "0",
        "pico.spatial.stage.immersion_max": "0",
    },
    "STAGE_PROGRESSIVE": {
        "pico.spatial.stage.style": "2",
        "pico.spatial.stage.immersion": "50",
        "pico.spatial.stage.immersion_min": "0",
        "pico.spatial.stage.immersion_max": "100",
    },
    "STAGE_FULL": {
        "pico.spatial.stage.style": "3",
        "pico.spatial.stage.immersion": "100",
        "pico.spatial.stage.immersion_min": "100",
        "pico.spatial.stage.immersion_max": "100",
    },
}

WINDOW_CONTAINERS = {"ON_PLAIN", "IN_VOLUME"}
SUPPORTED_CONTAINERS = WINDOW_CONTAINERS | set(STAGE_META_VALUES)


def find_manifest(output: Path) -> Path:
    candidates = list((output / "app" / "src" / "main").glob("AndroidManifest.xml"))
    if not candidates:
        raise SystemExit("[inject] AndroidManifest.xml not found")
    return candidates[0]


def set_meta_value(text: str, name: str, value: str) -> tuple[str, bool]:
    """Set the android:value of an existing <meta-data android:name="..."> tag.

    Handles both single-line and multi-line meta-data declarations. Returns the
    new text and whether a change was made.
    """
    pattern = re.compile(
        r'(<meta-data\b[^>]*android:name="' + re.escape(name) + r'"[^>]*android:value=")([^"]*)(")',
        re.DOTALL,
    )

    changed = False

    def repl(match: re.Match[str]) -> str:
        nonlocal changed
        if match.group(2) == value:
            return match.group(0)
        changed = True
        return match.group(1) + value + match.group(3)

    new_text = pattern.sub(repl, text)
    return new_text, changed


def apply_stage_variant(manifest: Path, container: str) -> list[str]:
    text = manifest.read_text(encoding="utf-8")
    updated: list[str] = []
    for name, value in STAGE_META_VALUES[container].items():
        if f'android:name="{name}"' not in text:
            raise SystemExit(
                f"[inject] expected meta-data {name!r} not found in {manifest}. "
                "The pico-cli stage template should already declare it."
            )
        text, changed = set_meta_value(text, name, value)
        if changed:
            updated.append(name)
    manifest.write_text(text, encoding="utf-8")
    return updated


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output",
        required=True,
        help="Project root produced by `pico-cli project create`",
    )
    parser.add_argument(
        "--container",
        required=True,
        choices=sorted(SUPPORTED_CONTAINERS),
        help="Target spatial container chosen in Phase 4",
    )
    args = parser.parse_args()

    output = Path(args.output).resolve()
    if not output.exists():
        raise SystemExit(f"[inject] Output dir not found: {output}")

    container = args.container

    if container in WINDOW_CONTAINERS:
        print(
            f"[inject] container={container}: pico-cli "
            f"--template {'planar' if container == 'ON_PLAIN' else 'volumetric'} "
            "output is already correct; nothing to refine."
        )
        return 0

    manifest = find_manifest(output)
    updated = apply_stage_variant(manifest, container)
    print(f"[inject] container={container}: stage meta updated={updated or 'already matched'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
