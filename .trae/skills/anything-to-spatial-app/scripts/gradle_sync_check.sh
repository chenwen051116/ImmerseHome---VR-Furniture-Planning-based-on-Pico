#!/usr/bin/env bash
# Verify that a newly generated module is visible to Gradle after settings.gradle
# changes. This is the CLI-side proxy for Android Studio's "Sync Project with
# Gradle Files" action.
#
# Important: the real Android Studio sync is an IDE action. When an IDE/MCP sync
# API is available, the agent must trigger that action after generation. This
# script guarantees the project model is discoverable by Gradle and records that
# IDE sync is still required for immediate Android Studio run-configuration use.
#
# Usage: bash scripts/gradle_sync_check.sh <target>

set -uo pipefail

if [ $# -lt 1 ]; then
    echo "Usage: $0 <target>" >&2
    exit 2
fi

DIR="$(cd "$1" && pwd)"
if [ ! -d "$DIR" ]; then
    echo "[gradle-sync] Target directory not found: $DIR" >&2
    exit 2
fi

SCRATCH="$DIR/.scratch"
mkdir -p "$SCRATCH"
RESULT="$SCRATCH/gradle_sync_result.json"
LOG="$SCRATCH/gradle_sync.log"

write_result() {
    local passed="$1"
    local reason="$2"
    local gradle_root="${3:-}"
    local module_path="${4:-}"
    python3 - "$RESULT" "$passed" "$reason" "$gradle_root" "$module_path" "$LOG" <<'PY'
import json
import sys
from pathlib import Path

path, passed, reason, gradle_root, module_path, log_path = sys.argv[1:]
Path(path).write_text(json.dumps({
    "passed": passed == "true",
    "reason": reason,
    "gradle_root": gradle_root,
    "module_path": module_path,
    "ide_sync_required": True,
    "ide_sync_note": "After creating or including a new Android module, run Android Studio: Sync Project with Gradle Files before expecting run configurations/module selection to appear immediately.",
    "log": log_path,
}, indent=2, ensure_ascii=False), encoding="utf-8")
PY
}

GRADLE_ROOT=""
MODULE_PATH=""
if [ -f "$DIR/gradlew" ]; then
    GRADLE_ROOT="$DIR"
    MODULE_PATH=":app"
else
    MODULE_NAME="$(basename "$DIR")"
    SEARCH="$DIR"
    while [ "$SEARCH" != "/" ] && [ -n "$SEARCH" ]; do
        SEARCH="$(dirname "$SEARCH")"
        if [ -f "$SEARCH/gradlew" ]; then
            GRADLE_ROOT="$SEARCH"
            MODULE_PATH=":${MODULE_NAME}"
            break
        fi
    done
fi

if [ -z "$GRADLE_ROOT" ]; then
    write_result false "gradlew_not_found"
    echo "[gradle-sync] gradlew not found in $DIR or any ancestor" >&2
    exit 2
fi

cd "$GRADLE_ROOT" || exit 2
chmod +x gradlew 2>/dev/null || true

echo "[gradle-sync] running ./gradlew projects (cwd: $GRADLE_ROOT, log: $LOG)"
./gradlew projects --no-daemon --console=plain > "$LOG" 2>&1
STATUS=$?
if [ "$STATUS" -ne 0 ]; then
    write_result false "gradle_projects_failed" "$GRADLE_ROOT" "$MODULE_PATH"
    echo "[gradle-sync] ./gradlew projects FAILED (exit=$STATUS). Last 80 lines:" >&2
    tail -n 80 "$LOG" >&2
    exit "$STATUS"
fi

if ! grep -q "Project '${MODULE_PATH}'" "$LOG"; then
    write_result false "module_not_visible_to_gradle" "$GRADLE_ROOT" "$MODULE_PATH"
    echo "[gradle-sync] module $MODULE_PATH is not visible in ./gradlew projects output" >&2
    tail -n 80 "$LOG" >&2
    exit 1
fi

write_result true "gradle_project_discovered_cli_proxy_for_ide_sync" "$GRADLE_ROOT" "$MODULE_PATH"
echo "[gradle-sync] PASS module=$MODULE_PATH is visible to Gradle"
echo "[gradle-sync] NOTE Android Studio still needs Sync Project with Gradle Files before first IDE run/config selection."
