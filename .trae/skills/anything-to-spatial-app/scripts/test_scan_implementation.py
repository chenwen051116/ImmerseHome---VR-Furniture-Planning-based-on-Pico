#!/usr/bin/env python3
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT_PATH = Path(__file__).resolve().parent / "scan_implementation.py"


def load_scanner_module():
    spec = importlib.util.spec_from_file_location("scan_implementation", SCRIPT_PATH)
    if spec is None or spec.loader is None:
        raise AssertionError(f"Unable to load scanner module from {SCRIPT_PATH}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ScanImplementationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.target_dir = Path(self.temp_dir.name) / "generated-app"
        self.scratch_dir = self.target_dir / ".scratch"
        self.src_dir = self.target_dir / "src/main/java/com/example/app"
        self.scratch_dir.mkdir(parents=True)
        self.src_dir.mkdir(parents=True)
        (self.target_dir / "src/main").mkdir(parents=True, exist_ok=True)

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def write_contract(self) -> None:
        (self.scratch_dir / "spatial_layout_contract.json").write_text(
            json.dumps(
                {
                    "container": "ON_PLAIN",
                    "window_model": "sidebar_content",
                    "window_chrome_ornaments": [
                        {
                            "id": "left_nav_rail",
                            "type": "TabBar",
                            "placement": "Left",
                        }
                    ],
                    "windows": [{"id": "main", "role": "primary_panel"}],
                    "regions": [
                        {
                            "id": "left_nav_rail",
                            "type": "window_chrome_ornament",
                            "implementation": "TabBar(placement = TabBarPlacement.Left)",
                        }
                    ],
                    "repeated_structures": [],
                    "states": [],
                }
            ),
            encoding="utf-8",
        )

    def write_visual_content_contract(self) -> None:
        (self.scratch_dir / "spatial_layout_contract.json").write_text(
            json.dumps(
                {
                    "container": "ON_PLAIN",
                    "window_model": "sidebar_content",
                    "visual_content_contract": {
                        "sidebar": {
                            "has_surface": True,
                            "preferred_component": "SideNavigation",
                            "chips": {
                                "active_preferred_component": "RemovableChip",
                                "recommendation_preferred_component": "ButtonChip",
                                "active_chips_have_close_icon": True,
                                "recommendation_chips_may_have_leading_icon": True,
                            },
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
                    },
                    "windows": [{"id": "main", "role": "primary_panel"}],
                    "regions": [],
                    "repeated_structures": ["result_image_card", "tab_label"],
                    "states": [],
                }
            ),
            encoding="utf-8",
        )

    def write_manifest(self) -> None:
        (self.target_dir / "src/main/AndroidManifest.xml").write_text(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
              <application>
                <activity android:name=".MainActivity">
                  <meta-data android:name="pico.spatial.windowcontainer.id" android:value="demo" />
                </activity>
              </application>
            </manifest>
            """,
            encoding="utf-8",
        )

    def test_rejects_window_chrome_declared_but_implemented_as_manual_overlay(self) -> None:
        scanner = load_scanner_module()
        self.write_contract()
        self.write_manifest()
        (self.src_dir / "Main.kt").write_text(
            """
            fun mainApp(scope: SpatialAppScope) = with(scope) {
                DefaultWindowContainer {
                    Box(Modifier.fillMaxSize()) {
                        MainPage()
                        Box(Modifier.align(Alignment.CenterStart)) {
                            Column { IconButton(onClick = {}) { } }
                        }
                    }
                }
            }
            """,
            encoding="utf-8",
        )

        summary = scanner.scan(self.target_dir)

        self.assertFalse(summary["passed"])
        self.assertIn("window_chrome_ornaments", "\n".join(summary["failures_or_explicit_none"]))

    def test_rejects_visual_content_that_contradicts_screenshot_semantics(self) -> None:
        scanner = load_scanner_module()
        self.write_visual_content_contract()
        self.write_manifest()
        (self.src_dir / "SearchResultsScreen.kt").write_text(
            """
            fun mainApp(scope: SpatialAppScope) = with(scope) {
                DefaultWindowContainer { SearchResultsScreen() }
            }

            @Composable
            private fun SearchResultsScreen() { }

            @Composable
            private fun TabsRow(tabs: List<Tab>) {
                Row { tabs.take(5).forEach { Text(it.label) } }
            }

            @Composable
            private fun SearchPill(text: String) {
                Row(Modifier.height(44.dp)) { Text(text) }
            }

            @Composable
            private fun ResultCardView(card: ResultCard) {
                Box {
                    Image(painterResource(card.artwork), null)
                    Box(Modifier.align(Alignment.BottomStart).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))) {
                        Text(card.title)
                    }
                }
            }
            """,
            encoding="utf-8",
        )

        summary = scanner.scan(self.target_dir)

        self.assertFalse(summary["passed"])
        failures = "\n".join(summary["failures_or_explicit_none"])
        self.assertIn("visual_content_contract", failures)
        self.assertIn("tabs.take(5)", failures)
        self.assertIn("image_only", failures)

    def test_rejects_static_search_pill_when_contract_requires_search_input(self) -> None:
        scanner = load_scanner_module()
        self.write_visual_content_contract()
        self.write_manifest()
        (self.src_dir / "SearchResultsScreen.kt").write_text(
            """
            fun mainApp(scope: SpatialAppScope) = with(scope) {
                DefaultWindowContainer { SearchResultsScreen() }
            }

            @Composable
            private fun SearchResultsScreen() { }

            @Composable
            private fun FilterSidebar() {
                Column(Modifier.background(Color.White)) { SearchPill("Search") }
            }

            @Composable
            private fun SearchPill(text: String) {
                Row(Modifier.fillMaxWidth().height(44.dp)) { Text(text) }
            }

            @Composable
            private fun TabsRow(tabs: List<Tab>) {
                Row(Modifier.background(Color.White)) { tabs.forEach { Text(it.label) } }
            }

            @Composable
            private fun ResultCardView(card: ResultCard) {
                Box { Image(painterResource(card.artwork), null) }
            }
            """,
            encoding="utf-8",
        )

        summary = scanner.scan(self.target_dir)

        self.assertFalse(summary["passed"])
        failures = "\n".join(summary["failures_or_explicit_none"])
        self.assertIn("SearchField", failures)

    def test_rejects_custom_sidebar_when_contract_prefers_side_navigation(self) -> None:
        scanner = load_scanner_module()
        self.write_visual_content_contract()
        self.write_manifest()
        (self.src_dir / "SearchResultsScreen.kt").write_text(
            """
            fun mainApp(scope: SpatialAppScope) = with(scope) {
                DefaultWindowContainer { SearchResultsScreen() }
            }

            @Composable
            private fun SearchResultsScreen() { }

            @Composable
            private fun FilterSidebar() {
                Column(Modifier.background(Color.White)) { SearchPill("Search", "", {}, {}) }
            }

            @Composable
            private fun SearchPill(
                text: String,
                query: String,
                onQueryChange: (String) -> Unit,
                onSearch: () -> Unit,
            ) {
                SearchField(
                    value = query,
                    onValueChange = onQueryChange,
                    onSearch = onSearch,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            @Composable
            private fun TabsRow(tabs: List<Tab>) {
                Row(Modifier.background(Color.White)) { tabs.forEach { Text(it.label) } }
            }

            @Composable
            private fun ResultCardView(card: ResultCard) {
                Box { Image(painterResource(card.artwork), contentDescription = card.title) }
            }
            """,
            encoding="utf-8",
        )

        summary = scanner.scan(self.target_dir)

        self.assertFalse(summary["passed"])
        failures = "\n".join(summary["failures_or_explicit_none"])
        self.assertIn("SideNavigation", failures)

    def test_rejects_custom_chips_when_contract_prefers_spatialui_chips(self) -> None:
        scanner = load_scanner_module()
        self.write_visual_content_contract()
        self.write_manifest()
        (self.src_dir / "SearchResultsScreen.kt").write_text(
            """
            fun mainApp(scope: SpatialAppScope) = with(scope) {
                DefaultWindowContainer { SearchResultsScreen() }
            }

            @Composable
            private fun SearchResultsScreen() { }

            @Composable
            private fun FilterSidebar() {
                Box(Modifier.background(Color.White)) {
                    SideNavigation(header = { SearchPill("Search", "", {}, {}) }) {
                        ChipWrap(listOf(FilterChip("a", "A")))
                    }
                }
            }

            @Composable
            private fun SearchPill(
                text: String,
                query: String,
                onQueryChange: (String) -> Unit,
                onSearch: () -> Unit,
            ) {
                SearchField(
                    value = query,
                    onValueChange = onQueryChange,
                    onSearch = onSearch,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            @Composable
            private fun ChipWrap(chips: List<FilterChip>) {
                Row { chips.forEach { Text(it.label) } }
            }

            @Composable
            private fun TabsRow(tabs: List<Tab>) {
                Row(Modifier.background(Color.White)) { tabs.forEach { Text(it.label) } }
            }

            @Composable
            private fun ResultCardView(card: ResultCard) {
                Box { Image(painterResource(card.artwork), contentDescription = card.title) }
            }
            """,
            encoding="utf-8",
        )

        summary = scanner.scan(self.target_dir)

        self.assertFalse(summary["passed"])
        failures = "\n".join(summary["failures_or_explicit_none"])
        self.assertIn("RemovableChip", failures)
        self.assertIn("ButtonChip", failures)

    def test_allows_image_only_card_with_accessibility_content_description(self) -> None:
        scanner = load_scanner_module()
        self.write_visual_content_contract()
        self.write_manifest()
        (self.src_dir / "SearchResultsScreen.kt").write_text(
            """
            fun mainApp(scope: SpatialAppScope) = with(scope) {
                DefaultWindowContainer { SearchResultsScreen() }
            }

            @Composable
            private fun SearchResultsScreen() { }

            @Composable
            private fun FilterSidebar() {
                Column(Modifier.background(Color.White)) { SearchPill("Search", "", {}, {}) }
            }

            @Composable
            private fun SearchPill(
                text: String,
                query: String,
                onQueryChange: (String) -> Unit,
                onSearch: () -> Unit,
            ) {
                SearchField(
                    value = query,
                    onValueChange = onQueryChange,
                    onSearch = onSearch,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            @Composable
            private fun TabsRow(tabs: List<Tab>) {
                Row(Modifier.background(Color.White)) { tabs.forEach { Text(it.label) } }
            }

            @Composable
            private fun ResultCardView(card: ResultCard) {
                Box { Image(painterResource(card.artwork), contentDescription = card.title) }
            }
            """,
            encoding="utf-8",
        )

        summary = scanner.scan(self.target_dir)

        failures = "\n".join(summary["failures_or_explicit_none"])
        self.assertNotIn("image_only/no text overlay", failures)


if __name__ == "__main__":
    unittest.main()
