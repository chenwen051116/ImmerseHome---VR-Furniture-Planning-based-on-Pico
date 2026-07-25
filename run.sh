#!/usr/bin/env bash
# One-command startup for the Room Planner on the PICO emulator.
# Starts the emulator if needed, waits for adb, (re)installs the debug APK if it
# exists, and launches the app fresh (pico-cli launch does NOT restart a running app).
#
# Usage:  bash run.sh            # start emulator + install + launch
#         SKIP_INSTALL=1 bash run.sh   # skip (re)install, just start + launch
#
# Override paths via env:  ADB, APP_ID, APK

set -u

ADB="${ADB:-/c/Users/Taven/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
APP_ID="${APP_ID:-com.example.testfull}"
APK="${APK:-app/build/outputs/apk/debug/app-debug.apk}"

log() { echo "[run.sh] $*"; }

# 1) Emulator: start only if it isn't already answering adb.
if "$ADB" shell true 2>/dev/null; then
    log "emulator already online, skipping start"
else
    log "starting emulator (first cold boot takes minutes)…"
    pico-cli emulator start || { log "ERROR: emulator start failed"; exit 1; }
fi

# 2) Wait until adb answers (up to ~5 min).
log "waiting for adb…"
for i in $(seq 1 60); do
    if "$ADB" shell true 2>/dev/null; then
        log "adb online"
        break
    fi
    if [ "$i" -eq 60 ]; then
        log "ERROR: adb never came online"
        exit 1
    fi
    sleep 5
done

# 3) Install the APK if present (skip with SKIP_INSTALL=1).
if [ "${SKIP_INSTALL:-0}" != "1" ]; then
    if [ -f "$APK" ]; then
        log "installing $APK …"
        pico-cli app install "$APK" || { log "ERROR: install failed"; exit 1; }
    else
        log "no APK at $APK — skipping install (build it with ./gradlew.bat :app:assembleDebug)"
    fi
fi

# 4) Launch fresh.
log "launching $APP_ID …"
"$ADB" shell am force-stop "$APP_ID"
pico-cli app launch "$APP_ID" || { log "ERROR: launch failed"; exit 1; }
log "done. If the startup ANR dialog appears, click 等待 (Wait) once."
