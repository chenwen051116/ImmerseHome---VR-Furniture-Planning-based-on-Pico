#!/usr/bin/env bash
# Run JVM unit tests for the generated module and gate on test results.
#
# Two layouts supported (mirrors smoke_build.sh):
#   (A) Standalone scaffold — <target>/gradlew exists.
#       Runs `./gradlew :app:testDebugUnitTest`.
#   (B) Monorepo submodule — gradlew lives in an ancestor.
#       Runs `./gradlew :<basename(target)>:testDebugUnitTest`.
#
# After Gradle returns 0, the script also parses the JUnit XML reports and
# fails when any `<testsuite ... failures="N">` or `errors="N"` has N>0,
# OR when total tests = 0 (no coverage).
#
# Usage: bash scripts/run_unit_tests.sh <target>

set -uo pipefail

if [ $# -lt 1 ]; then
    echo "Usage: $0 <target>" >&2
    exit 2
fi

DIR="$(cd "$1" && pwd)"
if [ ! -d "$DIR" ]; then
    echo "[unit-test] Directory not found: $DIR" >&2
    exit 2
fi

GRADLE_ROOT=""
GRADLE_TASK=""
TEST_RESULTS_DIR=""
if [ -f "$DIR/gradlew" ]; then
    GRADLE_ROOT="$DIR"
    GRADLE_TASK=":app:testDebugUnitTest"
    TEST_RESULTS_DIR="$DIR/app/build/test-results/testDebugUnitTest"
else
    MODULE_NAME="$(basename "$DIR")"
    SEARCH="$DIR"
    while [ "$SEARCH" != "/" ] && [ -n "$SEARCH" ]; do
        SEARCH="$(dirname "$SEARCH")"
        if [ -f "$SEARCH/gradlew" ]; then
            GRADLE_ROOT="$SEARCH"
            GRADLE_TASK=":${MODULE_NAME}:testDebugUnitTest"
            TEST_RESULTS_DIR="$DIR/build/test-results/testDebugUnitTest"
            break
        fi
    done
fi

if [ -z "$GRADLE_ROOT" ]; then
    echo "[unit-test] gradlew not found in $DIR or any ancestor; skipping unit tests" >&2
    exit 2
fi

cd "$GRADLE_ROOT"
chmod +x gradlew 2>/dev/null || true

LOG="$DIR/.scratch/unit_tests.log"
mkdir -p "$(dirname "$LOG")"

echo "[unit-test] running ./gradlew $GRADLE_TASK (cwd: $GRADLE_ROOT, log: $LOG)"
./gradlew "$GRADLE_TASK" --no-daemon --console=plain > "$LOG" 2>&1
STATUS=$?

if [ "$STATUS" -ne 0 ]; then
    echo "[unit-test] BUILD FAILED (exit=$STATUS). Last 60 lines:" >&2
    tail -n 60 "$LOG" >&2
    exit "$STATUS"
fi

if [ ! -d "$TEST_RESULTS_DIR" ]; then
    echo "[unit-test] FAIL no test reports under $TEST_RESULTS_DIR." >&2
    echo "[unit-test] FAIL the module declares no JVM unit tests; add at least one *ViewModelTest / *UseCaseTest." >&2
    exit 3
fi

TOTAL_TESTS=0
TOTAL_FAILURES=0
TOTAL_ERRORS=0
SHOPT_RESET="$(shopt -p nullglob)"
shopt -s nullglob
for xml in "$TEST_RESULTS_DIR"/TEST-*.xml; do
    while IFS= read -r line; do
        case "$line" in
            *tests=\"*)
                tests_part="${line#*tests=\"}"
                tests_part="${tests_part%%\"*}"
                TOTAL_TESTS=$((TOTAL_TESTS + tests_part))
                ;;
        esac
        case "$line" in
            *failures=\"*)
                fail_part="${line#*failures=\"}"
                fail_part="${fail_part%%\"*}"
                TOTAL_FAILURES=$((TOTAL_FAILURES + fail_part))
                ;;
        esac
        case "$line" in
            *errors=\"*)
                err_part="${line#*errors=\"}"
                err_part="${err_part%%\"*}"
                TOTAL_ERRORS=$((TOTAL_ERRORS + err_part))
                ;;
        esac
    done < <(grep -E "^<testsuite " "$xml" || true)
done
eval "$SHOPT_RESET"

echo "[unit-test] tests=$TOTAL_TESTS failures=$TOTAL_FAILURES errors=$TOTAL_ERRORS"

if [ "$TOTAL_TESTS" -eq 0 ]; then
    echo "[unit-test] FAIL no JVM unit tests executed; add ViewModel / UseCase tests." >&2
    exit 3
fi
if [ "$TOTAL_FAILURES" -gt 0 ] || [ "$TOTAL_ERRORS" -gt 0 ]; then
    echo "[unit-test] FAIL $TOTAL_FAILURES failures, $TOTAL_ERRORS errors. See $TEST_RESULTS_DIR." >&2
    exit 3
fi

# Persist a small summary alongside other gate artifacts.
SUMMARY="$DIR/.scratch/unit_tests_result.json"
cat > "$SUMMARY" <<EOF
{
  "passed": true,
  "tests": $TOTAL_TESTS,
  "failures": $TOTAL_FAILURES,
  "errors": $TOTAL_ERRORS,
  "results_dir": "$TEST_RESULTS_DIR"
}
EOF
echo "[unit-test] WROTE $SUMMARY"
echo "[unit-test] PASS"
