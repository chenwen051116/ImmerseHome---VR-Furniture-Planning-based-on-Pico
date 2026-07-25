---
adapter: screenshot-adapter
input_mode: visual_reference
status: active
owner: anything-to-spatial-app
---

# Screenshot Adapter

Use this adapter when `Input Envelope.input_mode == "visual_reference"` and the
strongest source is a screenshot, UI image, or visual mock without a Figma URL.

## 1. trigger

Match only when:

- `input_mode == "visual_reference"`
- `input_sources[]` contains `type == "image"` or an equivalent local visual
  file path
- the request is page / app level rather than a bounded one-component patch

If the user names one file or component and asks for a tiny change, keep
`incremental_patch` even when an image is attached.

## 2. inputs

Read from `input_envelope.json`:

- `generation_mode`
- `target_module` / `output_dir`
- `input_sources[]` for screenshot / image paths
- existing root container and package facts from Phase 2 when a module exists

## 3. produces

This adapter writes only existing workflow fields:

- `evidence_packet.json`
  - `facts.frame_hierarchy`
  - `facts.regions`
  - `facts.repeated_structures`
  - `facts.visible_states`
  - `facts.spatial_cues`
  - `facts.interaction_cues`
  - `facts.design_tokens` when visually inferable
  - `facts.asset_requirements`
  - `unknowns[]`
  - `conflicts[]`
  - `confidence.{layout,interaction,spatial_mode}`
- `normalized_spatial_spec.json`
  - `request_context`
  - `product_intent`
  - `spatial_intent`
  - `window_intent`
  - `layout_intent`
  - `ambiguities[]`
  - `evidence_trace[]`
- `assumption_ledger.json` when visual evidence is ambiguous

Do not add new top-level schema fields.

## 4. required_tools

- Image read / understanding capability for the supplied screenshot path.

No network call is required. If the current runtime cannot inspect the image,
return `BLOCKED` instead of inventing visual facts.

## 5. required_references

- `references/evidence-extraction.md`
- `references/input-normalization.md`
- `references/figma-mapping.md` for visual feature mapping reused from design inputs
- `references/spatial-windows-guide.md` for floating / edge UI interpretation
- `../spatial-ui-design-style/SKILL.md`

## 6. side_effects

- Writes `.scratch/evidence_packet.json`
- Writes `.scratch/normalized_spatial_spec.json`
- Writes `.scratch/assumption_ledger.json` when ambiguity exists

No external temp files are required.

## 7. failure_mode

- If the screenshot cannot be read, return `BLOCKED` to Phase 1 with the file
  path and the failing tool.
- If fine-grained text / icon identity is unreadable, keep layout facts, add the
  unreadable details to `unknowns[]`, and record conservative defaults in
  `assumption_ledger.json`.
- If the image suggests multiple interpretations, record each interpretation and
  the visual cue behind it. Phase 4 owns choosing between panel-local overlay,
  Subwindow, and multi-window.

## Procedure

1. Inspect the image before writing evidence. Do not infer from filename alone.
2. Extract facts only: outer frame, major regions, repeated rows/cards,
   selected/disabled/highlighted states, visible depth, passthrough / skybox /
   3D scene cues, and floating UI.
3. Normalize into a single app interpretation. Screenshots are weaker than an
   explicit user requirement or existing module architecture.
4. Do not select a root container or window model. Record evidence that may
   later support a flat panel, coordinated regions, visible volume, real-world
   passthrough with free 3D content, or disconnected surfaces.
5. Record all uncertain text, assets, and spatial cues in `unknowns[]` or
   `assumption_ledger.json`.
