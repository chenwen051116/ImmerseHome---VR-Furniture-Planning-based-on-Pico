#!/usr/bin/env python3
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT_PATH = Path(__file__).resolve().parent / "check_workflow_artifacts.py"


def load_checker_module():
    spec = importlib.util.spec_from_file_location("check_workflow_artifacts", SCRIPT_PATH)
    if spec is None or spec.loader is None:
        raise AssertionError(f"Unable to load checker module from {SCRIPT_PATH}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class CheckWorkflowArtifactsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.target_dir = Path(self.temp_dir.name) / "generated-app"
        self.scratch_dir = self.target_dir / ".scratch"
        self.scratch_dir.mkdir(parents=True)

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def write_json(self, name: str, data: object) -> None:
        (self.scratch_dir / name).write_text(json.dumps(data), encoding="utf-8")

    def write_minimal_visual_reference_artifacts(self) -> None:
        self.write_json(
            "input_envelope.json",
            {
                "input_mode": "visual_reference",
                "generation_mode": "new_project",
                "input_sources": [{"type": "image", "trust_level": "high"}],
            },
        )
        self.write_json(
            "evidence_packet.json",
            {
                "facts": {
                    "frame_hierarchy": "bounded panel",
                    "regions": ["main_panel"],
                    "spatial_cues": ["flat_panel"],
                },
                "unknowns": [],
                "conflicts": [],
                "confidence": {"layout": "high"},
            },
        )
        self.write_json(
            "normalized_spatial_spec.json",
            {
                "request_context": {"generation_mode": "new_project"},
                "product_intent": {"summary": "demo", "primary_task": "browse"},
                "spatial_intent": {"container_candidate": "ON_PLAIN"},
                "window_intent": {"window_model_candidate": "single_panel"},
                "layout_intent": {"primary_regions": ["main_panel"]},
                "ambiguities": [],
                "evidence_trace": [],
            },
        )
        self.write_json("assumption_ledger.json", [])
        self.write_json(
            "spatial_layout_contract.json",
            {
                "container": "ON_PLAIN",
                "container_reason": "facts.spatial_cues shows flat panel",
                "window_model": "single_panel",
                "window_reason": "facts.regions has one main panel",
                "windows": [
                    {
                        "id": "main",
                        "role": "primary_panel",
                        "anchor": "center",
                        "default_visibility": "visible",
                        "children": ["main_panel"],
                    }
                ],
                "regions": [{"id": "main_panel", "type": "content_region"}],
                "repeated_structures": [],
                "states": [],
                "evidence_trace": [{"window_id": "main", "fact_ref": "facts.regions"}],
                "rejected_near": {
                    "alternative": "IN_VOLUME",
                    "rejection_reason": "facts.spatial_cues has flat panel only",
                },
                "rejected_far": {
                    "alternative": "STAGE_MIXED",
                    "rejection_reason": "facts.spatial_cues has no anchor or env_mesh",
                },
            },
        )

    def add_reference_frame_only(self) -> None:
        reference_frame = {
            "screenshot_px": {"width": 864, "height": 542},
            "app_owned_bbox_px": {"x": 126, "y": 98, "width": 645, "height": 361},
            "target_window_dp": {"width": 1120, "height": 620},
            "scale_policy": "fit_app_owned_bbox_preserve_aspect",
            "dp_per_px": 1.736,
            "excluded_from_size": ["environment_context", "window_chrome_ornaments"],
        }
        evidence = json.loads((self.scratch_dir / "evidence_packet.json").read_text(encoding="utf-8"))
        evidence["facts"]["reference_frame"] = reference_frame
        self.write_json("evidence_packet.json", evidence)

        normalized = json.loads((self.scratch_dir / "normalized_spatial_spec.json").read_text(encoding="utf-8"))
        normalized["layout_intent"]["reference_frame"] = reference_frame
        self.write_json("normalized_spatial_spec.json", normalized)

        contract = json.loads((self.scratch_dir / "spatial_layout_contract.json").read_text(encoding="utf-8"))
        contract["reference_frame"] = reference_frame
        contract["regions"] = [{"id": "main_panel", "type": "content_region", "size_basis": "reference_frame.app_owned_bbox_px"}]
        self.write_json("spatial_layout_contract.json", contract)

    def add_content_layout_metrics_only(self) -> None:
        metrics = {
            "panel_padding_px": {"start": 10, "top": 11, "end": 17, "bottom": 15},
            "regions_px": {"main_panel": {"x": 126, "y": 98, "width": 645, "height": 361}},
            "repeated_metrics_px": {"result_image_card": {"width": 143, "height": 130, "gap_x": 11, "gap_y": 11}},
        }
        evidence = json.loads((self.scratch_dir / "evidence_packet.json").read_text(encoding="utf-8"))
        evidence["facts"]["content_layout_metrics"] = metrics
        self.write_json("evidence_packet.json", evidence)

        normalized = json.loads((self.scratch_dir / "normalized_spatial_spec.json").read_text(encoding="utf-8"))
        normalized["layout_intent"]["content_layout_metrics"] = metrics
        self.write_json("normalized_spatial_spec.json", normalized)

        contract = json.loads((self.scratch_dir / "spatial_layout_contract.json").read_text(encoding="utf-8"))
        contract["content_layout_metrics"] = metrics
        self.write_json("spatial_layout_contract.json", contract)

    def test_visual_reference_requires_reference_frame_for_layout_size_mapping(self) -> None:
        checker = load_checker_module()
        self.write_minimal_visual_reference_artifacts()

        with self.assertRaises(SystemExit) as context:
            old_argv = sys.argv
            try:
                sys.argv = ["check_workflow_artifacts.py", "--target", str(self.target_dir)]
                checker.main()
            finally:
                sys.argv = old_argv

        self.assertIn("reference_frame", str(context.exception))

    def test_visual_reference_requires_content_layout_metrics_for_internal_adaptation(self) -> None:
        checker = load_checker_module()
        self.write_minimal_visual_reference_artifacts()
        self.add_reference_frame_only()

        with self.assertRaises(SystemExit) as context:
            old_argv = sys.argv
            try:
                sys.argv = ["check_workflow_artifacts.py", "--target", str(self.target_dir)]
                checker.main()
            finally:
                sys.argv = old_argv

        self.assertIn("content_layout_metrics", str(context.exception))

    def test_visual_reference_requires_visual_content_contract_for_semantic_fidelity(self) -> None:
        checker = load_checker_module()
        self.write_minimal_visual_reference_artifacts()
        self.add_reference_frame_only()
        self.add_content_layout_metrics_only()

        with self.assertRaises(SystemExit) as context:
            old_argv = sys.argv
            try:
                sys.argv = ["check_workflow_artifacts.py", "--target", str(self.target_dir)]
                checker.main()
            finally:
                sys.argv = old_argv

        self.assertIn("visual_content_contract", str(context.exception))

    def test_visual_reference_search_pill_contract_requires_interaction_role(self) -> None:
        checker = load_checker_module()
        self.write_minimal_visual_reference_artifacts()
        self.add_reference_frame_only()
        self.add_content_layout_metrics_only()
        visual_contract = {
            "sidebar": {
                "has_surface": True,
                "preferred_component": "SideNavigation",
                "search_pill": {"width_policy": "fill_sidebar_content_width"},
            },
            "tabs": {"visible_count": 9, "style": "small_capsule_background"},
            "cards": {
                "layout": "fixed_3x2",
                "content": "image_only",
                "has_text_overlay": False,
                "asset_policy": "reference_like_images_or_crops",
            },
        }
        evidence = json.loads((self.scratch_dir / "evidence_packet.json").read_text(encoding="utf-8"))
        evidence["facts"]["visual_content_contract"] = visual_contract
        self.write_json("evidence_packet.json", evidence)

        normalized = json.loads((self.scratch_dir / "normalized_spatial_spec.json").read_text(encoding="utf-8"))
        normalized["layout_intent"]["visual_content_contract"] = visual_contract
        self.write_json("normalized_spatial_spec.json", normalized)

        contract = json.loads((self.scratch_dir / "spatial_layout_contract.json").read_text(encoding="utf-8"))
        contract["visual_content_contract"] = visual_contract
        self.write_json("spatial_layout_contract.json", contract)

        with self.assertRaises(SystemExit) as context:
            old_argv = sys.argv
            try:
                sys.argv = ["check_workflow_artifacts.py", "--target", str(self.target_dir)]
                checker.main()
            finally:
                sys.argv = old_argv

        self.assertIn("sidebar.search_pill.interaction_role", str(context.exception))

    def test_visual_reference_sidebar_contract_requires_preferred_side_navigation(self) -> None:
        checker = load_checker_module()
        self.write_minimal_visual_reference_artifacts()
        self.add_reference_frame_only()
        self.add_content_layout_metrics_only()
        visual_contract = {
            "sidebar": {
                "has_surface": True,
                "search_pill": {
                    "width_policy": "fill_sidebar_content_width",
                    "interaction_role": "search_input",
                    "preferred_component": "SearchField",
                },
            },
            "tabs": {"visible_count": 9, "style": "small_capsule_background"},
            "cards": {
                "layout": "fixed_3x2",
                "content": "image_only",
                "has_text_overlay": False,
                "asset_policy": "reference_like_images_or_crops",
            },
        }
        evidence = json.loads((self.scratch_dir / "evidence_packet.json").read_text(encoding="utf-8"))
        evidence["facts"]["visual_content_contract"] = visual_contract
        self.write_json("evidence_packet.json", evidence)

        normalized = json.loads((self.scratch_dir / "normalized_spatial_spec.json").read_text(encoding="utf-8"))
        normalized["layout_intent"]["visual_content_contract"] = visual_contract
        self.write_json("normalized_spatial_spec.json", normalized)

        contract = json.loads((self.scratch_dir / "spatial_layout_contract.json").read_text(encoding="utf-8"))
        contract["visual_content_contract"] = visual_contract
        self.write_json("spatial_layout_contract.json", contract)

        with self.assertRaises(SystemExit) as context:
            old_argv = sys.argv
            try:
                sys.argv = ["check_workflow_artifacts.py", "--target", str(self.target_dir)]
                checker.main()
            finally:
                sys.argv = old_argv

        self.assertIn("sidebar.preferred_component", str(context.exception))

    def test_visual_reference_sidebar_chips_contract_requires_builtin_chip_components(self) -> None:
        checker = load_checker_module()
        self.write_minimal_visual_reference_artifacts()
        self.add_reference_frame_only()
        self.add_content_layout_metrics_only()
        visual_contract = {
            "sidebar": {
                "has_surface": True,
                "preferred_component": "SideNavigation",
                "search_pill": {
                    "width_policy": "fill_sidebar_content_width",
                    "interaction_role": "search_input",
                    "preferred_component": "SearchField",
                },
                "chips": {
                    "active_chips_have_close_icon": True,
                    "recommendation_chips_may_have_leading_icon": True,
                },
            },
            "tabs": {"visible_count": 9, "style": "small_capsule_background"},
            "cards": {
                "layout": "fixed_3x2",
                "content": "image_only",
                "has_text_overlay": False,
                "asset_policy": "reference_like_images_or_crops",
            },
        }
        evidence = json.loads((self.scratch_dir / "evidence_packet.json").read_text(encoding="utf-8"))
        evidence["facts"]["visual_content_contract"] = visual_contract
        self.write_json("evidence_packet.json", evidence)

        normalized = json.loads((self.scratch_dir / "normalized_spatial_spec.json").read_text(encoding="utf-8"))
        normalized["layout_intent"]["visual_content_contract"] = visual_contract
        self.write_json("normalized_spatial_spec.json", normalized)

        contract = json.loads((self.scratch_dir / "spatial_layout_contract.json").read_text(encoding="utf-8"))
        contract["visual_content_contract"] = visual_contract
        self.write_json("spatial_layout_contract.json", contract)

        with self.assertRaises(SystemExit) as context:
            old_argv = sys.argv
            try:
                sys.argv = ["check_workflow_artifacts.py", "--target", str(self.target_dir)]
                checker.main()
            finally:
                sys.argv = old_argv

        self.assertIn("sidebar.chips.preferred_component", str(context.exception))


if __name__ == "__main__":
    unittest.main()
