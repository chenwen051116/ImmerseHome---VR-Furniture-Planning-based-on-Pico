---
title: SpatialUI Window-Level Components, Subwindows, and Floating-Layer Guide
audience: anything-to-spatial-app / Phase 4 (window model) + Phase 6 (build)
trigger: any decision involving Subwindow / TabBar / Toolbar / AlertDialog / Sheet / SpatialPopup / Menu / SnackbarHost / CoachmarkBox / Augment
migrated_from: legacy-d2c-reference/spatialui-sub-windows-guide.md (Phase B1.3)
---

# SpatialUI Window-Level Components, Subwindows, and Floating-Layer Guide

Use this guide for **window-level spatial structure**: `TabBar`, `Toolbar`, independent floating windows, dialogs, panels, menus, transient feedback layers, and coachmarks. Non-window abilities such as `Tooltip` and `backgroundMaterial` live in `spatial-ui-design-style/references/spatial-capabilities.md`.

## 1. Boundaries and Spatial Red Lines

- **Do not fake window hierarchy with plain layout**. Never use `Row` / `Column` to approximate a detached detail window or floating system chrome.
- **Window-level components must be top-level siblings** under `PicoTheme` or the container root.
- **Do not add manual avoidance padding** such as `padding(start = 120.dp)` just to make room for a floating panel or edge navigation.
- **`Subwindow` owns its shell, size, and material**. Do not wrap its content root in another fixed-size material shell.
- **Default visibility must match the first visible frame**. Do not keep `showSubwindow`, `showSheet`, `showDialog`, or `showMenu` permanently true just because the showcase screenshot displays the expanded state.
- **Overlay state should usually be `rememberSaveable`**.

> Unified rule: whenever you use `design.windows.*` components, or `Menu` as a window-level floating layer, follow this guide first rather than scattering knowledge across other references.

---

## 1.1 Window-level material vs view-level material

From `SpatialDialogAsPopup.kt`:

- glass on subwindows, dialogs, and popups is **window-level material**, passed through `SpatialWindowProperties.material`
- it is not implemented by wrapping the content root with `Modifier.backgroundMaterial(...)`
- fallback drawing paths only exist for compatibility and should not become the app-level default approach

**Conclusion**: if the design calls for a frosted subwindow shell, use the window component's material semantics rather than painting a fake shell on the content.

---

## 1.2 `Augment`: the base model for window-attached ornaments

From `SpatialSDK/spatialui/foundation/src/main/kotlin/com/pico/spatial/ui/foundation/window/Augments.kt`:

- `Augment` is a **window-attached spatial container**
- its positioning model is built around:
  - `anchor`: normalized attachment point on the `WindowContainer`
  - `alignment`: which point of the Augment content aligns to the anchor
  - `offset`: spatial offset after alignment
- it also supports `followViewpoints`, `rotation3D`, `cornerRadius`, `enableMaterialBackground`, and `focusable`
- high-level components such as `TabBar`, `Toolbar`, and `Subwindow` are conceptually closer to the Augment family than to plain 2D layout
- it only works in a `WindowContainer` context

> **D2C conclusion**: if a design shows an independent ornament attached around the main window edge rather than inside the content tree, the underlying model is usually Augment-family semantics.

---

## 1.3 Overview of window-level component families

| Component | Family | Typical Material / Layering | Better For | Not For |
|----------|--------|------------------------------|-----------|---------|
| `Subwindow` | attached side window | `Material.Regular`, independent side attachment | long-lived detail side panels, auxiliary workspaces | light hints and menus |
| `Sheet` / `HeadImageSheet` / `BasicSheet` | modal popup panel | `Material.Thick` | prompts, warnings, explanations that need more content or bottom actions | long-lived sidebars |
| `AlertDialog` | blocking modal dialog | `Material.Thick`, `isModal = true` | warning, confirmation, short explanation | long structured content |
| `DatePickerDialog` | specialized picker window | `Material.Thickest` | date / range selection | general-purpose modal content |
| `SpatialPopup` | lightweight anchored floating layer | `Material.Thick` | small contextual action panels | complex forms |
| `CoachmarkBox` / coachmarks | instructional overlay | anchored guidance layer | feature education and tutorials | long-term business content |
| `SnackbarHost` | transient feedback host | `Material.Thickest` or `None` | short feedback, toasts, snackbars | persistent warnings |
| `TabBar` | top/side/bottom window ornament | `Material.None`, `SpatialWindowType.Tabbar` | global page navigation | business panels |
| `Toolbar` | bottom tool ornament | ornament-style material semantics | short grouped actions | primary content containers |

### 1.3.1 Four responsibility groups

- **Attached content windows**: `Subwindow`
- **Prompt / warning modal panels**: `Sheet`, `HeadImageSheet`, `BasicSheet`, `AlertDialog`, `DatePickerDialog`
- **Prompt / floating feedback layers**: `SpatialPopup`, `CoachmarkBox`, `SnackbarHost`
- **Window chrome ornaments**: `TabBar`, `Toolbar`

### 1.3.2 `Augment` vs popup/modal families

- `Augment` is a **foundation-level ornament model**
- `TabBar`, `Toolbar`, and `Subwindow` are conceptually closer to Augment-style attached structures
- `AlertDialog`, `Sheet`, `DatePickerDialog`, `SnackbarHost`, and `CoachmarkBox` are closer to popup / modal / teaching-layer semantics

A reliable D2C question is:

- is this attached **around** the main window as an ornament?
- or does it **float out from** the main window as a popup / modal layer?

---

## 1.4 Quick component choice rules

- long-lived side detail panel next to the main window -> `Subwindow`
- prompt / warning popup that needs more explanation or richer bottom actions -> `Sheet`
- short confirm / cancel / destructive warning -> `AlertDialog`
- dedicated date-selection flow -> `DatePickerDialog`
- small anchored floating layer -> `SpatialPopup`
- teaching hint or guidance bubble -> `CoachmarkBox`
- transient bottom feedback -> `SnackbarHost`
- top / left / right / bottom page-switching ornament -> `TabBar`
- bottom grouped actions -> `Toolbar`

### 1.5 Source-level pitfalls

- `AlertDialog`, `DatePickerDialog`, and `Sheet` are not Web-style "outside click closes everything" layers by default; the closing state should still be modeled explicitly.
- `Subwindow` material is window-level. Do not stack `backgroundMaterial` on the content root.
- `SpatialPopup` comes with minimum sizing behavior and behaves more like a contextual panel than a pure `wrapContent` tooltip.
- `CoachmarkBox` is a combination of target + guidance layer, not an arbitrary generic popup container.
- `SnackbarHost` is a host + state pattern, not a one-off standalone Snackbar composable.
- `TabBar` and `Toolbar` belong to window-level ornament semantics and must stay at the root, not inside the page content flow.
- High-level business code should prefer `TabBar`, `Toolbar`, or `Subwindow` over naked `Augment` unless the design-system component truly does not exist.

---

## 2. `Subwindow`

```kotlin
import com.pico.spatial.ui.design.windows.Subwindow

Subwindow {
    Column(modifier = Modifier.fillMaxSize()) { /* content */ }

    IconButton(
        onClick = { onClose() },
        modifier = Modifier.padding(start = 24.dp, top = 24.dp),
        colors = ButtonDefaults.buttonColors(
            contentColor = Color.Vibrant.withVibrant(Vibrant.Darkest),
            containerColor = Color.Vibrant.withVibrant(Vibrant.Neutral)
        )
    ) {
        Icon(painter = painterResource(R.drawable.cancel), contentDescription = null)
    }
}
```

> **Warning**: `Subwindow` is an independent floating window, not a plain `Box`. The content, close action, and internal scrolling are your responsibility, but the shell, material, and spatial attachment are owned by the component itself.

> **Material interpretation**: if a subwindow uses glass, treat that as **window-level material**, not as a signal to add `backgroundMaterial` manually to the content root.

> **Source implication**: `Subwindow` behaves like a side-attached auxiliary window, with a default width around `360.dp`, height driven by the container, and `Material.Regular` as the shell material.

---

## 3. `AlertDialog`

```kotlin
import com.pico.spatial.ui.design.windows.AlertDialog

AlertDialog(
    onDismissRequest = { onDismiss() },
    title = { Text("Title", style = PicoTheme.typography.headlineLarge, color = Color.Vibrant.withVibrant(Vibrant.Darkest)) },
    content = { Text("Body", style = PicoTheme.typography.bodyMedium, color = Color.Vibrant.withVibrant(Vibrant.UltraDark)) },
    buttons = {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = { onDismiss() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Vibrant.withVibrant(Vibrant.Neutral),
                    contentColor = Color.Vibrant.withVibrant(Vibrant.Darkest)
                )
            ) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onConfirm() }) { Text("Confirm") }
        }
    }
)
```

> **Warning**: SpatialUI `AlertDialog` comes from `design.windows.AlertDialog`, not `androidx.compose.material3.AlertDialog`. Its API uses `buttons`, not `confirmButton` / `dismissButton`.

> **Source implication**: this is a blocking modal window with `Material.Thick` and `isModal = true`. Use it for short warning, confirmation, and interruption flows. If the prompt or warning needs more explanation area or a richer bottom action zone, switch to `Sheet`.

---

## 3.1 `DatePickerDialog`

```kotlin
import com.pico.spatial.ui.design.windows.DatePickerDialog

DatePickerDialog(
    title = { Text("Select Date") },
    positiveButton = { Button(onClick = onConfirm) { Text("Confirm") } },
    negativeButton = { Button(onClick = onDismiss) { Text("Cancel") } },
    onDismissRequest = onDismiss,
) {
    DatePicker(state = rememberDatePickerState())
}
```

> `DatePickerDialog` is a specialized date-selection window. Do not use it as a generic dialog shell.

---

## 4. `Sheet`

```kotlin
import com.pico.spatial.ui.design.windows.Sheet

Sheet(
    leadingAction = null,
    onDismissRequest = { onDismiss() },
    title = { Text("Title") },
    bottom = {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = { onDismiss() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Vibrant.withVibrant(Vibrant.Neutral),
                    contentColor = Color.Vibrant.withVibrant(Vibrant.Darkest)
                )
            ) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onConfirm() }) { Text("Confirm") }
        }
    }
) {
    // content
}
```

> `Sheet` is a modal popup panel. Prefer it for prompt and warning flows that need more explanatory content, stronger visual grouping, or a dedicated bottom action area. If the component already provides the modal structure, do not wrap its content in another heavy shell.

> `BasicSheet`, `Sheet`, and `HeadImageSheet` belong to the same family. Use them before falling back to hand-built modal containers.

---

## 5. `CoachmarkBox`

```kotlin
import com.pico.spatial.ui.design.windows.CoachmarkBox
import com.pico.spatial.ui.design.windows.CoachmarkDefaults
import com.pico.spatial.ui.design.windows.CoachmarkDirection
import com.pico.spatial.ui.design.windows.RichCoachmark

CoachmarkBox(
    showCoachmark = showCoachMark,
    direction = CoachmarkDirection.Below,
    coachmark = {
        RichCoachmark(
            title = { Text("Guide Title") },
            content = { Text("Guide content", style = PicoTheme.typography.bodyLarge) },
            image = { Image(painter = ...) },
            buttons = {
                CoachmarkDefaults.CoachmarkButton(onClick = { showCoachMark = false }) {
                    Text("Got it", style = PicoTheme.typography.labelMedium)
                }
            }
        )
    }
) {
    // target component
}
```

> `CoachmarkBox` must wrap the **actual target component**, not unrelated outer structure such as a whole page background or a `TabBar` shell.

Common mistakes:

- forgetting `showCoachmark`
- wrapping an oversized outer layout, which makes the coachmark anchor calculation meaningless

---

## 6. `SnackbarHost`

```kotlin
import com.pico.spatial.ui.design.windows.SnackbarHost
import com.pico.spatial.ui.design.windows.LocalSnackbarHostState

SnackbarHost {
    val snackState = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()

    Button(onClick = {
        scope.launch {
            snackState.show(message = "Hint", leadingIcon = { CircularProgressIndicator() })
        }
    }) { Text("Show Snackbar") }
}
```

> `SnackbarHost` wraps page content and exposes a host state via `LocalSnackbarHostState.current`.

---

## 7. `Menu` + `MenuItem`

```kotlin
import com.pico.spatial.ui.design.menu.Menu
import com.pico.spatial.ui.design.menu.MenuItem
import com.pico.spatial.ui.design.menu.rememberMenuPositionProvider
import com.pico.spatial.ui.design.windows.popup.HorizontalPlacement
import com.pico.spatial.ui.design.windows.popup.VerticalPlacement

var showMenu by rememberSaveable { mutableStateOf(false) }

IconButton(onClick = { showMenu = !showMenu }) { Icon(...) }

if (showMenu) {
    Menu(
        positionProvider = rememberMenuPositionProvider(
            horizontalPlacement = HorizontalPlacement.alignStart(),
            verticalPlacement = VerticalPlacement.above(offset = (-24).dp)
        ),
        onDismissRequest = { showMenu = false }
    ) {
        MenuItem(title = { Text("Menu Item") }, onClick = { }, leadingIcon = { Icon(...) })
        Spacer(modifier = Modifier.height(8.dp))
        Divider(modifier = Modifier.height(1.dp), color = Color(0x8080803D))
        Spacer(modifier = Modifier.height(8.dp))
        MenuItem(title = { Text("Another Item") }, onClick = { })
    }
}
```

> `Menu` comes from `design.menu.*`, not `design.windows.*`. `MenuItem` does not expose a `selected` parameter.

---

## 8. `SpatialPopup`

```kotlin
import com.pico.spatial.ui.design.windows.SpatialPopup
import com.pico.spatial.ui.design.windows.rememberSpatialPopupPositionProvider

if (showPopup) {
    SpatialPopup(
        onDismissRequest = { showPopup = false },
        defaultMinHeight = 64.dp,
        defaultMinWidth = 128.dp,
        popupPositionProvider = rememberSpatialPopupPositionProvider(
            verticalPlacement = VerticalPlacement.above(offset = (-24).dp)
        )
    ) { /* content */ }
}
```

> `SpatialPopup` is for small independent floating layers. If the design needs a proper side panel, long form, or content-heavy surface, switch to `Subwindow` or `Sheet`.

---

## 8.1 `TabBar`

```kotlin
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.design.windows.TabBarPlacement
import com.pico.spatial.ui.platform.ViewPoint

TabBar(
    placement = TabBarPlacement.Left,
    followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)
) {
    item(
        text = "Tab",
        modifier = Modifier.clickable { onSelect() },
        itemIcon = { Icon(painter = painterResource(R.drawable.tab_icon), contentDescription = "Tab") },
        selected = isSelected,
    )
}
```

> `TabBar` is a window-level ornament attached to the window edge. It is not a plain BottomNavigation equivalent. `item(...)` is a DSL call, not a standalone composable.

> Use it once at the app or window root for global navigation. Do not redeclare it inside every subpage.

---

## 8.2 `Toolbar`

```kotlin
import com.pico.spatial.ui.design.windows.Toolbar

Toolbar(cornerSize = 37.dp) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(onClick = { }) { Icon(...) }
        Icon(
            painter = painterResource(R.drawable.divider),
            modifier = Modifier.size(width = 8.dp, height = 40.dp),
            tint = Color.Unspecified,
        )
        IconButton(onClick = { }) { Icon(...) }
    }
}
```

> `Toolbar` floats at the bottom edge of the window. It is not an in-page app bar or a generic content container.

### 8.3 Why `TabBar` / `Toolbar` are not content containers

Even though both live under `design.windows`, they behave more like **window-level chrome ornaments** than subwindow content containers:

- `TabBar`: edge navigation ornament with `SpatialWindowType.Tabbar`
- `Toolbar`: action-strip ornament for short grouped operations
- both are conceptually closer to Augment-family attached layers than to modal windows such as `AlertDialog` or `Sheet`

### 8.4 `Augment` directly (foundation-level supplement)

```kotlin
import com.pico.spatial.ui.foundation.window.Augment
import com.pico.spatial.ui.foundation.window.AugmentContentAlignment
import com.pico.spatial.ui.foundation.geometry.DpOffset3D
import com.pico.spatial.ui.foundation.geometry.NormalizedPoint3D

Augment(
    anchor = NormalizedPoint3D.TopFront,
    alignment = AugmentContentAlignment.BottomCenter,
    offset = DpOffset3D.Zero,
    enableMaterialBackground = true,
) {
    // augment content
}
```

- `anchor`: normalized attachment point on the `WindowContainer`
- `alignment`: which point of the Augment content aligns to the anchor
- `offset`: additional spatial displacement after alignment
- `enableMaterialBackground`: controls the Augment shell material, not a view-level `backgroundMaterial`
- `focusable = false` means it cannot receive text input or accessibility focus

> D2C should not generate naked `Augment` by default. Prefer `TabBar`, `Toolbar`, or `Subwindow` unless the design-system layer truly has no matching higher-level component.

---

## 9. Non-window Abilities

`Tooltip` and `backgroundMaterial` are **not** window-level components. See `spatial-ui-design-style/references/spatial-capabilities.md` for those.

---

## 10. CoachmarkBox-specific Reminders

- `CoachmarkBox` should wrap only the concrete target that needs guidance.
- Do not wrap unrelated global structure such as a full-screen background or a `TabBar` shell.
- If SDK API evolution causes `TabBar.item()` signature confusion, remember:
  - the icon belongs to `itemIcon`
  - click handling belongs to the `modifier = Modifier.clickable { ... }` or the relevant current API contract
