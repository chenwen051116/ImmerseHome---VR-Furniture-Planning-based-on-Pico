# Augment / TabBar / Toolbar / Subwindow

Capability: companion windows attached to a `WindowContainer` via `Augment(...)`, `TabBar`, `Toolbar`, and `Subwindow`.

## Custom Augment

```kotlin
import com.pico.spatial.ui.augment.Augment
import com.pico.spatial.ui.augment.AugmentContentAlignment
import com.pico.spatial.math.NormalizedPoint3D
import com.pico.spatial.math.DpOffset3D

Augment(
    anchor = NormalizedPoint3D.TopFront,
    alignment = AugmentContentAlignment.BottomCenter,
    offset = DpOffset3D(0.dp, (-16).dp, 0.dp),  // lift upward by 16dp
    cornerRadius = 16.dp,
) {
    Row(Modifier.height(64.dp).padding(horizontal = 16.dp)) {
        Text("Floating Header")
    }
}
```

## TabBar

```kotlin
import com.pico.spatial.ui.augment.TabBar

TabBar {
    item(selected = current == 0, onClick = { current = 0 }, mainContent = { Text("Home") })
    item(selected = current == 1, onClick = { current = 1 }, mainContent = { Text("Library") })
}
```

## Toolbar

```kotlin
import com.pico.spatial.ui.augment.Toolbar

Toolbar {
    IconButton(onClick = ::play) { Icon(Icons.Default.PlayArrow, null) }
    IconButton(onClick = ::pause) { Icon(Icons.Default.Pause, null) }
}
```

## Subwindow (Side Panel)

```kotlin
import com.pico.spatial.ui.augment.Subwindow
import com.pico.spatial.ui.augment.SubwindowPlacement

Subwindow(placement = SubwindowPlacement.Right) {
    LazyColumn(Modifier.fillMaxHeight().width(360.dp)) {
        // side panel content
    }
}
```

## Non-Focusable, No-Material Widget

```kotlin
Augment(
    anchor = NormalizedPoint3D.BottomFront,
    alignment = AugmentContentAlignment.TopCenter,
    offset = DpOffset3D(0.dp, 16.dp, 0.dp),
    enableMaterialBackground = false,
    focusable = false,
) { /* display-only content */ }
```

## Notes

- These APIs must be used inside a `WindowContainer`; otherwise nothing is rendered.
- Every `Augment` is a real system window. Do not create one per list item.
- Real device placement is controlled by the system. The simulator only provides an approximation.
- `focusable = false` means the content cannot receive IME input.

## Augment Not Showing? Check This

1. Is it inside a `WindowContainer`?
2. Are you running on a Spatial platform?
3. Is another window covering it?
4. Are `anchor` and `alignment` configured correctly?
5. Are you creating too many Augments? Each one is a real window with system limits.

## Imports

```kotlin
import com.pico.spatial.ui.augment.Augment
import com.pico.spatial.ui.augment.AugmentContentAlignment
import com.pico.spatial.ui.augment.TabBar
import com.pico.spatial.ui.augment.Toolbar
import com.pico.spatial.ui.augment.Subwindow
import com.pico.spatial.ui.augment.SubwindowPlacement
import com.pico.spatial.math.NormalizedPoint3D
import com.pico.spatial.math.DpOffset3D
```

---

See also: [`troubleshooting.md`](troubleshooting.md) for cross-cutting checks (Spatial platform, `WindowContainer`, modifier order).
