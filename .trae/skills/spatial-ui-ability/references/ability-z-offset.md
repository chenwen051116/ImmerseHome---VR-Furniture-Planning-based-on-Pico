# Z-Axis Offsets

Capability: floating / sinking nodes via `Modifier.offset(z = ...)` (static) and `Modifier.zOffset { ... }` (dynamic).

## Static Offset

```kotlin
import androidx.compose.foundation.layout.offset

Box(
    Modifier
        .offset(z = 10.dp)
        .size(100.dp)
        .background(Color.Red)
)
```

## Dynamic Offset (Animation Or Gesture Driven)

```kotlin
import com.pico.spatial.ui.foundation.zOffset

var isFloating by remember { mutableStateOf(false) }
val offsetZ by animateDpAsState(if (isFloating) 100.dp else 0.dp)

Box(
    Modifier
        .zOffset { offsetZ.toPx() }
        .size(100.dp)
        .background(Color.Black)
        .clickable { isFloating = !isFloating }
)
```

## Notes

- Positive values move toward the user. Negative values move away.
- Use `offset(z)` for fixed values and `zOffset { ... }` for frequently changing values to reduce recomposition pressure.
- These APIs do not change measured size or layout occupancy. For thickness changes use `Modifier.depth` instead.

## Imports

```kotlin
import androidx.compose.foundation.layout.offset  // for offset(z = ...)
import com.pico.spatial.ui.foundation.zOffset     // for zOffset { ... }
```

---

See also: [`troubleshooting.md`](troubleshooting.md) for cross-cutting checks (Spatial platform, `WindowContainer`, modifier order).
