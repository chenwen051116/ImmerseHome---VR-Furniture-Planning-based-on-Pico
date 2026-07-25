# Tokens Reference — Color / Typography / Motion / Locals

Application-side entry points only. Library-private tokens
(`DimensionTokens`, `ColorTokens`, ...) are `@RestrictTo(LIBRARY)` and **must
not** be imported from app code.

## 1. Color Roles (`PicoTheme.colorScheme.*`, 16 roles)

- Fill: `fillPrimary` / `fillSecondary` / `fillTertiary` / `fillLight`
- Text/icon foreground: `labelPrimary` / `labelPrimaryLight` / `labelSecondary`
  / `labelTertiary` / `labelQuaternary`
- State layers: `lightenHover` / `lightenPressed`
- Semantic colors: `error` / `alert` / `passable` / `interaction`
- Divider: `dividerLine`

```kotlin
// ✅ Correct — for business cards / inner containers
Box(Modifier.background(PicoTheme.colorScheme.fillPrimary))
Text("Hello", color = PicoTheme.colorScheme.labelPrimary)

// ❌ Wrong — hardcoded color
Box(Modifier.background(Color(0xFF1A1A1A)))

// ❌ Wrong — fillPrimary on the WINDOW ROOT while the system glass is still
//          on. For DefaultWindowContainer the switch is the launcher
//          <activity> manifest meta-data
//          `pico.spatial.windowcontainer.materialbackground` (default "1");
//          for WindowContainer(...) / Augment(...) it is the DSL parameter
//          `enableMaterialBackground` (default true). A solid color over
//          the glass kills both the glass and vibrant linkage. See
//          references/window-background.md.
```

> Scope reminder: `fillPrimary / fillSecondary / fillTertiary` are intended for
> business cards and inner containers, not the window root. The window root
> is already glass by default (see `references/window-background.md`).

## 2. Typography Roles (`PicoTheme.typography.*`)

- display: `displayLarge / Medium / Small`
- headline: `headlineLarge / Medium / Small`
- title: `titleLarge / Medium / Small`
- body: `bodyLarge / bodyMedium / bodySmall`
- label: `labelLarge / Medium / Small`

> Some SDK versions also expose multi-line / tiny variants (e.g.
> `bodyLargeMultiline`, `bodyMediumMultiline`, `bodyTiny`). Check IDE
> auto-complete on the current SDK before relying on them; the 15
> roles above are the always-present subset.

```kotlin
Text("Title", style = PicoTheme.typography.titleMedium)
```

## 3. Sizes / Spacing

- Use `Modifier.padding(16.dp)` etc. directly with business constants.
- Prefer component-provided defaults: `ButtonDefaults.Regular`, `ChipsDefaults.Small`, ...
- **Do not** import `DimensionTokens` (library-private).

## 4. Motion

`MotionTokens.*` is exposed as an `object` but should still be a last resort.
Prefer built-in components, `tween`, or `spring` first. When you explicitly
want design-system timing/easing:

- `MotionTokens.bezierEasingStandard / Decelerate / Accelerate`
- `MotionTokens.springEasingGradual.toSpring()`
- `MotionTokens.durationShort1..3`

## 5. CompositionLocals (Application-Side)

| Local                    | Purpose                                                                   | Example                                                                                           |
| ------------------------ | ------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| `LocalContentColor`      | Current content color; `Text` and `Icon` read it by default               | `CompositionLocalProvider(LocalContentColor provides PicoTheme.colorScheme.labelPrimary) { ... }` |
| `LocalDisableAlpha`      | Disabled alpha (default `0.3f`)                                           | `Modifier.alpha(if (enabled) 1f else LocalDisableAlpha.current)`                                  |
| `LocalIndication`        | Indication for `clickable`; `PicoTheme` already provides `PicoIndication` | `Modifier.clickable(interactionSource = ..., indication = LocalIndication.current) {}`            |
| `LocalAudioEffectPlayer` | System audio-effect player (mainly for custom toggle audio); name and package vary by SDK version — verify with IDE auto-complete before use | `LocalAudioEffectPlayer.current.playSystem(SpatialSoundEffect.StateOn)` (subject to SDK confirmation) |

> `ProvideContentColor` is internal. From app code use
> `CompositionLocalProvider(LocalContentColor provides ...)`.

## 6. Package Lookup

| Topic                   | Package / Key Types |
| ----------------------- | ------------------- |
| Theme entry             | `com.pico.spatial.ui.design.PicoTheme`, `ColorScheme`, `Typography`, `systemColorScheme(Context)` |
| Composition Locals      | `com.pico.spatial.ui.design.LocalContentColor`, `LocalDisableAlpha`; Compose `LocalIndication`; `com.pico.spatial.ui.platform.LocalAudioEffectPlayer` |
| Built-in components     | `com.pico.spatial.ui.design.*` |
| Windows / overlays      | `com.pico.spatial.ui.design.windows.*` |
| Menus                   | `com.pico.spatial.ui.design.menu.*` |
| Hover effects           | `com.pico.spatial.ui.foundation.hover.*` |
| Haptics                 | `com.pico.spatial.ui.foundation.haptic.*` |
| App DSL                 | `com.pico.spatial.ui.foundation.dsl.*` |
| 3D content              | `com.pico.spatial.ui.foundation.content.*` |
| 3D modifiers / geometry | `com.pico.spatial.ui.foundation.layout.*` (depth, padding3D, alignDepth, Box3D, layout3D); `com.pico.spatial.ui.foundation.geometry.*` (DpOffset3D, IntOffset3D, Rotation3D, Scale3D, NormalizedPoint3D) |
| Vibrant / materials     | `com.pico.spatial.ui.foundation.vibrant.*` (vibrantEffect, withVibrant, animateColorVibrantAsState); `com.pico.spatial.ui.foundation.material.backgroundMaterial`; `com.pico.spatial.ui.platform.Material` (None / Regular / Thick / Thickest / Thin) |
| Window-attached ornament | `com.pico.spatial.ui.augment.Augment` (NOT in `design.windows.*`) |
| Spatial gestures        | `com.pico.spatial.ui.foundation.gesture.*` |

## 7. Color-Scheme Mechanics and Decision Rules

`PicoTheme { ... }` defaults to `systemColorScheme(LocalContext.current)`, so
application code normally does not need to pass a custom color scheme. Internally
the system color scheme relies on Vibrant-aware defaults; app code should treat
`PicoTheme.colorScheme` as the official semantic API, not import private token
objects.

### Two color families

- **Adaptive grayscale hierarchy roles** (`label*`, `fill*`, `lightenHover`,
  `lightenPressed`) are already mapped to Vibrant levels by the SDK. Example
  equivalences: `labelPrimary` behaves like `Vibrant.Darkest`; `fillPrimary`
  behaves like a `Darker`-style fill; `fillTertiary` behaves like `Neutral`.
- **Fixed semantic colored roles** (`error`, `alert`, `passable`,
  `interaction`, `dividerLine`, and `labelPrimaryLight`) are SDK-side pure
  semantic colors and should be treated as already protected from unwanted
  Vibrant blending.

### Generation rules

- If a Figma token name is a standard role (`Label Primary`, `Fill Tertiary`,
  `Error`, `Divider Line`), map the token name to `PicoTheme.colorScheme.xxx`
  before considering the raw swatch value.
- Do not hardcode adaptive hierarchy colors such as gray text values. Use the
  matching `PicoTheme.colorScheme.label*` role or `Color.Vibrant.withVibrant(...)`.
- Do not wrap stock semantic roles again, e.g. avoid
  `PicoTheme.colorScheme.error.withVibrant(Vibrant.None)`; use
  `PicoTheme.colorScheme.error` directly.
- If the design intentionally uses a translucent semantic color such as
  `Color(0xCCDDFF99)`, preserve the alpha with
  `Color(0xCCDDFF99).withVibrant(Vibrant.None)` instead of replacing it with an
  opaque stock role like `PicoTheme.colorScheme.passable`.
- For bright foreground on dark filled surfaces, prefer
  `PicoTheme.colorScheme.labelPrimaryLight` over `Color.White`.
