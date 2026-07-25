# Window Model Decision

Container selection answers **what spatial surface the app lives on**.
Window model selection answers **how many windows or panels the input implies**.

Do not skip this step.

## Primary models

| Model | Use when the input shows or implies... | Typical implementation |
|---|---|---|
| `single_panel` | One main panel, no independent overlay or second surface | One `DefaultWindowContainer` or one stage root |
| `single_panel_with_popup` | One main panel plus dropdown / menu / contextual overlay inside it | One window, popup rendered as overlay / `SpatialPopup` / `Subwindow` only if justified |
| `sidebar_content` | A persistent left rail / sidebar plus a main content region | One window with `Row(sidebar, content)` |
| `master_detail` | A navigation or list pane and a detail pane shown side by side at the same time | One window with two persistent panes, often `Row(list, detail)` |
| `window_plus_subwindow` | A clear primary window plus a secondary persistent tool panel | Main window plus `Subwindow`, or one additional `WindowContainer(...)` block alongside `DefaultWindowContainer` |
| `multi_window` | Multiple clearly independent panels in space, not just layered UI | One `DefaultWindowContainer` + multiple `WindowContainer(...)` DSL blocks, each opened via `openWindowContainer(id)` |

## Decision order

1. Count visible surfaces.
2. Separate **persistent panes** from **temporary overlays**.
3. Ask whether the input implies one coordinated window or multiple independent windows.
4. Prefer the simplest model that explains the input.
5. Map the chosen model to a concrete implementation path before coding.

## Hard rules

- A popup, dropdown, context menu, tooltip, toast, or hover card is **not** a second spatial window.
- A left sidebar plus right content region is usually **one** window.
- A list pane plus detail pane visible at the same time is usually `master_detail`, not `multi_window`.
- Only choose `window_plus_subwindow` or `multi_window` when the input strongly implies independent persistence or independent placement in space.
- Do not output `sidebar_content or master_detail`; compare candidates, then commit to one primary model.

## Subwindow vs multi_window escalation rule

Use this rule whenever the input shows more than one panel. The detailed
legality table lives in `container-decision.md`; the short version is:

1. **Layered UI inside one panel** (popup / dropdown / contextual menu) → stay
   in `single_panel` or `single_panel_with_popup`. No new window.
2. **One persistent auxiliary tool panel that lives in the same SpatialApp
   session** → `Subwindow` (`window_plus_subwindow`). One launcher, shared
   lifecycle.
3. **Multiple panels needing independent open / close lifecycles, or
   independent sizes/positions remembered across launches** → declare
   additional `WindowContainer(...)` DSL blocks alongside the single
   `DefaultWindowContainer` (`multi_window`). Each block is configured via
   DSL parameters (`id`, `form`, `defaultSize`, `targetActivity`, …) and is
   opened by `openWindowContainer(id)` / `closeWindowContainer(id)` from
   Kotlin — **not** by adding more `<activity>` meta-data to the manifest.

If you cannot justify rule #3 with at least one concrete reason (independent
launcher / independent lifecycle / independent placement memory), the answer is
`Subwindow`, not `multi_window`.

## Overlay vs second window cheat sheet

| Input clue | Usually means |
|---|---|
| Small panel anchored to a button or top-right corner | overlay / popup |
| Tooltip-like element that would close when focus changes | overlay |
| Sidebar + main content in one rounded panel | one window |
| Main view + detail view sharing one outer card/panel | one window |
| Two disconnected panels with separate bounds and no shared outer surface | likely multiple windows |

## Common mistakes

### Mistake: Treating popup as second window
Wrong because it over-spatializes normal 2D business UI.

### Mistake: Treating master-detail as multi-window
Wrong because both panes usually belong to one coordinated panel.

### Mistake: Flattening sidebar, content, and overlay into one undifferentiated tree
Wrong because it loses layout semantics and makes later code generation drift.

## Recommended implementation mapping

| Window model | First implementation choice |
|---|---|
| `single_panel` | one `DefaultWindowContainer` root with a single panel hierarchy |
| `single_panel_with_popup` | same root window, popup stays as overlay / popup composable |
| `sidebar_content` | one panel root with `Row(sidebar, content)` |
| `master_detail` | one panel root with persistent list/detail panes |
| `window_plus_subwindow` | start from one main window and add one true auxiliary window only if persistence is part of the design |
| `multi_window` | register multiple windows only when the input clearly implies disconnected surfaces |

## Window-fitting vs in-panel overlay (HARD)

When Phase 4 / 5 marks a region as a "floating navigation strip", "edge tool
strip", or "persistent auxiliary panel", **the implementation MUST use a
SpatialUI window-level fitting**, not a hand-rolled `Box.align(...)` overlay
inside the main panel. Visual similarity (a rounded capsule pinned to the
top of the screen) is not the same as semantic equivalence.

Add a dedicated `window_chrome_ornaments[]` entry in the Spatial Layout Contract
for any edge-pinned long-lived navigation/tool strip. Do not bury it in
`windows[].children` or `regions[]` as ordinary page content. The main window can
still use `sidebar_content`; the ornament is a sibling attached to that window,
not a separate `multi_window` surface.

| Phase-5 region semantics | Correct implementation | Wrong implementation |
|---|---|---|
| Persistent navigation pinned to a window edge (top/bottom/left/right) | `TabBar(placement = TabBarPlacement.Top \| Bottom \| Start \| End)` as a sibling node of the main panel | `Box.align(Alignment.TopCenter) { Row { … capsules … } }` inside the main panel |
| Persistent action strip / icon rail pinned to a window edge | `Toolbar { … }` as a sibling node of the main panel | hand-built `Row` with `clickable` icons inside the page tree |
| Long-lived auxiliary panel sharing the window lifecycle but rendering independently | `Subwindow { … }` (or a second `WindowContainer(...)` for `multi_window`) | a wide `Box` panel placed beside the main content with manual resize handling |
| In-content floater / anchored popup / contextual menu | `SpatialPopup` or an in-page overlay | (ok to keep as `Box.align(...)` inside the page) |

If `window_plus_subwindow` is chosen and you find yourself writing
`Box.align(Alignment.TopCenter)` (or any other `align(...)`) around what was
supposed to be the secondary surface, **stop**: the implementation has
collapsed back to `single_panel_with_popup`. Reach for the corresponding
window-level fitting instead and let the system position it.

## Pre-code checklist (run BEFORE writing UI for each region)

For every Phase-5 region (or any UI block reused at multiple places):

1. Is it pinned to a window edge regardless of page scroll? → window-level fitting
   (`TabBar` / `Toolbar`).
2. Is it a long-lived sibling surface with its own lifecycle / placement? →
   `Subwindow` (or a second `WindowContainer(...)`).
3. Does it move with page content / appear inside a card? → keep it as a
   normal Composable in the page tree.

Failing the checklist for a `TabBar`-shaped element is the single most
common regression in this skill — handle it here, not in Phase 7.

Common rationalization to reject: "it is not a second window, so it belongs in
the page tree." Correct response: not being `multi_window` only rules out an
independent lifecycle; it may still be a window-level ornament.

## Recommended output fields

When you record the intermediate structure, include:

```json
{
  "window_model": "single_panel_with_popup",
  "window_reason": "One main panel with a small options popup anchored to the content header.",
  "windows": [
    { "id": "main", "role": "primary_panel" },
    { "id": "popup", "role": "overlay", "anchor": "top_right_of_content" }
  ]
}
```

`window_reason` is required. It forces explicit reasoning and makes review easier.
