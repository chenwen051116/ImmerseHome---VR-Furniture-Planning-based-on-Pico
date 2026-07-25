---
name: spatial-ui-design-style
description: >-
  Hard-constraint skill for any application that uses SpatialUI in SpatialSDK
  to write Compose UI. Acts as the code-admission ruleset (lint-as-skill) for
  PicoTheme usage, theme-role routing (color / typography / disabled / hover /
  indication), built-in design component preference, custom-component
  parameter ordering, frosted-glass window background, and SpatialUI custom
  hover. ANY upstream skill that emits Compose code (spatial-app-onboarding,
  screenshot-to-spatial-app, anything-to-spatial-app, manual edits) MUST consult
  this skill before writing UI code. NOT for: 3D scene
  authoring, asset placement, performance diagnosis.
license: 'Apache-2.0'
---

# SpatialUI Design-Style Skill — Hard Constraints for Compose UI

This skill is the **code-admission ruleset** for any PICO spatial application
that emits Compose UI. It is intentionally short. Detailed guidance is split
into [`references/`](./references); read on demand.

## Highest-Priority Decision Table (Read First)

These four rules are absolute. They override personal preference, library
shortcut, and generic Compose / Material habits.

| #   | Scenario                      | Highest-Priority API                                                                                                                                                                                                                                                                                                                | Allowed Exception                                                                                                                                                                                                                                                                                                                                                                                |
| --- | ----------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 1   | Theme wrapping                | `PicoTheme { ... }` at the root Activity / `WindowContainer` / `Stage`                                                                                                                                                                                                                                                              | None                                                                                                                                                                                                                                                                                                                                                                                             |
| 2   | UI components                 | `com.pico.spatial.ui.design.*` built-ins                                                                                                                                                                                                                                                                                            | Only when no built-in fits — then custom (see `references/custom-component.md`)                                                                                                                                                                                                                                                                                                                  |
| 3   | Custom hover visuals          | `Modifier.spatialHoverEffect`                                                                                                                                                                                                                                                                                                       | None — never reimplement with `hoverable + animateFloatAsState(scale)`                                                                                                                                                                                                                                                                                                                           |
| 4   | Window / root-node background | **Use the system-default `Material.Regular` glass** — write nothing on the root. The on/off switch differs by container: `DefaultWindowContainer` → manifest `pico.spatial.windowcontainer.materialbackground` (per-Activity); `WindowContainer(...)` / `Augment(...)` → DSL parameter `enableMaterialBackground` (default `true`). | To switch glass style or go opaque, first flip the **right** switch off (`materialbackground="0"` for `DefaultWindowContainer`, `enableMaterialBackground = false` for `WindowContainer/Augment`), then either `Modifier.backgroundMaterial(enable = true, style = Material.<Style>)` or `// design-style: opaque-root` + `Modifier.background(<role>)`. **Never paint solid color over glass.** |

## Four Core Principles

1. **Always wrap with `PicoTheme`.** Top-level `Activity` /
   `DefaultWindowContainer` / `Stage` MUST be wrapped with `PicoTheme { ... }`.
   The default `colorScheme` already calls `systemColorScheme(LocalContext.current)`
   internally, so plain `PicoTheme { ... }` is enough; only pass an explicit
   `colorScheme = ...` when you need to override.
2. **Prefer built-in design components.** Search `com.pico.spatial.ui.design.*`
   first; custom only when no built-in fits.
3. **Route through theme roles & SpatialUI modifiers.**
   Colors → `PicoTheme.colorScheme.<role>`;
   typography → `PicoTheme.typography.<role>`;
   click → `LocalIndication.current` + `controllerHapticFeedback`
   (shared `interactionSource`);
   **hover MUST use `Modifier.spatialHoverEffect` — highest priority**;
   disabled → `LocalDisableAlpha.current`.
4. **Window / container root glass is system-provided; do not paint over it.**
   Every PICO window container ships with `Material.Regular` glass on by
   default — but the **on/off switch differs by container kind**:
   - `DefaultWindowContainer` → controlled per-Activity in the launcher
     `<activity>` manifest meta-data
     `pico.spatial.windowcontainer.materialbackground` (default `"1"`).
     There is no DSL knob for `DefaultWindowContainer`.
   - `WindowContainer(...)` / `Augment(...)` → controlled by the Kotlin
     DSL parameter `enableMaterialBackground: Boolean = true` on the
     constructor. Manifest meta-data does NOT apply to these.

   For the common case **write no background on the window root** — the
   system already paints the glass. To use a different glass style or to
   render no glass, first flip the **correct switch** for that container
   (manifest `"0"` for `DefaultWindowContainer`,
   `enableMaterialBackground = false` for `WindowContainer/Augment`), then
   either call `Modifier.backgroundMaterial(enable = true, style = Material.<Style>)`
   for a different style or add `// design-style: opaque-root` +
   `Modifier.background(PicoTheme.colorScheme.<role>)` for an opaque
   root. **Never paint a solid color (or `fillPrimary`) on top of the
   system glass — it defeats both the glass and vibrant linkage.**

## Minimal Compliant Skeleton

```kotlin
class MyApp : Application() {
    override fun onCreate() { super.onCreate(); launch(::mainApp) }
}

fun mainApp(scope: SpatialAppScope) = with(scope) {
    // Launcher window — Material.Regular glass is provided by the
    // <activity> manifest meta-data
    // `pico.spatial.windowcontainer.materialbackground="1"`.
    DefaultWindowContainer {
        PicoTheme {
            // ✅ Rule #4: do NOT add Modifier.background(...) /
            //    Modifier.backgroundMaterial(...) here unless you have first
            //    set that meta-data to "0".
            Box(Modifier.fillMaxSize()) {
                AppContent()
            }
        }
    }

    // Any extra window the app launches itself: glass is controlled by
    // the DSL parameter `enableMaterialBackground` (default true). Both
    // declaration styles are valid:
    //
    //  (A) Direct parameter style — used below
    //  (B) properties = { ... } DSL style — set fields inside the lambda,
    //      e.g. properties = { defaultSize = ...; enableMaterialBackground = false }
    //
    // Do NOT add a background on its root unless the matching switch is off.
    WindowContainer(
        id = "DetailPanel",
        form = Form.Planar,
        defaultSize = WindowContainerSize(width = 640.dp, height = 360.dp),
        // enableMaterialBackground = true // default; can be omitted
    ) {
        PicoTheme {
            Box(Modifier.fillMaxSize()) {
                DetailContent()
            }
        }
    }
}
```

## On-Demand References

Read the file under `references/` whose topic matches the current task.

| When you need to …                                                         | Read                                                                         |
| -------------------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| Pick a built-in component, role, or sizing tier                            | [`references/builtins.md`](./references/builtins.md)                         |
| Resolve color / typography / motion / locals                               | [`references/tokens.md`](./references/tokens.md)                             |
| Write a custom Composable (param order, click + haptics + audio, disabled) | [`references/custom-component.md`](./references/custom-component.md)         |
| Decide window-root / Subwindow / Stage background                          | [`references/window-background.md`](./references/window-background.md)       |
| Customize hover visuals                                                    | [`references/hover.md`](./references/hover.md)                               |
| Choose Vibrant levels, preserve fixed colors, or infer adaptive colors     | [`references/vibrant-guide.md`](./references/vibrant-guide.md)               |
| Use 3D modifiers, Augment, SpatialView, gestures, vibrant                  | [`references/spatial-capabilities.md`](./references/spatial-capabilities.md) |
| Look up the full anti-pattern table                                        | [`references/anti-patterns.md`](./references/anti-patterns.md)               |
| Look up grep-able compliance rules                                         | [`references/compliance-signals.md`](./references/compliance-signals.md)     |

## Verify (REQUIRED before reporting "done")

After producing or editing Compose code, run the verifier and fix every
`error`-level finding:

```bash
scripts/verify-design-style.sh <module-or-src-path>
```

The script enforces the rules in
[`references/compliance-signals.md`](./references/compliance-signals.md)
(R1–R7). For Figma-driven flows, also pass the same rule summary into
`d2c_verify_code` via `ruleContext` so the independent reviewer applies the
same yardstick.

## Delivery Checklist

Always emit, with the final code:

1. Built-in SpatialUI components used (names + packages).
2. Custom-component rules applied (token roles + modifiers).
3. Where `PicoTheme` is wrapped.
4. **Window / root-node background choice** — for **each** container the
   app launches (`DefaultWindowContainer`, every `WindowContainer(...)`,
   each `Augment(...)`), state which case applies:
   (a) using the system-default `Material.Regular` glass with no root
   background, (b) custom glass style after disabling the **right**
   switch (manifest for `DefaultWindowContainer`, DSL
   `enableMaterialBackground = false` for `WindowContainer/Augment`), or
   (c) explicit opaque root after disabling the same switch. Mention
   which switch was flipped and why.
5. **Custom hover handling**: confirm `Modifier.spatialHoverEffect` (never
   `hoverable + scale`).
6. `verify-design-style.sh` result (must be clean).

## Backlog

Review / update history lives in [`CHANGELOG.md`](./CHANGELOG.md).
Append new dated entries at the top using the template in that file.
