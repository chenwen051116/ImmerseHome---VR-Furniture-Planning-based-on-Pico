#!/usr/bin/env python3
import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT_PATH = Path(__file__).resolve().parent / "check_architecture.py"


def load_architecture_module():
    spec = importlib.util.spec_from_file_location("check_architecture", SCRIPT_PATH)
    if spec is None or spec.loader is None:
        raise AssertionError(f"Unable to load architecture checker from {SCRIPT_PATH}")
    module = importlib.util.module_from_spec(spec)
    sys.modules["check_architecture"] = module
    spec.loader.exec_module(module)
    return module


class CheckArchitectureTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.target_dir = Path(self.temp_dir.name) / "generated-app"
        self.module_root = self.target_dir / "src/main/java/com/picoxr/generated"
        self.feature_dir = self.module_root / "ui/search"
        (self.module_root / "platform").mkdir(parents=True)
        (self.module_root / "domain/model").mkdir(parents=True)
        (self.module_root / "data/repository").mkdir(parents=True)
        (self.module_root / "domain/usecase").mkdir(parents=True)
        self.feature_dir.mkdir(parents=True)
        (self.target_dir / ".scratch").mkdir(parents=True)
        (self.target_dir / "build.gradle.kts").write_text(
            'android { namespace = "com.picoxr.generated" }',
            encoding="utf-8",
        )
        (self.target_dir / "src/main/java/Main.kt").parent.mkdir(parents=True, exist_ok=True)
        (self.target_dir / "src/main/java/Main.kt").write_text(
            "fun mainApp(scope: SpatialAppScope) = Unit",
            encoding="utf-8",
        )
        (self.module_root / "data/repository/SearchRepository.kt").write_text(
            "interface SearchRepository",
            encoding="utf-8",
        )
        (self.module_root / "domain/usecase/SearchUseCase.kt").write_text(
            "class SearchUseCase",
            encoding="utf-8",
        )
        (self.feature_dir / "SearchViewModel.kt").write_text(
            "class SearchViewModel : ViewModel()",
            encoding="utf-8",
        )
        (self.feature_dir / "SearchUiState.kt").write_text(
            "data class SearchUiState(val query: String = \"\")\nsealed interface SearchEvent",
            encoding="utf-8",
        )
        (self.feature_dir / "SearchScreen.kt").write_text(
            """
            @Composable
            fun SearchScreen() {
                val vm = viewModel<SearchViewModel>()
                val state by vm.state.collectAsStateWithLifecycle()
                SearchContent()
            }

            @Composable private fun SearchContent() {}
            @Composable private fun FilterSidebar() {}
            @Composable private fun ResultGrid() {}
            """,
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def test_rejects_feature_with_empty_components_directory(self) -> None:
        checker = load_architecture_module()
        (self.feature_dir / "components").mkdir()

        report = checker.Report()
        module_root = checker.discover_module_root(self.target_dir)
        self.assertIsNotNone(module_root)
        pairs = checker.collect_pairs(module_root)
        checker.check_component_file_isolation(module_root, pairs, report)

        self.assertTrue(report.errors)
        messages = "\n".join(f.message for f in report.errors)
        self.assertIn("components", messages)
        self.assertIn("empty", messages)

    def test_accepts_feature_with_extracted_component_file(self) -> None:
        checker = load_architecture_module()
        components = self.feature_dir / "components"
        components.mkdir()
        (components / "FilterSidebar.kt").write_text(
            "@Composable internal fun FilterSidebar() {}",
            encoding="utf-8",
        )

        report = checker.Report()
        module_root = checker.discover_module_root(self.target_dir)
        self.assertIsNotNone(module_root)
        pairs = checker.collect_pairs(module_root)
        checker.check_component_file_isolation(module_root, pairs, report)

        self.assertFalse(report.errors)


if __name__ == "__main__":
    unittest.main()
