# WindowContainer (ON_PLAIN / IN_VOLUME)

A `WindowContainer` is a bounded panel. Apps run in **Shared Space** by
default; multiple apps' windows can coexist.

## DefaultWindowContainer vs WindowContainer

The SDK exposes **two distinct DSL entries** under
`com.pico.spatial.ui.foundation.dsl.*`:

| Aspect | `DefaultWindowContainer` | `WindowContainer(...)` |
|---|---|---|
| Role | The **first** window launched by PICO OS — the app's system entry | Custom / auxiliary windows opened from inside the app |
| Configured via | **`AndroidManifest.xml` meta-data** (`pico.spatial.windowcontainer.*`) | Code-level **DSL parameters** (`id`, `form`, `defaultSize`, `resizeType`, `worldScale`, `placement`, `enableMaterialBackground`, `targetActivity`, …) |
| Count per app entry | Exactly **one** | **Many** allowed |
| Activated by | PICO OS launcher (auto on app start) | `openWindowContainer(id)` / `closeWindowContainer(id)` from Kotlin |
| Activity binding | Launcher Activity (typically `LaunchActivity : SpatialLaunchActivity()`) | Each `WindowContainer` is associated with a **separate Activity** (configurable via `targetActivity`) |
| Internal registration | `SUISpatialContainerManager.registerDefaultWindowContainer(content)` | DSL builds `WindowContainerProperties` and registers the named container |

Recommended architecture:

> default to a `DefaultWindowContainer` + `WindowContainer` architecture —
> `DefaultWindowContainer` carries the main window; additional
> `WindowContainer(...)` blocks model auxiliary panels, secondary tools,
> and multi-window navigation.

This is exactly the SDK-level mechanism that backs this skill's
`window_plus_subwindow` and `multi_window` window models. When implementing
those models in Phase 6, the auxiliary surfaces should use
`WindowContainer(...)`, not duplicate `DefaultWindowContainer` entries.

Mini example:

```kotlin
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize

fun mainApp(scope: SpatialAppScope) = with(scope) {
    DefaultWindowContainer {
        PicoTheme { MainPanel() }
    }

    // Auxiliary "Detail" panel — opened via openWindowContainer("DetailPanel")
    WindowContainer(
        id = "DetailPanel",
        form = Form.Planar,
        defaultSize = WindowContainerSize(width = 640.dp, height = 360.dp),
    ) {
        PicoTheme { DetailPanel() }
    }
}
```

For full entry-chain and manifest coverage see `manifest-and-entry.md`; for
import lookup see `spatial-api-imports.md`.

## Two styles

| Style | Shape | Toolbar | Best for |
|---|---|---|---|
| `ON_PLAIN` | Flat panel | Mostly 2D content; standard business UI |
| `IN_VOLUME` | Window with meaningful depth | Mixed 2D+3D, front-face UI on a box-like container |

## Registration

Use `DefaultWindowContainer {}` as the root DSL entry. The planar vs volumetric
difference is primarily expressed by Activity metadata in `AndroidManifest.xml`.

```kotlin
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            PicoTheme {
                MainPanel(
                    modifier = Modifier.windowConstraints(
                        minWidth = 1280.dp,
                        minHeight = 720.dp,
                    )
                )
            }
        }
    }
```

Set these metadata values on the launcher Activity:

- `pico.spatial.windowcontainer.style = 1` for `ON_PLAIN`
- `pico.spatial.windowcontainer.style = 2` for `IN_VOLUME`

Additional independent windows should use `WindowContainer(...)` only when the
design truly implies a separate persistent surface.

## Main-window sizing rules

- The first-open size of the default window comes from manifest metadata such as
  `pico.spatial.windowcontainer.defaultsize="1280x720"`.
- `Modifier.windowConstraints(...)` controls resize bounds, not the initial open size.
- Keep `defaultsize` aligned with the main reference frame for input-driven apps.

## Sizing

- `ON_PLAIN`: prefer flat business layouts and panel-local overlays
- `IN_VOLUME`: use only when the panel itself is clearly volumetric, not merely because there is a 3D model inside the UI

## When NOT to use WindowContainer

- App needs to place anchors, scan environment mesh, or use ray casting
  → use `Stage` (those APIs require Full Space)
- App needs a global virtual environment / skybox → use `Stage`
- App is meant to be fully immersive → use `Stage` with `FULL` immersion

If the input suggests one of the above, switch the container — don't
try to force a WindowContainer with workarounds.

## Overlay vs real second window

Keep the following inside the main window unless the input clearly shows an
independent persistent surface:

- dropdown menus
- small help bubbles
- contextual popups
- in-panel dialogs
- master-detail panes inside the same outer panel

## Edge-pinned navigation / tool strip → window-level fitting

A floating navigation strip or icon rail that stays pinned to the top /
bottom / left / right edge of the window (regardless of page scroll) is a
**window-level fitting**, not page content. Implement it with `TabBar` or
`Toolbar` as a **sibling** of the main panel — never as a `Box.align(...)`
overlay inside the main panel.

```kotlin
fun mainApp(scope: SpatialAppScope) = with(scope) {
    DefaultWindowContainer {
        PicoTheme {
            Box(modifier = Modifier.fillMaxSize()) {
                MainPage()                                  // 主面板（满铺）
                TopNavTabBar()                              // 窗口挂件，与 MainPage 平级
            }
        }
    }
}

@Composable
fun TopNavTabBar(viewModel: HomeViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    TabBar(placement = TabBarPlacement.Top) {              // 不接受 modifier
        TopTab.values().forEach { tab ->
            item(
                text = tab.label,
                selected = state.selectedTopTab == tab,
                modifier = Modifier.clickable { viewModel.selectTopTab(tab) },
                itemIcon = null,
            )
        }
    }
}
```

Key invariants:

- Main panel renders at `fillMaxSize()`; do **not** add top/bottom padding to
  "make room" for the fitting — the system handles edge insets.
- Window-level fittings have no `modifier` at the call site; their placement
  is owned by SpatialUI.
- The fitting is a sibling of the main panel inside the same
  `DefaultWindowContainer { ... }`, NOT a child of the main panel's layout
  tree.

If the secondary surface is not just a navigation strip but a long-lived
auxiliary panel (independent settings drawer, persistent inspector, …), use
`Subwindow { ... }` instead, with the same sibling pattern.
