#!/usr/bin/env bash
# Validate workflow artifacts, run smoke build, enforce architecture
# conventions, unit tests, and design-style admission, then print the adapter
# verification / cleanup hook handoff for generated projects.
#
# Usage:
#   bash scripts/validate_workflow_and_build.sh <target>
#   bash scripts/validate_workflow_and_build.sh <target> --require-assumptions
#   bash scripts/validate_workflow_and_build.sh <target> --skip-design-style   # forbidden: exits non-zero
#   bash scripts/validate_workflow_and_build.sh <target> --skip-architecture
#   bash scripts/validate_workflow_and_build.sh <target> --skip-unit-tests
#   bash scripts/validate_workflow_and_build.sh <target> --skip-gradle-sync
#   bash scripts/validate_workflow_and_build.sh <target> --skip-runtime-launch
#   bash scripts/validate_workflow_and_build.sh <target> --skip-runtime-launch --allow-degraded
#
# Phase-7 step order:
#   1. adapter contract checker
#   2. workflow artifact checker
#   3. layout/structure checker
#   4. implementation scanner
#   5. Gradle sync/project discovery check (CLI proxy for Android Studio sync)
#   6. smoke build (assembleDebug)
#   7. runtime launch check (installDebug + launch Activity + crash scan)
#   8. architecture conventions checker (check_architecture.py)
#   9. JVM unit tests (testDebugUnitTest, failures=0 errors=0 tests>0)
#   10. spatial-ui-design-style verifier
#   11. adapter hook handoff (agent-owned MCP verify / cleanup when selected)
#
# The --skip-* flags are emergency hatches, except --skip-design-style which is
# intentionally a hard failure for generated Compose UI. The SKILL "Self-eval &
# exit criteria" still requires a clean run before any sign-off.

set -euo pipefail

if [ $# -lt 1 ]; then
    echo "Usage: $0 <target> [--require-assumptions] [--skip-design-style] [--skip-architecture] [--skip-unit-tests] [--skip-gradle-sync] [--skip-runtime-launch] [--allow-degraded]" >&2
    exit 2
fi

TARGET="$1"
shift || true

REQUIRE_ASSUMPTIONS="false"
SKIP_DESIGN_STYLE="false"
SKIP_ARCHITECTURE="false"
SKIP_UNIT_TESTS="false"
SKIP_GRADLE_SYNC="false"
SKIP_RUNTIME_LAUNCH="false"
ALLOW_DEGRADED="false"
while [ $# -gt 0 ]; do
    case "$1" in
        --require-assumptions)
            REQUIRE_ASSUMPTIONS="true"
            ;;
        --skip-design-style)
            SKIP_DESIGN_STYLE="true"
            ;;
        --skip-architecture)
            SKIP_ARCHITECTURE="true"
            ;;
        --skip-unit-tests)
            SKIP_UNIT_TESTS="true"
            ;;
        --skip-gradle-sync)
            SKIP_GRADLE_SYNC="true"
            ;;
        --skip-runtime-launch)
            SKIP_RUNTIME_LAUNCH="true"
            ;;
        --allow-degraded)
            ALLOW_DEGRADED="true"
            ;;
        *)
            echo "[validate] Unknown option: $1" >&2
            echo "Usage: $0 <target> [--require-assumptions] [--skip-design-style] [--skip-architecture] [--skip-unit-tests] [--skip-gradle-sync] [--skip-runtime-launch] [--allow-degraded]" >&2
            exit 2
            ;;
    esac
    shift
done

if [ ! -d "$TARGET" ]; then
    echo "[validate] Target directory not found: $TARGET" >&2
    exit 2
fi

SCRATCH_DIR="$TARGET/.scratch"
mkdir -p "$SCRATCH_DIR"
VERIFICATION_SUMMARY="$SCRATCH_DIR/verification_summary.json"
DESIGN_STYLE_RESULT="$SCRATCH_DIR/design_style_result.json"
WARNINGS=()
SKIPS=()

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CHECKER="$SCRIPT_DIR/check_workflow_artifacts.py"
ADAPTER_CHECKER="$SCRIPT_DIR/check_adapter_contract.py"
STRUCTURE_CHECKER="$SCRIPT_DIR/check_layout_structure.py"
IMPL_SCANNER="$SCRIPT_DIR/scan_implementation.py"
SMOKE_BUILD="$SCRIPT_DIR/smoke_build.sh"
GRADLE_SYNC="$SCRIPT_DIR/gradle_sync_check.sh"
ARCH_CHECKER="$SCRIPT_DIR/check_architecture.py"
UNIT_TESTS="$SCRIPT_DIR/run_unit_tests.sh"
RUNTIME_LAUNCH="$SCRIPT_DIR/runtime_launch_check.sh"

# Resolve sibling spatial-ui-design-style verifier.
SKILLS_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DESIGN_STYLE_VERIFIER="$SKILLS_ROOT/spatial-ui-design-style/scripts/verify-design-style.sh"

for required in "$ADAPTER_CHECKER" "$CHECKER" "$STRUCTURE_CHECKER" "$IMPL_SCANNER" "$GRADLE_SYNC" "$SMOKE_BUILD" "$RUNTIME_LAUNCH" "$ARCH_CHECKER" "$UNIT_TESTS"; do
    if [ ! -f "$required" ]; then
        echo "[validate] required script not found: $required" >&2
        exit 2
    fi
done

CHECK_CMD=(python3 "$CHECKER" --target "$TARGET")
if [ "$REQUIRE_ASSUMPTIONS" = "true" ]; then
    CHECK_CMD+=(--require-assumptions)
fi

echo "[validate] [1/11] adapter contract checker"
python3 "$ADAPTER_CHECKER"

echo "[validate] [2/11] workflow artifact checker"
"${CHECK_CMD[@]}"

echo "[validate] [3/11] layout/structure checker"
python3 "$STRUCTURE_CHECKER" --target "$TARGET"

echo "[validate] [4/11] implementation scanner"
python3 "$IMPL_SCANNER" --target "$TARGET"

# ---------- Gradle sync / project discovery gate ----------
echo "[validate] [5/11] Gradle sync/project discovery"
if [ "$SKIP_GRADLE_SYNC" = "true" ]; then
    SKIPS+=("gradle_sync")
    WARNINGS+=("--skip-gradle-sync supplied; Gradle project discovery / IDE sync proxy not enforced")
    echo "[validate] WARN --skip-gradle-sync supplied; Gradle project discovery / IDE sync proxy NOT enforced. The skill still requires Android Studio Sync Project with Gradle Files before first IDE run."
else
    bash "$GRADLE_SYNC" "$TARGET"
fi

echo "[validate] [6/11] smoke build"
bash "$SMOKE_BUILD" "$TARGET"

# ---------- runtime install + launch gate ----------
echo "[validate] [7/11] runtime install/launch"
if [ "$SKIP_RUNTIME_LAUNCH" = "true" ]; then
    SKIPS+=("runtime_launch")
    WARNINGS+=("--skip-runtime-launch supplied; install/launch not enforced")
    echo "[validate] WARN --skip-runtime-launch supplied; install/launch NOT enforced. The skill still requires a clean run for sign-off."
else
    bash "$RUNTIME_LAUNCH" "$TARGET"
fi

# ---------- architecture conventions gate ----------
echo "[validate] [8/11] architecture conventions"
if [ "$SKIP_ARCHITECTURE" = "true" ]; then
    SKIPS+=("architecture")
    WARNINGS+=("--skip-architecture supplied; architecture conventions not enforced")
    echo "[validate] WARN --skip-architecture supplied; architecture conventions NOT enforced. The skill still requires a clean run for sign-off."
else
    python3 "$ARCH_CHECKER" --target "$TARGET"
fi

# ---------- unit-test gate ----------
echo "[validate] [9/11] unit tests"
if [ "$SKIP_UNIT_TESTS" = "true" ]; then
    SKIPS+=("unit_tests")
    WARNINGS+=("--skip-unit-tests supplied; ViewModel/UseCase tests not enforced")
    echo "[validate] WARN --skip-unit-tests supplied; ViewModel/UseCase tests NOT enforced. The skill still requires a clean run for sign-off."
else
    bash "$UNIT_TESTS" "$TARGET"
fi

# ---------- design-style admission gate ----------
echo "[validate] [10/11] design-style admission"
if [ "$SKIP_DESIGN_STYLE" = "true" ]; then
    echo "[validate] FAIL design-style admission is mandatory; --skip-design-style is not allowed for screenshot/generated Compose code" >&2
    python3 - "$DESIGN_STYLE_RESULT" <<'PY'
import json
import sys
from pathlib import Path
Path(sys.argv[1]).write_text(json.dumps({"passed": False, "skipped": True, "summary": {"errors": 1, "warnings": 0}, "failures": ["--skip-design-style is forbidden"]}, indent=2), encoding="utf-8")
PY
    exit 1
elif [ ! -f "$DESIGN_STYLE_VERIFIER" ]; then
    echo "[validate] FAIL spatial-ui-design-style verifier not found at: $DESIGN_STYLE_VERIFIER" >&2
    python3 - "$DESIGN_STYLE_RESULT" "$DESIGN_STYLE_VERIFIER" <<'PY'
import json
import sys
from pathlib import Path
Path(sys.argv[1]).write_text(json.dumps({"passed": False, "skipped": False, "summary": {"errors": 1, "warnings": 0}, "failures": [f"spatial-ui-design-style verifier not found: {sys.argv[2]}"]}, indent=2), encoding="utf-8")
PY
    exit 1
else
    DESIGN_STYLE_PATH=""
    for candidate in \
        "$TARGET/src/main/java" \
        "$TARGET/src/main/kotlin" \
        "$TARGET"; do
        if [ -d "$candidate" ]; then
            DESIGN_STYLE_PATH="$candidate"
            break
        fi
    done

    if [ -z "$DESIGN_STYLE_PATH" ]; then
        echo "[validate] FAIL no Compose source root found under $TARGET; design-style admission cannot run" >&2
        python3 - "$DESIGN_STYLE_RESULT" "$TARGET" <<'PY'
import json
import sys
from pathlib import Path
Path(sys.argv[1]).write_text(json.dumps({"passed": False, "skipped": False, "summary": {"errors": 1, "warnings": 0}, "failures": [f"no Compose source root found under {sys.argv[2]}"]}, indent=2), encoding="utf-8")
PY
        exit 1
    else
        echo "[validate] running spatial-ui-design-style verifier on: $DESIGN_STYLE_PATH"
        DESIGN_STYLE_LOG="$SCRATCH_DIR/design_style.log"
        if bash "$DESIGN_STYLE_VERIFIER" "$DESIGN_STYLE_PATH" >"$DESIGN_STYLE_LOG" 2>&1; then
            cat "$DESIGN_STYLE_LOG"
            python3 - "$DESIGN_STYLE_RESULT" "$DESIGN_STYLE_LOG" "$DESIGN_STYLE_PATH" <<'PY'
import json
import re
import sys
from pathlib import Path
result_path = Path(sys.argv[1])
log_path = Path(sys.argv[2])
source_path = sys.argv[3]
log = log_path.read_text(encoding="utf-8", errors="replace")
errors = int(re.search(r"errors:\s+(\d+)", log).group(1)) if re.search(r"errors:\s+(\d+)", log) else 0
warnings = int(re.search(r"warnings:\s+(\d+)", log).group(1)) if re.search(r"warnings:\s+(\d+)", log) else 0
result_path.write_text(json.dumps({"passed": True, "skipped": False, "source_path": source_path, "log": str(log_path), "summary": {"errors": errors, "warnings": warnings}, "failures": []}, indent=2, ensure_ascii=False), encoding="utf-8")
PY
        else
            status=$?
            cat "$DESIGN_STYLE_LOG"
            python3 - "$DESIGN_STYLE_RESULT" "$DESIGN_STYLE_LOG" "$DESIGN_STYLE_PATH" "$status" <<'PY'
import json
import re
import sys
from pathlib import Path
result_path = Path(sys.argv[1])
log_path = Path(sys.argv[2])
source_path = sys.argv[3]
status = int(sys.argv[4])
log = log_path.read_text(encoding="utf-8", errors="replace")
errors = int(re.search(r"errors:\s+(\d+)", log).group(1)) if re.search(r"errors:\s+(\d+)", log) else 1
warnings = int(re.search(r"warnings:\s+(\d+)", log).group(1)) if re.search(r"warnings:\s+(\d+)", log) else 0
failures = [line for line in log.splitlines() if line.startswith("[error]")]
result_path.write_text(json.dumps({"passed": False, "skipped": False, "source_path": source_path, "log": str(log_path), "exit_code": status, "summary": {"errors": errors, "warnings": warnings}, "failures": failures}, indent=2, ensure_ascii=False), encoding="utf-8")
PY
            exit "$status"
        fi
    fi
fi

echo "[validate] [11/11] adapter hook handoff"
echo "[validate] machine wrapper cannot execute MCP adapter hooks. If the selected adapter declares hooks.verify / hooks.cleanup (for example figma-adapter), run d2c_verify_code exactly once, apply targeted Critical/Moderate fixes, then run d2c_cleanup_temp exactly once and write .scratch/adapter_hooks_result.json."

CLEAN="true"
if [ "${#WARNINGS[@]}" -gt 0 ] || [ "${#SKIPS[@]}" -gt 0 ]; then
    CLEAN="false"
fi

python3 - "$VERIFICATION_SUMMARY" "$CLEAN" "$ALLOW_DEGRADED" "${WARNINGS[@]+"${WARNINGS[@]}"}" -- "${SKIPS[@]+"${SKIPS[@]}"}" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
clean = sys.argv[2] == "true"
allow_degraded = sys.argv[3] == "true"
rest = sys.argv[4:]
split = rest.index("--") if "--" in rest else len(rest)
warnings = rest[:split]
skips = rest[split + 1:] if split < len(rest) else []
data = {
    "passed": clean,
    "operational": True,
    "clean": clean,
    "allow_degraded": allow_degraded,
    "warnings": warnings,
    "skips": skips,
    "adapter_hooks_agent_owned": True,
    "note": "clean=false means a gate was skipped or degraded; --allow-degraded is operational-only, not skill-complete. Adapter hooks, when declared by the selected adapter, must be completed by the agent and recorded in adapter_hooks_result.json before final handoff.",
}
path.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")
PY

echo "[validate] WROTE $VERIFICATION_SUMMARY"
if [ "$CLEAN" = "true" ]; then
    echo "[validate] MACHINE GATES PASSED clean: adapter contract + artifacts + structure + implementation + Gradle sync/project discovery + smoke build + runtime launch + architecture + unit tests + design-style passed. If adapter hooks are declared, the run is still pending until agent-owned hooks write adapter_hooks_result.json"
else
    echo "[validate] DEGRADED: machine gates completed with warnings/skips; exit 0 with --allow-degraded is operational-only, not skill-complete. See $VERIFICATION_SUMMARY and disclose warnings/skips in handoff"
    if [ "$ALLOW_DEGRADED" != "true" ]; then
        echo "[validate] FAIL degraded run requires explicit --allow-degraded to exit 0" >&2
        exit 1
    fi
fi
