# Window Constraints

Capability: `Modifier.windowConstraints` for `WindowContainer` / `DefaultWindowContainer` resize control.

## Range Constraints

```kotlin
import com.pico.spatial.ui.WindowContainer
import com.pico.spatial.ui.ContainerResizeType
import com.pico.spatial.ui.foundation.windowConstraints

WindowContainer(id = "main", resizeType = ContainerResizeType.ContentSize) {
    Box(
        modifier = Modifier.windowConstraints(
            minWidth = 500.dp, minHeight = 500.dp,
            maxWidth = 1500.dp, maxHeight = 1500.dp,
        )
    ) { /* content */ }
}
```

## Fixed Size (ContentSize Mode Only)

```kotlin
WindowContainer(id = "main", resizeType = ContainerResizeType.ContentSize) {
    Box(
        modifier = Modifier.windowConstraints(width = 1280.dp, height = 720.dp)
    ) { /* content */ }
}
```

## DefaultWindowContainer Usage

```kotlin
import com.pico.spatial.ui.DefaultWindowContainer

// manifest: pico.spatial.windowcontainer.resizetype = 2 (ContentSize)
DefaultWindowContainer {
    HomePage(Modifier.windowConstraints(width = 1280.dp, height = 720.dp))
}
```

## Notes

- `windowConstraints` only works on the **direct root child** of `WindowContainer` or `DefaultWindowContainer`.
- The fixed-size form only applies when `resizeType` is `ContainerResizeType.ContentSize`.
- Final window size is still clamped by system boundaries through `coerceIn`.

## Window Constraints Not Working? Check This

1. Is the modifier attached to the **direct child** of `WindowContainer` / `DefaultWindowContainer`?
2. Is `resizeType` set to `ContainerResizeType.ContentSize`?
3. Is `pico.spatial.windowcontainer.resizetype` configured correctly in the manifest? (`0 = Disabled`, `1 = User`, `2 = ContentSize`)
4. Is another modifier such as `fillMaxSize()` overriding the constraint result?

## Imports

```kotlin
import com.pico.spatial.ui.WindowContainer
import com.pico.spatial.ui.DefaultWindowContainer
import com.pico.spatial.ui.ContainerResizeType
import com.pico.spatial.ui.foundation.windowConstraints
```

---

See also: [`troubleshooting.md`](troubleshooting.md) for cross-cutting checks (Spatial platform, `WindowContainer`, modifier order).
