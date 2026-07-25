# SpatialUI Component Whitelist

Use ONLY the names listed here. SpatialUI is declarative (Compose-style)
and lives under `com.pico.spatial.ui.{platform, foundation, design}`.

> **Hard rule for codegen**: if a component is not in this file, it does
> not exist. Do not invent names like `SpatialButton` or `XRPanel`. When
> uncertain, fall back to a `Box` or `Column` and a `Text` placeholder.

## Container-level (root nodes)

| layout.json `type` | Kotlin DSL | Where it goes |
|---|---|---|
| `WindowContainer` | `DefaultWindowContainer { ... }` | `mainApp(scope: SpatialAppScope)` |
| `Stage` | `DefaultStage { ... }` | `mainApp(scope: SpatialAppScope)` |
| `Toolbar` | `Toolbar { ... }` | inside container; auto-positioned by container style |

`WindowContainer` planar vs volumetric style is primarily controlled by
manifest metadata such as `pico.spatial.windowcontainer.style = 1 / 2`.

Stage immersion mode is primarily controlled by manifest metadata such as
`pico.spatial.stage.immersion`, `immersion_min`, and `immersion_max`.

## Layout primitives

| `type` | Kotlin | Notes |
|---|---|---|
| `Column` | `Column(modifier) { ... }` | vertical stack |
| `Row` | `Row(modifier) { ... }` | horizontal stack |
| `Box` | `Box(modifier) { ... }` | overlay / absolute positioning |
| `LazyColumn` | `LazyColumn { items(...) { ... } }` | scrollable list |
| `LazyRow` | `LazyRow { items(...) { ... } }` | horizontal scrollable list |
| `Spacer` | `Spacer(Modifier.size(...))` | gap |

## Atomic components (`com.pico.spatial.ui.design`)

| `type` | Kotlin | Common props |
|---|---|---|
| `Text` | `Text(text)` | `text`, `style` |
| `Button` | `Button(onClick) { Text(...) }` | `text`, `enabled`, `variant` |
| `IconButton` | `IconButton(onClick) { Icon(...) }` | `icon`, `contentDescription` |
| `ButtonChip` / `RemovableChip` / `ToggleableChip` | `ButtonChip(...)`, `RemovableChip(...)`, `ToggleableChip(...)` | filter/tag chips |
| `Icon` | `Icon(...)` | icon glyph only |
| `TextField` | `TextField(value, onValueChange)` | `placeholder`, `label` |
| `TextArea` | `TextArea(value, onValueChange)` | multiline input |
| `SearchField` | `SearchField(...)` | search-box semantics |
| `NumberField` | `NumberField(...)` | numeric input |
| `Switch` | `Switch(checked, onCheckedChange)` | `enabled` |
| `Slider` | `Slider(value, onValueChange)` | `range`, `steps` |
| `Checkbox` | `Checkbox(checked, onCheckedChange)` | `enabled` |
| `Option` | `Option(...)` | selectable option row |
| `Divider` | `Divider()` | — |
| `CircularProgressIndicator` | `CircularProgressIndicator()` | loading state |
| `LinearProgressIndicator` | `LinearProgressIndicator()` | progress track |
| `TitleBar` | `TitleBar(...)` | page header |
| `SideNavigation` / `SideNavigationItem` | `SideNavigation { ... }` | in-page side navigation |
| `SegmentControl` / `SegmentItem` | `SegmentControl { ... }` | segmented switching |

## Window-level / spatial-specific UI

| `type` | Kotlin | Notes |
|---|---|---|
| `TabBar` | `TabBar { ... }` | window-level edge navigation, not page content |
| `Toolbar` | `Toolbar { ... }` | window-level action strip |
| `Subwindow` | `Subwindow { ... }` | independently persistent auxiliary window |
| `AlertDialog` | `AlertDialog(...)` | prompt / confirmation dialog |
| `Sheet` | `Sheet { ... }` | heavier modal / bottom / side sheet |
| `SpatialPopup` | `SpatialPopup(...) { ... }` | lightweight anchored floating layer |
| `Menu` / `MenuItem` | `Menu { ... }` | menu semantics |
| `SpatialView` | `SpatialView(modifier) { ... }` | embed any composable as a 3D-aware surface |
| `SpatialModelView` | `SpatialModelView(model, modifier)` | embed a glTF/glb 3D model in a 2D layout |

## Overlay / popup / subwindow boundary

Use these distinctions consistently:

| Input semantics | First choice |
|---|---|
| small help bubble / tooltip / anchored menu | overlay in the main panel, optionally `SpatialPopup` |
| detached but lightweight contextual floating layer | `SpatialPopup` |
| long-lived tool panel or side detail window | `Subwindow` |
| multiple disconnected major surfaces | true `multi_window` reasoning, not `SpatialPopup` |

If the input is ambiguous, choose the smallest explanation first:

`overlay in main panel` → `SpatialPopup` → `Subwindow` → `multi_window`

## Imports cheat sheet (for codegen)

```kotlin
import com.pico.spatial.ui.foundation.dsl.*
import com.pico.spatial.ui.design.*
import com.pico.spatial.ui.design.windows.*
```

3D-related (Stage / SpatialModelView):

```kotlin
import com.pico.spatial.ui.foundation.content.*
```

Tracking / anchors (only valid in Stage; verified against SpatialSDK source — package is `com.pico.spatial.sense.*`, NOT `com.pico.spatial.tracking.*`):

```kotlin
import com.pico.spatial.sense.world.WorldTrackingManager
import com.pico.spatial.sense.world.WorldAnchor
import com.pico.spatial.sense.world.WorldTrackingResult
// Plane / mesh anchors:
// import com.pico.spatial.sense.plane.{PlaneTrackingManager, PlaneAnchor}
// import com.pico.spatial.sense.mesh.{MeshTrackingManager, MeshAnchor}
// ECS-side anchor entity:
// import com.pico.spatial.core.ecs.{AnchorEntity, AnchorComponent}
// import com.pico.spatial.core.ecs.anchor.AnchorTarget
```

## What's NOT here (do not emit)

These names look plausible but DO NOT exist in Spatial SDK:

- `SpatialButton`, `SpatialText`, `SpatialCard`, `SpatialList`
- `XRPanel`, `XRWindow`, `VRView`
- `Window`, `Screen`, `Page` (these are not Spatial container types)
- Anything from `androidx.compose.material3.*` directly — Spatial SDK has
  its own design module. Don't mix.

If an input needs something not on this list, generate a `Box` with a
`// TODO(missing-component): describe-what-was-here` comment rather than
inventing a name.

## Common mistakes

- Do not treat `SpatialPopup` as proof of `multi_window`; it is still a floating layer choice.
- Do not upgrade a master-detail page to `Subwindow` unless the detail panel is independently persistent.
- Do not use `TabBar` / `Toolbar` as ordinary content containers inside a page `Column`.
- Prefer `SearchField` over a hand-built `TextField + search icon + placeholder` when the input clearly shows search-box semantics.
- `TitleBar` APIs vary by SDK version: `title` is often a `@Composable` lambda and newer variants may use `endContent` rather than `actions`; verify the signature before assuming raw `String` / `actions` parameters.
- `TabBar` may expose DSL-style `item(...)` entries rather than a dedicated `TabItem`. Do not assume `icon = painterResource(...)` or `onClick = {}` parameters exist on every SDK version; common item shape is `item(text = ..., selected = ..., modifier = Modifier.clickable { ... }, itemIcon = { Icon(...) })`.
- Chips / tags can contain icons. If the design shows chip icons, model the chip content as a `Row` or slot-based structure instead of plain `Text`.
- Configure `IconButton` through its component APIs: shell color via `colors = ButtonDefaults.buttonColors(containerColor = ...)`, render size via `IconButtonDefaults.iconButtonSize(...)`, outer size via `modifier = Modifier.size(...)`. Avoid painting `Modifier.background()` / `clip()` directly on the `IconButton` shell.
- **Do not rebuild `TabBar` / `Toolbar` by hand.** A rounded capsule pinned to the top/bottom/left/right edge of the window is a window-level fitting, not page content. The wrong shape looks like:

  ```kotlin
  // WRONG — collapses window_plus_subwindow back to single_panel_with_popup
  Box(modifier = Modifier.fillMaxSize()) {
      MainPage()
      Box(modifier = Modifier.align(Alignment.TopCenter)) {
          Row(Modifier.clip(RoundedCornerShape(24.dp)).background(...)) {
              tabs.forEach { /* clickable capsule */ }
          }
      }
  }
  ```

  The correct shape uses the SDK fitting as a **sibling** of the main panel, with no `align(...)` wrapping — the system positions it at the window edge:

  ```kotlin
  // CORRECT — true window-level edge navigation
  DefaultWindowContainer {
      PicoTheme {
          Box(modifier = Modifier.fillMaxSize()) {
              MainPage()
              TabBar(placement = TabBarPlacement.Top) {
                  tabs.forEach { tab ->
                      item(
                          text = tab.label,
                          selected = state.selected == tab,
                          modifier = Modifier.clickable { onSelect(tab) },
                          itemIcon = null,
                      )
                  }
              }
          }
      }
  }
  ```

  Note: window-level fittings (`TabBar` / `Toolbar`) do **not** accept a `modifier` parameter at the call site — their position is owned by the system. If you find yourself wanting to pass `Modifier.align(...)` into one of them, you have picked the wrong shape.

## Hover effects (verified against the current SpatialUI capability knowledge base)

PICO design rules require every hoverable container to use
`Modifier.spatialHoverEffect`. **Reimplementing hover via Compose's
`hoverable + animateFloatAsState(scale)` is BLOCKED** by the
`spatial-ui-design-style` verifier.

### Authoritative imports

| Symbol | Fully-qualified name |
|---|---|
| `Modifier.spatialHoverEffect` | `com.pico.spatial.ui.foundation.hover.spatialHoverEffect` |
| `SpatialHoverStyle.Default` / `.Highlight` | `com.pico.spatial.ui.foundation.hover.SpatialHoverStyle` |
| `Modifier.spatialHoverEffectGroup(group)` | `com.pico.spatial.ui.foundation.hover.spatialHoverEffectGroup` |
| `SpatialHoverEffectGroup.obtain()` | `com.pico.spatial.ui.foundation.hover.SpatialHoverEffectGroup` |
| `Modifier.disableSpatialHoverEffect(disabled)` | `com.pico.spatial.ui.foundation.hover.disableSpatialHoverEffect` |

### Four usage shapes

```kotlin
// 1. preset style (shortest)
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import com.pico.spatial.ui.foundation.hover.SpatialHoverStyle

Box(Modifier.size(100.dp).spatialHoverEffect())                     // = SpatialHoverStyle.Default
Box(Modifier.spatialHoverEffect(style = SpatialHoverStyle.Highlight))

// 2. custom DSL
Box(Modifier.spatialHoverEffect {
    scale(if (it.isActive) 1.1f else 1f)
    offset(y = if (it.isActive) (-4).dp else 0.dp)
    alpha(if (it.isActive) 1f else 0.8f)
})

// 3. custom animation curves
Modifier.spatialHoverEffect {
    animation(tween(durationMillis = 250)) { scale(if (it.isActive) 1.05f else 1f) }
    animation(spring(stiffness = 700f))    { offset(y = if (it.isActive) (-4).dp else 0.dp) }
}

// 4. cross-view coordination (hover group)
import com.pico.spatial.ui.foundation.hover.SpatialHoverEffectGroup
import com.pico.spatial.ui.foundation.hover.spatialHoverEffectGroup

val group = remember { SpatialHoverEffectGroup.obtain() }
items.forEach { Card(Modifier.spatialHoverEffectGroup(group).spatialHoverEffect()) { /* ... */ } }
```

### Mandatory modifier order

```
clip → (border/background → backgroundMaterial) → spatialHoverEffect → clickable
```

`clip` defines the shape first, `spatialHoverEffect` reads that shape, then
`clickable` appends the click behaviour. Wrong order → hover highlight does
**not** track the `clip` boundary.

### When NOT to add `spatialHoverEffect`

- Built-in interactive components (`Button`, `IconButton`, SpatialUI design
  components) **already include** hover; do not stack another one. To
  suppress, use `Modifier.disableSpatialHoverEffect(true)`.
- Only add `.spatialHoverEffect().clickable{}` manually for **custom
  clickable containers** or item composables (e.g. `SideNavigationItem`).

For full DSL coverage and edge cases see
`../spatial-ui-ability/SKILL.md` § 3 Hover Effects and
`references/spatial-api-imports.md`.
