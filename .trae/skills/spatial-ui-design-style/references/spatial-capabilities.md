# Spatial Capabilities (3D / Containers / Content)

Application-side spatial APIs. For window root background see
`references/window-background.md`. For hover see `references/hover.md`.

## 1. 3D Modifiers

Packages:

- `com.pico.spatial.ui.foundation.layout.*` — `depth`, `padding3D`, `alignDepth`, `Box3D`, `layout3D`
- `com.pico.spatial.ui.foundation.geometry.*` — `DpOffset3D`, `IntOffset3D`, `Offset3D`, `Rotation3D`, `Scale3D`, `NormalizedPoint3D`
- 3D transform / Z-offset modifiers (`zOffset`, `rotate3D`, `scale3D`) live under the foundation namespace; pin them by IDE auto-import.

```kotlin
Modifier
    .depth(40.dp)                        // give the element Z-axis thickness
    .alignDepth(DepthAlignment.Center)
    .zOffset { 20.dp.roundToPx() }       // Z-axis offset
    .padding3D(back = 10.dp, front = 10.dp)
    // or: .padding3D(all = 8.dp)
    .rotate3D(/* Rotation3D */)
    .scale3D(/* Scale3D */)
```

Containers: `Box3D { ... }` (children typically pair with `.depth(...)`),
`Modifier.layout3D { ... }`.

## 2. Augment — Window-Attached Ornament

`Augment` is **NOT** a window root and **NOT** a `design.windows.*`
component. It is a separate ornament window attached to a parent window.

- Package: `com.pico.spatial.ui.augment.Augment`
- Signature (verify with IDE auto-complete on the current SDK; window-level
  semantics are summarized in `anything-to-spatial-app/references/spatial-windows-guide.md`):

```kotlin
import com.pico.spatial.ui.augment.Augment

Augment(
    anchor = NormalizedPoint3D.TopFront,                 // attachment point on the parent window
    alignment = AugmentContentAlignment.BottomCenter,    // alignment relative to the anchor
    offset = DpOffset3D(0.dp, (-16).dp, 0.dp),           // optional — DpOffset3D, NOT IntOffset3D
    cornerRadius = 16.dp,                                // optional
    enableMaterialBackground = true,                     // DSL switch for system glass; default true
    focusable = true,                                    // optional
) {
    Row(Modifier.height(64.dp).padding(horizontal = 16.dp)) {
        Text("Floating Header")
    }
}
```

Notes:

- Every `Augment` is a real system window — do not create one per list item.
- Common `anchor` values include `NormalizedPoint3D.TopFront / BottomFront / ...`.
- Common `alignment` values include `AugmentContentAlignment.BottomCenter / TopCenter / ...` (verify with IDE auto-complete; do not invent).
- Even though `Augment` shares the DSL parameter name `enableMaterialBackground` with `WindowContainer(...)`, R4 still applies: do not paint a solid color over the system glass; flip `enableMaterialBackground = false` first.

## 3. Window-Level Glass Material (per container)

Window-level glass material is **not** an application-side type — there is
no public `WindowMaterials` symbol. It is toggled per container:

- `DefaultWindowContainer` → manifest meta-data
  `pico.spatial.windowcontainer.materialbackground` (per launcher
  `<activity>`, default `"1"`). No DSL knob.
- `WindowContainer(...)` / `Augment(...)` → DSL parameter
  `enableMaterialBackground: Boolean = true`.
- `Stage { ... }` → no `enableMaterialBackground` switch; do not paint a
  background on the Stage root.

See `references/window-background.md`. `WindowMaterials.kt` is an internal
SDK file name only and is not part of the application-facing API.

## 4. SpatialView / SpatialModelView / AttachmentPanel

- `SpatialView { ... }` — host regular Compose UI inside 3D space
- `SpatialModelView { Model(...) }` — 3D model presentation
- `attachmentPanelComponent { content { ... } }` + `panelSize(...)` —
  attach Compose content to an ECS entity

## 5. Vibrant and Material Modifiers

For Vibrant levels, propagation, imports, mixing rules, and Figma / screenshot
color inference, see `references/vibrant-guide.md`.

Material modifier (note: it is **view-level**, not a window-root API; see
`references/window-background.md`):

- `Modifier.backgroundMaterial(enable: Boolean = true, style: Material = Material.Regular)`
  — package `com.pico.spatial.ui.foundation.material`. Use it for cards
  / inner containers, not for the window root.
- `backgroundMaterial` is not just a translucent 2D paint. The SDK applies it
  through a spatial layer during layout; it reserves depth behind the content
  (source constants: `DEPTH = -1`, `BACK_OFFSET = 1`). Treat it as a local
  spatial material surface.
- The effect is supported when `SpatialBuild.isSpatialPlatform()` is true; SDK
  Debug builds may keep the API usable for preview/debugging, while non-Spatial
  Release builds can degrade to a no-op. Do not diagnose plain Android preview
  differences as a code-generation failure by default.
- Window-level glass on `Subwindow`, dialogs, menus, and popups belongs to the
  window shell (`SpatialWindowProperties.material` semantics), not to an
  app-side `Modifier.backgroundMaterial(...)` wrapper on their content root.

## 6. Window Navigation and State Observation

Use these APIs only when the design explicitly depends on opening / closing
additional containers or on spatial visibility / focus state.

```kotlin
import android.os.Bundle
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.LocalSpatialContainerStateManager

val navigator = LocalSpatialNavigator.current
navigator.openWindowContainer("DetailWindow")
navigator.openWindowContainer(
    "DetailWindow",
    tag = "selected-item",
    bundle = Bundle().apply { putString("id", itemId) }
)
navigator.closeWindowContainer("DetailWindow")

val stateManager = LocalSpatialContainerStateManager.current
val isFocused = stateManager.isFocused.value
val isOnstage = stateManager.isOnstage.value
val isSighted = stateManager.isSighted.value
```

- `LocalSpatialNavigator` is window-container navigation, not ordinary in-page
  navigation.
- `isFocused`, `isOnstage`, and `isSighted` are spatial-container state signals;
  keep business selection / loading state in the app ViewModel instead.

## 7. Gestures (3D / Custom Interaction)

For regular 2D UI, `clickable` / `toggleable` / `selectable` are sufficient.
For drag / scale / rotate / 3D pointer (package
`com.pico.spatial.ui.foundation.gesture.*`):

- Compose-style: `detectTapGestures`, `detectDragGestures`,
  `detectHorizontalDragGestures`, ...
- Spatial: `detectSpatialTapGesture`, `detectSpatialDragGesture`,
  `detectSpatialRotateGesture`, `detectSpatialScaleGesture`,
  `detectSpatialTransformGesture`, `detectSpatialPointerEvent`
- Value types: `SpatialTapValue`, `SpatialDragValue`, `SpatialRotateValue`,
  `SpatialScaleValue`, `SpatialTransformValue`, `SpatialPointerInfo`
