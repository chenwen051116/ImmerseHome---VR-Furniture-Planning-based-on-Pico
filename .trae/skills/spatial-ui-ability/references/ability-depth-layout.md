# Depth Layout

Capability: `Modifier.depth` / `depthIn` / `requiredDepth` / `Box3D` / `padding3D` / `alignDepth` / `layout3D`.

## Declare Depth

```kotlin
import com.pico.spatial.ui.foundation.layout.depth
import com.pico.spatial.ui.foundation.layout.depthIn
import com.pico.spatial.ui.foundation.layout.requiredDepth

Box(Modifier.size(100.dp).depth(20.dp))           // preferred thickness = 20dp
Box(Modifier.size(100.dp).depthIn(10.dp, 50.dp))  // constrained thickness range
Box(Modifier.size(100.dp).requiredDepth(30.dp))   // ignore parent depth constraints
```

## Box3D: Z-Stacking Container

```kotlin
import com.pico.spatial.ui.foundation.layout.Box3D
import com.pico.spatial.ui.foundation.layout.DepthAlignment

Box3D(depthAlignment = DepthAlignment.DepthCenter) {
    Box(Modifier.size(100.dp).depth(10.dp).background(Color.Blue))
    Box(Modifier.size(80.dp).depth(10.dp).background(Color.Green))
    Box(Modifier.size(60.dp).depth(10.dp).background(Color.Red))
}
// The three layers are stacked along the Z axis. Total thickness = 30dp.
```

## 3D Padding

```kotlin
import com.pico.spatial.ui.foundation.layout.padding3D

Box(Modifier.padding3D(back = 10.dp, front = 10.dp)) {
    // Reserve 10dp space on the front and back along the Z axis.
}
Box(Modifier.padding3D(all = 8.dp)) {
    // Reserve 8dp on all six sides.
}
```

## Depth Alignment

```kotlin
import com.pico.spatial.ui.foundation.layout.alignDepth

Box(
    Modifier
        .size(100.dp)
        .requiredDepth(100.dp)
        .alignDepth(DepthAlignment.DepthFront)  // place content toward the user
)
```

## Custom 3D Measurement

```kotlin
import com.pico.spatial.ui.foundation.layout.layout3D

Modifier.layout3D { measurable, constraints3d ->
    val placeable = measurable.measure(constraints3d)
    layout(placeable.width, placeable.height, placeable.depth) {
        placeable.place3D(0, 0, 0)
    }
}
```

## Notes

- `Box3D` stacks multiple children **cumulatively** on the Z axis; it is not a painter-style overlap container.
- `depth` changes measurement and thickness. `zOffset` only changes drawing position.
- These APIs only work when an ancestor participates in `Constraints3D`.

## Imports

```kotlin
import com.pico.spatial.ui.foundation.layout.depth
import com.pico.spatial.ui.foundation.layout.depthIn
import com.pico.spatial.ui.foundation.layout.requiredDepth
import com.pico.spatial.ui.foundation.layout.Box3D
import com.pico.spatial.ui.foundation.layout.DepthAlignment
import com.pico.spatial.ui.foundation.layout.padding3D
import com.pico.spatial.ui.foundation.layout.alignDepth
import com.pico.spatial.ui.foundation.layout.layout3D
```

---

See also: [`troubleshooting.md`](troubleshooting.md) for cross-cutting checks (Spatial platform, `WindowContainer`, modifier order).
