# Input Normalization

This guide defines how every supported input type converges into one shared
`Normalized Spatial Spec`.

The key rule is simple:

> Different inputs may be parsed differently, but they MUST produce the same
> normalized structure before any spatial architecture or code decision is made.

## Why normalization exists

Without normalization, the skill tends to drift:

- screenshots push pixel-first reasoning
- PRDs push feature-list-first reasoning
- one-line prompts push speculative reasoning
- design links push component-name-first reasoning

Normalization forces all of them into one comparable structure.

## Required normalized object

```json
{
  "request_context": {
    "generation_mode": "existing_module",
    "target_module": "myapp",
    "output_dir": null,
    "existing_root_container": "ON_PLAIN",
    "root_architecture_override": false
  },
  "product_intent": {
    "app_type": "file_manager",
    "primary_user_goal": "browse and open files",
    "core_tasks": ["navigate", "search", "preview"]
  },
  "spatial_intent": {
    "container_candidate": "ON_PLAIN",
    "container_confidence": 0.82,
    "spatial_features": [],
    "immersion_need": "none"
  },
  "window_intent": {
    "window_model_candidate": "sidebar_content",
    "window_confidence": 0.87,
    "surfaces": [
      { "id": "main", "role": "primary_panel" }
    ]
  },
  "layout_intent": {
    "regions": [
      { "id": "sidebar", "type": "nav_region" },
      { "id": "content", "type": "content_region" }
    ],
    "repeated_structures": ["nav_item", "file_card"],
    "states": ["selected_nav_item"]
  },
  "ambiguities": [
    {
      "key": "popup_persistence",
      "default_decision": "treat_as_overlay",
      "reason": "smallest explanation first"
    }
  ],
  "evidence_trace": [
    {
      "claim": "ON_PLAIN",
      "because": "existing module is windowed and no immersive evidence exists"
    }
  ]
}
```

## Field intent

### `request_context`

Captures whether this is an in-place module update or a new scaffold.

Recommended optional fields for `existing_module` runs:

- `existing_root_container`: the current module root container (`ON_PLAIN` / `IN_VOLUME` / `STAGE_*`)
- `root_architecture_override`: `true` only when the user explicitly approved changing the root architecture

### `product_intent`

Describes what the app is for, regardless of how much UI evidence is available.

### `spatial_intent`

Describes the current spatial hypothesis. This is still a candidate layer, not
the final implementation contract.

### `window_intent`

Describes how many coordinated surfaces the input implies.

### `layout_intent`

Records the stable page-level structure before component mapping.

### `ambiguities`

Lists unresolved or conservatively handled areas. This should not be empty when
the input is sparse.

### `evidence_trace`

Stores the reasoning chain for important claims such as container choice,
window-model choice, and architecture preservation.

## Input-specific normalization guidance

### `visual_design`

Usually provides strong layout structure, weaker runtime behavior.

Normalize into:

- strong `layout_intent`
- medium `window_intent`
- weak-to-medium `spatial_intent` unless explicit spatial cues exist

### `visual_reference`

Usually provides visible structure and state, but not intent.

Normalize into:

- medium-to-strong `layout_intent`
- medium `window_intent`
- weak `product_intent` unless the UI type is obvious

### `product_doc`

Usually provides tasks and flows, but weak layout fidelity.

Normalize into:

- strong `product_intent`
- medium `window_intent`
- weak `layout_intent` unless the doc is structurally explicit

### `intent_only`

Usually provides one use case and almost no reliable structure.

Normalize into:

- medium `product_intent`
- weak `layout_intent`
- weak `window_intent`
- conservative `spatial_intent`

### `hybrid`

Merge all relevant strengths, then resolve conflicts using the workflow contract
priority rules.

## Normalization rules

1. One input run produces exactly one `Normalized Spatial Spec`.
2. If two sources disagree, do not preserve both as parallel truths.
3. Pick one best interpretation and log the conflict in `evidence_trace` or
   `ambiguities`.
4. If evidence is weak, degrade to a simpler app shape rather than inventing
   spatial richness.
5. The normalized object should be understandable without reading the original
   raw input again.

## Anti-patterns

### Anti-pattern: Screenshot path → immediate layout tree

Wrong because it skips intent consolidation.

### Anti-pattern: PRD → direct file generation

Wrong because it hides structural assumptions.

### Anti-pattern: Figma visual richness → Stage by default

Wrong because polished visuals do not automatically imply immersive space.
