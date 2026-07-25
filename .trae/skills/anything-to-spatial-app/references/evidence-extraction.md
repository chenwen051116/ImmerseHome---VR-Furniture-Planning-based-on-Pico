# Evidence Extraction

This guide defines how to extract stable facts from different input types before
normalization.

The key rule:

> Extract facts first. Do not jump from raw input to spatial architecture or
> Kotlin code.

## Required output: Evidence Packet

```json
{
  "facts": {
    "app_type_candidates": ["dashboard"],
    "regions": ["header", "sidebar", "content"],
    "repeated_structures": ["nav_item", "content_card"],
    "visible_states": ["selected_nav_item"],
    "spatial_cues": ["flat_panel"],
    "interaction_cues": ["search", "tab_switch", "popup_open_close"]
  },
  "unknowns": ["popup_persistence"],
  "conflicts": [],
  "confidence": {
    "layout": 0.83,
    "interaction": 0.66,
    "spatial_mode": 0.72
  }
}
```

## Per-input extraction strategy

### 1. `visual_design`

Extract:

- frame and section hierarchy
- major regions
- repeated modules
- persistent vs transient layers
- clear spatial cues such as depth, passthrough, scene framing, HUD layout

Do not assume runtime behavior that the design does not show.

### 2. `visual_reference`

Extract:

- visible regions
- repeated structures
- selected / disabled / highlighted states
- overlay vs persistent-pane cues
- whether the content looks flat, boxed, or scene-like

Use smallest-explanation-first for floating UI:

`overlay in main panel` → `SpatialPopup` → `Subwindow` → `multi_window`

### 3. `product_doc`

Extract:

- user goals
- task flows
- explicit screens or panels mentioned
- explicit spatial requirements
- explicit states and transitions
- constraints such as "keep existing module" or "do not rebuild architecture"

Do not invent a high-fidelity layout just because the PRD is detailed.

### 4. `intent_only`

Extract:

- closest app archetype
- core task verbs
- any explicit spatial keywords
- any architecture constraints

Typical weak-evidence defaults:

- flat 2D panel
- single primary surface
- no spatial feature unless explicitly requested

### 5. `hybrid`

Extract from every source separately first, then merge.

When sources disagree, record a `conflicts[]` item such as:

```json
{
  "key": "container_signal",
  "source_a": "visual_reference suggests flat panel",
  "source_b": "text prompt requests passthrough scene"
}
```

## Fact categories

### `app_type_candidates`

Examples:

- `settings`
- `dashboard`
- `chat`
- `file_manager`
- `launcher`
- `immersive_scene`

### `regions`

Examples:

- `header`
- `sidebar`
- `content`
- `detail`
- `toolbar`
- `popup`

### `repeated_structures`

Examples:

- `nav_item`
- `tab_item`
- `list_row`
- `card`

### `visible_states`

Examples:

- `selected`
- `disabled`
- `locked`
- `expanded`

### `spatial_cues`

Examples:

- `flat_panel`
- `visible_depth`
- `passthrough_background`
- `free_floating_scene_content`
- `virtual_environment`

### `interaction_cues`

Examples:

- `search`
- `tab_switch`
- `list_selection`
- `popup_open_close`
- `drag_rotate_scale`

## Extraction rules

1. Facts must be observable or explicitly requested.
2. Unknowns should stay unknown until normalization or conservative fallback.
3. Conflicts must be recorded, not silently collapsed.
4. Confidence can be approximate, but should reflect how speculative the signal is.

## Anti-patterns

### Anti-pattern: Component-first extraction

Wrong because it mistakes UI widgets for page semantics.

### Anti-pattern: Immediate container choice

Wrong because container choice belongs after evidence normalization.

### Anti-pattern: Treating every floating rectangle as another window

Wrong because most business UIs still live in one coordinated surface.
