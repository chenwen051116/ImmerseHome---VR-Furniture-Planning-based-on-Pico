#!/usr/bin/env bash
# Smoke build for a generated PICO Spatial App project.
#
# Two layouts are supported:
#   (A) Standalone scaffold — <target>/gradlew exists.
#       Runs `./gradlew :app:assembleDebug` inside <target>.
#   (B) Monorepo submodule — <target> has no gradlew of its own; the wrapper
#       lives in an ancestor directory. Walks up until it finds gradlew, then
#       runs `./gradlew :<module>:assembleDebug` where <module> is
#       basename(<target>).
#
# Surfaces last error chunk on failure.
#
# Usage: bash scripts/smoke_build.sh <output-dir>

set -uo pipefail

if [ $# -lt 1 ]; then
    echo "Usage: $0 <output-dir>" >&2
    exit 2
fi

DIR="$(cd "$1" && pwd)"
if [ ! -d "$DIR" ]; then
    echo "[smoke] Directory not found: $DIR" >&2
    exit 2
fi

# Resolve gradle root + task name.
GRADLE_ROOT=""
GRADLE_TASK=""
if [ -f "$DIR/gradlew" ]; then
    GRADLE_ROOT="$DIR"
    GRADLE_TASK=":app:assembleDebug"
else
    MODULE_NAME="$(basename "$DIR")"
    SEARCH="$DIR"
    while [ "$SEARCH" != "/" ] && [ -n "$SEARCH" ]; do
        SEARCH="$(dirname "$SEARCH")"
        if [ -f "$SEARCH/gradlew" ]; then
            GRADLE_ROOT="$SEARCH"
            GRADLE_TASK=":${MODULE_NAME}:assembleDebug"
            break
        fi
    done
fi

if [ -z "$GRADLE_ROOT" ]; then
    echo "[smoke] gradlew not found in $DIR or any ancestor (was scaffold run?)" >&2
    exit 2
fi

cd "$GRADLE_ROOT"
chmod +x gradlew 2>/dev/null || true

LOG="$DIR/.scratch/smoke_build.log"
mkdir -p "$(dirname "$LOG")"

echo "[smoke] running ./gradlew $GRADLE_TASK (cwd: $GRADLE_ROOT, log: $LOG)"
./gradlew "$GRADLE_TASK" --no-daemon --console=plain > "$LOG" 2>&1
STATUS=$?

if [ "$STATUS" -ne 0 ]; then
    echo "[smoke] BUILD FAILED (exit=$STATUS). Last 60 lines:" >&2
    tail -n 60 "$LOG" >&2
    exit "$STATUS"
fi

echo "[smoke] BUILD SUCCESS"
# APK lookup: standalone scaffold uses app/build/...; monorepo submodule uses <target>/build/...
if [ "$GRADLE_TASK" = ":app:assembleDebug" ]; then
    APK="$(find "$GRADLE_ROOT/app/build/outputs/apk/debug" -name '*.apk' 2>/dev/null | head -n1)"
else
    APK="$(find "$DIR/build/outputs/apk/debug" -name '*.apk' 2>/dev/null | head -n1)"
fi
if [ -n "${APK:-}" ]; then
    echo "[smoke] APK: $APK"
fi

