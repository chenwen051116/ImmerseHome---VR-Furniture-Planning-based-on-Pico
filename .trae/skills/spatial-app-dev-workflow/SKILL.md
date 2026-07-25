---
name: spatial-app-dev-workflow
description: 'Runs an iterative PICO Spatial SDK feature-development workflow after spatial-app-onboarding handoff. Use for follow-up requirements in an existing Spatial app: inspect AGENTS.md, plan/implement one increment, build, install, launch in the PICO emulator/device, capture screenshots, check success criteria, watch for crashes/logcat, and self-repair until verified or clearly blocked.'
license: 'Apache-2.0'
allowed-tools: 'Bash(pico-cli *) Bash(adb *) Bash(./gradlew *) Bash(git *) Read Edit Write'
---

# Spatial App Development Workflow

Use this skill for **post-onboarding development** in an existing PICO Spatial SDK Android/Kotlin project.
It continues from the project state left by `spatial-app-onboarding`: a runnable scaffold, a project-specific `AGENTS.md`, and a baseline that should already build/install/launch.

The operating rhythm is:

`handoff review → one requirement → implement → build → install/launch simulator/device → observe → fix crashes/logs → report evidence → next requirement`

This skill coordinates feature work and verification. When a step needs deeper domain guidance, consult the specific skill/reference named below instead of inventing APIs:

- `spatial-sdk-guideline` for Stage/WindowContainer, ECS, resources, interaction, physics, coordinates, and performance-budget API patterns.
- Use the SDK version and dependency coordinates already declared by the project; do not hard-code or introduce a fixed Spatial SDK version unless the user explicitly asks for a migration.
- `spatial-ui-ability` / `spatial-ui-design-style` for SpatialUI Compose capabilities and visual style.
- `spatial-sdk-guideline` playbooks (e.g. `playbooks/scene-surface-placement.md`) for wall/table/floor placement.
- `spatial-sdk-scene-builder` for measuring 3D asset bounds and producing transform config.
- `spatial-emulator-usage` command guidance when device/emulator operations become the main task.
- `spatial-app-perf-diagnose` for real-device performance diagnosis with `pico-cli perf` and Perfetto; do not use emulator-only checks as proof of frame-rate quality.

## 0. Start From the Onboarding Handoff

Before changing code:

1. Read the project root `AGENTS.md`.
2. Inspect the current project structure, current branch/diff, and build files.
3. Identify package name, main launch activity, generated template/container type, and existing run commands from `AGENTS.md` or Gradle/manifest files.
4. Confirm the current user request is a follow-up feature/fix inside this project. If the directory is empty or not a Spatial SDK app, route to `spatial-app-onboarding` instead of using this workflow.
5. Preserve the existing scaffold and visible baseline unless the user explicitly asks for a migration.

Handoff rule:

- Treat `AGENTS.md` as the local navigation source, not as a substitute for verification.
- If `AGENTS.md` is missing or stale, reconstruct the minimum facts from the repo and update it after the change.
- Do not restart project creation for follow-up requirements.

## 1. Work One Requirement at a Time

Convert the user's request into a small, observable increment.

For each requirement, write down internally:

- **Goal**: what user-visible behavior should change.
- **Touch points**: files/components likely affected.
- **Acceptance check**: what proves the goal works in the simulator/device.
- **Risk**: likely crash/build/runtime failure modes.

Ask at most one clarifying question only when the answer changes the architecture or visible behavior. Otherwise choose the smallest stable path, state the assumption, and proceed.

Prefer incremental edits over rewrites:

- Keep generated project conventions.
- Keep package names, activity names, asset paths, Gradle config, and template container shape stable unless the requirement demands otherwise.
- Use `spatial-sdk-guideline` references before writing nontrivial ECS, resource loading, interaction, physics, animation, or coordinate conversion code.
- Keep snippets idiomatic for the existing Kotlin/Compose style.

## 2. Build Before Runtime Verification

After each implemented increment, run host-side checks before launching:

```bash
./gradlew assembleDebug
```

If the project has relevant tests, run the narrowest meaningful checks first, then broader checks when affordable:

```bash
./gradlew testDebugUnitTest
./gradlew connectedAndroidTest
```

Handle failures yourself:

1. Read the first actionable compiler/test error.
2. Fix the code or configuration.
3. Re-run the failed command.
4. Repeat until it passes or an external prerequisite blocks progress.

Do not ask the user to run Gradle, install, launch, or log commands unless an interactive/local prerequisite is outside agent control.

## 3. Ensure a Simulator or Device Is Available

Before install/launch, verify target state with `pico-cli`:

```bash
pico-cli emulator doctor --format json
pico-cli device list --format json
pico-cli emulator list --managed-only --format json
```

If no target is available and the machine is otherwise ready, start or create a PICO emulator using the smallest safe path:

```bash
pico-cli emulator start --wait-timeout 180 -y
pico-cli emulator status --format json
pico-cli device list --format json
```

If a specific AVD is needed or already known:

```bash
pico-cli emulator start --avd <name> --wait-timeout 180 -y
```

Respect these rules:

- Prefer `pico-cli` over raw `adb` when it covers the operation.
- If multiple devices are present, pass `--device <id>` explicitly to install, launch, log, and capture commands.
- Treat long emulator downloads as normal while the CLI is still reporting progress.
- Clearly report environment blockers such as missing Android SDK, no device, unauthorized/offline device, or emulator setup failure.

## 4. Install, Launch, and Observe Every Completed Requirement

After `assembleDebug` succeeds, install and launch the app on the target:

```bash
pico-cli app install app/build/outputs/apk/debug/app-debug.apk
pico-cli app launch <package> [--activity <activity>]
```

If package-only launch fails, retry with the manifest activity or the activity documented in `AGENTS.md`.

Immediately collect runtime evidence appropriate to the requirement:

- Static UI/model/state: screenshot.
- Interaction, animation, physics, timed transition, crash reproduction: screenshot plus logs for now.
- Nonvisual behavior or failure: logcat/crash output.
- Always observe the Android crash buffer during the launch/verification window so native or framework crashes are not missed.
- Do not try to advance a volumetric/spatial UI flow with `pico-cli shell input tap x y`; that only injects 2D screen coordinates and is not reliable for spatial containers or Gaming-state transitions.

Examples:

```bash
pico-cli capture screenshot --out ./artifacts/<requirement>-screen.png
pico-cli app logcat --lines 300 --level E
adb logcat -b crash -d
```

For a fresh per-run crash check, clear crash/log buffers immediately before launch, then dump the crash buffer after launch or after reproducing the issue:

```bash
adb logcat -c
pico-cli app launch <package> [--activity <activity>]
adb logcat -b crash -d
```

If multiple devices are connected, set `ANDROID_SERIAL=<device-id>` or use `adb -s <device-id> ...` consistently with the `pico-cli --device <id>` target.

Verify capture files exist before claiming visual evidence was collected.

## 5. Check Against the Requirement, Not Just “App Launched”

For each completed increment, compare evidence against the acceptance check.

Use a concrete checklist:

- Build passed: `./gradlew assembleDebug` succeeded.
- Install passed: APK installed to the selected target.
- Launch passed: the app process/activity started successfully.
- No immediate crash: `adb logcat -b crash` and app/error logs show no new fatal exception for the package during the verification window.
- Visible/behavioral goal met: screenshot, log, or test confirms the requested change.
- Regression guard: the previous onboarding baseline still works unless the requirement intentionally changed it.

When the requirement is visual or interactive, do not rely only on logs. Capture a screenshot and inspect it if possible. If automated visual inspection is insufficient, show the artifact path and ask the user to confirm what appears on-device. For volumetric/spatial containers, say explicitly that adb-backed screenshot and 2D tap automation may both be insufficient without simulator-side support.

## 6. Crash and Runtime-Failure Repair Loop

If the app crashes, freezes, fails to launch, or shows a blank/incorrect spatial scene, debug it yourself before handing back.

Crash loop:

1. Reproduce after a clean launch:

   ```bash
   pico-cli app stop <package>
   pico-cli app launch <package> [--activity <activity>]
   ```

2. Collect crash evidence from both the crash buffer and filtered error logs. Prefer a fresh buffer from immediately before reproduction:

   ```bash
   adb logcat -c
   pico-cli app stop <package>
   pico-cli app launch <package> [--activity <activity>]
   adb logcat -b crash -d
   pico-cli app logcat --lines 500 --level E
   pico-cli log --lines 300 --tag AndroidRuntime --level E
   ```

   When the crash is timing-dependent, run a bounded watcher while reproducing:

   ```bash
   timeout 30 adb logcat -b crash
   ```

   If multiple devices are connected, include `adb -s <device-id>` or set `ANDROID_SERIAL=<device-id>` for every `adb logcat` command.

3. Identify the first relevant exception for the package from `adb logcat -b crash -d` first, then from app/error logs if needed. Capture the exception type, process/package, top app frame, and resource/API that failed.
4. Map the failure to a likely fix:
   - missing asset or bad `asset://` path → verify asset location and Gradle `noCompress`/packaging config.
   - Spatial SDK API misuse → read the relevant `spatial-sdk-guideline` reference and correct the API/thread/container usage.
   - entity not visible/interactive → check container, transform units, lighting/IBL, `CollisionComponent`, `InteractableComponent`, and scene attachment.
   - main-thread blocking or lifecycle issue → move loading/work to the recommended async/lifecycle location.
   - launch/activity issue → verify manifest package/activity and retry explicit `--activity`.
5. Apply the fix.
6. Rebuild, reinstall, relaunch, and re-check logs/evidence.

Do at least one self-repair attempt for actionable code/config crashes before asking the user. Stop and report clearly only when blocked by missing external prerequisites, missing private assets, nondeterministic device failure, or lack of enough error evidence.

## 7. Keep Documentation and Handoff Current

After a requirement is verified, update project-local handoff docs when the change affects future work:

- `AGENTS.md`: current behavior, key files, build/install/run commands, verification notes, and next likely tasks.
- Any project-specific artifact/readme path for screenshots, crash dumps, or debug logs.

Do not turn `AGENTS.md` into a tutorial. Keep it a concise navigation guide for the next agent.

## 8. Final Response Format

For each requirement, report the actual result in this order:

1. **Changed**: files and behavior implemented.
2. **Verified**: exact commands that passed.
3. **Runtime evidence**: target device/emulator, launch result, screenshot/log paths.
4. **Crash/log status**: whether any fatal errors were observed; if fixed, summarize root cause and fix.
5. **Blocked or remaining**: only if something could not be completed, with the exact blocker and next command/action.
6. **Next suggestions**: 1–3 state-aware follow-up options.

Never claim success for build, install, launch, screenshot, test, or crash-free status unless the command output or captured artifact confirms it.

## Quick Command Sequence

Use this as the default per-requirement verification skeleton, adjusting package/activity/artifact paths to the project:

```bash
./gradlew assembleDebug
pico-cli device list --format json
pico-cli emulator status --format json
pico-cli app install app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
pico-cli app stop <package>
pico-cli app launch <package> --activity <activity>
adb logcat -b crash -d
pico-cli app logcat --lines 300 --level E
pico-cli capture screenshot --out ./artifacts/<requirement>-screen.png
```

For interaction/animation/physics requirements, supplement the screenshot with focused log collection and explicit user confirmation if visual automation is insufficient.
