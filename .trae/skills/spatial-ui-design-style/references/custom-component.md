# Custom Component Patterns

When no built-in component fits, follow these rules so call sites stay
consistent with SpatialUI built-ins (`Button`, `Switch`, `Card`, ...).

## 1. Parameter Ordering

```
1. Required business params      — no defaults
   |- state / data               : checked, value, text, items, selected ...
   \- callbacks                  : onClick, onCheckedChange, onValueChange ...
2. modifier                      — immediately after required params, default Modifier
3. enabled                       — Boolean = true (when supported)
4. Visual / semantic optionals   — colors / shape / elevation / contentPadding / textStyle / border
                                   (semantic first, then visual; outer→inner: shape/border → container color
                                    → content color → spacing)
5. Slot composables              — leadingIcon / trailingIcon / label / supportingText / header ...
                                   🔴 MUST be `(@Composable () -> Unit)? = null`
                                       — NOT `Painter? / ImageVector? / Bitmap?`
6. interactionSource             — default `remember { MutableInteractionSource() }`
7. content / main slot           — last, so trailing-lambda call site works
```

Shortcut: **required → modifier → enabled → optional visuals → slots → interactionSource → content**.

### Three Signature Patterns

**A. Standard clickable (Button / Card / ListItem)**

```kotlin
@Composable
fun MyCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    colors: MyCardColors = MyCardDefaults.colors(),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    leadingIcon: (@Composable () -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit,
) { ... }
```

**B. State-switching (Switch / Checkbox / Toggle)**

```kotlin
@Composable
fun MyToggle(
    checked: Boolean,                          // value first
    onCheckedChange: (Boolean) -> Unit,        // then callback
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: MyToggleColors = MyToggleDefaults.colors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) { ... }
```

**C. Pure display (Image / Text)**

```kotlin
@Composable
fun MyAvatar(
    user: User,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    placeholder: Painter? = null,   // raw type ok in pure data field
) { ... }
```

### Common Bad Orders

| Anti-pattern | Problem |
| --- | --- |
| `MyCard(modifier, onClick, ...)` | `modifier` before required args |
| `MyCard(onClick, content, modifier)` | `content` not last → trailing lambda broken |
| `MyCard(onClick, interactionSource, modifier, ...)` | `interactionSource` front-loaded |
| `MyCard(onClick, modifier, content, enabled)` | optionals after `content` |
| `MyToggle(onCheckedChange, checked, ...)` | callback before value |
| `leadingIcon: Painter? = null` | slots must be `(@Composable () -> Unit)?` |

## 2. Modifier Chain Order

Use this order for custom components so layout, hit area, hover shape, and
decoration stay predictable:

```text
outer layout / size -> shape -> hover -> interaction -> decoration -> inner padding
```

Example:

```kotlin
Modifier
    .fillMaxWidth()                         // outer layout first
    .clip(RoundedCornerShape(12.dp))        // shape before hover/click/background
    .spatialHoverEffect(enabled = enabled)
    .clickable { onClick() }
    .background(Color.Vibrant.withVibrant(Vibrant.Neutral))
    .padding(16.dp)                         // content padding last
```

Rules:

- Put layout constraints (`fillMaxWidth`, `fillMaxSize`, `width`, `height`,
  `size`, `defaultMinSize`) toward the front of the chain.
- Put drawing decoration (`background`, `border`, `backgroundMaterial`) after
  size / shape / interaction and before inner content padding.
- `Modifier.size(100.dp).padding(16.dp)` means a 100dp visual box with inset
  content; `Modifier.padding(16.dp).size(100.dp)` changes outer layout and often
  produces the wrong background / hit area.
- Apply `spatialHoverEffect` before `clickable`; otherwise hover may not follow
  the clipped shape correctly.
- Do not stack `backgroundMaterial(...)` on top of a fully opaque background.

### Exposed `modifier` parameter red line

If a component exposes `modifier: Modifier = Modifier`, do **not** append fixed
size constraints after the incoming modifier. That prevents callers from
overriding layout.

```kotlin
// Wrong: caller-provided height may be clamped by the internal fixed height.
@Composable
fun BannerCard(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(172.dp)
            .clip(RoundedCornerShape(18.dp))
    )
}

// Correct: defaults first, caller override last.
@Composable
fun BannerCard(modifier: Modifier = Modifier) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(172.dp)
            .clip(RoundedCornerShape(18.dp))
            .then(modifier)
    )
}

// Also correct: defaultMinSize describes a default, not an immutable size.
@Composable
fun BannerCard(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 172.dp)
            .clip(RoundedCornerShape(18.dp))
    )
}
```

Use `then(...)` for conditional modifiers instead of branching the whole chain:

```kotlin
Modifier
    .padding(16.dp)
    .then(
        if (selected) Modifier.background(Color.Vibrant.withVibrant(Vibrant.SemiLight))
        else Modifier
    )
```

For frequently changing alpha / scale / rotation, prefer `graphicsLayer` so
visual changes do not mutate layout-type modifiers every frame.

```kotlin
Modifier.graphicsLayer {
    alpha = if (enabled) 1f else LocalDisableAlpha.current
    scaleX = scale
    scaleY = scale
}
```

## 3. Clickable + Indication + Haptics

```kotlin
val interactionSource = remember { MutableInteractionSource() }
Box(
    Modifier
        .clip(RoundedCornerShape(12.dp))
        .spatialHoverEffect(enabled = enabled)
        .background(PicoTheme.colorScheme.fillPrimary)
        .clickable(
            enabled = enabled,
            interactionSource = interactionSource,
            indication = LocalIndication.current,    // built-in press + click audio
            onClick = onClick,
        )
        .controllerHapticFeedback(interactionSource = interactionSource)
        .padding(16.dp)
) { content() }
```

Rules:
- `clickable` / `toggleable` / `selectable` MUST pass
  `indication = LocalIndication.current`. Omitting or `null` loses
  hover + press + click audio.
- `controllerHapticFeedback` MUST share the same `interactionSource` as
  `clickable`.
- `spatialHoverEffect` is a no-op off PICO OS — no platform branching needed.

## 4. Audio for Stateful (Switch / Toggle) Components

When the click is a state toggle, play `StateOn / StateOff` rather than
`OpClick`. Trigger the sound from the **new** state, not the captured
press, so the user always hears the transition target. (The exact
package and member names of `LocalAudioEffectPlayer` / `SpatialSoundEffect`
vary by SDK version — verify with IDE auto-complete before adopting.)

```kotlin
val interactionSource = remember { MutableInteractionSource() }
val audioEffectPlayer = LocalAudioEffectPlayer.current
var lastChecked by remember { mutableStateOf(checked) }

// Fire on the new state — `checked` already reflects the new value here.
LaunchedEffect(checked) {
    if (checked != lastChecked) {
        val effect = if (checked) SpatialSoundEffect.StateOn else SpatialSoundEffect.StateOff
        audioEffectPlayer.playSystem(effect)
        lastChecked = checked
    }
}

Box(
    Modifier.toggleable(
        value = checked,
        interactionSource = interactionSource,
        indication = null,                  // disable PicoIndication's default OpClick
        onValueChange = onCheckedChange,
    )
)
```

## 5. Disabled State

```kotlin
val alpha by rememberUpdatedState(if (enabled) 1f else LocalDisableAlpha.current)
Box(Modifier.alpha(alpha)) { ... }
```

Never hardcode `0.3f`.

## 6. Child Content Color

```kotlin
CompositionLocalProvider(LocalContentColor provides PicoTheme.colorScheme.labelPrimary) {
    Text("Auto colored")
    Icon(painter, contentDescription = null)
}
```

## 7. Advanced Hover Visuals

See `references/hover.md`.
