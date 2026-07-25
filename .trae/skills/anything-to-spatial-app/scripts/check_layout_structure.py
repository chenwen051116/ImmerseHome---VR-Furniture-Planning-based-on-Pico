#!/usr/bin/env python3
"""Validate spatial layout/structure rules for anything-to-spatial-app.

Usage:
    python3 -m scripts.check_layout_structure --target ./generated-spatial-app
    python3 /abs/path/to/check_layout_structure.py --target ./myapp

The checker reads workflow artifacts from <target>/.scratch and enforces
architecture rules that are stricter than plain artifact shape validation:

- window_model must match the declared window structure
- multi_window requires disconnected-surface evidence
- Stage-only features must not appear in WindowContainer flows
- existing-module runs must not silently switch root architecture
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


VALID_CONTAINERS = {
    "ON_PLAIN",
    "IN_VOLUME",
    "STAGE_MIXED",
    "STAGE_PROGRESSIVE",
    "STAGE_FULL",
}
WINDOW_CONTAINERS = {"ON_PLAIN", "IN_VOLUME"}
STAGE_CONTAINERS = {"STAGE_MIXED", "STAGE_PROGRESSIVE", "STAGE_FULL"}
LAYOUT_CANDIDATES = [
    "spatial_layout_contract.json",
    "spatial_layout.json",
    "window_structure.json",
]
PATCH_CONTRACT = "patch_contract.json"

OVERLAY_ROLE_KEYWORDS = {
    "overlay",
    "popup",
    "tooltip",
    "toast",
    "menu",
    "dropdown",
    "hover",
}
SUBWINDOW_ROLE_KEYWORDS = {
    "subwindow",
    "secondary",
    "secondary_panel",
    "tool_panel",
    "auxiliary",
    "detail_window",
}
OVERLAY_TEXT_SIGNALS = {
    "overlay",
    "popup",
    "tooltip",
    "dropdown",
    "menu",
    "toast",
    "hover card",
    "anchored",
    "attached to the main panel",
    "treat_as_overlay",
}
DISCONNECTED_TEXT_SIGNALS = {
    "disconnected surface",
    "disconnected surfaces",
    "separate bounds",
    "independent placement",
    "independent position",
    "independent positions",
    "independent size",
    "independent sizes",
    "independent lifecycle",
    "independent lifecycles",
    "independent panel",
    "independent panels",
    "detached tool window",
    "separate window",
    "separate windows",
    "multiple windowcontainers",
    "launcher entry",
    "launcher entries",
}
ROOT_OVERRIDE_TEXT_SIGNALS = {
    "explicit user requirement",
    "user explicitly asked",
    "architecture change justified",
    "root architecture override",
    "override root architecture",
    "requires stage",
    "stage required",
}


def _load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise SystemExit(f"[layout-check] Missing required file: {path}") from exc
    except json.JSONDecodeError as exc:
        raise SystemExit(f"[layout-check] Invalid JSON in {path}: {exc}") from exc


def _ensure_dict(value: Any, name: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise SystemExit(f"[layout-check] {name} must be a JSON object")
    return value


def _ensure_list(value: Any, name: str) -> list[Any]:
    if not isinstance(value, list):
        raise SystemExit(f"[layout-check] {name} must be a JSON array")
    return value


def _print_ok(message: str) -> None:
    print(f"[layout-check] PASS {message}")


def _print_warn(message: str) -> None:
    print(f"[layout-check] WARN {message}")


def resolve_layout_contract_path(scratch_dir: Path) -> Path:
    for candidate in LAYOUT_CANDIDATES:
        path = scratch_dir / candidate
        if path.exists():
            return path
    tried = ", ".join(LAYOUT_CANDIDATES)
    raise SystemExit(
        f"[layout-check] Missing Spatial Layout Contract under {scratch_dir}. Tried: {tried}"
    )


def patch_contract_to_layout_contract(path: Path) -> dict[str, Any]:
    patch = _ensure_dict(_load_json(path), "Patch Contract")
    inherits = _ensure_dict(patch.get("inherits", {}), "Patch Contract.inherits")
    container = inherits.get("container")
    window_model = inherits.get("window_model")
    regions = [
        {"id": str(region), "type": "patched_region"}
        for region in _ensure_list(patch.get("regions_touched", []), "Patch Contract.regions_touched")
    ]
    return {
        "container": container,
        "container_reason": "Inherited from existing module by incremental_patch Patch Contract.",
        "window_model": window_model,
        "window_reason": "Inherited from existing module by incremental_patch Patch Contract.",
        "windows": [
            {
                "id": "main",
                "role": "primary_panel",
                "default_visibility": "visible",
                "children": [region["id"] for region in regions],
            }
        ],
        "regions": regions,
        "repeated_structures": [],
        "states": patch.get("states_to_add", []),
        "spatial_features": [],
        "evidence_trace": [
            {
                "window_id": "main",
                "claim": "inherited primary window",
                "fact_ref": "Patch Contract.inherits",
                "because": "incremental_patch preserves existing root container and window model",
            }
        ],
    }


def apply_patch_inheritance_to_normalized_spec(
    normalized_spec: dict[str, Any],
    patch_path: Path,
) -> None:
    """Let existing-module preservation checks use Patch Contract inheritance.

    Incremental patch runs intentionally skip the full Spatial Layout Contract,
    so the inherited root container may live only in patch_contract.json. Copying
    it into request_context keeps the checker strict without requiring the agent
    to duplicate the same fact in multiple artifacts.
    """
    patch = _ensure_dict(_load_json(patch_path), "Patch Contract")
    inherits = _ensure_dict(patch.get("inherits", {}), "Patch Contract.inherits")
    request_context = _ensure_dict(
        normalized_spec.setdefault("request_context", {}),
        "Normalized Spatial Spec.request_context",
    )
    request_context.setdefault("existing_root_container", inherits.get("container"))


def iter_strings(value: Any) -> list[str]:
    items: list[str] = []
    if isinstance(value, str):
        items.append(value.lower())
    elif isinstance(value, dict):
        for key, child in value.items():
            items.append(str(key).lower())
            items.extend(iter_strings(child))
    elif isinstance(value, list):
        for child in value:
            items.extend(iter_strings(child))
    return items


def container_root_kind(container: str | None) -> str | None:
    if container in WINDOW_CONTAINERS:
        return "window"
    if container in STAGE_CONTAINERS:
        return "stage"
    return None


def role_kind(window: dict[str, Any]) -> str:
    role = str(window.get("role", "")).lower()
    if any(keyword in role for keyword in OVERLAY_ROLE_KEYWORDS):
        return "overlay"
    if any(keyword in role for keyword in SUBWINDOW_ROLE_KEYWORDS):
        return "subwindow"
    return "primary"


def collect_spatial_features(normalized_spec: dict[str, Any], layout_contract: dict[str, Any]) -> set[str]:
    spatial_intent = _ensure_dict(normalized_spec.get("spatial_intent", {}), "Normalized Spatial Spec.spatial_intent")
    raw_features = list(spatial_intent.get("spatial_features", [])) + list(layout_contract.get("spatial_features", []))
    return {str(feature) for feature in raw_features}


def has_any_signal(strings: list[str], signals: set[str]) -> bool:
    haystack = "\n".join(strings)
    return any(signal in haystack for signal in signals)


def has_explicit_root_override(
    input_envelope: dict[str, Any],
    normalized_spec: dict[str, Any],
    layout_contract: dict[str, Any],
) -> bool:
    request_context = _ensure_dict(normalized_spec.get("request_context", {}), "Normalized Spatial Spec.request_context")
    if request_context.get("root_architecture_override") is True:
        return True
    if input_envelope.get("root_architecture_override") is True:
        return True
    if layout_contract.get("root_architecture_override") is True:
        return True
    return has_any_signal(
        iter_strings(normalized_spec.get("evidence_trace", []))
        + iter_strings(normalized_spec.get("ambiguities", []))
        + iter_strings(layout_contract.get("container_reason"))
        + iter_strings(layout_contract.get("window_reason")),
        ROOT_OVERRIDE_TEXT_SIGNALS,
    )


def validate_window_model_structure(layout_contract: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    windows = _ensure_list(layout_contract.get("windows", []), "Spatial Layout Contract.windows")
    window_model = layout_contract.get("window_model")
    non_overlay = [window for window in windows if role_kind(_ensure_dict(window, "window")) != "overlay"]
    overlay_count = len(windows) - len(non_overlay)
    primary_count = sum(1 for window in non_overlay if role_kind(window) == "primary")
    subwindow_count = sum(1 for window in non_overlay if role_kind(window) == "subwindow")

    if window_model in {"single_panel", "sidebar_content", "master_detail"}:
        if len(non_overlay) != 1 or overlay_count != 0:
            errors.append(
                f"window_model={window_model} must stay within one non-overlay window and no separate overlay windows"
            )
    elif window_model == "single_panel_with_popup":
        if len(non_overlay) != 1:
            errors.append("single_panel_with_popup must have exactly one primary window")
        if subwindow_count != 0:
            errors.append("single_panel_with_popup must not declare persistent subwindows")
    elif window_model == "window_plus_subwindow":
        if len(non_overlay) < 2:
            errors.append("window_plus_subwindow must declare a main window plus at least one persistent subwindow")
        if primary_count < 1 or subwindow_count < 1:
            errors.append("window_plus_subwindow must include both a primary_panel and a secondary/subwindow role")
    elif window_model == "multi_window":
        if len(non_overlay) < 2:
            errors.append("multi_window must declare at least two non-overlay windows")

    return errors


def validate_stage_feature_legality(
    normalized_spec: dict[str, Any],
    layout_contract: dict[str, Any],
) -> list[str]:
    errors: list[str] = []
    container = layout_contract.get("container")
    features = collect_spatial_features(normalized_spec, layout_contract)
    if container not in VALID_CONTAINERS:
        errors.append(f"invalid container={container!r} in Spatial Layout Contract")
        return errors

    if container in WINDOW_CONTAINERS:
        illegal = sorted(feature for feature in features if feature in {"anchor", "env_mesh", "passthrough", "skybox"})
        if illegal:
            errors.append(
                "WindowContainer flow contains Stage-only features that require a Stage container: "
                + ", ".join(illegal)
            )
    if "skybox" in features and container not in {"STAGE_PROGRESSIVE", "STAGE_FULL"}:
        errors.append("skybox requires STAGE_PROGRESSIVE or STAGE_FULL")
    if "passthrough" in features and container not in {"STAGE_MIXED"}:
        errors.append("passthrough requires STAGE_MIXED")
    return errors


def validate_overlay_vs_multi_window(
    evidence_packet: dict[str, Any],
    normalized_spec: dict[str, Any],
    layout_contract: dict[str, Any],
) -> list[str]:
    if layout_contract.get("window_model") != "multi_window":
        return []

    strings = (
        iter_strings(evidence_packet)
        + iter_strings(normalized_spec.get("ambiguities", []))
        + iter_strings(normalized_spec.get("evidence_trace", []))
        + iter_strings(layout_contract.get("window_reason"))
        + iter_strings(layout_contract.get("windows", []))
    )
    has_disconnected_evidence = has_any_signal(strings, DISCONNECTED_TEXT_SIGNALS)
    has_overlay_evidence = has_any_signal(strings, OVERLAY_TEXT_SIGNALS)
    if has_disconnected_evidence:
        return []

    if has_overlay_evidence:
        return [
            "multi_window is blocked: evidence still reads like overlay/popup UI and does not show disconnected surfaces"
        ]
    return [
        "multi_window is blocked: disconnected-surface evidence is missing (independent placement/size/lifecycle or separate bounds)"
    ]


def validate_existing_module_root_preservation(
    input_envelope: dict[str, Any],
    normalized_spec: dict[str, Any],
    layout_contract: dict[str, Any],
) -> list[str]:
    errors: list[str] = []
    request_context = _ensure_dict(normalized_spec.get("request_context", {}), "Normalized Spatial Spec.request_context")
    generation_mode = request_context.get("generation_mode") or input_envelope.get("generation_mode")
    if generation_mode != "existing_module":
        return errors

    existing_root_container = request_context.get("existing_root_container") or input_envelope.get("existing_root_container")
    if not existing_root_container:
        _print_warn("existing_module run has no existing_root_container metadata; root-preservation check is partial")
        return errors

    existing_root_kind = container_root_kind(str(existing_root_container))
    new_root_kind = container_root_kind(str(layout_contract.get("container")))
    if existing_root_kind is None or new_root_kind is None:
        return ["existing module root architecture could not be compared because one container value is invalid"]

    if existing_root_kind != new_root_kind and not has_explicit_root_override(
        input_envelope,
        normalized_spec,
        layout_contract,
    ):
        errors.append(
            "existing module root architecture changed from "
            f"{existing_root_container} to {layout_contract.get('container')} without explicit override"
        )
    return errors


def validate_target(target: Path, scratch_dir: Path | None = None) -> None:
    actual_scratch = scratch_dir or (target / ".scratch")
    if not actual_scratch.exists() or not actual_scratch.is_dir():
        raise SystemExit(f"[layout-check] Scratch directory not found: {actual_scratch}")

    input_envelope = _ensure_dict(_load_json(actual_scratch / "input_envelope.json"), "Input Envelope")
    evidence_packet = _ensure_dict(_load_json(actual_scratch / "evidence_packet.json"), "Evidence Packet")
    normalized_spec = _ensure_dict(
        _load_json(actual_scratch / "normalized_spatial_spec.json"),
        "Normalized Spatial Spec",
    )
    if input_envelope.get("input_mode") == "incremental_patch":
        patch_path = actual_scratch / PATCH_CONTRACT
        apply_patch_inheritance_to_normalized_spec(normalized_spec, patch_path)
        layout_contract = patch_contract_to_layout_contract(patch_path)
    else:
        layout_contract = _ensure_dict(
            _load_json(resolve_layout_contract_path(actual_scratch)),
            "Spatial Layout Contract",
        )

    window_model_errors = validate_window_model_structure(layout_contract)
    stage_feature_errors = validate_stage_feature_legality(normalized_spec, layout_contract)
    overlay_errors = validate_overlay_vs_multi_window(evidence_packet, normalized_spec, layout_contract)
    root_errors = validate_existing_module_root_preservation(
        input_envelope, normalized_spec, layout_contract
    )

    errors: list[str] = []
    errors.extend(window_model_errors)
    errors.extend(stage_feature_errors)
    errors.extend(overlay_errors)
    errors.extend(root_errors)

    result = {
        "schema_version": 1,
        "checks": {
            "window_model_structure": {
                "passed": not window_model_errors,
                "failures": window_model_errors,
            },
            "stage_api_legality": {
                "passed": not stage_feature_errors,
                "failures": stage_feature_errors,
            },
            "overlay_vs_multi_window": {
                "passed": not overlay_errors,
                "failures": overlay_errors,
            },
            "existing_module_root_preserved": {
                "passed": not root_errors,
                "failures": root_errors,
            },
        },
        "failures_or_explicit_none": errors if errors else "none",
        "passed": not errors,
    }
    output_path = actual_scratch / "legality_check_result.json"
    output_path.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"[layout-check] WROTE {output_path}")

    if errors:
        raise SystemExit("[layout-check] BLOCKED\n- " + "\n- ".join(errors))

    _print_ok("window model matches declared windows")
    _print_ok("container and spatial features are structurally compatible")
    _print_ok("overlay vs multi_window rules passed")
    _print_ok("existing-module root architecture is preserved or explicitly justified")
    print("[layout-check] SUCCESS layout/structure rules look valid")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", required=True, help="Project directory containing .scratch/")
    parser.add_argument(
        "--scratch-dir",
        help="Optional override for the scratch directory. Defaults to <target>/.scratch",
    )
    args = parser.parse_args(argv)

    target = Path(args.target).expanduser().resolve()
    scratch_dir = Path(args.scratch_dir).expanduser().resolve() if args.scratch_dir else None
    validate_target(target, scratch_dir)
    return 0


if __name__ == "__main__":
    sys.exit(main())
