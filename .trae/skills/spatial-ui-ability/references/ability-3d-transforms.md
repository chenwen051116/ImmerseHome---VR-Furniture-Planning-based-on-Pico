# 3D Transforms

Capability: `Modifier.rotate3D(...)` and `Modifier.scale3D(...)`.

## Static Rotation

```kotlin
import com.pico.spatial.ui.foundation.rotate3D
import com.pico.spatial.math.RotationAxis3D
import com.pico.spatial.math.NormalizedPoint3D

Box(
    Modifier
        .size(200.dp)
        .rotate3D(degree = 30f, axis = RotationAxis3D.Y, pivot = NormalizedPoint3D.Center)
        .background(Color.Cyan)
)
```

## Animated Rotation

```kotlin
import com.pico.spatial.math.Rotation3D

val degree by rememberInfiniteTransition().animateFloat(
    0f, 360f, infiniteRepeatable(tween(4000))
)
Box(
    Modifier
        .size(120.dp)
        .rotate3D { Rotation3D(degree, RotationAxis3D.Y) }
        .background(Color.Magenta)
)
```

## 3D Scale

```kotlin
import com.pico.spatial.ui.foundation.scale3D
import com.pico.spatial.math.Scale3D

// Uniform scaling
Box(Modifier.size(200.dp).scale3D(scale = 0.8f))

// Per-axis scaling
Box(Modifier.size(200.dp).scale3D(scaleX = 1.2f, scaleY = 0.8f, scaleZ = 1f))

// Dynamic scaling (gesture-driven)
var scale by remember { mutableFloatStateOf(1f) }
Box(
    Modifier
        .size(200.dp)
        .scale3D { Scale3D(scale, scale, scale) }
        .pointerInput(Unit) {
            detectSpatialScaleGesture(context) { scale *= it.scaleValue }
        }
)
```

## Notes

- `rotate3D` and `scale3D` create separate layers. Avoid stacking multiple redundant transforms on the same node.
- Prefer the lambda overloads (`rotate3D { }`, `scale3D { }`) for dynamic values to reduce recomposition cost.
- `scale3D` is a no-op on non-Spatial platforms. `rotate3D` always takes effect.
- `pivot` uses normalized coordinates in `[0, 1]`, not pixels.

## Imports

```kotlin
import com.pico.spatial.ui.foundation.rotate3D
import com.pico.spatial.ui.foundation.scale3D
import com.pico.spatial.math.Rotation3D
import com.pico.spatial.math.RotationAxis3D
import com.pico.spatial.math.NormalizedPoint3D
import com.pico.spatial.math.Scale3D
```

---

See also: [`troubleshooting.md`](troubleshooting.md) for cross-cutting checks (Spatial platform, `WindowContainer`, modifier order).
