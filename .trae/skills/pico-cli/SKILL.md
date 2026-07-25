---
name: pico-cli
description: Generic pico-cli usage guide for command discovery, command-family selection, help/version/setup checks, output formats, device targeting, safe defaults, and deciding when to hand off to spatial-emulator-usage, spatial-app-perf-diagnose, spatial-app-onboarding, or lower-level adb escape hatches.
license: 'Apache-2.0'
---

# pico-cli Usage Skill

Use this skill when the user asks how to use `pico-cli` itself: which command family to choose, how to discover options, how to target a device, how to inspect JSON output, or how to make a safe first command sequence.

This is a **routing and CLI-usage skill**, not the owner of every workflow implemented by `pico-cli`.

## Reference Loading

Load shared references only when needed:

| Reference                                                    | Load when                                                                                                          |
| ------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------ |
| `../../references/shared/pico-cli/command-families.md`       | Choosing the right `pico-cli` command family or explaining what each family owns                                   |
| `../../references/shared/pico-cli/conventions-and-safety.md` | Device targeting, output formats, verification, destructive commands, raw `adb` fallback, or long-running commands |
| `../../references/shared/pico-cli/troubleshooting.md`        | CLI setup, unknown commands, missing devices, command discovery, or first-pass diagnosis                           |

## When To Use

Use this skill for requests like:

- "How do I use `pico-cli`?"
- "Which `pico-cli` command should I run for this?"
- "Show me the command families in `pico-cli`."
- "How do I get JSON output from `pico-cli`?"
- "How do I pick a target device?"
- "When should I use `pico-cli adb` instead of `pico-cli app` or `pico-cli device`?"
- "What is the safe way to inspect my emulator/device/app state first?"

## Route Away For Specialized Workflows

Prefer the most specific skill once the user's intent is clear:

- Use `spatial-emulator-usage` when the task is to operate an emulator or connected device end-to-end: create/start/stop emulator, inspect devices, install/launch APKs, push/pull files, collect screenshots/logcat, or clean up emulator resources.
- Use `spatial-app-perf-diagnose` for `pico-cli perf`, Perfetto Trace capture/load/query, frame drops, jank, high CPU/GPU load, slow startup, or rendering-pipeline diagnosis.
- Use `spatial-app-onboarding` for `pico-cli project create` or first Spatial SDK project scaffolding.
- Use `spatial-sdk-guideline`, `spatial-ui-*`, or migration skills when the request is about app code, SDK APIs, UI implementation, or Gradle migration rather than CLI usage.

If a request starts as generic `pico-cli` help but becomes an execution workflow, keep this skill as the command-selection rationale and load `spatial-emulator-usage` as the emulator-specific supplement.

## Core Workflow

1. Identify the user's goal in one sentence.
2. Select the command family and explain why.
3. Prefer discovery commands before state-changing commands:
   - `pico-cli --help`
   - `pico-cli doctor --help`, then `pico-cli doctor --format json` when available for broad read-only diagnostics
   - `pico-cli <family> --help`
   - `pico-cli <family> <command> --help`
   - `pico-cli setup --tool <host>` when plugin/host setup is the issue and the user wants repair
4. Prefer JSON output when a command supports it and the result will drive later decisions.
5. Choose explicit device targeting when more than one target may exist.
6. For destructive or long-running actions, route to the specialized skill or confirm intent before proceeding.
7. Report actual command results and next smallest useful step.

## Quick Command-Family Map

Use this summary for routing; load `command-families.md` for details.

| Goal                                      | Start with                                                                                     |
| ----------------------------------------- | ---------------------------------------------------------------------------------------------- |
| Broad read-only environment diagnostics   | `pico-cli doctor --format json` when available; otherwise use help/version discovery           |
| Install/update agent plugin or skills     | `pico-cli setup`, `pico-cli plugin update`                                                     |
| Create a Spatial SDK starter project      | `pico-cli project create`                                                                      |
| Check emulator prerequisites or lifecycle | `pico-cli emulator ...`                                                                        |
| Inspect connected devices                 | `pico-cli device ...`                                                                          |
| Install/launch/inspect app packages       | `pico-cli app ...`                                                                             |
| Transfer or inspect device files          | `pico-cli files ...`                                                                           |
| Capture screenshots                       | `pico-cli capture screenshot ...`                                                              |
| Read logs                                 | `pico-cli log`, `pico-cli app logcat`                                                          |
| Performance profiling and Perfetto        | `pico-cli perf ...`                                                                            |
| Raw device escape hatch                   | `pico-cli adb ...` or `pico-cli shell ...` only when high-level commands do not cover the need |

## Safe Defaults

- Prefer high-level `pico-cli` command families before raw `pico-cli adb`.
- Prefer `--format json` for inspection commands when the output will be parsed or compared.
- Prefer explicit `--device <id>` / `-d <id>` when multiple devices are connected.
- Inspect before writing, deleting, uninstalling, clearing logs, or starting a long-running watch.
- Treat `pico-cli shell ...` as a raw escape hatch: if the CLI needs to auto-start a managed emulator first, explain that wait explicitly instead of treating it like immediate shell output.
- Do not route volumetric/spatial interaction through `pico-cli shell input tap x y`; that path only covers 2D screen-coordinate input and is not reliable for spatial containers.
- Do not invent command names or flags; use help output or repository command definitions when uncertain.

## Response Pattern

When using this skill, answer in this shape:

1. **Intent**: the CLI goal you inferred.
2. **Recommended command family**: the family and why it fits.
3. **Suggested commands**: the minimal command sequence, starting with help/inspection when useful.
4. **Safety / targeting note**: device id, output format, destructive/long-running behavior if relevant.
5. **Handoff**: name the specialized skill to use if the user wants the operation executed end-to-end.
