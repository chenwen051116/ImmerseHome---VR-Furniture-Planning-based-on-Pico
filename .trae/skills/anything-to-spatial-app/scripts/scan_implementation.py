#!/usr/bin/env python3
"""Implementation-level scanner for anything-to-spatial-app.

Goes one step beyond `check_layout_structure.py`: instead of only validating
the .scratch/ artifacts, this scanner reads the actual generated Kotlin and
AndroidManifest.xml files and compares them to the Spatial Layout Contract.

Usage:
    python3 -m scripts.scan_implementation --target ./myapp
    python3 /abs/path/to/scan_implementation.py --target ./generated-spatial-app

It writes a structured artifact to `<target>/.scratch/implementation_scan_result.json`
with per-check pass/fail and concrete failure messages, and exits non-zero when
any check fails.

Checks performed (best-effort, regex-based; never blocks on missing files):

- root_match            — Root container in code matches contract.container
- entry_wired           — `mainApp` / `Application.launch(::mainApp)` are present
- manifest_consistency  — Manifest declares the right windowcontainer.id and
                          (when applicable) windowcontainer.style for IN_VOLUME
- stage_api_legality    — Stage-only APIs (anchor, env_mesh, ECS) only appear
                          when contract.container is a Stage container
- whitelist_components  — Compose imports do not reference invented SpatialUI
                          symbols outside a small allow-list
- window_chrome_ornaments — edge-pinned window ornaments declared in the
                          contract use window-level fittings instead of
                          hand-rolled in-page overlays

This scanner is intentionally conservative: false positives are worse than
false negatives. Unknown patterns degrade to WARN, not FAIL.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any


WINDOW_CONTAINERS = {"ON_PLAIN", "IN_VOLUME"}
STAGE_CONTAINERS = {"STAGE_MIXED", "STAGE_PROGRESSIVE", "STAGE_FULL"}
STAGE_MANIFEST_EXPECTATIONS = {
    "STAGE_MIXED": {
        "pico.spatial.stage.style": "1",
        "pico.spatial.stage.immersion": "0",
        "pico.spatial.stage.immersion_min": "0",
        "pico.spatial.stage.immersion_max": "0",
    },
    "STAGE_PROGRESSIVE": {
        "pico.spatial.stage.style": "2",
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
LAYOUT_CANDIDATES = [
    "spatial_layout_contract.json",
    "spatial_layout.json",
    "window_structure.json",
]
PATCH_CONTRACT = "patch_contract.json"

# Patterns used to detect root invocations in code. Imports alone are ignored.
WINDOW_ROOT_PATTERNS = (
    r"\bDefaultWindowContainer\s*(?:\(|\{)",
    r"\bWindowContainer\s*(?:\(|\{)",
)
STAGE_ROOT_PATTERNS = (
    r"\bDefaultStage\s*(?:\(|\{)",
    r"\bStage\s*(?:\(|\{)",
)

# Stage-only API hints. Patterns verified against the PICO SpatialSDK
# source (sensepack + spatialpack/core/ecs). Each manager / anchor type below
# is annotated `@RequiredFullSpace` and throws when called outside Full Space.
STAGE_ONLY_API_PATTERNS = (
    r"\bWorldTrackingManager\b",      # com.pico.spatial.sense.world.WorldTrackingManager
    r"\bPlaneTrackingManager\b",      # com.pico.spatial.sense.plane.PlaneTrackingManager
    r"\bMeshTrackingManager\b",       # com.pico.spatial.sense.mesh.MeshTrackingManager
    r"\bWorldAnchor\b",               # com.pico.spatial.sense.world.WorldAnchor
    r"\bPlaneAnchor\b",               # com.pico.spatial.sense.plane.PlaneAnchor
    r"\bMeshAnchor\b",                # com.pico.spatial.sense.mesh.MeshAnchor
    r"\bWorldTrackingResult\b",       # sealed result type
    r"\bAnchorEntity\b",              # com.pico.spatial.core.ecs.AnchorEntity
    r"\bAnchorComponent\b",           # com.pico.spatial.core.ecs.AnchorComponent
    r"\bAnchorTarget\b",              # com.pico.spatial.core.ecs.anchor.AnchorTarget
    r"\b@RequiredFullSpace\b",        # com.pico.spatial.core.annotation.RequiredFullSpace
    r"\bscene\.rayCast\b",            # raycast on scene instance (Stage-only)
    r"\bscene\.convexCast\b",         # convex cast on scene instance
    r"\bcom\.pico\.spatial\.sense\.", # any sensepack import is Stage-only
)

ENTRY_TOKENS = (
    "mainApp",
    "launch(::mainApp)",
    "SpatialLaunchActivity",
)

# Allow-listed SpatialUI / Spatial ECS import roots. Anything else under
# `com.pico.spatial.*` is reported as a warning so reviewers can confirm.
ALLOWED_SPATIAL_PACKAGE_PREFIXES = (
    "com.pico.spatial.ui.design",
    "com.pico.spatial.ui.foundation",
    "com.pico.spatial.ui.platform",
    "com.pico.spatial.foundation",
    "com.pico.spatial.scene",
    "com.pico.spatial.physics",
    "com.pico.spatial.input",
    "com.pico.spatial.entity",
    "com.pico.spatial.runtime",
)


def _load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise SystemExit(f"[impl-scan] Missing required file: {path}") from exc
    except json.JSONDecodeError as exc:
        raise SystemExit(f"[impl-scan] Invalid JSON in {path}: {exc}") from exc


def resolve_layout_contract_path(scratch_dir: Path) -> Path:
    for candidate in LAYOUT_CANDIDATES:
        path = scratch_dir / candidate
        if path.exists():
            return path
    tried = ", ".join(LAYOUT_CANDIDATES)
    raise SystemExit(
        f"[impl-scan] Missing Spatial Layout Contract under {scratch_dir}. Tried: {tried}"
    )


def load_contract_for_scan(scratch_dir: Path) -> dict[str, Any]:
    input_path = scratch_dir / "input_envelope.json"
    input_envelope: dict[str, Any] = {}
    if input_path.exists():
        loaded = _load_json(input_path)
        if isinstance(loaded, dict):
            input_envelope = loaded
    if input_envelope.get("input_mode") == "incremental_patch":
        patch = _load_json(scratch_dir / PATCH_CONTRACT)
        if not isinstance(patch, dict):
            raise SystemExit("[impl-scan] Patch Contract must be a JSON object")
        inherits = patch.get("inherits")
        if not isinstance(inherits, dict):
            raise SystemExit("[impl-scan] Patch Contract.inherits must be a JSON object")
        return {
            "container": inherits.get("container"),
            "container_reason": "Inherited from existing module by incremental_patch Patch Contract.",
            "window_model": inherits.get("window_model"),
            "window_reason": "Inherited from existing module by incremental_patch Patch Contract.",
            "spatial_features": [],
        }
    contract = _load_json(resolve_layout_contract_path(scratch_dir))
    if not isinstance(contract, dict):
        raise SystemExit("[impl-scan] Spatial Layout Contract must be a JSON object")
    return contract


def container_kind(container: str | None) -> str | None:
    if container in WINDOW_CONTAINERS:
        return "window"
    if container in STAGE_CONTAINERS:
        return "stage"
    return None


def collect_kotlin_files(target: Path) -> list[Path]:
    candidates: list[Path] = []
    for sub in ("src/main", "app/src/main", "src"):
        base = target / sub
        if base.exists():
            candidates.extend(base.rglob("*.kt"))
    if not candidates:
        candidates.extend(target.rglob("*.kt"))
    # de-dupe while preserving order
    seen: set[Path] = set()
    out: list[Path] = []
    for path in candidates:
        if path in seen:
            continue
        seen.add(path)
        out.append(path)
    return out


def collect_manifests(target: Path) -> list[Path]:
    out: list[Path] = []
    for sub in ("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml"):
        path = target / sub
        if path.exists():
            out.append(path)
    if not out:
        out.extend(target.rglob("AndroidManifest.xml"))
    return out


def read_text_safe(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError:
        return ""


def strip_comments_and_imports(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
    text = re.sub(r"^\s*//.*$", "", text, flags=re.MULTILINE)
    text = re.sub(r"^\s*import\s+.*$", "", text, flags=re.MULTILINE)
    return text


def detect_root_kind(kotlin_sources: list[tuple[Path, str]]) -> tuple[str | None, list[str]]:
    """Returns (root_kind, evidence_lines)."""
    evidence: list[str] = []
    has_window = False
    has_stage = False
    for path, text in kotlin_sources:
        body = strip_comments_and_imports(text)
        for pattern in WINDOW_ROOT_PATTERNS:
            if re.search(pattern, body):
                has_window = True
                evidence.append(f"{path.name}: window-root pattern '{pattern}'")
                break
        for pattern in STAGE_ROOT_PATTERNS:
            if re.search(pattern, body):
                has_stage = True
                evidence.append(f"{path.name}: stage-root pattern '{pattern}'")
                break
    if has_window and has_stage:
        return "mixed", evidence
    if has_window:
        return "window", evidence
    if has_stage:
        return "stage", evidence
    return None, evidence


def check_root_match(
    contract: dict[str, Any],
    kotlin_sources: list[tuple[Path, str]],
) -> dict[str, Any]:
    contract_container = contract.get("container")
    contract_kind = container_kind(str(contract_container) if contract_container else None)
    detected, evidence = detect_root_kind(kotlin_sources)

    failures: list[str] = []
    if contract_kind is None:
        failures.append(f"contract.container={contract_container!r} is not a recognized container")
    elif detected is None:
        failures.append(
            "no DefaultWindowContainer / DefaultStage / WindowContainer / Stage root invocation found in code"
        )
    elif detected == "mixed":
        failures.append("code declares BOTH a window root and a stage root; only one is allowed")
    elif detected != contract_kind:
        failures.append(
            f"contract.container={contract_container} ({contract_kind}) does not match code root ({detected})"
        )
    return {
        "passed": not failures,
        "failures": failures,
        "evidence": evidence,
    }


def check_entry_wired(kotlin_sources: list[tuple[Path, str]]) -> dict[str, Any]:
    found: dict[str, list[str]] = {token: [] for token in ENTRY_TOKENS}
    for path, text in kotlin_sources:
        for token in ENTRY_TOKENS:
            if token in text:
                found[token].append(path.name)
    failures: list[str] = []
    if not found["mainApp"]:
        failures.append("no `mainApp` entry function found in any .kt file")
    return {
        "passed": not failures,
        "failures": failures,
        "evidence": {token: sorted(set(files)) for token, files in found.items() if files},
    }


def check_manifest_consistency(
    contract: dict[str, Any],
    manifests: list[tuple[Path, str]],
) -> dict[str, Any]:
    failures: list[str] = []
    evidence: dict[str, Any] = {}
    if not manifests:
        return {
            "passed": True,
            "failures": [],
            "evidence": {"note": "no AndroidManifest.xml found; skipped"},
        }
    has_windowcontainer_meta = False
    has_in_volume_style = False
    manifest_meta: dict[str, set[str]] = {}
    for path, text in manifests:
        if "pico.spatial.windowcontainer.id" in text:
            has_windowcontainer_meta = True
            evidence[path.name] = "declares pico.spatial.windowcontainer.id"
        if 'pico.spatial.windowcontainer.style' in text and 'value="2"' in text:
            has_in_volume_style = True
        for meta_name, meta_value in re.findall(
            r'android:name="([^"]+)"\s+android:value="([^"]+)"',
            text,
        ):
            manifest_meta.setdefault(meta_name, set()).add(meta_value)
    contract_container = contract.get("container")
    contract_kind = container_kind(str(contract_container) if contract_container else None)
    if contract_kind == "window":
        if not has_windowcontainer_meta:
            failures.append(
                "WindowContainer flow but manifest is missing pico.spatial.windowcontainer.id meta-data"
            )
        if contract_container == "IN_VOLUME" and not has_in_volume_style:
            failures.append(
                "container=IN_VOLUME but manifest does not set pico.spatial.windowcontainer.style=2"
            )
    elif contract_kind == "stage":
        if "pico.spatial.stage.id" not in manifest_meta:
            failures.append("Stage flow but manifest is missing pico.spatial.stage.id meta-data")
        expected = STAGE_MANIFEST_EXPECTATIONS.get(str(contract_container), {})
        for name, expected_value in expected.items():
            actual_values = manifest_meta.get(name, set())
            if expected_value not in actual_values:
                actual = ", ".join(sorted(actual_values)) if actual_values else "<missing>"
                failures.append(
                    f"container={contract_container} expects {name}={expected_value}, got {actual}"
                )
        if expected:
            evidence["stage_manifest"] = {
                name: sorted(manifest_meta.get(name, set())) for name in expected.keys()
            }
    return {"passed": not failures, "failures": failures, "evidence": evidence}


def check_stage_api_legality(
    contract: dict[str, Any],
    kotlin_sources: list[tuple[Path, str]],
) -> dict[str, Any]:
    contract_container = contract.get("container")
    contract_kind = container_kind(str(contract_container) if contract_container else None)
    hits: list[str] = []
    compiled = [re.compile(p) for p in STAGE_ONLY_API_PATTERNS]
    for path, text in kotlin_sources:
        for pattern in compiled:
            for match in pattern.finditer(text):
                hits.append(f"{path.name}: {match.group(0)}")
    failures: list[str] = []
    if hits and contract_kind == "window":
        failures.append(
            "WindowContainer flow uses Stage-only API symbols: "
            + ", ".join(sorted(set(hits)))
        )
    return {"passed": not failures, "failures": failures, "evidence": hits}


_IMPORT_RE = re.compile(r"^\s*import\s+([\w.]+)", re.MULTILINE)


def check_whitelist_components(
    kotlin_sources: list[tuple[Path, str]],
) -> dict[str, Any]:
    suspicious: dict[str, list[str]] = {}
    for path, text in kotlin_sources:
        for match in _IMPORT_RE.finditer(text):
            symbol = match.group(1)
            if symbol.startswith("com.pico.spatial."):
                if not any(symbol.startswith(p) for p in ALLOWED_SPATIAL_PACKAGE_PREFIXES):
                    suspicious.setdefault(symbol, []).append(path.name)
    failures: list[str] = []
    # Suspicious imports are reported as warnings (no failure) — they only
    # become failures when they look clearly invented (no `.` after the prefix).
    for symbol in suspicious:
        if symbol.endswith("."):
            failures.append(f"malformed spatial import: {symbol}")
    return {
        "passed": not failures,
        "failures": failures,
        "warnings": [f"unrecognized spatial import (verify against whitelist): {sym}" for sym in sorted(suspicious)],
    }


def _contract_window_chrome_ornaments(contract: dict[str, Any]) -> list[dict[str, Any]]:
    raw = contract.get("window_chrome_ornaments")
    ornaments: list[dict[str, Any]] = []
    if isinstance(raw, list):
        ornaments.extend(item for item in raw if isinstance(item, dict))
    for region in contract.get("regions", []):
        if not isinstance(region, dict):
            continue
        region_type = str(region.get("type", "")).lower()
        implementation = str(region.get("implementation", ""))
        if "window_chrome" in region_type or any(name in implementation for name in ("TabBar", "Toolbar", "Subwindow")):
            ornaments.append(region)
    return ornaments


def check_window_chrome_ornaments(
    contract: dict[str, Any],
    kotlin_sources: list[tuple[Path, str]],
) -> dict[str, Any]:
    ornaments = _contract_window_chrome_ornaments(contract)
    if not ornaments:
        return {"passed": True, "failures": [], "evidence": {"note": "no window_chrome_ornaments declared"}}

    combined_body = "\n".join(strip_comments_and_imports(text) for _, text in kotlin_sources)
    has_tabbar = re.search(r"\bTabBar\s*\(", combined_body) is not None
    has_toolbar = re.search(r"\bToolbar\s*\(", combined_body) is not None
    has_subwindow = re.search(r"\bSubwindow\s*\(", combined_body) is not None
    has_window_fitting = has_tabbar or has_toolbar or has_subwindow
    manual_edge_overlay = re.search(
        r"Box\s*\([^)]*\.align\s*\(\s*Alignment\.(?:CenterStart|CenterEnd|TopCenter|BottomCenter|TopStart|TopEnd|BottomStart|BottomEnd)[\s\S]{0,500}\b(?:IconButton|Column|Row)\b",
        combined_body,
    ) is not None

    failures: list[str] = []
    expected_types = {str(item.get("type", "")).lower() for item in ornaments}
    if "tabbar" in expected_types and not has_tabbar:
        failures.append("window_chrome_ornaments declares TabBar but code has no TabBar(...) invocation")
    if "toolbar" in expected_types and not has_toolbar:
        failures.append("window_chrome_ornaments declares Toolbar but code has no Toolbar(...) invocation")
    if "subwindow" in expected_types and not has_subwindow:
        failures.append("window_chrome_ornaments declares Subwindow but code has no Subwindow(...) invocation")
    if not has_window_fitting:
        failures.append(
            "window_chrome_ornaments declared but code uses no window-level fitting (TabBar/Toolbar/Subwindow)"
        )
    if manual_edge_overlay and not has_window_fitting:
        failures.append(
            "window_chrome_ornaments must not be hand-rolled with Box.align(...) / offset(...) page overlays"
        )

    return {
        "passed": not failures,
        "failures": failures,
        "evidence": {
            "declared": ornaments,
            "has_tabbar": has_tabbar,
            "has_toolbar": has_toolbar,
            "has_subwindow": has_subwindow,
            "manual_edge_overlay": manual_edge_overlay,
        },
    }


def _function_body(combined_body: str, function_name: str) -> str:
    match = re.search(rf"\bfun\s+{re.escape(function_name)}\s*\([^)]*\)\s*\{{", combined_body)
    if not match:
        return ""
    start = match.end()
    depth = 1
    index = start
    while index < len(combined_body) and depth > 0:
        char = combined_body[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
        index += 1
    return combined_body[start:index - 1]


def check_visual_content_contract(contract: dict[str, Any], kotlin_sources: list[tuple[Path, str]]) -> dict[str, Any]:
    visual_contract = contract.get("visual_content_contract")
    if not isinstance(visual_contract, dict):
        return {"passed": True, "failures": [], "evidence": {"note": "no visual_content_contract declared"}}

    combined_body = "\n".join(strip_comments_and_imports(text) for _, text in kotlin_sources)
    failures: list[str] = []

    tabs = visual_contract.get("tabs") if isinstance(visual_contract.get("tabs"), dict) else {}
    visible_count = tabs.get("visible_count") if isinstance(tabs, dict) else None
    if isinstance(visible_count, int):
        for take in re.findall(r"\btabs\s*\.\s*take\s*\(\s*(\d+)\s*\)", combined_body):
            if int(take) < visible_count:
                failures.append(
                    f"visual_content_contract.tabs.visible_count={visible_count} but code uses tabs.take({take})"
                )
    tab_style = str(tabs.get("style", "")) if isinstance(tabs, dict) else ""
    if "capsule" in tab_style or "pill" in tab_style:
        tabs_body = _function_body(combined_body, "TabsRow")
        if tabs_body and ".background" not in tabs_body:
            failures.append("visual_content_contract.tabs requires capsule/pill background but TabsRow has no background modifier")

    cards = visual_contract.get("cards") if isinstance(visual_contract.get("cards"), dict) else {}
    cards_body = _function_body(combined_body, "ResultCardView")
    if isinstance(cards, dict):
        if cards.get("content") == "image_only" or cards.get("has_text_overlay") is False:
            has_scrim_overlay = "Brush.verticalGradient" in cards_body or "ImageScrim" in cards_body
            has_card_text_overlay = re.search(
                r"\bText\s*\(\s*(?:text\s*=\s*)?card\.(?:title|subtitle)\b",
                cards_body,
            )
            if has_scrim_overlay or has_card_text_overlay:
                failures.append("visual_content_contract.cards declares image_only/no text overlay but ResultCardView renders scrim/title/subtitle")
        if cards.get("layout") == "fixed_3x2" and "LazyColumn" in combined_body:
            failures.append("visual_content_contract.cards.layout=fixed_3x2 but code uses LazyColumn instead of a fixed grid")

    sidebar = visual_contract.get("sidebar") if isinstance(visual_contract.get("sidebar"), dict) else {}
    if isinstance(sidebar, dict):
        filter_body = _function_body(combined_body, "FilterSidebar")
        if sidebar.get("has_surface") is True and filter_body and ".background" not in filter_body:
            failures.append("visual_content_contract.sidebar.has_surface=true but FilterSidebar has no background/surface")
        if sidebar.get("preferred_component") == "SideNavigation":
            if filter_body and "SideNavigation" not in filter_body:
                failures.append(
                    "visual_content_contract.sidebar.preferred_component=SideNavigation but FilterSidebar does not use SpatialUI SideNavigation"
                )
        search_pill = sidebar.get("search_pill") if isinstance(sidebar.get("search_pill"), dict) else {}
        if search_pill.get("width_policy") == "fill_sidebar_content_width":
            search_body = _function_body(combined_body, "SearchPill")
            if search_body and "fillMaxWidth" not in search_body and ".width" not in search_body:
                failures.append("visual_content_contract.sidebar.search_pill requires fill_sidebar_content_width but SearchPill has no fillMaxWidth/width")
        if search_pill.get("interaction_role") == "search_input":
            search_body = _function_body(combined_body, "SearchPill")
            has_search_field = "SearchField" in search_body
            has_value_binding = "value" in search_body and "onValueChange" in search_body
            has_search_event = "onSearch" in search_body or "UpdateQuery" in search_body
            if search_body and not (has_search_field and has_value_binding and has_search_event):
                failures.append(
                    "visual_content_contract.sidebar.search_pill interaction_role=search_input requires SpatialUI SearchField with value/onValueChange/onSearch binding"
                )
        chips = sidebar.get("chips") if isinstance(sidebar.get("chips"), dict) else {}
        if chips.get("active_preferred_component") == "RemovableChip":
            if "RemovableChip" not in combined_body:
                failures.append(
                    "visual_content_contract.sidebar.chips active_preferred_component=RemovableChip but code does not use SpatialUI RemovableChip"
                )
        if chips.get("recommendation_preferred_component") == "ButtonChip":
            if "ButtonChip" not in combined_body:
                failures.append(
                    "visual_content_contract.sidebar.chips recommendation_preferred_component=ButtonChip but code does not use SpatialUI ButtonChip"
                )

    return {
        "passed": not failures,
        "failures": failures,
        "evidence": {"declared": visual_contract},
    }


def scan(target: Path, scratch_dir: Path | None = None) -> dict[str, Any]:
    actual_scratch = scratch_dir or (target / ".scratch")
    if not actual_scratch.exists() or not actual_scratch.is_dir():
        raise SystemExit(f"[impl-scan] Scratch directory not found: {actual_scratch}")

    contract = load_contract_for_scan(actual_scratch)

    kotlin_paths = collect_kotlin_files(target)
    kotlin_sources = [(p, read_text_safe(p)) for p in kotlin_paths]
    manifest_paths = collect_manifests(target)
    manifests = [(p, read_text_safe(p)) for p in manifest_paths]

    checks: dict[str, Any] = {
        "root_match": check_root_match(contract, kotlin_sources),
        "entry_wired": check_entry_wired(kotlin_sources),
        "manifest_consistency": check_manifest_consistency(contract, manifests),
        "stage_api_legality": check_stage_api_legality(contract, kotlin_sources),
        "whitelist_components": check_whitelist_components(kotlin_sources),
        "window_chrome_ornaments": check_window_chrome_ornaments(contract, kotlin_sources),
        "visual_content_contract": check_visual_content_contract(contract, kotlin_sources),
    }
    failures: list[str] = []
    for name, result in checks.items():
        for failure in result.get("failures", []):
            failures.append(f"[{name}] {failure}")

    summary = {
        "schema_version": 1,
        "scanned": {
            "kotlin_files": len(kotlin_paths),
            "manifest_files": len(manifest_paths),
        },
        "checks": checks,
        "failures_or_explicit_none": failures if failures else "none",
        "passed": not failures,
    }
    output_path = actual_scratch / "implementation_scan_result.json"
    output_path.write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"[impl-scan] WROTE {output_path}")
    return summary


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", required=True, help="Project directory containing .scratch/ and source files")
    parser.add_argument(
        "--scratch-dir",
        help="Optional override for the scratch directory. Defaults to <target>/.scratch",
    )
    args = parser.parse_args(argv)

    target = Path(args.target).expanduser().resolve()
    scratch_dir = Path(args.scratch_dir).expanduser().resolve() if args.scratch_dir else None
    summary = scan(target, scratch_dir)

    if not summary["passed"]:
        failures = summary["failures_or_explicit_none"]
        raise SystemExit("[impl-scan] BLOCKED\n- " + "\n- ".join(failures))

    print(f"[impl-scan] SUCCESS scanned {summary['scanned']['kotlin_files']} kotlin files, "
          f"{summary['scanned']['manifest_files']} manifest files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
