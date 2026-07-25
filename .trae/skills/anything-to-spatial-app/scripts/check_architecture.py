#!/usr/bin/env python3
"""Validate Android architecture conventions for an anything-to-spatial-app target.

Usage:
    python3 -m scripts.check_architecture --target ./generated-spatial-app
    python3 /abs/path/to/check_architecture.py --target ./myapp

Rules enforced (see references/architecture-conventions.md):

A1. `Main.kt` ≤ 50 non-blank, non-comment lines (only entry wiring).
A2. Required packages exist when the module has any UI:
    - data/repository (if `data/` referenced anywhere)
    - domain/model
    - ui/<feature>
A3. Each ViewModel has matching UiState + Screen + sealed Event.
A4. Each Composable Screen uses `viewModel(factory = …)` and
    `collectAsStateWithLifecycle()` (no top-level `remember { mutableStateOf }`
    holding business state).
A5. No `Repository` interface is referenced directly from a Composable.
A6. Mock data: top-level `private val *_MOCK*` / `private val MOCK_*` are
    forbidden in `ui/` files.
A7. Single fat-file modules require `<target>/.scratch/architecture_waiver.json`
    with a non-empty `reason`.
A8. Non-trivial Screen files must extract reusable / region-level Composables
    into `ui/<feature>/components/`; an empty components directory is an error.

Output: `<target>/.scratch/architecture_check_result.json`.
Exit 0 only when no `error`-level findings.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


@dataclass
class Finding:
    severity: str  # "error" | "warning" | "info"
    rule: str
    message: str
    file: str | None = None


@dataclass
class Report:
    findings: list[Finding] = field(default_factory=list)

    def add(self, severity: str, rule: str, message: str, file: str | None = None) -> None:
        self.findings.append(Finding(severity=severity, rule=rule, message=message, file=file))

    @property
    def errors(self) -> list[Finding]:
        return [f for f in self.findings if f.severity == "error"]

    @property
    def warnings(self) -> list[Finding]:
        return [f for f in self.findings if f.severity == "warning"]

    def to_dict(self) -> dict[str, Any]:
        return {
            "passed": not self.errors,
            "summary": {
                "errors": len(self.errors),
                "warnings": len(self.warnings),
                "info": sum(1 for f in self.findings if f.severity == "info"),
            },
            "findings": [
                {"severity": f.severity, "rule": f.rule, "message": f.message, "file": f.file}
                for f in self.findings
            ],
        }


def _kt_files(root: Path) -> list[Path]:
    if not root.is_dir():
        return []
    return sorted(p for p in root.rglob("*.kt") if "/build/" not in str(p))


def _read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        return ""


def _strip_comments(src: str) -> str:
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.DOTALL)
    src = re.sub(r"^\s*//.*$", "", src, flags=re.MULTILINE)
    return src


def _significant_line_count(src: str) -> int:
    stripped = _strip_comments(src)
    return sum(1 for line in stripped.splitlines() if line.strip())


def _has_waiver(target: Path) -> tuple[bool, str | None]:
    waiver = target / ".scratch" / "architecture_waiver.json"
    if not waiver.exists():
        return False, None
    try:
        data = json.loads(waiver.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return False, None
    reason = (data or {}).get("reason")
    if isinstance(reason, str) and reason.strip():
        return True, reason.strip()
    return False, None


# ---------------- rule checks ----------------

def check_main_kt(target: Path, report: Report) -> None:
    candidates = (
        sorted(target.glob("src/main/java/Main.kt"))
        + sorted(target.glob("src/main/kotlin/Main.kt"))
        + sorted(target.glob("app/src/main/java/**/Main.kt"))
        + sorted(target.glob("app/src/main/kotlin/**/Main.kt"))
        + sorted(target.glob("src/main/java/**/App.kt"))
        + sorted(target.glob("src/main/kotlin/**/App.kt"))
        + sorted(target.glob("app/src/main/java/**/App.kt"))
        + sorted(target.glob("app/src/main/kotlin/**/App.kt"))
    )
    if not candidates:
        report.add(
            "warning",
            "A1",
            "Main.kt/App.kt not found under src/main or app/src/main — skipping size check.",
        )
        return
    main = candidates[0]
    src = _read(main)
    line_count = _significant_line_count(src)
    if line_count > 50:
        report.add(
            "error",
            "A1",
            f"Main.kt has {line_count} significant lines (>50). It must only contain mainApp(scope) wiring; "
            "move UI / data / state into proper layers.",
            file=str(main),
        )
    if "mainApp" not in src:
        report.add(
            "error",
            "A1",
            f"{main.name} missing `fun mainApp(scope: SpatialAppScope)` entry.",
            file=str(main),
        )


def _namespace_from_gradle(target: Path) -> str | None:
    for candidate in (target / "build.gradle.kts", target / "app" / "build.gradle.kts"):
        if not candidate.exists():
            continue
        text = _read(candidate)
        match = re.search(r'namespace\s*=\s*"([^"]+)"', text)
        if match:
            return match.group(1)
    return None


def discover_module_root(target: Path) -> Path | None:
    """Return the source package root using Gradle namespace when available."""
    namespace = _namespace_from_gradle(target)
    if namespace:
        package_path = Path(*namespace.split("."))
        for source_root in (
            target / "src" / "main" / "java",
            target / "src" / "main" / "kotlin",
            target / "app" / "src" / "main" / "java",
            target / "app" / "src" / "main" / "kotlin",
        ):
            candidate = source_root / package_path
            if candidate.is_dir():
                return candidate

    # Backward-compatible monorepo default.
    for base in (
        target / "src" / "main" / "java" / "com" / "picoxr",
        target / "app" / "src" / "main" / "java" / "com" / "picoxr",
    ):
        if not base.is_dir():
            continue
        children = [p for p in base.iterdir() if p.is_dir()]
        if len(children) == 1:
            return children[0]
        match = base / target.name
        if match.is_dir():
            return match
    return None


def check_package_layout(module_root: Path, report: Report, has_waiver: bool) -> None:
    required_dirs = [
        ("platform", "Application + LaunchActivity entry"),
        ("ui", "Compose Screens + ViewModels"),
        ("domain/model", "Domain models"),
    ]
    missing: list[tuple[str, str]] = []
    for rel, purpose in required_dirs:
        if not (module_root / rel).is_dir():
            missing.append((rel, purpose))

    if missing:
        severity = "warning" if has_waiver else "error"
        for rel, purpose in missing:
            report.add(
                severity,
                "A2",
                f"Missing required package `{rel}/` ({purpose}) under {module_root}.",
                file=str(module_root),
            )


def find_files_in(module_root: Path, sub: str) -> list[Path]:
    base = module_root / sub
    if not base.is_dir():
        return []
    return sorted(base.rglob("*.kt"))


def collect_pairs(module_root: Path) -> dict[str, list[Path]]:
    # Only treat top-level `ui/<feature>/*Screen.kt` as actual stateful Screens.
    # Sub-components (e.g. `ui/<feature>/components/PlaceholderScreen.kt`) are
    # exempt from the ViewModel-injection rule.
    feature_screens: list[Path] = []
    ui_dir = module_root / "ui"
    if ui_dir.is_dir():
        for p in ui_dir.rglob("*.kt"):
            if not p.name.endswith("Screen.kt"):
                continue
            # depth: ui/<feature>/<file> → relative parts has 2 dirs before file
            rel = p.relative_to(ui_dir)
            # Accept iff the file sits directly under a feature folder
            # (one path segment between ui/ and the file).
            if len(rel.parts) == 2:
                feature_screens.append(p)
    return {
        "viewmodels": [p for p in find_files_in(module_root, "ui") if p.name.endswith("ViewModel.kt")],
        "uistates": [p for p in find_files_in(module_root, "ui") if p.name.endswith("UiState.kt")],
        "screens": feature_screens,
        "repositories_iface": [
            p
            for p in find_files_in(module_root, "data")
            if p.name.endswith("Repository.kt") and not p.name.startswith("Fake")
        ],
        "repositories_fakes": [
            p for p in find_files_in(module_root, "data") if p.name.startswith("Fake")
        ],
        "usecases": [p for p in find_files_in(module_root, "domain/usecase") if p.name.endswith("UseCase.kt")],
    }


def check_viewmodel_pairs(pairs: dict[str, list[Path]], report: Report) -> None:
    if not pairs["viewmodels"]:
        report.add(
            "error",
            "A3",
            "No `*ViewModel.kt` found under ui/. Every Screen must be backed by a ViewModel.",
        )
        return
    for vm in pairs["viewmodels"]:
        feature = vm.name.removesuffix("ViewModel.kt")
        sibling = vm.parent
        ui_state = sibling / f"{feature}UiState.kt"
        screen = sibling / f"{feature}Screen.kt"
        if not ui_state.exists():
            report.add(
                "error",
                "A3",
                f"{vm.name} is missing matching {ui_state.name} in {sibling}.",
                file=str(vm),
            )
        else:
            src = _read(ui_state)
            if "data class" not in src or f"{feature}UiState" not in src:
                report.add(
                    "error",
                    "A3",
                    f"{ui_state.name} must declare `data class {feature}UiState(...)`.",
                    file=str(ui_state),
                )
            if "sealed interface" not in src and "sealed class" not in src:
                report.add(
                    "error",
                    "A3",
                    f"{ui_state.name} must declare a sealed `*Event` hierarchy for unidirectional events.",
                    file=str(ui_state),
                )
        if not screen.exists():
            report.add(
                "error",
                "A3",
                f"{vm.name} is missing matching {screen.name} in {sibling}.",
                file=str(vm),
            )


def check_screen_state_wiring(pairs: dict[str, list[Path]], report: Report) -> None:
    for screen in pairs["screens"]:
        src = _read(screen)
        if "viewModel(" not in src and "viewModel<" not in src:
            report.add(
                "error",
                "A4",
                f"{screen.name} must obtain its ViewModel via `viewModel(factory = …)`.",
                file=str(screen),
            )
        if "collectAsStateWithLifecycle" not in src and "collectAsState" not in src:
            report.add(
                "warning",
                "A4",
                f"{screen.name} should observe state via `collectAsStateWithLifecycle()`.",
                file=str(screen),
            )


def check_no_repository_in_composables(module_root: Path, report: Report) -> None:
    ui_dir = module_root / "ui"
    if not ui_dir.is_dir():
        return
    pattern = re.compile(r"\b(\w*Repository)\b")
    for path in ui_dir.rglob("*.kt"):
        if path.name.endswith("Screen.kt"):
            continue  # Screen may inject Repository as default param
        if path.name.endswith("ViewModel.kt") or path.name.endswith("UiState.kt"):
            continue
        src = _read(path)
        if "@Composable" not in src:
            continue
        for match in pattern.finditer(src):
            symbol = match.group(1)
            if symbol == "Repository":
                continue
            report.add(
                "error",
                "A5",
                f"{path.name} (Composable file) references `{symbol}` directly. "
                "Composables must not depend on Repository — go through ViewModel / UseCase.",
                file=str(path),
            )
            break


def check_no_top_level_mock_in_ui(module_root: Path, report: Report) -> None:
    ui_dir = module_root / "ui"
    if not ui_dir.is_dir():
        return
    bad = re.compile(r"^\s*(?:private\s+|internal\s+)?val\s+(MOCK_\w+|\w+_MOCK\w*)\s*=", re.MULTILINE)
    for path in ui_dir.rglob("*.kt"):
        src = _read(path)
        for match in bad.finditer(src):
            symbol = match.group(1)
            report.add(
                "error",
                "A6",
                f"{path.name} declares top-level mock `{symbol}`. "
                "Move mock data into data/repository/Fake*.kt.",
                file=str(path),
            )


def check_use_cases_or_repository_minimum(module_root: Path, pairs: dict[str, list[Path]], report: Report) -> None:
    has_data = (module_root / "data").is_dir()
    has_use_case = bool(pairs["usecases"])
    has_repo_iface = bool(pairs["repositories_iface"])
    if not has_data:
        report.add(
            "warning",
            "A2",
            "Module has no `data/` package. If the Screen reads any list, "
            "introduce a Repository instead of inlining mock data.",
        )
    elif not has_repo_iface:
        report.add(
            "error",
            "A2",
            "data/ exists but no `*Repository.kt` interface found. Define an interface "
            "and at least one (Fake/Remote) implementation.",
        )
    if has_data and not has_use_case:
        report.add(
            "warning",
            "A2",
            "domain/usecase/ is missing. Consider adding UseCases when ViewModel coordinates more than 1 data call.",
        )


def check_component_file_isolation(module_root: Path, pairs: dict[str, list[Path]], report: Report) -> None:
    for screen in pairs["screens"]:
        feature_dir = screen.parent
        components_dir = feature_dir / "components"
        component_files = sorted(components_dir.glob("*.kt")) if components_dir.is_dir() else []
        screen_src = _read(screen)
        composable_count = len(re.findall(r"@Composable\s+(?:internal\s+|private\s+|public\s+)?fun\s+", screen_src))
        if components_dir.is_dir() and not component_files:
            report.add(
                "error",
                "A8",
                f"{components_dir} exists but is empty. Move region-level Compose building blocks into components/ or remove the directory.",
                file=str(components_dir),
            )
            continue
        if composable_count >= 3 and not component_files:
            report.add(
                "error",
                "A8",
                f"{screen.name} declares {composable_count} Composable functions but has no component files. "
                "Keep the Screen file for state wiring and place region-level UI in ui/<feature>/components/.",
                file=str(screen),
            )
# ---------------- main ----------------

def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Architecture conventions checker for anything-to-spatial-app.")
    parser.add_argument("--target", required=True, help="Path to generated module")
    args = parser.parse_args(argv)

    target = Path(args.target).resolve()
    if not target.is_dir():
        print(f"[arch-check] target not found: {target}", file=sys.stderr)
        return 2

    report = Report()
    has_waiver, reason = _has_waiver(target)
    if has_waiver:
        report.add(
            "info",
            "A7",
            f"architecture_waiver.json present (reason: {reason}); package-layout violations are downgraded to warnings.",
        )

    check_main_kt(target, report)
    module_root = discover_module_root(target)
    if module_root is None:
        report.add(
            "error",
            "A2",
            "Cannot resolve module package root from Gradle namespace or src/main/java/com/picoxr/. ",
        )
    else:
        check_package_layout(module_root, report, has_waiver)
        pairs = collect_pairs(module_root)
        check_viewmodel_pairs(pairs, report)
        check_screen_state_wiring(pairs, report)
        check_no_repository_in_composables(module_root, report)
        check_no_top_level_mock_in_ui(module_root, report)
        check_use_cases_or_repository_minimum(module_root, pairs, report)
        check_component_file_isolation(module_root, pairs, report)

    out_dir = target / ".scratch"
    out_dir.mkdir(parents=True, exist_ok=True)
    result = out_dir / "architecture_check_result.json"
    result.write_text(json.dumps(report.to_dict(), ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"[arch-check] WROTE {result}")
    for f in report.findings:
        location = f" :: {f.file}" if f.file else ""
        print(f"[arch-check] {f.severity.upper()} [{f.rule}] {f.message}{location}")

    if report.errors:
        print(f"[arch-check] FAIL ({len(report.errors)} errors, {len(report.warnings)} warnings)")
        return 1
    print(f"[arch-check] PASS ({len(report.warnings)} warnings)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
