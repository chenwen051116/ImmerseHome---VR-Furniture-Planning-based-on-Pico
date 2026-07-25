---
adapter: incremental-patch-adapter
input_mode: incremental_patch
status: active
owner: anything-to-spatial-app
---

# Incremental Patch Adapter

Use this adapter when `Input Envelope.input_mode == "incremental_patch"` for a
small, bounded update to an existing module, file, panel, or one to two regions.

## 1. trigger

Match only when all hold:

- `input_mode == "incremental_patch"`
- the user names a target module, file, panel, or bounded component area
- the change is limited to one or two regions / components
- root container and overall window model do not need to change

If the patch requires new independent windows, Stage-only capabilities, or a root
container switch, escalate back to the full workflow before building.

## 2. inputs

Read from `input_envelope.json` plus Phase 2 workspace inspection notes:

- `generation_mode == "existing_module"`
- `target_module`
- named `target_files` / panel / component when present
- inherited root container and window model from existing code
- user-requested region / component changes and non-goals

## 3. produces

This adapter writes only existing workflow fields:

- `evidence_packet.json`
  - `facts.target_files`
  - `facts.existing_root_container`
  - `facts.existing_window_model`
  - `facts.regions_touched`
  - `facts.components_to_add_or_modify`
  - `facts.states_to_add_or_modify`
  - `facts.non_goals`
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
- `assumption_ledger.json` when a target file / inherited model is inferred

Do not add new top-level schema fields.

## 4. required_tools

- Local file inspection tools for target files and module metadata.

No external network or design-fetch tool is required unless the patch source
itself includes a design URL or image.

## 5. required_references

- `references/workflow-contract.md`
- `references/evidence-extraction.md`
- `references/input-normalization.md`
- `references/architecture-conventions.md`
- `../spatial-ui-design-style/SKILL.md`

## 6. side_effects

- Writes `.scratch/evidence_packet.json`
- Writes `.scratch/normalized_spatial_spec.json`
- Writes `.scratch/assumption_ledger.json` when ambiguity exists

The later Phase 5 lightweight patch artifact is owned by the main workflow, not
by this adapter.

## 7. failure_mode

- If no target module / file / panel can be identified, return `BLOCKED` to
  Phase 1 because a patch without a target is unsafe.
- If workspace inspection shows that the requested change needs a root container
  or window-model change, stop the patch path and escalate to the full workflow.
- If the patch source is visual but only affects one component, preserve the
  existing architecture and record visual uncertainty in `assumption_ledger.json`.

## Procedure

1. Read the named target files and existing module metadata before producing
   evidence.
2. Record inherited container / window model as facts; do not re-decide them in
   Phase 4.
3. Extract the smallest set of regions, components, and states that the patch
   touches.
4. Add explicit non-goals: do not switch root container, do not modify manifest
   metadata, do not introduce Stage-only APIs unless the user explicitly asks for
   full workflow escalation.
5. Phase 5 should emit the lightweight patch artifact from these normalized
   fields, then Phase 6 applies minimal in-place edits.

