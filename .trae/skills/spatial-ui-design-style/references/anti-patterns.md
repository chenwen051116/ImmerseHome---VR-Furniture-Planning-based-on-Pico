# Anti-Patterns (Full Table)

| ❌ Anti-pattern | ✅ Correct Approach |
| --- | --- |
| `Color(0xFF1A1A1A)` | `PicoTheme.colorScheme.fillPrimary` |
| `TextStyle(fontSize = 16.sp)` | `PicoTheme.typography.bodyMedium` |
| `Modifier.alpha(0.3f)` | `Modifier.alpha(LocalDisableAlpha.current)` |
| `clickable { }` without indication | `clickable(interactionSource, LocalIndication.current) { }` |
| Using `LocalIndication.current` for Switch / Toggle state audio | `indication = null` + manually play `StateOn / StateOff` |
| Separate `MutableInteractionSource` for `controllerHapticFeedback` | Share the same `interactionSource` with `clickable` |
| Manually calling `LocalAudioEffectPlayer.playSystem(OpClick)` for click audio | Use `LocalIndication.current` — already provides `OpClick` |
| Using SpatialUI components without `PicoTheme` wrapping | Wrap once at root WindowContainer / Stage with `PicoTheme { ... }` |
| Importing `DimensionTokens` / `ColorTokens` | `@RestrictTo(LIBRARY)` internals — use `Modifier.padding(16.dp)` or component defaults |
| Inventing `VibrantColor.Darkest` or `com.pico.spatial.ui.theme.VibrantColor` | No `VibrantColor` object exists. Use `PicoTheme.colorScheme.<role>` first, or `Color.Vibrant.withVibrant(Vibrant.Darkest)` with the proper Vibrant imports |
| Inventing packages such as `com.pico.spatial.ui.design.components.*` / `com.pico.spatial.ui.design.modifiers.*` | Components live directly under `com.pico.spatial.ui.design.*`; hover lives under `com.pico.spatial.ui.foundation.hover.spatialHoverEffect` |
| Modeling slots as `leadingIcon: Painter? = null` / `ImageVector?` | Slots MUST be `(@Composable () -> Unit)? = null` |
| Calling `Button(role = Primary)` / inventing button `role` enum names like `Pass / Borderless / Danger` | `Button` has no public `role` enum. Express semantic role via `ButtonDefaults.buttonColors(containerColor = ..., contentColor = ...)`; gate destructive actions behind `AlertDialog` / `Sheet` |
| Treating `Augment` as a `design.windows.*` window root | `Augment` lives in `com.pico.spatial.ui.augment.Augment` and is a window-attached ornament, not a root window. Its DSL parameter `enableMaterialBackground` is the on/off switch (default `true`). |
| Using `Augment(offset = IntOffset3D(...), size = ..., contentAlignment = ...)` | Real signature: `Augment(anchor = NormalizedPoint3D.<...>, alignment = AugmentContentAlignment.<...>, offset = DpOffset3D(...), cornerRadius = ..., enableMaterialBackground = ..., focusable = ...)`. There is no `size` parameter; the parameter name is `alignment`, not `contentAlignment`. |
| Using `padding3D(top = ..., depth = ...)` | Real parameters are `back / front / all`. |
| Hover DSL builders `clipShape(...)` / `offset { ... }` (lambda) | Verified builders are `scale / offset(y = ...) / alpha / animation(spec) { ... }`. |
| Painting `Modifier.background(fillPrimary / Color.Black)` on the **`DefaultWindowContainer` root** while the launcher `<activity>` manifest still has `pico.spatial.windowcontainer.materialbackground="1"` | Either leave the root unbacked (let the system glass paint it), or first set the manifest flag to `"0"` and then make a documented choice |
| Painting solid color on a non-default `WindowContainer(...) { Box(Modifier.background(...)) }` while the DSL call still has the default `enableMaterialBackground = true` | Either leave the root unbacked, or pass `enableMaterialBackground = false` to the DSL call first |
| Trying to disable the glass on a non-default `WindowContainer(...)` via the manifest meta-data | The manifest meta-data only governs `DefaultWindowContainer`. Use the DSL parameter `enableMaterialBackground = false` (direct args or inside `properties = { ... }`) |
| Calling `Modifier.backgroundMaterial(enable = true, ...)` on the root **without** turning off the matching system switch (manifest for `DefaultWindowContainer`, DSL for `WindowContainer/Augment`) | Flip the right switch off first; otherwise the system glass and your custom glass stack |
| Stacking `backgroundMaterial(...) + .background(<role>)` on the same node | Pick exactly one |
| Reimplementing hover with `Modifier.hoverable + animateFloatAsState(scale)` | `Modifier.spatialHoverEffect` — highest-priority hover API |
| Using `MaterialTheme.colorScheme.*` / `MaterialTheme.typography.*` | `PicoTheme.colorScheme.*` / `PicoTheme.typography.*` |
| Listing fictitious components such as `Timepicker` (lowercase `p`), `WheelPicker`, `ProgressPageControl`, `SymbolicCircularProgressIndicator`, `HeadImageSheet`, `WindowSizeBehaviors` | Use only the components verified in `references/builtins.md`; verify with IDE auto-complete on the current SDK before adopting any other name |
| Hardcoding `Color(0xFF333333)` for hierarchy text or treating Spatial OS semantic colors as fixed literals | Use `PicoTheme.colorScheme.<role>` or `Color.Vibrant.withVibrant(...)`; preserve literal colors only when the design explicitly requires fixed color |
| Forking SpatialUI source code | Wrap from app code; file an issue / send an MR |

For machine-readable detection rules see `references/compliance-signals.md`.
