#!/usr/bin/env bash
#
# push-model.sh — upload a 3D model into the PICO Room Planner models folder
# on the running PICO emulator.
#
# Usage:
#   ./push-model.sh path/to/chair.glb
#   ./push-model.sh            # prompts for the file path
#
# Supported: .glb .gltf .usda .usdc .usdz

set -euo pipefail

# Stop Git Bash/MSYS from rewriting adb's Unix-style arguments (/sdcard/...) as Windows paths.
export MSYS_NO_PATHCONV=1

PACKAGE="com.example.testfull"
REMOTE_DIR="/sdcard/Android/data/${PACKAGE}/files/models"
ADB_DEFAULT="/c/Users/Taven/AppData/Local/Android/Sdk/platform-tools/adb.exe"

# --- locate adb -------------------------------------------------------------
ADB="${ADB:-}"
if [ -z "${ADB}" ]; then
    if command -v adb >/dev/null 2>&1; then
        ADB="adb"
    elif [ -x "${ADB_DEFAULT}" ]; then
        ADB="${ADB_DEFAULT}"
    else
        echo "ERROR: adb not found on PATH and not at ${ADB_DEFAULT}." >&2
        echo "Set the ADB environment variable to your adb.exe path." >&2
        exit 1
    fi
fi

# --- get the model file -------------------------------------------------------
INPUT="${1:-}"
if [ -z "${INPUT}" ]; then
    read -r -p "Path to the model file: " INPUT
fi

# Strip surrounding quotes users often paste from Explorer.
INPUT="${INPUT%\"}"
INPUT="${INPUT#\"}"

# Accept Windows paths (D:\...\file.glb or models\file.glb) as well as Git Bash paths.
if command -v cygpath >/dev/null 2>&1; then
    case "${INPUT}" in
        *\\*)
            INPUT="$(cygpath "${INPUT}")"
            ;;
    esac
fi

if [ ! -f "${INPUT}" ]; then
    echo "ERROR: file not found: ${INPUT}" >&2
    exit 1
fi

# adb.exe is a native Windows program: give it a Windows path for the local file.
PUSH_PATH="${INPUT}"
if command -v cygpath >/dev/null 2>&1; then
    PUSH_PATH="$(cygpath -w "${INPUT}")"
fi

BASENAME="$(basename "${INPUT}")"
EXT="$(echo "${BASENAME##*.}" | tr '[:upper:]' '[:lower:]')"
case "${EXT}" in
    glb|gltf|usda|usdc|usdz) ;;
    *)
        echo "ERROR: unsupported format '.${EXT}'." >&2
        echo "Supported: .glb .gltf .usda .usdc .usdz" >&2
        exit 1
        ;;
esac

# --- check the emulator -------------------------------------------------------
if ! "${ADB}" devices | grep -q $'\tdevice$'; then
    echo "No emulator detected, restarting adb server…" >&2
    "${ADB}" kill-server >/dev/null 2>&1 || true
    "${ADB}" start-server >/dev/null 2>&1 || true
    sleep 2
fi
if ! "${ADB}" devices | grep -q $'\tdevice$'; then
    echo "ERROR: no device/emulator connected. Start the PICO emulator first." >&2
    exit 1
fi

# --- push ----------------------------------------------------------------------
echo "Target folder: ${REMOTE_DIR}"
"${ADB}" shell mkdir -p "${REMOTE_DIR}"
echo "Pushing ${BASENAME}…"
"${ADB}" push "${PUSH_PATH}" "${REMOTE_DIR}/"

# --- verify -------------------------------------------------------------------
if "${ADB}" shell ls "${REMOTE_DIR}/${BASENAME}" >/dev/null 2>&1; then
    echo "OK — uploaded:"
    "${ADB}" shell ls -la "${REMOTE_DIR}"
    echo
    echo "Now open the app → Objects → 'Scan models folder' and pick '${BASENAME%.*}'."
else
    echo "ERROR: push reported success but the file is not on the emulator." >&2
    exit 1
fi
