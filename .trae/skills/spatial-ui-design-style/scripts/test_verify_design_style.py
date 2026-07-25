#!/usr/bin/env python3
import subprocess
import tempfile
import unittest
from pathlib import Path


SCRIPT_PATH = Path(__file__).resolve().parent / "verify-design-style.sh"


class VerifyDesignStyleTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.module_dir = Path(self.temp_dir.name) / "demo"
        self.src_dir = self.module_dir / "src/main/java/com/example"
        self.src_dir.mkdir(parents=True)

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def write_ui(self, body: str) -> None:
        (self.src_dir / "Demo.kt").write_text(body, encoding="utf-8")

    def run_verifier(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["bash", str(SCRIPT_PATH), str(self.module_dir)],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )

    def test_clickable_without_haptic_feedback_is_rejected(self) -> None:
        self.write_ui(
            """
            import androidx.compose.foundation.clickable
            import androidx.compose.foundation.LocalIndication
            import androidx.compose.foundation.interaction.MutableInteractionSource
            import androidx.compose.runtime.remember
            import androidx.compose.ui.Modifier
            import com.pico.spatial.ui.design.PicoTheme

            fun Demo() {
                PicoTheme {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                    ) { }
                }
            }
            """,
        )

        result = self.run_verifier()

        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("controllerHapticFeedback", result.stdout)

    def test_clickable_with_shared_haptic_feedback_is_accepted(self) -> None:
        self.write_ui(
            """
            import androidx.compose.foundation.clickable
            import androidx.compose.foundation.LocalIndication
            import androidx.compose.foundation.interaction.MutableInteractionSource
            import androidx.compose.runtime.remember
            import androidx.compose.ui.Modifier
            import com.pico.spatial.ui.design.PicoTheme
            import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback

            fun Demo() {
                PicoTheme {
                    val interactionSource = remember { MutableInteractionSource() }
                    Modifier
                        .clickable(
                            interactionSource = interactionSource,
                            indication = LocalIndication.current,
                        ) { }
                        .controllerHapticFeedback(interactionSource = interactionSource)
                }
            }
            """,
        )

        result = self.run_verifier()

        self.assertEqual(0, result.returncode, result.stdout)

    def test_commented_haptic_feedback_does_not_satisfy_clickable_requirement(self) -> None:
        self.write_ui(
            """
            import androidx.compose.foundation.clickable
            import androidx.compose.foundation.LocalIndication
            import androidx.compose.foundation.interaction.MutableInteractionSource
            import androidx.compose.runtime.remember
            import androidx.compose.ui.Modifier
            import com.pico.spatial.ui.design.PicoTheme
            // import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback

            fun Demo() {
                PicoTheme {
                    val interactionSource = remember { MutableInteractionSource() }
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                    ) { }
                    // .controllerHapticFeedback(interactionSource = interactionSource)
                }
            }
            """,
        )

        result = self.run_verifier()

        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("controllerHapticFeedback", result.stdout)


if __name__ == "__main__":
    unittest.main()
