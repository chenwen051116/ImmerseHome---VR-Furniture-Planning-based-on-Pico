#!/usr/bin/env python3
"""Validate anything-to-spatial-app adapter registry and markdown contracts."""
from __future__ import annotations

import argparse
import json
import re
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
VALID_ADAPTER_STATUSES = {"active", "planned", "deprecated", "removed"}
REQUIRED_ADAPTER_SECTIONS = {
    "trigger",
    "inputs",
    "produces",
    "required_tools",
    "required_references",
    "side_effects",
    "failure_mode",
}
ALLOWED_OUTPUT_FILES = {
    "evidence_packet.json",
    "normalized_spatial_spec.json",
    "assumption_ledger.json",
}
FORBIDDEN_SCHEMA_HINTS = {
    "container_decision.json",
    "window_model_decision.json",
    "spatial_layout_contract.json",
    "patch_contract.json",
}
FORBIDDEN_DECISION_REFERENCES = {
    "references/container-decision.md",
    "references/window-model-decision.md",
}
FORBIDDEN_DECISION_HINT_PATTERNS = {
    r"\bcontainer_candidate\b",
    r"\bwindow_model_candidate\b",
}
FIGMA_VERIFY_TOOL = "mcp__codin-d2c-figma-to-code__d2c_verify_code"
FIGMA_CLEANUP_TOOL = "mcp__codin-d2c-figma-to-code__d2c_cleanup_temp"


def _load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise SystemExit(f"[adapter-check] Missing registry: {path}") from exc
    except json.JSONDecodeError as exc:
        raise SystemExit(f"[adapter-check] Invalid JSON in {path}: {exc}") from exc


def _ensure_dict(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise SystemExit(f"[adapter-check] {label} must be a JSON object")
    return value


def _ensure_list(value: Any, label: str) -> list[Any]:
    if not isinstance(value, list):
        raise SystemExit(f"[adapter-check] {label} must be a JSON array")
    return value


def _section_names(markdown: str) -> set[str]:
    sections: set[str] = set()
    for match in re.finditer(r"^##\s+\d+\.\s+([a-z_]+)\s*$", markdown, flags=re.MULTILINE):
        sections.add(match.group(1))
    return sections


def _validate_adapter_markdown(path: Path, input_mode: str, adapter_name: str) -> None:
    try:
        text = path.read_text(encoding="utf-8")
    except FileNotFoundError as exc:
        raise SystemExit(f"[adapter-check] Missing adapter file: {path}") from exc

    missing_sections = REQUIRED_ADAPTER_SECTIONS - _section_names(text)
    if missing_sections:
        raise SystemExit(
            f"[adapter-check] {path} missing sections: {', '.join(sorted(missing_sections))}"
        )

    if f"input_mode: {input_mode}" not in text:
        raise SystemExit(
            f"[adapter-check] {path} frontmatter must include input_mode: {input_mode}"
        )
    if f"adapter: {adapter_name}" not in text:
        raise SystemExit(
            f"[adapter-check] {path} frontmatter must include adapter: {adapter_name}"
        )

    for forbidden in FORBIDDEN_SCHEMA_HINTS:
        if forbidden in text:
            raise SystemExit(
                f"[adapter-check] {path} mentions forbidden adapter output {forbidden}; "
                "adapters may only fill evidence/normalized/assumption artifacts"
            )

    for forbidden in FORBIDDEN_DECISION_REFERENCES:
        if forbidden in text:
            raise SystemExit(
                f"[adapter-check] {path} references Phase 4 decision doc {forbidden}; "
                "adapters may extract evidence only, while container/window decisions belong to Phase 4"
            )

    for pattern in FORBIDDEN_DECISION_HINT_PATTERNS:
        if re.search(pattern, text):
            raise SystemExit(
                f"[adapter-check] {path} contains Phase 4 decision hint matching {pattern!r}; "
                "record evidence/assumptions instead of adapter-level candidates"
            )

    mentioned_outputs = {name for name in ALLOWED_OUTPUT_FILES if name in text}
    if "evidence_packet.json" not in mentioned_outputs or "normalized_spatial_spec.json" not in mentioned_outputs:
        raise SystemExit(
            f"[adapter-check] {path} must declare evidence_packet.json and "
            "normalized_spatial_spec.json in produces/side_effects"
        )

    if adapter_name == "figma-adapter":
        verify_pos = text.find(FIGMA_VERIFY_TOOL)
        cleanup_pos = text.find(FIGMA_CLEANUP_TOOL)
        if verify_pos < 0 or cleanup_pos < 0:
            raise SystemExit(
                f"[adapter-check] {path} must mention both {FIGMA_VERIFY_TOOL} "
                f"and {FIGMA_CLEANUP_TOOL}"
            )
        if cleanup_pos < verify_pos:
            raise SystemExit(
                f"[adapter-check] {path} must order figma cleanup after d2c_verify_code"
            )


def _validate_required_flag(value: Any, label: str) -> None:
    if value is True or value == "when_condition_matches":
        return
    raise SystemExit(f"[adapter-check] {label}.required must be true or when_condition_matches")


def _validate_figma_cleanup_hook(entry: dict[str, Any], index: int) -> None:
    hooks = _ensure_dict(entry.get("hooks"), f"registry.adapters[{index}].hooks")
    cleanup = _ensure_dict(hooks.get("cleanup"), f"registry.adapters[{index}].hooks.cleanup")

    if cleanup.get("tool") != FIGMA_CLEANUP_TOOL:
        raise SystemExit(
            f"[adapter-check] registry.adapters[{index}].hooks.cleanup.tool must be "
            f"{FIGMA_CLEANUP_TOOL}"
        )
    if cleanup.get("runs_after") != FIGMA_VERIFY_TOOL:
        raise SystemExit(
            f"[adapter-check] registry.adapters[{index}].hooks.cleanup.runs_after must be "
            f"{FIGMA_VERIFY_TOOL}"
        )
    _validate_required_flag(cleanup.get("required"), f"registry.adapters[{index}].hooks.cleanup")
    args_from = _ensure_list(
        cleanup.get("args_from"),
        f"registry.adapters[{index}].hooks.cleanup.args_from",
    )
    required_args = {"input_sources[].url", "target_code_dir"}
    if not required_args.issubset(set(args_from)):
        raise SystemExit(
            f"[adapter-check] registry.adapters[{index}].hooks.cleanup.args_from "
            f"must include {', '.join(sorted(required_args))}"
        )


def _validate_figma_verify_hook(entry: dict[str, Any], index: int) -> None:
    hooks = _ensure_dict(entry.get("hooks"), f"registry.adapters[{index}].hooks")
    verify = _ensure_dict(hooks.get("verify"), f"registry.adapters[{index}].hooks.verify")

    if verify.get("tool") != FIGMA_VERIFY_TOOL:
        raise SystemExit(
            f"[adapter-check] registry.adapters[{index}].hooks.verify.tool must be "
            f"{FIGMA_VERIFY_TOOL}"
        )
    if verify.get("runs_before") != FIGMA_CLEANUP_TOOL:
        raise SystemExit(
            f"[adapter-check] registry.adapters[{index}].hooks.verify.runs_before must be "
            f"{FIGMA_CLEANUP_TOOL}"
        )
    _validate_required_flag(verify.get("required"), f"registry.adapters[{index}].hooks.verify")
    if verify.get("call_count") != "exactly_once":
        raise SystemExit(
            f"[adapter-check] registry.adapters[{index}].hooks.verify.call_count "
            "must be exactly_once"
        )
    args_from = _ensure_list(
        verify.get("args_from"),
        f"registry.adapters[{index}].hooks.verify.args_from",
    )
    required_args = {"input_sources[].url", "generated_code_files", "platform", "code_context"}
    if not required_args.issubset(set(args_from)):
        raise SystemExit(
            f"[adapter-check] registry.adapters[{index}].hooks.verify.args_from "
            f"must include {', '.join(sorted(required_args))}"
        )


def _adapter_uses_figma_get_data(path: Path) -> bool:
    try:
        text = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        return False
    return "d2c_get_figma_data" in text or "mcp__codin-d2c-figma-to-code__d2c_get_figma_data" in text


def validate_registry(skill_root: Path) -> None:
    registry_path = skill_root / "adapters" / "_registry.json"
    registry = _ensure_dict(_load_json(registry_path), "registry")
    adapters = _ensure_list(registry.get("adapters"), "registry.adapters")
    if not adapters:
        raise SystemExit("[adapter-check] registry.adapters must not be empty")

    seen_modes: set[str] = set()
    for index, item in enumerate(adapters, start=1):
        entry = _ensure_dict(item, f"registry.adapters[{index}]")
        for key in ("input_mode", "adapter", "path", "status", "priority"):
            if key not in entry:
                raise SystemExit(f"[adapter-check] registry.adapters[{index}] missing {key}")
        mode = entry["input_mode"]
        if mode not in VALID_INPUT_MODES:
            raise SystemExit(f"[adapter-check] invalid input_mode in registry: {mode!r}")
        if mode in seen_modes:
            raise SystemExit(f"[adapter-check] duplicate adapter for input_mode={mode!r}")
        seen_modes.add(mode)

        status = entry["status"]
        if status not in VALID_ADAPTER_STATUSES:
            raise SystemExit(
                f"[adapter-check] registry.adapters[{index}] has invalid status={status!r}"
            )
        adapter_path = skill_root / entry["path"]
        if status == "active" and not adapter_path.exists():
            raise SystemExit(
                f"[adapter-check] active adapter file is missing for input_mode={mode!r}: "
                f"{adapter_path}"
            )
        if status in {"active", "planned"} and adapter_path.exists():
            _validate_adapter_markdown(adapter_path, mode, entry["adapter"])
        has_hooks = isinstance(entry.get("hooks"), dict)
        if has_hooks:
            _validate_figma_verify_hook(entry, index)
            _validate_figma_cleanup_hook(entry, index)
        if _adapter_uses_figma_get_data(adapter_path) and not has_hooks:
            raise SystemExit(
                f"[adapter-check] registry.adapters[{index}] uses d2c_get_figma_data "
                "but does not declare verify/cleanup hooks"
            )

    missing_modes = VALID_INPUT_MODES - seen_modes
    if missing_modes:
        raise SystemExit(
            f"[adapter-check] registry missing input modes: {', '.join(sorted(missing_modes))}"
        )

    print(f"[adapter-check] PASS registry: {registry_path}")
    print(f"[adapter-check] PASS input modes: {', '.join(sorted(seen_modes))}")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--skill-root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="Path to the anything-to-spatial-app skill directory",
    )
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    validate_registry(args.skill_root.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
