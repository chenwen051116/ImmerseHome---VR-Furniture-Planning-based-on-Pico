# Spatial Layout Contract

This file defines the minimum layout structure you should produce after
`Normalized Spatial Spec` is stable and before writing Kotlin.

Workflow position:

1. classify input mode
2. extract evidence
3. build `Normalized Spatial Spec`
4. produce this `Spatial Layout Contract`
5. generate code

You can keep it:

- inline in reasoning / scratch notes, or
- in `.scratch/spatial_layout.json`

Either way, the same fields should be present.

## Top-level structure

```json
{
  "name": "DemoApp",
  "package": "com.example.demo",
  "container": "ON_PLAIN",
  "container_reason": "Flat panel with bottom toolbar; no visible depth.",
  "window_model": "sidebar_content",
  "window_reason": "One coordinated panel with persistent left navigation and right content.",
  "spatial_features": ["model_3d"],
  "windows": [
    {
      "id": "main",
      "role": "primary_panel",
      "children": ["header", "sidebar", "content"]
    }
  ],
  "regions": [
    { "id": "sidebar", "type": "nav_region", "repeated": "nav_item" },
    { "id": "content", "type": "content_region", "children": ["toolbar", "grid"] }
  ],
  "repeated_structures": ["nav_item", "content_card"],
  "states": ["selected_nav_item"],
  "tree": { ... }
}
```

## Field reference

| Field | Type | Required | Notes |
|---|---|---|---|
| `name` | string | scaffolded app: yes | App display name, also Application class prefix when scaffolding a new app |
| `package` | string | scaffolded app: yes | Android package name, e.g. `com.example.demo` |
| `container` | enum | yes | One of `ON_PLAIN`, `IN_VOLUME`, `STAGE_MIXED`, `STAGE_PROGRESSIVE`, `STAGE_FULL` |
| `container_reason` | string | yes | One-sentence justification — used in handoff message |
| `window_model` | string | yes | One of the window models from `window-model-decision.md` |
| `window_reason` | string | yes | One-sentence justification for the chosen window model |
| `windows` | array | yes | High-level spatial windows / overlays / subwindows |
| `regions` | array | yes | Major layout regions inside the selected window model |
| `repeated_structures` | string[] | yes | Templates such as nav items, tabs, rows, cards |
| `states` | string[] | yes | Visible state such as selected / disabled / locked / popup_visible |
| `spatial_features` | string[] | no | Subset of the feature whitelist below |
| `tree` | object | yes | Optional codegen-oriented component tree once the layout is stable |

## `spatial_features` whitelist

| Value | Triggers | Adds dependency |
|---|---|---|
| `model_3d` | Use `SpatialModelView` for embedded 3D models | core |
| `audio_3d` | Spatial audio playback | core |
| `anchor` | `WorldTrackingManager` for spatial anchors | tracking; **requires Stage container** |
| `passthrough` | MR background visible | implied by `STAGE_MIXED`; not a manual feature for WindowContainer |
| `skybox` | Custom virtual environment | implied by `STAGE_PROGRESSIVE` / `STAGE_FULL` |
| `hand_gesture` | `SpatialRotateGesture`, drag/pinch | platform |
| `hand_haptic` | `SpatialHandControllerHaptic` | platform |
| `env_mesh` | Use scanned environment mesh | sense; **requires Stage container** |

If a feature requires Stage but `container` is a WindowContainer, the
layout is contradictory — don't produce such JSON; fix the container first.

## Tree node schema

```json
{
  "type": "Column",
  "props": { "padding": 16, "spacing": 8 },
  "children": [
    { "type": "Text", "props": { "text": "Welcome" } },
    { "type": "Button", "props": { "text": "Open Stage" },
      "action": "openStage:HelloStage" }
  ]
}
```

- `type` MUST come from the approved SpatialUI / Spatial SDK references.
  No invented names.
- `props` are passed to the component verbatim (snake_case → keep as-is,
  the codegen handles conversion).
- `action` is an optional behavior hint:
  - `openStage:<id>` → `spatialNavigator.openStage(...)`
  - `openWindow:<id>` → `spatialNavigator.openWindowGroup(...)`
  - `closeSelf` → `spatialNavigator.closeWindowGroup()`
  - `closeStage` → `spatialNavigator.closeStage()`
  - `custom:<funcName>` → emit a stub function for the user to fill

## Root node rules

| Container | Required root `type` |
|---|---|
| `ON_PLAIN` | `DefaultWindowContainer` root with planar manifest metadata |
| `IN_VOLUME` | `DefaultWindowContainer` root with volumetric manifest metadata |
| `STAGE_*` | `DefaultStage` root with stage manifest metadata |

## Full example (ON_PLAIN)

```json
{
  "name": "HelloPico",
  "package": "com.example.hellopico",
  "container": "ON_PLAIN",
  "container_reason": "Flat settings panel, toolbar under the panel, no 3D content sticking out.",
  "window_model": "single_panel",
  "window_reason": "One coordinated settings surface with no detached overlay.",
  "windows": [
    { "id": "main", "role": "primary_panel", "children": ["header", "settings_list"] }
  ],
  "regions": [
    { "id": "header", "type": "header" },
    { "id": "settings_list", "type": "list_region", "repeated": "setting_row" }
  ],
  "repeated_structures": ["setting_row"],
  "states": [],
  "spatial_features": [],
  "tree": {
    "type": "DefaultWindowContainer",
    "children": [
      {
        "type": "Column",
        "props": { "padding": 24, "spacing": 12 },
        "children": [
          { "type": "Text", "props": { "text": "Welcome to PICO" } },
          { "type": "Button", "props": { "text": "Continue" },
            "action": "custom:onContinue" }
        ]
      }
    ]
  }
}
```
