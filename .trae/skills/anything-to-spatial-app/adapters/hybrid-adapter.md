---
adapter: hybrid-adapter
input_mode: hybrid
status: active
owner: anything-to-spatial-app
---

# Hybrid Adapter

Use this adapter when `Input Envelope.input_mode == "hybrid"` and two or more
source types are present, such as Figma + PRD, screenshot + text constraints, or
concept art + an existing-module instruction.

## 1. trigger

Match only when:

- `input_mode == "hybrid"`
- `input_sources[]` contains at least two different source types
- no single source should fully override the others without conflict handling

If the user only adds a tiny text instruction to a bounded component patch, keep
`incremental_patch` instead.

## 2. inputs

Read from `input_envelope.json`:

- `generation_mode`
- `target_module` / `output_dir`
- all `input_sources[]` with trust levels
- explicit user requirements and existing module facts from Phase 2

## 3. produces

This adapter writes only existing workflow fields:

- `evidence_packet.json`
  - `facts.source_summaries`
  - `facts.regions`
  - `facts.repeated_structures`
  - `facts.visible_states`
  - `facts.spatial_cues`
  - `facts.interaction_cues`
  - `facts.design_tokens` when present
  - `facts.data_requirements` when present
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
- `assumption_ledger.json` for unresolved conflicts and provisional extraction
  assumptions

Do not add new top-level schema fields.

## 4. required_tools

- The tools required by the concrete sources being inspected, for example image
  understanding for screenshots or `d2c_get_figma_data` for Figma URLs.

Use only the tools that correspond to actual `input_sources[]`; do not fetch
unprovided sources.

## 5. required_references

- `references/evidence-extraction.md`
- `references/input-normalization.md`
- `references/figma-mapping.md` when a design source exists
- `references/spatial-windows-guide.md`

## 6. side_effects

- Writes `.scratch/evidence_packet.json`
- Writes `.scratch/normalized_spatial_spec.json`
- Writes `.scratch/assumption_ledger.json`
- May create source-specific temp files only when the corresponding source tool
  requires them; source-specific cleanup must follow that tool's contract.
- If any source is a Figma URL, Phase 7 MUST run the same `d2c_verify_code`
  then `d2c_cleanup_temp` hook order as `figma-adapter`.

## 7. failure_mode

- If one source fails but another high-confidence source remains, continue only
  if the failed source does not materially change root architecture; record the
  failure in `unknowns[]` and `assumption_ledger.json`.
- If sources conflict on root container or window model, record the competing
  evidence and source priority signals from `workflow-contract.md`; Phase 4 owns
  the decision.
- If evidence suggests changing existing root architecture, return `BLOCKED`
  unless the user explicitly requested full-workflow escalation.

## Procedure

1. Extract facts per source and keep source labels in `facts.source_summaries`.
2. List conflicts before normalization. Do not hide conflicting spatial cues.
3. Apply source priority only to normalize product/layout semantics; do not use
   the adapter to choose the root container or window model.
4. Record every high-confidence source claim that conflicts with the normalized
   interpretation in `evidence_trace[]` or `conflicts[]` with its source label.
5. Carry evidence for Phase 4 decisions forward; do not carry preselected
   container or window-model candidates.
