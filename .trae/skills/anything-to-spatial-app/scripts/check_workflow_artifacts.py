#!/usr/bin/env python3
"""Validate scratch workflow artifacts for anything-to-spatial-app.

Usage:
    # From the anything-to-spatial-app skill root:
    python3 -m scripts.check_workflow_artifacts --target ./generated-spatial-app
    python3 -m scripts.check_workflow_artifacts --target ./myapp --require-assumptions

    # Or invoke the script directly from any directory:
    python3 /abs/path/to/scripts/check_workflow_artifacts.py --target ./generated-spatial-app

The checker looks under <target>/.scratch by default and validates the
recommended workflow artifacts:

- input_envelope.json
- evidence_packet.json
- normalized_spatial_spec.json
- assumption_ledger.json (required; use [] when no assumptions exist)
- spatial_layout_contract.json (or patch_contract.json for incremental_patch)

Legacy layout filenames are also accepted:

- spatial_layout.json
- window_structure.json
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


VALID_INPUT_MODES = {
    "visual_design",
    "visual_reference",
    "product_doc",
    "intent_only",
    "hybrid",
    "incremental_patch",
}

VALID_GENERATION_MODES = {"existing_module", "new_project"}
VALID_CONTAINERS = {
    "ON_PLAIN",
    "IN_VOLUME",
    "STAGE_MIXED",
    "STAGE_PROGRESSIVE",
    "STAGE_FULL",
}
VALID_WINDOW_MODELS = {
    "single_panel",
    "single_panel_with_popup",
    "sidebar_content",
    "master_detail",
    "window_plus_subwindow",
    "multi_window",
}
LAYOUT_CANDIDATES = [
    "spatial_layout_contract.json",
    "spatial_layout.json",
    "window_structure.json",
]
PATCH_CONTRACT = "patch_contract.json"
EMPTY_REASON_STRINGS = {
    "not needed",
    "not applicable",
    "no evidence",
    "n/a",
    "none",
}


def _load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise SystemExit(f"[artifact-check] Missing required file: {path}") from exc
    except json.JSONDecodeError as exc:
        raise SystemExit(f"[artifact-check] Invalid JSON in {path}: {exc}") from exc


def _ensure_dict(value: Any, name: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise SystemExit(f"[artifact-check] {name} must be a JSON object")
    return value


def _ensure_list(value: Any, name: str) -> list[Any]:
    if not isinstance(value, list):
        raise SystemExit(f"[artifact-check] {name} must be a JSON array")
    return value


def _require_keys(obj: dict[str, Any], keys: list[str], label: str) -> None:
    missing = [key for key in keys if key not in obj]
    if missing:
        raise SystemExit(
            f"[artifact-check] {label} missing required keys: {', '.join(missing)}"
        )


def _require_non_empty_reason(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise SystemExit(f"[artifact-check] {label} must be a non-empty string")
    normalized = value.strip().lower()
    if normalized in EMPTY_REASON_STRINGS:
        raise SystemExit(f"[artifact-check] {label} is not acceptable evidence: {value!r}")
    return value.strip()


def _print_ok(label: str, path: Path) -> None:
    print(f"[artifact-check] PASS {label}: {path}")


def _print_warn(message: str) -> None:
    print(f"[artifact-check] WARN {message}")


def validate_input_envelope(path: Path) -> None:
    data = _ensure_dict(_load_json(path), "Input Envelope")
    _require_keys(data, ["input_mode", "generation_mode", "input_sources"], "Input Envelope")
    if data["input_mode"] not in VALID_INPUT_MODES:
        raise SystemExit(
            f"[artifact-check] Input Envelope has invalid input_mode={data['input_mode']!r}"
        )
    if data["generation_mode"] not in VALID_GENERATION_MODES:
        raise SystemExit(
            f"[artifact-check] Input Envelope has invalid generation_mode={data['generation_mode']!r}"
        )
    sources = _ensure_list(data["input_sources"], "Input Envelope.input_sources")
    if not sources:
        raise SystemExit("[artifact-check] Input Envelope.input_sources must not be empty")
    _print_ok("Input Envelope", path)


def _is_visual_reference_input(input_envelope: dict[str, Any]) -> bool:
    if input_envelope.get("input_mode") != "visual_reference":
        return False
    sources = input_envelope.get("input_sources")
    if not isinstance(sources, list):
        return True
    visual_types = {"image", "screenshot", "mockup", "visual_reference"}
    return any(isinstance(source, dict) and source.get("type") in visual_types for source in sources) or True


def _validate_size_obj(obj: dict[str, Any], label: str) -> None:
    for key in ("width", "height"):
        value = obj.get(key)
        if not isinstance(value, (int, float)) or value <= 0:
            raise SystemExit(f"[artifact-check] {label}.{key} must be a positive number")


def validate_reference_frame(ref: Any, label: str) -> dict[str, Any]:
    obj = _ensure_dict(ref, label)
    _require_keys(
        obj,
        ["screenshot_px", "app_owned_bbox_px", "target_window_dp", "scale_policy", "dp_per_px", "excluded_from_size"],
        label,
    )
    screenshot_px = _ensure_dict(obj["screenshot_px"], f"{label}.screenshot_px")
    app_bbox = _ensure_dict(obj["app_owned_bbox_px"], f"{label}.app_owned_bbox_px")
    target_window = _ensure_dict(obj["target_window_dp"], f"{label}.target_window_dp")
    _validate_size_obj(screenshot_px, f"{label}.screenshot_px")
    _validate_size_obj(app_bbox, f"{label}.app_owned_bbox_px")
    _validate_size_obj(target_window, f"{label}.target_window_dp")
    for key in ("x", "y"):
        value = app_bbox.get(key)
        if not isinstance(value, (int, float)) or value < 0:
            raise SystemExit(f"[artifact-check] {label}.app_owned_bbox_px.{key} must be a non-negative number")
    if app_bbox["width"] > screenshot_px["width"] or app_bbox["height"] > screenshot_px["height"]:
        raise SystemExit(f"[artifact-check] {label}.app_owned_bbox_px must fit within screenshot_px")
    if not isinstance(obj["scale_policy"], str) or not obj["scale_policy"].strip():
        raise SystemExit(f"[artifact-check] {label}.scale_policy must be a non-empty string")
    if not isinstance(obj["dp_per_px"], (int, float)) or obj["dp_per_px"] <= 0:
        raise SystemExit(f"[artifact-check] {label}.dp_per_px must be a positive number")
    excluded = _ensure_list(obj["excluded_from_size"], f"{label}.excluded_from_size")
    if "environment_context" not in excluded:
        raise SystemExit(f"[artifact-check] {label}.excluded_from_size must include environment_context")
    return obj


def _validate_rect_obj(obj: dict[str, Any], label: str, reference_frame: dict[str, Any]) -> None:
    for key in ("x", "y"):
        value = obj.get(key)
        if not isinstance(value, (int, float)) or value < 0:
            raise SystemExit(f"[artifact-check] {label}.{key} must be a non-negative number")
    _validate_size_obj(obj, label)
    screenshot = _ensure_dict(reference_frame["screenshot_px"], "reference_frame.screenshot_px")
    if obj["x"] + obj["width"] > screenshot["width"] or obj["y"] + obj["height"] > screenshot["height"]:
        raise SystemExit(f"[artifact-check] {label} must fit within reference_frame.screenshot_px")


def _validate_edge_insets(obj: dict[str, Any], label: str) -> None:
    for key in ("start", "top", "end", "bottom"):
        value = obj.get(key)
        if not isinstance(value, (int, float)) or value < 0:
            raise SystemExit(f"[artifact-check] {label}.{key} must be a non-negative number")


def validate_content_layout_metrics(metrics: Any, label: str, reference_frame: dict[str, Any]) -> dict[str, Any]:
    obj = _ensure_dict(metrics, label)
    _require_keys(obj, ["panel_padding_px", "regions_px", "repeated_metrics_px"], label)
    _validate_edge_insets(_ensure_dict(obj["panel_padding_px"], f"{label}.panel_padding_px"), f"{label}.panel_padding_px")

    regions = _ensure_dict(obj["regions_px"], f"{label}.regions_px")
    if not regions:
        raise SystemExit(f"[artifact-check] {label}.regions_px must not be empty")
    for region_id, rect in regions.items():
        _validate_rect_obj(_ensure_dict(rect, f"{label}.regions_px.{region_id}"), f"{label}.regions_px.{region_id}", reference_frame)

    repeated = _ensure_dict(obj["repeated_metrics_px"], f"{label}.repeated_metrics_px")
    if not repeated:
        raise SystemExit(f"[artifact-check] {label}.repeated_metrics_px must not be empty")
    for item_id, metric in repeated.items():
        metric_obj = _ensure_dict(metric, f"{label}.repeated_metrics_px.{item_id}")
        for key in ("width", "height"):
            value = metric_obj.get(key)
            if not isinstance(value, (int, float)) or value <= 0:
                raise SystemExit(f"[artifact-check] {label}.repeated_metrics_px.{item_id}.{key} must be a positive number")
        for key in ("gap_x", "gap_y"):
            if key in metric_obj:
                value = metric_obj[key]
                if not isinstance(value, (int, float)) or value < 0:
                    raise SystemExit(f"[artifact-check] {label}.repeated_metrics_px.{item_id}.{key} must be a non-negative number")
    return obj


def validate_visual_content_contract(contract: Any, label: str) -> dict[str, Any]:
    obj = _ensure_dict(contract, label)
    _require_keys(obj, ["sidebar", "tabs", "cards"], label)

    sidebar = _ensure_dict(obj["sidebar"], f"{label}.sidebar")
    if "has_surface" in sidebar and not isinstance(sidebar["has_surface"], bool):
        raise SystemExit(f"[artifact-check] {label}.sidebar.has_surface must be a boolean")
    sidebar_preferred_component = sidebar.get("preferred_component")
    if sidebar_preferred_component != "SideNavigation":
        raise SystemExit(
            f"[artifact-check] {label}.sidebar.preferred_component must be SideNavigation "
            "for vertical sidebar regions unless an explicit no-built-in-fit exception is recorded"
        )
    search_pill = _ensure_dict(sidebar.get("search_pill"), f"{label}.sidebar.search_pill")
    _require_non_empty_reason(search_pill.get("width_policy"), f"{label}.sidebar.search_pill.width_policy")
    interaction_role = search_pill.get("interaction_role")
    if interaction_role not in ("search_input", "search_action", "display_only"):
        raise SystemExit(
            f"[artifact-check] {label}.sidebar.search_pill.interaction_role must be one of "
            "search_input/search_action/display_only"
        )
    if interaction_role == "search_input":
        preferred_component = search_pill.get("preferred_component")
        if preferred_component != "SearchField":
            raise SystemExit(
                f"[artifact-check] {label}.sidebar.search_pill.preferred_component must be SearchField "
                "when interaction_role=search_input"
            )
    chips = _ensure_dict(sidebar.get("chips"), f"{label}.sidebar.chips")
    active_preferred_component = chips.get("active_preferred_component")
    recommendation_preferred_component = chips.get("recommendation_preferred_component")
    if active_preferred_component != "RemovableChip":
        raise SystemExit(
            f"[artifact-check] {label}.sidebar.chips.preferred_component active chips must prefer "
            "RemovableChip when active_chips_have_close_icon=true"
        )
    if recommendation_preferred_component != "ButtonChip":
        raise SystemExit(
            f"[artifact-check] {label}.sidebar.chips.preferred_component recommendation chips must prefer "
            "ButtonChip unless a no-built-in-fit exception is recorded"
        )
    for key in ("active_chips_have_close_icon", "recommendation_chips_may_have_leading_icon"):
        if key in chips and not isinstance(chips[key], bool):
            raise SystemExit(f"[artifact-check] {label}.sidebar.chips.{key} must be a boolean")

    tabs = _ensure_dict(obj["tabs"], f"{label}.tabs")
    visible_count = tabs.get("visible_count")
    if not isinstance(visible_count, int) or visible_count <= 0:
        raise SystemExit(f"[artifact-check] {label}.tabs.visible_count must be a positive integer")
    _require_non_empty_reason(tabs.get("style"), f"{label}.tabs.style")

    cards = _ensure_dict(obj["cards"], f"{label}.cards")
    for key in ("layout", "content", "asset_policy"):
        _require_non_empty_reason(cards.get(key), f"{label}.cards.{key}")
    if "has_text_overlay" in cards and not isinstance(cards["has_text_overlay"], bool):
        raise SystemExit(f"[artifact-check] {label}.cards.has_text_overlay must be a boolean")
    return obj


def validate_evidence_packet(path: Path) -> None:
    data = _ensure_dict(_load_json(path), "Evidence Packet")
    _require_keys(data, ["facts", "unknowns", "conflicts", "confidence"], "Evidence Packet")
    _ensure_dict(data["facts"], "Evidence Packet.facts")
    _ensure_list(data["unknowns"], "Evidence Packet.unknowns")
    _ensure_list(data["conflicts"], "Evidence Packet.conflicts")
    _ensure_dict(data["confidence"], "Evidence Packet.confidence")
    _print_ok("Evidence Packet", path)


def validate_visual_reference_size_contract(
    input_envelope: dict[str, Any],
    evidence_packet: dict[str, Any],
    normalized_spec: dict[str, Any],
    layout_contract: dict[str, Any],
) -> None:
    if not _is_visual_reference_input(input_envelope):
        return
    evidence_facts = _ensure_dict(evidence_packet.get("facts"), "Evidence Packet.facts")
    evidence_ref = validate_reference_frame(
        evidence_facts.get("reference_frame"),
        "Evidence Packet.facts.reference_frame",
    )
    evidence_metrics = validate_content_layout_metrics(
        evidence_facts.get("content_layout_metrics"),
        "Evidence Packet.facts.content_layout_metrics",
        evidence_ref,
    )
    layout_intent = _ensure_dict(normalized_spec.get("layout_intent"), "Normalized Spatial Spec.layout_intent")
    normalized_ref = validate_reference_frame(
        layout_intent.get("reference_frame"),
        "Normalized Spatial Spec.layout_intent.reference_frame",
    )
    normalized_metrics = validate_content_layout_metrics(
        layout_intent.get("content_layout_metrics"),
        "Normalized Spatial Spec.layout_intent.content_layout_metrics",
        normalized_ref,
    )
    contract_ref = validate_reference_frame(
        layout_contract.get("reference_frame"),
        "Spatial Layout Contract.reference_frame",
    )
    contract_metrics = validate_content_layout_metrics(
        layout_contract.get("content_layout_metrics"),
        "Spatial Layout Contract.content_layout_metrics",
        contract_ref,
    )
    evidence_visual_contract = validate_visual_content_contract(
        evidence_facts.get("visual_content_contract"),
        "Evidence Packet.facts.visual_content_contract",
    )
    normalized_visual_contract = validate_visual_content_contract(
        layout_intent.get("visual_content_contract"),
        "Normalized Spatial Spec.layout_intent.visual_content_contract",
    )
    contract_visual_contract = validate_visual_content_contract(
        layout_contract.get("visual_content_contract"),
        "Spatial Layout Contract.visual_content_contract",
    )
    for label, ref in (
        ("Normalized Spatial Spec.layout_intent.reference_frame", normalized_ref),
        ("Spatial Layout Contract.reference_frame", contract_ref),
    ):
        if ref["target_window_dp"] != evidence_ref["target_window_dp"]:
            raise SystemExit(f"[artifact-check] {label}.target_window_dp must match Evidence Packet reference_frame")
    for label, metrics in (
        ("Normalized Spatial Spec.layout_intent.content_layout_metrics", normalized_metrics),
        ("Spatial Layout Contract.content_layout_metrics", contract_metrics),
    ):
        if metrics != evidence_metrics:
            raise SystemExit(f"[artifact-check] {label} must match Evidence Packet content_layout_metrics")
    for label, visual_contract in (
        ("Normalized Spatial Spec.layout_intent.visual_content_contract", normalized_visual_contract),
        ("Spatial Layout Contract.visual_content_contract", contract_visual_contract),
    ):
        if visual_contract != evidence_visual_contract:
            raise SystemExit(f"[artifact-check] {label} must match Evidence Packet visual_content_contract")
    regions = _ensure_list(layout_contract.get("regions"), "Spatial Layout Contract.regions")
    primary_regions = [item for item in regions if isinstance(item, dict) and item.get("type") != "window_chrome_ornament"]
    if primary_regions and not any(isinstance(item, dict) and item.get("size_basis") for item in primary_regions):
        raise SystemExit(
            "[artifact-check] Spatial Layout Contract.regions primary content must include size_basis for visual_reference"
        )


def validate_normalized_spec(path: Path) -> None:
    data = _ensure_dict(_load_json(path), "Normalized Spatial Spec")
    _require_keys(
        data,
        [
            "request_context",
            "product_intent",
            "spatial_intent",
            "window_intent",
            "layout_intent",
            "ambiguities",
            "evidence_trace",
        ],
        "Normalized Spatial Spec",
    )
    request_context = _ensure_dict(data["request_context"], "Normalized Spatial Spec.request_context")
    if "generation_mode" in request_context and request_context["generation_mode"] not in VALID_GENERATION_MODES:
        raise SystemExit(
            "[artifact-check] Normalized Spatial Spec.request_context.generation_mode "
            f"is invalid: {request_context['generation_mode']!r}"
        )
    if "existing_root_container" in request_context and request_context["existing_root_container"] not in VALID_CONTAINERS:
        raise SystemExit(
            "[artifact-check] Normalized Spatial Spec.request_context.existing_root_container "
            f"is invalid: {request_context['existing_root_container']!r}"
        )
    if "root_architecture_override" in request_context and not isinstance(
        request_context["root_architecture_override"],
        bool,
    ):
        raise SystemExit(
            "[artifact-check] Normalized Spatial Spec.request_context.root_architecture_override "
            "must be a boolean when present"
        )
    spatial_intent = _ensure_dict(data["spatial_intent"], "Normalized Spatial Spec.spatial_intent")
    if "container_candidate" in spatial_intent and spatial_intent["container_candidate"] not in VALID_CONTAINERS:
        raise SystemExit(
            "[artifact-check] Normalized Spatial Spec.spatial_intent.container_candidate "
            f"is invalid: {spatial_intent['container_candidate']!r}"
        )
    window_intent = _ensure_dict(data["window_intent"], "Normalized Spatial Spec.window_intent")
    if "window_model_candidate" in window_intent and window_intent["window_model_candidate"] not in VALID_WINDOW_MODELS:
        raise SystemExit(
            "[artifact-check] Normalized Spatial Spec.window_intent.window_model_candidate "
            f"is invalid: {window_intent['window_model_candidate']!r}"
        )
    _ensure_dict(data["product_intent"], "Normalized Spatial Spec.product_intent")
    _ensure_dict(data["layout_intent"], "Normalized Spatial Spec.layout_intent")
    _ensure_list(data["ambiguities"], "Normalized Spatial Spec.ambiguities")
    _ensure_list(data["evidence_trace"], "Normalized Spatial Spec.evidence_trace")
    _print_ok("Normalized Spatial Spec", path)


def validate_assumption_ledger(path: Path) -> None:
    data = _ensure_list(_load_json(path), "Assumption Ledger")
    for index, item in enumerate(data, start=1):
        obj = _ensure_dict(item, f"Assumption Ledger[{index}]")
        _require_keys(obj, ["assumption", "impact", "confidence"], f"Assumption Ledger[{index}]")
    _print_ok("Assumption Ledger", path)


def _valid_fact_ref(ref: str, evidence_facts: dict[str, Any]) -> bool:
    if ref.startswith("facts."):
        return ref.removeprefix("facts.") in evidence_facts
    return ref.startswith((
        "decision.",
        "container_decision.",
        "window_model_decision.",
        "phase4.",
        "Phase 4",
    ))


def _validate_optional_rejection(obj: dict[str, Any], label: str, evidence_facts: dict[str, Any]) -> None:
    if label not in obj:
        return
    rejection = _ensure_dict(obj[label], f"Spatial Layout Contract.{label}")
    _require_keys(rejection, ["alternative", "rejection_reason"], f"Spatial Layout Contract.{label}")
    reason = _require_non_empty_reason(rejection["rejection_reason"], f"Spatial Layout Contract.{label}.rejection_reason")
    if not any(token in reason for token in ("facts.", "decision.", "container_decision.", "window_model_decision.", "phase4", "Phase 4", "rule #", "legality")):
        raise SystemExit(
            f"[artifact-check] Spatial Layout Contract.{label}.rejection_reason must cite a fact, "
            "Phase-4 decision, legality row, or escalation rule"
        )
    if "facts." in reason:
        refs = [part.strip(" ,;:()[]{}\"'") for part in reason.split() if "facts." in part]
        if refs and not any(_valid_fact_ref(ref[ref.find("facts."):], evidence_facts) for ref in refs):
            raise SystemExit(
                f"[artifact-check] Spatial Layout Contract.{label}.rejection_reason cites unknown facts.* key"
            )


def validate_layout_evidence_trace(contract: dict[str, Any], evidence_facts: dict[str, Any]) -> None:
    trace = _ensure_list(contract.get("evidence_trace"), "Spatial Layout Contract.evidence_trace")
    if not trace:
        raise SystemExit("[artifact-check] Spatial Layout Contract.evidence_trace must not be empty")

    covered_windows: set[str] = set()
    for index, item in enumerate(trace, start=1):
        obj = _ensure_dict(item, f"Spatial Layout Contract.evidence_trace[{index}]")
        fact_ref = obj.get("fact_ref")
        if not isinstance(fact_ref, str) or not fact_ref.strip():
            raise SystemExit(
                f"[artifact-check] Spatial Layout Contract.evidence_trace[{index}].fact_ref must be a non-empty string"
            )
        if not _valid_fact_ref(fact_ref.strip(), evidence_facts):
            raise SystemExit(
                f"[artifact-check] Spatial Layout Contract.evidence_trace[{index}].fact_ref "
                f"must cite Evidence Packet.facts.* or a Phase-4 decision field: {fact_ref!r}"
            )
        window_id = obj.get("window_id")
        if isinstance(window_id, str) and window_id.strip():
            covered_windows.add(window_id.strip())

    windows = _ensure_list(contract.get("windows"), "Spatial Layout Contract.windows")
    for index, item in enumerate(windows, start=1):
        window = _ensure_dict(item, f"Spatial Layout Contract.windows[{index}]")
        window_id = window.get("id")
        if not isinstance(window_id, str) or not window_id.strip():
            raise SystemExit(f"[artifact-check] Spatial Layout Contract.windows[{index}].id must be a non-empty string")
        role = str(window.get("role", "")).lower()
        is_overlay = "overlay" in role or "popup" in role or "tooltip" in role
        if not is_overlay and window_id.strip() not in covered_windows:
            raise SystemExit(
                f"[artifact-check] Primary window {window_id!r} has no evidence_trace entry with window_id"
            )


def resolve_layout_contract_path(scratch_dir: Path) -> Path:
    for candidate in LAYOUT_CANDIDATES:
        path = scratch_dir / candidate
        if path.exists():
            return path
    tried = ", ".join(LAYOUT_CANDIDATES)
    raise SystemExit(
        f"[artifact-check] Missing Spatial Layout Contract under {scratch_dir}. "
        f"Tried: {tried}"
    )


def validate_layout_contract(path: Path, evidence_facts: dict[str, Any]) -> None:
    data = _ensure_dict(_load_json(path), "Spatial Layout Contract")
    _require_keys(
        data,
        [
            "container",
            "container_reason",
            "window_model",
            "window_reason",
            "windows",
            "regions",
            "repeated_structures",
            "states",
            "evidence_trace",
        ],
        "Spatial Layout Contract",
    )
    _require_non_empty_reason(data["container_reason"], "Spatial Layout Contract.container_reason")
    _require_non_empty_reason(data["window_reason"], "Spatial Layout Contract.window_reason")
    if data["container"] not in VALID_CONTAINERS:
        raise SystemExit(
            f"[artifact-check] Spatial Layout Contract has invalid container={data['container']!r}"
        )
    if data["window_model"] not in VALID_WINDOW_MODELS:
        raise SystemExit(
            f"[artifact-check] Spatial Layout Contract has invalid window_model={data['window_model']!r}"
        )
    _ensure_list(data["windows"], "Spatial Layout Contract.windows")
    _ensure_list(data["regions"], "Spatial Layout Contract.regions")
    _ensure_list(data["repeated_structures"], "Spatial Layout Contract.repeated_structures")
    _ensure_list(data["states"], "Spatial Layout Contract.states")
    validate_layout_evidence_trace(data, evidence_facts)
    _validate_optional_rejection(data, "rejected_near", evidence_facts)
    _validate_optional_rejection(data, "rejected_far", evidence_facts)
    _print_ok("Spatial Layout Contract", path)


def validate_patch_contract(path: Path) -> None:
    data = _ensure_dict(_load_json(path), "Patch Contract")
    _require_keys(
        data,
        [
            "target_module",
            "target_files",
            "inherits",
            "regions_touched",
            "components_to_add",
            "states_to_add",
            "non_goals",
        ],
        "Patch Contract",
    )
    inherits = _ensure_dict(data["inherits"], "Patch Contract.inherits")
    _require_keys(inherits, ["container", "window_model"], "Patch Contract.inherits")
    if inherits["container"] not in VALID_CONTAINERS:
        raise SystemExit(
            f"[artifact-check] Patch Contract has invalid inherits.container={inherits['container']!r}"
        )
    if inherits["window_model"] not in VALID_WINDOW_MODELS:
        raise SystemExit(
            f"[artifact-check] Patch Contract has invalid inherits.window_model={inherits['window_model']!r}"
        )
    _ensure_list(data["target_files"], "Patch Contract.target_files")
    _ensure_list(data["regions_touched"], "Patch Contract.regions_touched")
    _ensure_list(data["components_to_add"], "Patch Contract.components_to_add")
    _ensure_list(data["states_to_add"], "Patch Contract.states_to_add")
    _ensure_list(data["non_goals"], "Patch Contract.non_goals")
    _print_ok("Patch Contract", path)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", required=True, help="Project directory containing .scratch/")
    parser.add_argument(
        "--scratch-dir",
        help="Optional override for the scratch directory. Defaults to <target>/.scratch",
    )
    parser.add_argument(
        "--require-assumptions",
        action="store_true",
        help="Deprecated: assumption_ledger.json is always required; kept for CLI compatibility",
    )
    args = parser.parse_args()

    target = Path(args.target).expanduser().resolve()
    scratch_dir = Path(args.scratch_dir).expanduser().resolve() if args.scratch_dir else (target / ".scratch")
    if not scratch_dir.exists() or not scratch_dir.is_dir():
        raise SystemExit(f"[artifact-check] Scratch directory not found: {scratch_dir}")

    input_envelope_path = scratch_dir / "input_envelope.json"
    validate_input_envelope(input_envelope_path)
    input_envelope = _ensure_dict(_load_json(input_envelope_path), "Input Envelope")
    evidence_path = scratch_dir / "evidence_packet.json"
    validate_evidence_packet(evidence_path)
    evidence_packet = _ensure_dict(_load_json(evidence_path), "Evidence Packet")
    evidence_facts = _ensure_dict(evidence_packet.get("facts"), "Evidence Packet.facts")
    normalized_spec_path = scratch_dir / "normalized_spatial_spec.json"
    validate_normalized_spec(normalized_spec_path)
    normalized_spec = _ensure_dict(_load_json(normalized_spec_path), "Normalized Spatial Spec")

    assumption_path = scratch_dir / "assumption_ledger.json"
    if not assumption_path.exists():
        raise SystemExit(
            f"[artifact-check] Assumption Ledger required but missing: {assumption_path}. "
            "Use [] when there are no assumptions."
        )
    validate_assumption_ledger(assumption_path)

    if input_envelope.get("input_mode") == "incremental_patch":
        validate_patch_contract(scratch_dir / PATCH_CONTRACT)
    else:
        layout_contract_path = resolve_layout_contract_path(scratch_dir)
        validate_layout_contract(layout_contract_path, evidence_facts)
        layout_contract = _ensure_dict(_load_json(layout_contract_path), "Spatial Layout Contract")
        validate_visual_reference_size_contract(
            input_envelope,
            evidence_packet,
            normalized_spec,
            layout_contract,
        )
    print("[artifact-check] SUCCESS workflow artifacts look structurally valid")
    return 0


if __name__ == "__main__":
    sys.exit(main())
