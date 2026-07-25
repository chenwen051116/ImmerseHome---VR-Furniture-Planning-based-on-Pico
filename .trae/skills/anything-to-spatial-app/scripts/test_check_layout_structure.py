#!/usr/bin/env python3
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT_PATH = Path(__file__).resolve().parent / "check_layout_structure.py"


def load_checker_module():
    spec = importlib.util.spec_from_file_location("check_layout_structure", SCRIPT_PATH)
    if spec is None or spec.loader is None:
        raise AssertionError(f"Unable to load checker module from {SCRIPT_PATH}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class CheckLayoutStructureTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.target_dir = Path(self.temp_dir.name) / "generated-app"
        self.scratch_dir = self.target_dir / ".scratch"
        self.scratch_dir.mkdir(parents=True)

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def write_artifacts(
        self,
        *,
        input_envelope: dict | None = None,
        evidence_packet: dict | None = None,
        normalized_spec: dict | None = None,
        layout_contract: dict | None = None,
    ) -> None:
        (self.scratch_dir / "input_envelope.json").write_text(
            json.dumps(input_envelope or self.default_input_envelope()),
            encoding="utf-8",
        )
        (self.scratch_dir / "evidence_packet.json").write_text(
            json.dumps(evidence_packet or self.default_evidence_packet()),
            encoding="utf-8",
        )
        (self.scratch_dir / "normalized_spatial_spec.json").write_text(
            json.dumps(normalized_spec or self.default_normalized_spec()),
            encoding="utf-8",
        )
        (self.scratch_dir / "spatial_layout_contract.json").write_text(
            json.dumps(layout_contract or self.default_layout_contract()),
            encoding="utf-8",
        )

    @staticmethod
    def default_input_envelope() -> dict:
        return {
            "input_mode": "visual_reference",
            "generation_mode": "new_project",
            "input_sources": [{"type": "screenshot", "trust_level": "medium"}],
        }

    @staticmethod
    def default_evidence_packet() -> dict:
        return {
            "facts": {
                "app_type_candidates": ["dashboard"],
                "regions": ["header", "sidebar", "content", "popup"],
                "repeated_structures": ["nav_item", "content_card"],
                "visible_states": ["selected_nav_item", "popup_visible"],
                "spatial_cues": ["flat_panel"],
                "interaction_cues": ["search", "popup_open_close"],
            },
            "unknowns": ["popup_persistence"],
            "conflicts": [],
            "confidence": {"layout": 0.84, "interaction": 0.72, "spatial_mode": 0.7},
        }

    @staticmethod
    def default_normalized_spec() -> dict:
        return {
            "request_context": {
                "generation_mode": "new_project",
                "target_module": None,
                "output_dir": None,
            },
            "product_intent": {
                "app_type": "dashboard",
                "primary_user_goal": "browse information",
                "core_tasks": ["navigate", "inspect"],
            },
            "spatial_intent": {
                "container_candidate": "ON_PLAIN",
                "container_confidence": 0.82,
                "spatial_features": [],
                "immersion_need": "none",
            },
            "window_intent": {
                "window_model_candidate": "single_panel_with_popup",
                "window_confidence": 0.81,
                "surfaces": [{"id": "main", "role": "primary_panel"}],
            },
            "layout_intent": {
                "regions": [
                    {"id": "sidebar", "type": "nav_region"},
                    {"id": "content", "type": "content_region"},
                ],
                "repeated_structures": ["nav_item", "content_card"],
                "states": ["popup_visible"],
            },
            "ambiguities": [
                {
                    "key": "popup_persistence",
                    "default_decision": "treat_as_overlay",
                    "reason": "smallest explanation first",
                }
            ],
            "evidence_trace": [
                {
                    "claim": "single_panel_with_popup",
                    "because": "small popup remains attached to the main panel",
                }
            ],
        }

    @staticmethod
    def default_layout_contract() -> dict:
        return {
            "container": "ON_PLAIN",
            "container_reason": "Flat dashboard with no immersive cues.",
            "window_model": "single_panel_with_popup",
            "window_reason": "One coordinated panel with a small anchored popup.",
            "spatial_features": [],
            "windows": [
                {"id": "main", "role": "primary_panel"},
                {"id": "popup", "role": "overlay", "anchor": "top_right_of_content"},
            ],
            "regions": [
                {"id": "sidebar", "type": "nav_region"},
                {"id": "content", "type": "content_region"},
            ],
            "repeated_structures": ["nav_item", "content_card"],
            "states": ["popup_visible"],
        }

    def test_rejects_multi_window_without_disconnected_surface_evidence(self) -> None:
        checker = load_checker_module()
        layout_contract = self.default_layout_contract() | {
            "window_model": "multi_window",
            "window_reason": "Two panels are floating independently in space.",
            "windows": [
                {"id": "main", "role": "primary_panel"},
                {"id": "secondary", "role": "secondary_panel"},
            ],
        }
        self.write_artifacts(layout_contract=layout_contract)

        with self.assertRaises(SystemExit) as context:
            checker.main(["--target", str(self.target_dir)])

        self.assertIn("multi_window", str(context.exception))
        self.assertIn("disconnected", str(context.exception))

    def test_rejects_stage_only_features_in_window_container(self) -> None:
        checker = load_checker_module()
        normalized_spec = self.default_normalized_spec()
        normalized_spec["spatial_intent"]["spatial_features"] = ["anchor", "env_mesh"]
        layout_contract = self.default_layout_contract() | {
            "spatial_features": ["anchor", "env_mesh"],
        }
        self.write_artifacts(normalized_spec=normalized_spec, layout_contract=layout_contract)

        with self.assertRaises(SystemExit) as context:
            checker.main(["--target", str(self.target_dir)])

        self.assertIn("require a Stage container", str(context.exception))
        self.assertIn("anchor", str(context.exception))

    def test_rejects_existing_module_root_switch_without_override(self) -> None:
        checker = load_checker_module()
        input_envelope = self.default_input_envelope() | {
            "generation_mode": "existing_module",
        }
        normalized_spec = self.default_normalized_spec()
        normalized_spec["request_context"] = {
            "generation_mode": "existing_module",
            "target_module": "myapp",
            "output_dir": None,
            "existing_root_container": "ON_PLAIN",
        }
        normalized_spec["spatial_intent"]["container_candidate"] = "STAGE_MIXED"
        layout_contract = self.default_layout_contract() | {
            "container": "STAGE_MIXED",
            "container_reason": "Switched to mixed stage without explicit approval.",
        }
        self.write_artifacts(
            input_envelope=input_envelope,
            normalized_spec=normalized_spec,
            layout_contract=layout_contract,
        )

        with self.assertRaises(SystemExit) as context:
            checker.main(["--target", str(self.target_dir)])

        self.assertIn("existing module", str(context.exception))
        self.assertIn("root architecture", str(context.exception))


if __name__ == "__main__":
    unittest.main()
