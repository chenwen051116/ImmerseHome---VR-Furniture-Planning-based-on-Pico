# Glass Material Backgrounds

Capability: `Modifier.backgroundMaterial(style)` for system-rendered glass-like surfaces.

## Shortest Usage

```kotlin
import com.pico.spatial.ui.foundation.backgroundMaterial
import com.pico.spatial.ui.foundation.Material

Box(Modifier.size(150.dp).backgroundMaterial())  // Material.Regular
```

## Explicit Styles

```kotlin
Box(Modifier.size(150.dp).backgroundMaterial(style = Material.Thick))     // dialog-grade
Box(Modifier.size(150.dp).backgroundMaterial(style = Material.Thickest))  // tooltip-grade
Box(Modifier.size(150.dp).backgroundMaterial(style = Material.Thin))      // subtle floating effect (view-level only)
```

## Rounded Corners + Material

```kotlin
Box(
    Modifier
        .size(150.dp)
        .background(Color.Transparent, RoundedCornerShape(16.dp))
        .backgroundMaterial(style = Material.Thickest)
)
```

## Combine With Other Spatial Modifiers

```kotlin
Box(
    Modifier
        .size(150.dp)
        .zOffset { 12f }
        .rotate3D { Rotation3D(15f, RotationAxis3D.Y) }
        .backgroundMaterial(style = Material.Thick)
        .spatialHoverEffect()
)
```

## Notes

- `backgroundMaterial` is rendered by the PICO OS compositor in the system process. It becomes a no-op on non-Spatial platforms.
- The material consumes 1px of depth, which reduces the remaining max depth for children.
- `Material.Thin` does not work for windows; it only applies at the view level.
- Recommended modifier order: `size` → `border/background` → `backgroundMaterial` → `spatialHoverEffect`.

## Material Not Showing? Check This

1. Are you running on a Spatial platform (PICO OS simulator or device)?
2. Is the content inside a `WindowContainer`?
3. Is the background color transparent or partially transparent as required?
4. Is another control covering the view?
5. Are you using `Material.Thin` on a window? Switch to `Regular`, `Thick`, or `Thickest`.

## Imports

```kotlin
import com.pico.spatial.ui.foundation.backgroundMaterial
import com.pico.spatial.ui.foundation.Material
```

---

See also: [`troubleshooting.md`](troubleshooting.md) for cross-cutting checks (Spatial platform, `WindowContainer`, modifier order).
