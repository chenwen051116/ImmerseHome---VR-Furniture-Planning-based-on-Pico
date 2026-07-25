---
adapter: intent-adapter
input_mode: intent_only
status: active
owner: anything-to-spatial-app
---

# Intent Adapter

Use this adapter when `Input Envelope.input_mode == "intent_only"`.

## 1. trigger

Match only when the strongest source is a short natural-language app intent and
there is no Figma URL, screenshot, PRD, or bounded patch target.

Examples:

- "做一个资讯类空间应用"
- "实现一个杨氏双缝干涉实验空间应用"
- "做一个文件管理器面板"

## 2. inputs

Read from `input_envelope.json`:

- `generation_mode`
- `target_module` / `output_dir`
- `input_sources[]` where `type == "text_prompt"`
- any explicit user constraints captured during Phase 1

## 3. produces

This adapter writes only existing workflow fields:

- `evidence_packet.json`
  - `facts.app_type_candidates`
  - `facts.regions`
  - `facts.repeated_structures`
  - `facts.visible_states`
  - `facts.spatial_cues`
  - `facts.interaction_cues`
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
- `assumption_ledger.json` when ambiguity exists

Do not add new top-level schema fields.

## 4. required_tools

None. This adapter is pure LLM extraction.

## 5. required_references

- `references/evidence-extraction.md`
- `references/input-normalization.md`

## 6. side_effects

- Writes `.scratch/evidence_packet.json`
- Writes `.scratch/normalized_spatial_spec.json`
- Writes `.scratch/assumption_ledger.json` when ambiguity exists

No external network, no MCP calls, no temp files outside `.scratch/`.

## 7. failure_mode

If the intent is too vague to infer an app archetype or target module / output,
return `BLOCKED` to Phase 1 and ask only for the missing architecture-impacting
information. Otherwise record weak-evidence assumptions in
`assumption_ledger.json` without choosing the root container or window model.

Weak-evidence extraction policy:

- record absence of explicit spatial features as evidence, not as a decision
- record absence of immersion need as evidence, not as a decision
- use `layout_intent.regions = ["header", "content"]` only as a provisional
  content-structure assumption when no better structure is explicit

## Extraction procedure

1. Extract facts only: app archetype, task verbs, explicit spatial words,
   explicit architecture constraints.
2. Normalize to one app interpretation. Do not preserve multiple parallel app
   shapes unless there is a real conflict; record conflict or ambiguity instead.
3. Do not select a root container or window model. Preserve evidence that may
   later support a flat business UI, passthrough, 3D entities, volumetric depth,
   immersive environment, or multiple independent windows.
4. Every provisional layout/content assumption must appear in `assumption_ledger.json` with impact and
   confidence.

## Output template

```json
{
  "facts": {
    "app_type_candidates": ["<archetype>"],
    "regions": ["header", "content"],
    "repeated_structures": [],
    "visible_states": [],
    "spatial_cues": ["flat_panel"],
    "interaction_cues": []
  },
  "unknowns": ["visual_style", "exact_layout"],
  "conflicts": [],
  "confidence": {
    "layout": 0.35,
    "interaction": 0.45,
    "spatial_mode": 0.55
  }
}
```
