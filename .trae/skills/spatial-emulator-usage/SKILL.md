---
name: spatial-emulator-usage
description: Supplements pico-cli with detailed emulator and device operation guidance. Invoke when the user needs multi-step emulator, app, files, capture, or log workflows.
license: 'Apache-2.0'
---

# Spatial Emulator Usage Skill

Use this skill as a **supplement to `pico-cli`** when the task is about operating the local **PICO emulator**, a connected **ADB device**, or the app/files/logging workflows around them through real `pico-cli` commands.

This skill is for **execution-oriented environment work**, not for general Kotlin/UI feature implementation. Use `pico-cli` first for command-family routing, then load this skill when the emulator flow becomes multi-step, stateful, or failure-prone.

## What This Skill Should Do

When triggered, the agent should:

- prefer the high-level `pico-cli` commands over raw `adb` when the CLI already covers the workflow
- choose the smallest command sequence that solves the user's problem
- verify the environment before claiming success
- verify screenshot artifacts exist on the host before claiming capture succeeded
- explain blockers clearly when the machine or device state prevents progress
- tell the user when a device-scoped command is auto-starting a managed emulator so the wait does not look like a hang
- avoid destructive cleanup unless the user explicitly asked for it

## When To Use This Skill

Use this skill for requests like:

- "Start or stop the PICO emulator"
- "Check whether the emulator is installed correctly"
- "Create an AVD for the PICO emulator"
- "Install, launch, stop, or uninstall an APK on the current emulator/device"
- "Collect logcat, crash clues, screenshots, or emulator logs"
- "Push or pull files from the current emulator/device"
- "Check battery, props, device info, or connected devices"
- "Clean up CLI-created emulator instances safely"

Do not use this skill as the primary skill when the request is mainly:

- Spatial SDK feature implementation
- project scaffolding from templates
- SDK version upgrade or migration

In those cases, use the more specific skill first and bring this one in only if the work reaches a real emulator/device operation step.

## Core Rules

### 1. Use the real CLI surface

Always use the real `pico-cli` command names that exist in the repository. Do not invent wrapper commands or undocumented flags.

Preferred command families:

- `pico-cli emulator ...`
- `pico-cli device ...`
- `pico-cli app ...`
- `pico-cli files ...`
- `pico-cli capture ...`
- `pico-cli log`

Fallback:

- do not use `pico-cli adb logcat` for first-pass AI routing; use `pico-cli log` or `pico-cli app logcat` instead
- do not jump straight to plain `adb` unless the user explicitly asks for raw adb behavior

### 2. Respect device targeting

Many commands operate on the "preferred current device".

Selection priority:

1. explicit `--device <id>`
2. `PICO_CLI_DEVICE`
3. `ADB_SERIAL`
4. automatic selection by the CLI

When multiple devices may exist, prefer passing `--device <id>` explicitly instead of relying on implicit selection.

### 2.5 Spatial interaction limitation

- do not use `pico-cli shell input tap x y` to drive volumetric or spatial windows
- treat `shell input tap` as a 2D Android surface fallback only, such as launcher lists, standard settings pages, or flat app UI that is known to live on a normal screen buffer
- if the target flow depends on volumetric window interaction, spatial hit testing, controller rays, or hand-gesture style input, report that the current CLI cannot automate it reliably without simulator-side input support
- when this limitation blocks a verification flow, say so clearly instead of spending turns retrying different tap coordinates

### 3. Verify before and after

Before risky or multi-step operations:

- check environment or connectivity first
- verify the target emulator/device exists
- verify the expected output artifact or runtime state after execution

Typical preflight:

```bash
pico-cli emulator doctor --format json
pico-cli device list --format json
pico-cli emulator list --managed-only --format json
```

### 4. Prefer the least destructive path

Safe order:

- inspect
- create
- start
- operate
- collect evidence
- stop
- clean up only if requested

Do not delete emulator bundles, caches, or AVDs just to "reset everything" unless the user clearly requested cleanup.

## Command Map

### Emulator Lifecycle

Use these for environment validation and emulator lifecycle control:

- `pico-cli emulator doctor`
  - Checks whether Android Studio / SDK / emulator prerequisites are present
  - Use first when setup health is unknown
- `pico-cli emulator setup`
  - Guides installation/setup flow
  - Use when prerequisites are missing and the user wants help fixing the machine
- `pico-cli emulator list`
  - Lists available AVDs
  - Use `--managed-only` to focus on CLI-created PICO emulator AVDs
- `pico-cli emulator create`
  - Creates a PICO emulator AVD without launching it
  - Use when the user wants a persistent emulator instance created ahead of time
- `pico-cli emulator start`
  - Starts a chosen AVD and can download the emulator bundle if missing
  - Use when the user needs a runnable emulator session
- `pico-cli emulator status`
  - Checks process and adb visibility
  - Use before start/stop if state is unclear
- `pico-cli emulator stop`
  - Stops the running emulator
- `pico-cli emulator delete`
  - Deletes CLI-created PICO AVDs only
  - Prefer `--avd <name>` for precise cleanup
- `pico-cli emulator dump-logs`
  - Collects emulator-related logs for crash/debug analysis
- `pico-cli emulator delete-image`
  - Deletes downloaded PICO emulator bundle roots and download cache
  - Treat as destructive; use only with explicit user consent

Common examples:

```bash
pico-cli emulator doctor --format json
pico-cli emulator list --managed-only --format json
pico-cli emulator create --avd Pico_Emulator_0_11 --source auto -y
pico-cli emulator start --avd Pico_Emulator_0_11 --wait-timeout 180 -y
pico-cli emulator status --format json
pico-cli emulator dump-logs --out /tmp/pico-cli-sim-logs
```

### Device Inspection

Use these when the task is about the currently connected emulator/device rather than emulator lifecycle itself:

- `pico-cli device list`
- `pico-cli device connect <address>`
- `pico-cli device disconnect [address]`
- `pico-cli device info`
- `pico-cli device battery`
- `pico-cli device props`
- `pico-cli shell [commands...]`
  - If no device is online but a managed AVD already exists, the CLI may start that emulator first and then continue
  - If no managed AVD exists, prefer returning the missing-device error directly instead of implying the CLI can bootstrap one implicitly
  - Do not use `pico-cli shell input tap x y` for volumetric windows or spatial containers; it only injects 2D screen coordinates and is not a reliable path to enter gameplay or other spatial states

Examples:

```bash
pico-cli device list --format json
pico-cli device info --format json
pico-cli device battery --format json
pico-cli device props --filter pico --format plain
pico-cli shell getprop ro.product.model
```

### App Operations

Use these after the target emulator/device is online:

- `pico-cli app install <apk>`
- `pico-cli app list`
- `pico-cli app info <package>`
- `pico-cli app launch <package> [--activity <activity>]`
- `pico-cli app stop <package>`
- `pico-cli app uninstall <package>`
- `pico-cli app logcat`
- `pico-cli app watch-crash <package>`

Examples:

```bash
pico-cli app install app/build/outputs/apk/debug/app-debug.apk
pico-cli app info com.example.spatialdemo --format json
pico-cli app launch com.example.spatialdemo --activity .platform.LaunchActivity
pico-cli app logcat --lines 200 --level E
pico-cli app watch-crash com.example.spatialdemo --start --interval 2
```

Important:

- if plain package launch fails, retry with `--activity`
- do not claim install or launch succeeded unless the command actually succeeded

### Files and Capture

Use these for moving assets, inspecting runtime files, and collecting visual evidence:

- `pico-cli files push <local> <remote>`
- `pico-cli files pull <remote> [local]`
- `pico-cli files ls [remote]`
- `pico-cli files mkdir <remote>`
- `pico-cli files rm <remote>`
- `pico-cli files cat <remote>`
- `pico-cli files stat <remote>`
- `pico-cli capture screenshot`

Capture guidance:

- use `pico-cli capture screenshot` when the user needs a single visual proof point
- prefer an explicit `--out <path>` so the artifact location is predictable and easy to report back
- prefer an explicit `--device <id>` when more than one device or emulator may be connected
- after capture, verify the screenshot file exists on the host before saying the artifact was collected
- if the target UI is volumetric/spatial, warn that `pico-cli capture screenshot` may return a black image because adb `screencap` does not guarantee compositor output from spatial containers

Important options:

- `pico-cli capture screenshot -o, --out <path>`

Examples:

```bash
pico-cli files mkdir /sdcard/Download/demo-assets
pico-cli files push ./local.png /sdcard/Download/demo-assets/local.png
pico-cli files ls /sdcard/Download/demo-assets -l
pico-cli files stat /sdcard/Download/demo-assets/local.png --format json
pico-cli capture screenshot --out ./artifacts/screen.png
```

### Logs and Raw ADB Escape Hatch

Use these when the user needs low-level diagnostics:

- `pico-cli log`
- `pico-cli shell`
- `pico-cli log`
- `pico-cli app logcat`
- `pico-cli device props`

Prefer `pico-cli log` or `pico-cli app logcat` before any lower-level fallback. Do not route AI through `pico-cli adb logcat` first.

Examples:

```bash
pico-cli log --lines 150 --tag AndroidRuntime --level E
pico-cli device props --filter ro.build.version.release
pico-cli app logcat --lines 200 --level W
```

## Scenario Playbooks

### Scenario 1: "I do not know whether the machine is ready"

Use:

```bash
pico-cli emulator doctor --format json
pico-cli emulator list --managed-only --format json
pico-cli device list --format json
```

Then:

- explain which prerequisite is missing
- recommend `emulator setup` only if the environment is not ready

### Scenario 2: "Create and launch a fresh emulator"

Use:

```bash
pico-cli emulator create --avd <name> -y
pico-cli emulator start --avd <name> --wait-timeout 180 -y
pico-cli emulator status --format json
pico-cli device list --format json
```

### Scenario 3: "Install and run an app for validation"

Use:

```bash
pico-cli app install <apk>
pico-cli app launch <package> [--activity <activity>]
pico-cli app logcat --lines 200 --level E
```

If the user wants visible evidence:

```bash
pico-cli capture screenshot --out <path>
```

Then:

- prefer screenshot for a static UI state or success confirmation
- verify the artifact path exists before reporting success

### Scenario 4: "Collect crash/debug evidence"

Use:

```bash
pico-cli app watch-crash <package> --start
pico-cli app logcat --lines 300 --level E
pico-cli emulator dump-logs --out <dir>
pico-cli capture screenshot --out <path>
```

Use screenshot in this scenario when logs alone are not enough to show the failure mode.

### Scenario 5: "Capture screenshot evidence"

Use:

```bash
pico-cli device list --format json
pico-cli emulator status --format json
pico-cli capture screenshot --device <id> --out <path>
```

Then:

- confirm which emulator/device was targeted
- report the exact local artifact path
- do not claim success unless the command completed and the artifact exists on disk

### Scenario 6: "Clean up what the CLI created"

Use:

```bash
pico-cli emulator list --managed-only --format json
pico-cli emulator stop
pico-cli emulator delete --avd <name> -y
```

Only if the user explicitly asks to remove downloaded emulator bundles too:

```bash
pico-cli emulator delete-image -y
```

## Safety Notes

### Destructive commands

Treat these as destructive and confirm intent unless the user explicitly requested them:

- `pico-cli emulator delete`
- `pico-cli emulator delete-image`
- `pico-cli files rm`
- `pico-cli app uninstall`
- `pico-cli adb setprop`
- `pico-cli adb root`

### `emulator delete-image` caution

This command is intentionally limited to the PICO SDK bundle roots and the CLI download cache, but it is still destructive.

Before running it:

- stop the emulator first
- be extra careful on Windows, where `adb.exe` or Android Studio may still hold file locks
- do not run it as a generic troubleshooting shortcut

### `emulator start` caution

Before using heavy options such as `--wipe-data`, `--watch`, custom `--artifact-url`, or custom `--args`:

- make sure the user actually needs them
- explain the tradeoff briefly
- prefer the default start path first

### Emulator download patience

`pico-cli emulator start` or `pico-cli emulator install` may spend a long time downloading the emulator bundle.

- treat a long-running download as normal, especially when the machine is on Wi-Fi or a slower network
- a 10 minute download window is possible and does not mean the command failed
- do not report failure just because the command has been running for a while
- only treat the download as failed when the CLI returns an explicit failure result or error message
- if there is no failure result yet, assume the CLI is still downloading, verifying, or extracting
- when progress lines include a percentage such as `(... 35%)`, use that percentage when the user asks for progress
- if total bytes are shown, summarize progress in plain language, for example "about 35% downloaded"
- if the CLI only reports stage changes, explain the current stage honestly, such as "still downloading" or "now verifying files"

### Logging and watch mode

For long-running commands like:

- `pico-cli log --follow`
- `pico-cli app logcat --follow`
- `pico-cli app watch-crash ...`
- `pico-cli emulator start --watch`

Make sure the user asked for streaming/monitoring behavior or the debugging session truly needs it.

## Response Pattern

When using this skill, answer in this order:

1. state which emulator/device step you are performing
2. run the relevant `pico-cli` command(s)
3. report the actual outcome, not the intended outcome
4. if blocked, name the exact missing prerequisite or command failure
5. suggest the next smallest useful step

Never say the emulator is running, the APK is installed, or the screenshot/log was captured unless the command output confirms it.

If the emulator bundle is still downloading and no failure result has been returned yet, explicitly tell the user that the command is still in progress and that waiting is expected.
