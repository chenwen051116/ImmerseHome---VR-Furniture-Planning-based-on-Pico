---
title: SpatialUI Import Inference Reference
audience: anything-to-spatial-app / figma-adapter / Phase 6 build
trigger: any code-generation phase that needs to resolve a SpatialUI import path
migrated_from: legacy-d2c-reference/spatial-api-reference.md (Phase B1.2)
---

# SpatialUI Import Inference Reference

Use this file for **import inference rules** and **package-path lookup** only.

- For component semantics, see `spatial-ui-components.md`.
- For subwindows and floating layers, see `spatial-windows-guide.md`.
- For ability APIs such as Hover, `backgroundMaterial`, `zOffset`, and 3D content, see `spatial-ui-design-style/references/spatial-capabilities.md`.
- For app entry, `WindowContainer`, and `Stage` setup rules, see `manifest-and-entry.md` + `window-container.md` / `stage.md`.

---

## 1. Import Inference Rules

### Rule 1: infer the package by API family

| API Family | Package Prefix | Example |
|------------|----------------|---------|
| design components (`Button`, `Text`, ...) | `com.pico.spatial.ui.design.` | `design.Button` |
| window components (`AlertDialog`, `Sheet`, ...) | `com.pico.spatial.ui.design.windows.` | `windows.AlertDialog` |
| menu components (`Menu`, `MenuItem`) | `com.pico.spatial.ui.design.menu.` | `menu.Menu` |
| spatial layout (`zOffset`, `Box3D`, ...) | `com.pico.spatial.ui.foundation.layout.` | `layout.zOffset` |
| hover effects | `com.pico.spatial.ui.foundation.hover.` | `hover.spatialHoverEffect` |
| Vibrant system | `com.pico.spatial.ui.foundation.vibrant.` + `com.pico.spatial.ui.graphics.` | see `spatial-ui-design-style/references/vibrant-guide.md` |
| 3D content | `com.pico.spatial.ui.foundation.content.` | `content.SpatialView` |
| DSL (`WindowContainer`, ...) | `com.pico.spatial.ui.foundation.dsl.` | `dsl.WindowContainer` |
| platform (`ViewPoint`, navigator, ...) | `com.pico.spatial.ui.platform.` | `platform.ViewPoint` |
| tooltip | `com.pico.spatial.ui.foundation.tooltip` | import directly |
| augment foundation API | `com.pico.spatial.ui.foundation.window.` | `window.Augment` |

### Rule 2: `Defaults` usually live next to the main component

```text
Button -> ButtonDefaults
Slider -> SliderDefaults
PageControl -> PageControlDefaults
```

### Rule 3: Vibrant requires three imports

```kotlin
import com.pico.spatial.ui.foundation.vibrant.Vibrant
import com.pico.spatial.ui.foundation.vibrant.withVibrant
import com.pico.spatial.ui.graphics.Vibrant
```

> **Common failure**: importing only `foundation.vibrant.Vibrant` and forgetting `graphics.Vibrant` causes `Unresolved reference: Vibrant` on `Color.Vibrant`.

---

## 2. Complete Import Lookup Table

### Spatial / app-structure APIs

| API | Import |
|-----|--------|
| `SpatialAppScope` | `com.pico.spatial.ui.foundation.dsl.SpatialAppScope` |
| `launch` | `com.pico.spatial.ui.foundation.dsl.launch` |
| `DefaultWindowContainer` | `com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer` |
| `WindowContainer` | `com.pico.spatial.ui.foundation.dsl.WindowContainer` |
| `DefaultStage` | `com.pico.spatial.ui.foundation.dsl.DefaultStage` |
| `Stage` | `com.pico.spatial.ui.foundation.dsl.Stage` |
| `Form` / `WindowContainerSize` / `Immersion` / `Placement` / `WorldScale` | `com.pico.spatial.ui.foundation.dsl.*` |
| `windowConstraints` | `com.pico.spatial.ui.foundation.dsl.windowConstraints` |
| `zOffset` | `com.pico.spatial.ui.foundation.layout.zOffset` |
| `rotation3D` | `com.pico.spatial.ui.foundation.layout.rotation3D` |
| `scale3D` | `com.pico.spatial.ui.foundation.layout.scale3D` |
| `backgroundMaterial` | `com.pico.spatial.ui.foundation.material.backgroundMaterial` |
| `Box3D` / `depth` / `requiredDepth` / `DepthAlignment` | `com.pico.spatial.ui.foundation.layout.*` |
| `SpatialView` | `com.pico.spatial.ui.foundation.content.SpatialView` |
| `SpatialModelView` / `Source` / `Resizability` | `com.pico.spatial.ui.foundation.content.*` |
| `spatialHoverEffect` | `com.pico.spatial.ui.foundation.hover.spatialHoverEffect` |
| `disableSpatialHoverEffect` | `com.pico.spatial.ui.foundation.hover.disableSpatialHoverEffect` |
| `tooltip` | `com.pico.spatial.ui.foundation.tooltip` |
| `Augment` / `AugmentContentAlignment` | `com.pico.spatial.ui.foundation.window.*` |

### Vibrant

| API | Import |
|-----|--------|
| `Vibrant` enum | `com.pico.spatial.ui.foundation.vibrant.Vibrant` |
| `withVibrant` | `com.pico.spatial.ui.foundation.vibrant.withVibrant` |
| `Color.Vibrant` | `com.pico.spatial.ui.graphics.Vibrant` |
| `vibrantEffect` | `com.pico.spatial.ui.foundation.vibrant.vibrantEffect` |
| `terminateVibrantEffect` | `com.pico.spatial.ui.foundation.vibrant.terminateVibrantEffect` |

### Design components (`com.pico.spatial.ui.design`)

| Component | Import Suffix |
|-----------|---------------|
| `PicoTheme` | `.PicoTheme` |
| `Text` / `Icon` / `Button` / `IconButton` | `.Text` / `.Icon` / `.Button` / `.IconButton` |
| `ButtonDefaults` / `IconButtonDefaults` | `.ButtonDefaults` / `.IconButtonDefaults` |
| `Checkbox` / `Switch` / `Slider` / `Option` | `.Checkbox` / `.Switch` / `.Slider` / `.Option` |
| `TextField` / `TextArea` / `SearchField` / `NumberField` | `.TextField` / `.TextArea` / `.SearchField` / `.NumberField` |
| `DatePicker` / `rememberDatePickerState` | `.DatePicker` / `.rememberDatePickerState` |
| `TitleBar` / `TitleAlignment` | `.TitleBar` / `.TitleAlignment` |
| `SideNavigation` / `SideNavigationItem` | `.SideNavigation` / `.SideNavigationItem` |
| `SegmentControl` / `SegmentItem` | `.SegmentControl` / `.SegmentItem` |
| `PageControl` / `PageControlDefaults` | `.PageControl` / `.PageControlDefaults` |
| `Badge` / `BadgeDefaults` | `.Badge` / `.BadgeDefaults` |
| `Divider` / `Link` | `.Divider` / `.Link` |
| `CircularProgressIndicator` / `LinearProgressIndicator` | `.CircularProgressIndicator` / `.LinearProgressIndicator` |
| `ButtonChip` / `RemovableChip` / `ToggleableChip` | `.ButtonChip` / `.RemovableChip` / `.ToggleableChip` |

### Window components

| Component | Import Suffix |
|-----------|---------------|
| `TabBar` / `TabBarPlacement` | `.windows.TabBar` / `.windows.TabBarPlacement` |
| `Toolbar` | `.windows.Toolbar` |
| `Subwindow` | `.windows.Subwindow` |
| `AlertDialog` | `.windows.AlertDialog` |
| `Sheet` | `.windows.Sheet` |
| `CoachmarkBox` / `RichCoachmark` / `CoachmarkDefaults` | `.windows.CoachmarkBox` / `.windows.RichCoachmark` / `.windows.CoachmarkDefaults` |
| `SnackbarHost` / `LocalSnackbarHostState` | `.windows.SnackbarHost` / `.windows.LocalSnackbarHostState` |
| `SpatialPopup` | `.windows.SpatialPopup` |
| `Menu` / `MenuItem` | `.menu.Menu` / `.menu.MenuItem` |

### Platform APIs

| API | Import |
|-----|--------|
| `ViewPoint` | `com.pico.spatial.ui.platform.ViewPoint` |
| `LocalSpatialNavigator` | `com.pico.spatial.ui.platform.containers.LocalSpatialNavigator` |
| `SpatialLaunchActivity` | `com.pico.spatial.ui.platform.stub.SpatialLaunchActivity` |
| `LocalSpatialContainerStateManager` | `com.pico.spatial.ui.platform.LocalSpatialContainerStateManager` |

---

## 3. High-risk Import Traps

- `Augment` does **not** come from `design.windows.*`; it comes from `com.pico.spatial.ui.foundation.window.*`.
- `tooltip` is a foundation-level modifier, not a window component.
- `Menu` belongs to `design.menu.*`, not `design.windows.*`.
- `spatialHoverEffect` belongs to `foundation.hover.*`, not any `design.*` package.
- Never invent packages such as `com.pico.spatial.ui.design.components.*` or `com.pico.spatial.ui.design.modifiers.*`.
