#!/usr/bin/env python3
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


SOURCE_SCRIPT = Path(__file__).resolve().parent / "validate_workflow_and_build.sh"


class ValidateWorkflowAndBuildTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name) / "skills"
        self.script_dir = self.root / "anything-to-spatial-app/scripts"
        self.design_script_dir = self.root / "spatial-ui-design-style/scripts"
        self.script_dir.mkdir(parents=True)
        shutil.copy(SOURCE_SCRIPT, self.script_dir / "validate_workflow_and_build.sh")
        for name in (
            "check_adapter_contract.py",
            "check_workflow_artifacts.py",
            "check_layout_structure.py",
            "scan_implementation.py",
            "gradle_sync_check.sh",
            "smoke_build.sh",
            "runtime_launch_check.sh",
            "check_architecture.py",
            "run_unit_tests.sh",
        ):
            path = self.script_dir / name
            if name.endswith(".py"):
                path.write_text("#!/usr/bin/env python3\n", encoding="utf-8")
            else:
                path.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
            path.chmod(0o755)
        self.target = Path(self.temp_dir.name) / "target"
        (self.target / "src/main/java/com/example").mkdir(parents=True)
        (self.target / "src/main/java/com/example/Demo.kt").write_text("fun Demo() {}\n", encoding="utf-8")

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def run_validate(self, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["bash", str(self.script_dir / "validate_workflow_and_build.sh"), str(self.target), *args],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )

    def install_design_verifier(self, body: str = "#!/usr/bin/env bash\nexit 0\n") -> None:
        self.design_script_dir.mkdir(parents=True)
        path = self.design_script_dir / "verify-design-style.sh"
        path.write_text(body, encoding="utf-8")
        path.chmod(0o755)

    def test_skip_design_style_cannot_be_bypassed_by_allow_degraded(self) -> None:
        self.install_design_verifier()

        result = self.run_validate("--skip-design-style", "--allow-degraded")

        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("design-style admission is mandatory", result.stdout)

    def test_missing_design_style_verifier_is_hard_failure(self) -> None:
        result = self.run_validate()

        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("spatial-ui-design-style verifier not found", result.stdout)

    def test_design_style_result_json_is_written_on_success(self) -> None:
        self.install_design_verifier("#!/usr/bin/env bash\necho design ok\nexit 0\n")

        result = self.run_validate()

        self.assertEqual(0, result.returncode, result.stdout)
        design_result = self.target / ".scratch/design_style_result.json"
        self.assertTrue(design_result.exists())
        self.assertIn('"passed": true', design_result.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
