#!/usr/bin/env bash
# Install and launch a generated PICO Spatial App on a connected device/emulator.
#
# This catches runtime-entry problems that assembleDebug cannot prove:
# - package not installable on the target device
# - no resolvable launcher Activity
# - Activity launch failure
# - immediate AndroidRuntime crash after launch
#
# Usage: bash scripts/runtime_launch_check.sh <target>

set -uo pipefail

if [ $# -lt 1 ]; then
    echo "Usage: $0 <target>" >&2
    exit 2
fi

DIR="$(cd "$1" && pwd)"
if [ ! -d "$DIR" ]; then
    echo "[runtime] Target directory not found: $DIR" >&2
    exit 2
fi

SCRATCH="$DIR/.scratch"
mkdir -p "$SCRATCH"
RESULT="$SCRATCH/runtime_launch_result.json"
LOG="$SCRATCH/runtime_launch.log"

write_result() {
    local passed="$1"
    local reason="$2"
    local package_name="${3:-}"
    local activity_name="${4:-}"
    python3 - "$RESULT" "$passed" "$reason" "$package_name" "$activity_name" "$LOG" <<'PY'
import json
import sys
from pathlib import Path

path, passed, reason, package_name, activity_name, log_path = sys.argv[1:]
data = {
    "passed": passed == "true",
    "package": package_name,
    "activity": activity_name,
    "reason": reason,
    "log": log_path,
}
Path(path).write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")
PY
}

if ! command -v adb >/dev/null 2>&1; then
    write_result false "adb_not_found"
    echo "[runtime] adb not found; cannot prove install/launch" >&2
    exit 1
fi

GRADLE_ROOT=""
INSTALL_TASK=""
BUILD_FILE=""
if [ -f "$DIR/gradlew" ]; then
    GRADLE_ROOT="$DIR"
    INSTALL_TASK=":app:installDebug"
    BUILD_FILE="$DIR/app/build.gradle.kts"
else
    MODULE_NAME="$(basename "$DIR")"
    SEARCH="$DIR"
    while [ "$SEARCH" != "/" ] && [ -n "$SEARCH" ]; do
        SEARCH="$(dirname "$SEARCH")"
        if [ -f "$SEARCH/gradlew" ]; then
            GRADLE_ROOT="$SEARCH"
            INSTALL_TASK=":${MODULE_NAME}:installDebug"
            BUILD_FILE="$DIR/build.gradle.kts"
            break
        fi
    done
fi

if [ -z "$GRADLE_ROOT" ]; then
    write_result false "gradlew_not_found"
    echo "[runtime] gradlew not found in $DIR or any ancestor" >&2
    exit 2
fi

PACKAGE_NAME="$(python3 - "$BUILD_FILE" <<'PY'
import re
import sys
from pathlib import Path

text = Path(sys.argv[1]).read_text(encoding="utf-8")
for key in ("applicationId", "namespace"):
    m = re.search(rf'{key}\s*=\s*"([^"]+)"', text)
    if m:
        print(m.group(1))
        raise SystemExit(0)
raise SystemExit(1)
PY
)"
if [ -z "$PACKAGE_NAME" ]; then
    write_result false "package_name_not_found"
    echo "[runtime] cannot infer package/applicationId from $BUILD_FILE" >&2
    exit 1
fi

DEVICE_COUNT="$(adb devices | awk 'NR>1 && $2 == "device" { count++ } END { print count+0 }')"
if [ "$DEVICE_COUNT" -lt 1 ]; then
    write_result false "no_connected_device" "$PACKAGE_NAME"
    echo "[runtime] no connected adb device/emulator; cannot prove install/launch" >&2
    exit 1
fi

cd "$GRADLE_ROOT" || exit 2
chmod +x gradlew 2>/dev/null || true

echo "[runtime] running ./gradlew $INSTALL_TASK (cwd: $GRADLE_ROOT, log: $LOG)"
./gradlew "$INSTALL_TASK" --no-daemon --console=plain > "$LOG" 2>&1
STATUS=$?
if [ "$STATUS" -ne 0 ]; then
    write_result false "installDebug_failed" "$PACKAGE_NAME"
    echo "[runtime] installDebug FAILED (exit=$STATUS). Last 80 lines:" >&2
    tail -n 80 "$LOG" >&2
    exit "$STATUS"
fi

RESOLVE_OUTPUT="$(adb shell cmd package resolve-activity --brief "$PACKAGE_NAME" 2>&1 | tr -d '\r')"
ACTIVITY_NAME="$(printf '%s\n' "$RESOLVE_OUTPUT" | awk '/^.*\/.*$/ { value=$0 } END { print value }')"
if [ -z "$ACTIVITY_NAME" ]; then
    write_result false "launcher_activity_not_resolved" "$PACKAGE_NAME"
    echo "[runtime] launcher Activity not resolved for $PACKAGE_NAME" >&2
    echo "$RESOLVE_OUTPUT" >&2
    exit 1
fi

adb logcat -c >/dev/null 2>&1 || true
echo "[runtime] launching $ACTIVITY_NAME"
START_OUTPUT="$(adb shell am start -W -n "$ACTIVITY_NAME" 2>&1 | tr -d '\r')"
printf '\n--- am start ---\n%s\n' "$START_OUTPUT" >> "$LOG"
if ! printf '%s\n' "$START_OUTPUT" | grep -q "Status: ok"; then
    if printf '%s\n' "$START_OUTPUT" | grep -q "Status: timeout"; then
        sleep 3
        RUNNING_PID="$(adb shell pidof "$PACKAGE_NAME" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
        if [ -z "$RUNNING_PID" ]; then
            write_result false "am_start_timeout_process_not_running" "$PACKAGE_NAME" "$ACTIVITY_NAME"
            echo "[runtime] am start timed out and app process is not running:" >&2
            echo "$START_OUTPUT" >&2
            exit 1
        fi
        echo "[runtime] am start returned timeout, but app process is running (pid=$RUNNING_PID); continuing crash scan"
        printf '\n--- timeout accepted: process running pid=%s ---\n' "$RUNNING_PID" >> "$LOG"
    else
        write_result false "am_start_failed" "$PACKAGE_NAME" "$ACTIVITY_NAME"
        echo "[runtime] am start failed:" >&2
        echo "$START_OUTPUT" >&2
        exit 1
    fi
fi

sleep 3
adb logcat -d -v time -t 1200 > "$SCRATCH/runtime_launch_logcat.log" 2>/dev/null || true
cat "$SCRATCH/runtime_launch_logcat.log" >> "$LOG" 2>/dev/null || true

if grep -E "Process: ${PACKAGE_NAME}([, ]|$)|FATAL EXCEPTION.*${PACKAGE_NAME}" "$SCRATCH/runtime_launch_logcat.log" >/dev/null 2>&1; then
    write_result false "android_runtime_crash_after_launch" "$PACKAGE_NAME" "$ACTIVITY_NAME"
    echo "[runtime] crash detected after launch. Matching log lines:" >&2
    grep -E "FATAL EXCEPTION|AndroidRuntime|$PACKAGE_NAME" "$SCRATCH/runtime_launch_logcat.log" | tail -n 120 >&2
    exit 1
fi

write_result true "installed_and_launched_without_immediate_crash" "$PACKAGE_NAME" "$ACTIVITY_NAME"
echo "[runtime] PASS package=$PACKAGE_NAME activity=$ACTIVITY_NAME"
