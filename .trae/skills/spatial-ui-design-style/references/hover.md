# Hover Effect (Highest-Priority Rule for Custom Hover)

For custom hover visuals on application-side composables,
**`Modifier.spatialHoverEffect` is the highest-priority API**. Never roll
your own with `Modifier.hoverable + animateFloatAsState(scale)`.

`spatialHoverEffect` only has visual effect on PICO OS and degrades to a
no-op on other platforms — no manual platform branching needed.

## 1. Preset Styles (Recommended)

Verified against `spatial-ui-ability/SKILL.md §3`:

```kotlin
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import com.pico.spatial.ui.foundation.hover.SpatialHoverStyle

Box(
    Modifier
        .size(100.dp)
        .clip(RoundedCornerShape(12.dp))
        .spatialHoverEffect()                              // default = SpatialHoverStyle.Default
        // or .spatialHoverEffect(style = SpatialHoverStyle.Highlight)
        .background(PicoTheme.colorScheme.fillPrimary)
        .clickable(...)
)
```

The presets already cover the common cases (default highlight / scale).

## 2. DSL Customization

When a preset isn't enough, customize via the DSL block. The lambda
receives a `SpatialHoverContext` (referred to as `it`) whose
`it.isActive` is true on hover. Available builders:

- `scale(factor)`
- `offset(y = ...)` (also `offset(x = ...)` / `offset(z = ...)`)
- `alpha(factor)`
- `animation(spec) { ... }` — wrap inner builders in a custom animation curve

```kotlin
Box(
    Modifier
        .size(100.dp)
        .spatialHoverEffect {
            scale(if (it.isActive) 1.1f else 1f)
            offset(y = if (it.isActive) (-4).dp else 0.dp)
            alpha(if (it.isActive) 1f else 0.8f)
        }
)
```

Custom animation curves:

```kotlin
Modifier.spatialHoverEffect {
    animation(tween(durationMillis = 250, easing = FastOutSlowInEasing)) {
        scale(if (it.isActive) 1.05f else 1f)
    }
    animation(spring(stiffness = 700f)) {
        offset(y = if (it.isActive) (-4).dp else 0.dp)
    }
}
```

> Important: keep the active and inactive branches **shape-identical**
> (same set of builders called) so the animation can interpolate between
> them. Do not invent builders such as `clipShape(...)` or
> `offset { ... }` (lambda) — they are not part of the verified DSL.

## 3. Cross-View Coordination (Hover Group)

```kotlin
import com.pico.spatial.ui.foundation.hover.SpatialHoverEffectGroup
import com.pico.spatial.ui.foundation.hover.spatialHoverEffectGroup

val group = remember { SpatialHoverEffectGroup.obtain() }
Row {
    items.forEach { item ->
        Card(
            Modifier
                .spatialHoverEffectGroup(group)
                .spatialHoverEffect()
        ) { /* content */ }
    }
}
```

## 4. Anti-Patterns

| ❌ | ✅ |
| --- | --- |
| `Modifier.hoverable(...) + animateFloatAsState(scale)` | `Modifier.spatialHoverEffect()` |
| Driving hover from your own `MutableInteractionSource.collect { Hover... }` | `spatialHoverEffect` reads system hover signal |
| Conditional `if (isPicoOs) hoverable else ...` | `spatialHoverEffect` already degrades safely |
| DSL builders `clipShape(...)` / `offset { ... }` (lambda form) | Use the verified builders `scale / offset(y = ...) / alpha / animation(spec) { ... }` |

## 5. Package

`com.pico.spatial.ui.foundation.hover.*`

- `Modifier.spatialHoverEffect`
- `SpatialHoverStyle` (presets: `Default`, `Highlight`, ...)
- `SpatialHoverEffectGroup` + `Modifier.spatialHoverEffectGroup`
- `Modifier.disableSpatialHoverEffect` (opt-out for descendants)
