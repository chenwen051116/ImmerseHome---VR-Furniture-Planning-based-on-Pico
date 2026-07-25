# Anything-to-Spatial-App Workflow Contract

This file defines the stable end-to-end workflow for `anything-to-spatial-app`.

Use ONE workflow for all input types.
Do not create separate generation standards for screenshot / Figma / PRD /
one-line intent. Different inputs may use different extraction tactics, but they
must converge into the same required artifacts and pass the same gates.

## Execution style

Execute this workflow gate-by-gate.

- Finish the current artifact before starting the next one.
- Prefer explicit structured notes over vague narrative reasoning.
- If a gate fails, do not push forward optimistically.
- Resolve the failure by either fixing the artifact, applying a conservative
  default with an explicit assumption, or asking the user only when the
  unresolved issue materially changes the architecture.

## Standard gate-output format

Use this shape for required gate summaries (Phases 1, 4, 5, and 7 in
`SKILL.md`; optional lightweight summaries may reuse it elsewhere):

```text
Step Output
- Artifact: <artifact name>
- Summary: <1-3 lines>
- Key fields: <key fields or bullets>
- Reflection: <citation-bearing field per the requirements below>
- Gate result: PASS | BLOCKED
- Next action: <proceed | revise artifact | apply conservative default | ask user>
```

Rules:

- Emit the structured artifact first.
- Then emit the `Step Output` summary immediately after it.
- For Phases 4, 5, and 7, missing or generic `Reflection` is `BLOCKED`.
- `Gate result: PASS` means the next phase may start.
- `Gate result: BLOCKED` means the next phase must not start yet.

If you want scriptable checking, persist the key artifacts with these recommended
scratch filenames:

- `.scratch/input_envelope.json`
- `.scratch/evidence_packet.json`
- `.scratch/normalized_spatial_spec.json`
- `.scratch/assumption_ledger.json`
- `.scratch/spatial_layout_contract.json` ← **canonical name** for non-patch runs
- `.scratch/patch_contract.json` ← **canonical name** for `incremental_patch`

> Legacy filenames (`spatial_layout.json`, `window_structure.json`) are still
> accepted by `check_workflow_artifacts.py` for backward compatibility, but new
> runs MUST emit `spatial_layout_contract.json`.

Then run:

```bash
# From the anything-to-spatial-app skill root:
python3 -m scripts.check_workflow_artifacts --target <target>
python3 -m scripts.check_layout_structure --target <target>

# Or from any directory:
python3 <skill-root>/scripts/check_workflow_artifacts.py --target <target>
python3 <skill-root>/scripts/check_layout_structure.py --target <target>
```

For generated-project validation plus build, use:

```bash
bash <skill-root>/scripts/validate_workflow_and_build.sh <target>
```

`assumption_ledger.json` is always required; use an empty JSON array `[]` only
when there are explicitly no assumptions.

Recommended artifact-to-step mapping (7-phase flow, see `SKILL.md`):

- Phase 1 (Frame) → `Input Envelope`
- Phase 1.5 (Adapter Selection) → selected adapter + hook plan; adapter execution may wait for Phase 2 workspace facts
- Phase 2 (Read) → workspace inspection notes (no separate artifact)
- Phase 3 (Spec) → `Evidence Packet` + `Normalized Spatial Spec` + `Assumption Ledger`
- Phase 4 (Decide) → `Container Decision` and `Window Model Decision` (with **container × feature legality** checked inline)
- Phase 5 (Plan) → `Spatial Layout Contract` (or `Patch Contract` for `incremental_patch`)
- Phase 6 (Build) → `Implementation Mapping` / code-change summary
- Phase 7 (Verify) → see the canonical gate order below.

## Phase 7 canonical gate order

`scripts/validate_workflow_and_build.sh` owns gates 1–10; adapter MCP hooks are
agent-owned because shell wrappers cannot call MCP tools.

| # | Check | Artifact / signal | Skip flag |
|---|---|---|---|
| 1 | adapter contracts | `check_adapter_contract.py` | — |
| 2 | workflow artifacts | `check_workflow_artifacts.py` | — |
| 3 | layout / structure | `.scratch/legality_check_result.json` → `passed: true` | — |
| 4 | implementation scan | `.scratch/implementation_scan_result.json` → `passed: true` | — |
| 5 | Gradle sync / project discovery | `.scratch/gradle_sync_result.json` → `passed: true`; plus IDE Sync Project with Gradle Files for new modules | `--skip-gradle-sync` |
| 6 | smoke build | `:<module>:assembleDebug` | — |
| 7 | runtime install / launch | `.scratch/runtime_launch_result.json` → `passed: true` | `--skip-runtime-launch` |
| 8 | architecture conventions | `.scratch/architecture_check_result.json` → `passed: true` | `--skip-architecture` |
| 9 | unit tests | `.scratch/unit_tests_result.json` → `tests>0 ∧ failures=0 ∧ errors=0` | `--skip-unit-tests` |
| 10 | design-style admission | `.scratch/design_style_result.json` → `passed: true`; `verify-design-style.sh` → `errors: 0` | none (`--skip-design-style` is a hard failure) |
| 11 | adapter hooks | Figma: `d2c_verify_code` exactly once, targeted Critical / Moderate fixes, then `d2c_cleanup_temp` exactly once; write `.scratch/adapter_hooks_result.json` | — |

Skip flags are emergency hatches, except `--skip-design-style`: screenshot / generated Compose UI must be admitted by `spatial-ui-design-style`, so skipping that gate is a hard failure even with `--allow-degraded`. By default, degraded runs exit non-zero; use `--allow-degraded` only for explicit environment limitations. The script writes `<target>/.scratch/verification_summary.json`; `clean: false` / `passed: false` requires final handoff disclosure of every `warnings[]` and `skips[]` entry.

Figma hook ordering is strict: `d2c_verify_code` uses XML / preview temp files,
so cleanup must run only after verification and after targeted Critical /
Moderate fixes.

Agent-owned hook result shape (required when the selected adapter declares hooks):

```json
{
  "selected_adapter": "figma-adapter",
  "verify": { "called": true, "call_count": 1, "tool": "d2c_verify_code" },
  "cleanup": { "called": true, "tool": "d2c_cleanup_temp", "runs_after_verify": true },
  "targeted_fixes_applied": ["Critical/Moderate verifier findings only"],
  "passed": true
}
```

`validate_workflow_and_build.sh` can only complete machine gates 1–10 and print
the hook handoff. A run that needs adapter hooks is not skill-complete until the
agent performs those MCP calls and writes `adapter_hooks_result.json`.

## Supported input modes

| Input mode | Typical inputs | Main risk |
|---|---|---|
| `visual_design` | Figma link | overfitting visuals into the wrong spatial architecture |
| `visual_reference` | screenshot, mockup image, UI photo, screen capture | mistaking environment for app content, or mistaking window ornaments for page overlays |
| `product_doc` | PRD, feature spec, interaction doc | inventing missing UI structure too early |
| `intent_only` | one-line description, short natural-language prompt | weak evidence causing unstable layout drift |
| `hybrid` | any combination of the above | conflicts between sources |
| `incremental_patch` | "add a search bar to MainPanel", "fix the header in mymodule", small diff-style asks against an existing module | running the full 7-phase flow when a focused patch contract is enough |

### `incremental_patch` lightweight path

`incremental_patch` exists because the full 7-phase flow over-spends tokens on
small, surgical edits to a known module. When the request matches one of the
following, classify it as `incremental_patch`:

- the user names a specific file, panel, or module to modify
- the change scope is bounded to one or two regions / components
- root container, window model, and overall window structure stay unchanged

Lightweight-path rules:

- skip Phase 4 (Decide) — inherit `container` and `window_model` from the
  existing module; record the inheritance explicitly in the patch contract
- skip Phase 5's full `Spatial Layout Contract` — emit a smaller `Patch
  Contract` instead (see below)
- still run Phase 7 (Verify): smoke build + legality check are mandatory

Minimum `Patch Contract` shape (saved as `.scratch/patch_contract.json` when
persisted):

```json
{
  "target_module": "myapp",
  "target_files": ["myapp/src/main/java/.../MainPanel.kt"],
  "inherits": {
    "container": "ON_PLAIN",
    "window_model": "sidebar_content"
  },
  "regions_touched": ["search_bar"],
  "components_to_add": ["SearchField"],
  "states_to_add": ["query_text"],
  "non_goals": [
    "do NOT switch root container",
    "do NOT add Stage-only APIs"
  ]
}
```

If during the patch any non-goal becomes necessary, escalate to the full
7-phase flow before continuing — do not silently rewrite a non-goal.

## Mandatory artifacts

Every run must produce the following artifacts inline in reasoning or scratch
notes:

1. **Input Envelope**
2. **Evidence Packet**
3. **Normalized Spatial Spec**
4. **Assumption Ledger** (always present; `[]` only when explicitly no assumptions exist)
5. **Spatial Layout Contract** for non-patch runs, or **Patch Contract** for `incremental_patch`

If one of these is missing, the workflow is incomplete.

## Artifact definitions

### 1. Input Envelope

Captures what inputs were provided and what generation mode is in play.

```json
{
  "input_mode": "hybrid",
  "generation_mode": "existing_module",
  "target_module": "myapp",
  "input_sources": [
    { "type": "figma_url", "trust_level": "high" },
    { "type": "text_prompt", "trust_level": "high" }
  ]
}
```

### 2. Evidence Packet

Extract facts, unknowns, conflicts, and confidence levels. Facts only — no code,
no container decision yet.

### 3. Normalized Spatial Spec

The single source of truth for app intent after all input types are merged.

Required sections:

- `request_context`
- `product_intent`
- `spatial_intent`
- `window_intent`
- `layout_intent`
- `ambiguities`
- `evidence_trace`

### 4. Assumption Ledger

Every conservative default or meaningful guess must be recorded here.

### 5. Spatial Layout Contract

The layout-and-window structure used directly for implementation.

Full Phase-4 + Phase-5 example (use this as the canonical schema reference;
SKILL.md only lists field names):

```json
// Phase 4a — Container Decision
{
  "container": "ON_PLAIN",
  "container_reason": "Existing module is windowed; flat business panel; no immersive cues.",
  "container_evidence": [
    "Evidence Packet.facts.module_facts.root_token=DefaultWindowContainer",
    "Evidence Packet.facts.spatial_cues=['flat_panel']"
  ],
  "rejected_near": {
    "alternative": "IN_VOLUME",
    "rejection_reason": "Evidence Packet.facts.spatial_cues=['flat_panel'] only; no boxed front-face UI per the legality table."
  },
  "rejected_far": {
    "alternative": "STAGE_MIXED",
    "rejection_reason": "legality table: STAGE_MIXED requires anchor / env_mesh / free 3D entity; none in Evidence Packet.facts."
  }
}

// Phase 4b — Window Model Decision
{
  "window_model": "single_panel_with_popup",
  "window_reason": "One main panel with an anchored options popup; no independent persistent surface.",
  "rejected_near": {
    "alternative": "single_panel",
    "rejection_reason": "Evidence Packet.facts.regions includes 'popup'; pure single_panel would lose it."
  },
  "rejected_far": {
    "alternative": "multi_window",
    "rejection_reason": "Subwindow-vs-multi_window rule #3 unmet: no independent launcher / lifecycle / placement memory."
  }
}

// Phase 5 — Spatial Layout Contract (canonical)
{
  "container": "ON_PLAIN",
  "container_reason": "...",
  "window_model": "single_panel_with_popup",
  "window_reason": "...",
  "windows": [
    { "id": "main", "role": "primary_panel", "children": ["header", "sidebar", "content_list"] },
    { "id": "popup_menu", "role": "overlay", "anchor": "top_right_of_content", "default_visibility": "visible_in_mock" }
  ],
  "regions": [
    { "id": "sidebar", "type": "nav_region" },
    { "id": "content_list", "type": "content_region" }
  ],
  "repeated_structures": ["nav_item", "content_card"],
  "states": ["selected_nav_item", "popup_visible"],
  "evidence_trace": [
    { "window_id": "main", "claim": "primary_panel", "fact_ref": "Evidence Packet.facts.regions", "because": "all visible regions share one outer card" },
    { "window_id": "popup_menu", "claim": "overlay", "fact_ref": "Evidence Packet.unknowns=['popup_persistence']", "because": "anchored to button, would close on focus loss" }
  ]
}
```

For screenshot / visual-reference runs, add these fields when applicable:

```json
{
  "facts": {
    "reference_frame": {
      "screenshot_px": { "width": 1024, "height": 768 },
      "app_owned_bbox_px": { "x": 120, "y": 82, "width": 812, "height": 520 },
      "target_window_dp": { "width": 1120, "height": 620 },
      "scale_policy": "fit_app_owned_bbox_preserve_aspect",
      "dp_per_px": 1.38,
      "excluded_from_size": ["environment_context", "window_chrome_ornaments"]
    },
    "app_owned_regions": ["main_panel", "filter_sidebar", "result_grid"],
    "environment_context": ["passthrough_background", "floor", "skybox"],
    "window_chrome_ornaments": ["left_nav_rail"],
    "window_chrome_rule": "left_nav_rail is edge-pinned and long-lived; implement as TabBar, not Box.align overlay"
  }
}
```

And in `spatial_layout_contract.json`:

```json
{
  "reference_frame": {
    "screenshot_px": { "width": 1024, "height": 768 },
    "app_owned_bbox_px": { "x": 120, "y": 82, "width": 812, "height": 520 },
    "target_window_dp": { "width": 1120, "height": 620 },
    "scale_policy": "fit_app_owned_bbox_preserve_aspect",
    "dp_per_px": 1.38,
    "excluded_from_size": ["environment_context", "window_chrome_ornaments"]
  },
  "window_chrome_ornaments": [
    {
      "id": "left_nav_rail",
      "type": "TabBar",
      "placement": "Left",
      "role": "window_attached_navigation_ornament"
    }
  ],
  "windows": [
    {
      "id": "main",
      "children": ["filter_sidebar", "results_region"]
    }
  ],
  "visual_content_contract": {
    "sidebar": {
      "preferred_component": "SideNavigation",
      "has_surface": true,
      "search_pill": {
        "width_policy": "fill_sidebar_content_width",
        "interaction_role": "search_input",
        "preferred_component": "SearchField"
      },
      "chips": {
        "active_preferred_component": "RemovableChip",
        "recommendation_preferred_component": "ButtonChip",
        "active_chips_have_close_icon": true,
        "recommendation_chips_may_have_leading_icon": true
      }
    },
    "tabs": { "visible_count": 9, "style": "small_capsule_background" },
    "cards": {
      "layout": "fixed_3x2",
      "content": "image_only",
      "has_text_overlay": false,
      "asset_policy": "reference_like_images_or_crops"
    }
  }
}
```

`window_chrome_ornaments[]` entries are siblings of the main page under the same
container. They MUST NOT be listed as ordinary page children unless the evidence
explicitly says they scroll/move with page content.

`reference_frame` is mandatory for `visual_reference`: it prevents using the
entire screenshot (including spatial background) as the application window. The
implementation must set manifest `defaultsize` / direct root constraints from
`target_window_dp`, then use `app_owned_bbox_px` proportions for internal layout.

`visual_content_contract` is mandatory for `visual_reference` and must include
both visual and interaction semantics. If a screenshot element is recognized as a
search input, `search_pill.interaction_role` MUST be `search_input` and
`preferred_component` MUST be SpatialUI `SearchField`; emitting a static
`Row + Icon + Text` for that element is a Phase-6 implementation error.

Patch Contract shape (used only when `input_mode = incremental_patch`):

```json
{
  "target_module": "myapp",
  "target_files": ["myapp/src/main/java/.../MainPanel.kt"],
  "inherits": { "container": "ON_PLAIN", "window_model": "sidebar_content" },
  "regions_touched": ["search_bar"],
  "components_to_add": ["SearchField"],
  "states_to_add": ["query_text"],
  "non_goals": ["do NOT switch root container", "do NOT add Stage-only APIs"]
}
```

## Backtrack table (canonical, referenced by SKILL.md Phase 7)

After 2 consecutive failed attempts on the same Phase 7 check, backtrack to
the phase that owns the root cause:

| Phase 7 failure signal | Backtrack to |
|---|---|
| `legality_check_result.json` → `stage_api_legality.failures` non-empty | Phase 4 (container) |
| `legality_check_result.json` → `existing_module_root_preserved.failures` non-empty | Phase 4 + Phase 6 entry wiring |
| `legality_check_result.json` → `overlay_vs_multi_window.failures` non-empty | Phase 4b (window model) |
| `implementation_scan_result.json` → `root_match.failures` non-empty | Phase 6 (UI generation + entry) |
| `implementation_scan_result.json` → `entry_wired.failures` non-empty | Phase 6 (manifest + Application) |
| `implementation_scan_result.json` → `manifest_consistency.failures` non-empty | Phase 6 (manifest meta-data) |
| `design_style_result.json` missing / `passed=false` / `summary.errors>0` | Phase 6 (SpatialUI design-style admission during UI generation) |
| `implementation_scan_result.json` → `whitelist_components.failures` non-empty | Phase 5 mapping → Phase 6 |
| Smoke build `Unresolved reference: <Component>` | Phase 5 mapping |
| Smoke build `IllegalStateException: not in Full Space` | Phase 4 |

When backtracking, edit the offending phase's artifact first, then regenerate
downstream artifacts only as needed. Never silently rewrite code that
contradicts an unchanged contract.

## Workflow phases

This contract is the canonical 7-phase flow. Each phase has exactly one
purpose; do not re-emit the same artifact twice across phases.

### Phase 1 — Frame (input classification)

- determine `input_mode` (including `incremental_patch`)
- determine `generation_mode`
- list all sources in `Input Envelope`

### Phase 1.5 — Adapter Selection

- select exactly one active adapter for the current `input_mode`
- install any declared Phase 7 hooks, but do not execute MCP verification yet
- if the adapter needs workspace/platform/root-container facts, execute it after
  Phase 2 inspection and feed its outputs into Phase 3
- if an adapter `failure_mode` reroutes to another `input_mode`, revise
  `input_envelope.json` and repeat Phase 1.5; never chain two adapters under one
  `input_mode`

### Phase 2 — Read (workspace inspection)

- in existing module mode, read the target module's `build.gradle.kts`,
  `AndroidManifest.xml`, main entry / `mainApp`, and existing root container
- in new project mode, confirm the output directory and skip module-specific
  reads
- this phase produces no separate JSON artifact; it produces context the next
  phases rely on

### Phase 3 — Spec (evidence + normalization + assumptions)

- emit `Evidence Packet` (facts vs unknowns vs conflicts vs confidence)
- merge into one `Normalized Spatial Spec`
- record every architecture-impacting default in `Assumption Ledger`
- the legacy "Semantic Analysis Summary" step is folded into this phase — do
  not produce a third copy of regions / repeated_structures / states

### Phase 4 — Decide (container × window model, with legality inline)

- consult `references/container-decision.md` (now contains the **container ×
  feature legality** table)
- consult `references/window-model-decision.md` (now contains the **Subwindow
  vs multi_window escalation rule**)
- emit one `Container Decision` with `container` + `container_reason` +
  `container_evidence[]` + `rejected_near.{alternative,rejection_reason}` +
  `rejected_far.{alternative,rejection_reason}`
- emit one `Window Model Decision` with `window_model` + `window_reason` +
  `rejected_near.{alternative,rejection_reason}` +
  `rejected_far.{alternative,rejection_reason}`
- if any required spatial feature is illegal under the chosen container, fix
  the conflict here, not at build time
- `incremental_patch` mode skips this phase and inherits from the target
  module; record the inheritance explicitly

### Phase 5 — Plan (Spatial Layout Contract)

- produce `Spatial Layout Contract` directly — it owns the entire layout tree
  including `windows`, `regions`, `repeated_structures`, and `states`
- there is no separate "internal layout tree" step; the contract IS the tree
- `incremental_patch` mode emits `Patch Contract` instead

### Phase 6 — Build (implementation)

- existing module mode: minimal in-place edits, preserve root architecture
- new project mode: scaffold the complete base project with `pico-cli project create --template <planar|volumetric|stage>` (entry chain + manifest meta included); for Stage only, refine the immersion variant with `scripts/inject_container.py --container STAGE_*`
- wire entry, manifest, root container, and UI tree according to the contract
- for new modules or any `settings.gradle.kts` include change, trigger Android Studio
  **Sync Project with Gradle Files** before final handoff; if no IDE sync API is
  available, Phase 7's Gradle project discovery is only a CLI proxy and the
  final handoff must explicitly tell the user to run IDE sync before the first
  Android Studio run/configuration selection

### Phase 7 — Verify (contracts → legality → build → style → adapter hooks)

- run `scripts/check_adapter_contract.py` — validates adapter registry shape,
  seven-field adapter markdown contracts, and Figma hook ordering
- run `scripts/check_workflow_artifacts.py` — validates required workflow
  artifacts before code-level checks
- run `scripts/check_layout_structure.py` — writes
  `.scratch/legality_check_result.json` (artifact-level legality)
- run `scripts/scan_implementation.py` — writes
  `.scratch/implementation_scan_result.json` (code-level: root match, entry
  wiring, manifest meta-data, Stage-only API symbols, spatial-import whitelist)
- run Gradle sync / project discovery (`scripts/gradle_sync_check.sh`) — writes
  `.scratch/gradle_sync_result.json`; this verifies the new module is visible to
  Gradle and records whether Android Studio sync still must be run manually
- run smoke build (`scripts/smoke_build.sh` or module Gradle task)
- run runtime launch check (`scripts/runtime_launch_check.sh`) when an adb device / emulator is available; this must install the APK, resolve the launcher Activity, call `am start -W`, and scan for immediate `AndroidRuntime` crashes
- run architecture conventions and JVM unit tests when not explicitly skipped
- run spatial-ui-design-style admission as a mandatory gate for generated Compose UI; missing verifier, missing source root, verifier failure, or `--skip-design-style` is a hard failure and must write `.scratch/design_style_result.json`
- run selected adapter hooks after normal machine gates; for Figma this means
  `d2c_verify_code` exactly once, targeted Critical / Moderate fixes only, then
  `d2c_cleanup_temp` exactly once; persist `.scratch/adapter_hooks_result.json`
- compare implementation back against `Input Envelope` + `Normalized Spatial
  Spec` + `Spatial Layout Contract`; the JSON artifacts above already cover
  most of this — the LLM only owns the residual semantic-faithfulness review

Recommended generated-project command:

```bash
bash <skill-root>/scripts/validate_workflow_and_build.sh <target>
```

## Conflict-resolution priority

When sources disagree, apply this order:

1. **explicit user requirement**
2. **existing module architecture**
3. **professional design / high-confidence design source**
4. **visual reference evidence**
5. **product-doc descriptive hints**
6. **conservative default**

Record any material override in `evidence_trace`.

## Conservative fallback policy

If evidence is weak:

- `container` → default `ON_PLAIN`
- `window_model` → default `single_panel`
- overlay handling → default panel-local overlay
- spatial features → default none
- generation mode → existing module if clearly named, otherwise new project

Defaults are allowed.
Silent invention is not allowed.

## Required gates

### Gate 1 — Input sufficiency

Before spatial decisions, you must know or conservatively propose:

- generation mode
- app type or closest archetype
- one container candidate
- one or more window model candidates

If Gate 1 fails:

- keep extracting evidence, or
- fall back conservatively and record the assumption, or
- ask the user only if the unresolved issue changes root architecture or target location

### Gate 2 — Container legality

Before implementation:

- container choice must not conflict with required spatial features
- existing module mode must not silently switch root architecture

If Gate 2 fails:

- revise `spatial_intent`, or
- remove conflicting spatial features, or
- explicitly escalate the architecture change with justification

### Gate 3 — Window model singularity

- exactly one primary `window_model`
- key rejected alternatives are recorded

If Gate 3 fails:

- compare the closest alternatives directly, then choose one, or
- default to the smaller explanation and record the assumption

### Reflection-field requirements at decision gates

To prevent Step Output from collapsing into mechanical PASS templates, the
following gates MUST emit non-empty, **citation-bearing** reflection fields.
"Citation-bearing" means the value points to a concrete `Evidence Packet.facts`
key, a row of the `Container × feature legality` table, or a numbered rule of
the Subwindow-vs-multi_window escalation rule. Generic phrases like "not
needed" / "not applicable" / "no evidence" are BLOCK conditions.

- `Container Decision` → `rejected_near.{alternative, rejection_reason}` and
  `rejected_far.{alternative, rejection_reason}`
  - `rejected_near.alternative` must be a neighbouring container of the
    chosen one (e.g. `ON_PLAIN ↔ IN_VOLUME`, `STAGE_MIXED ↔ STAGE_PROGRESSIVE`)
  - `rejected_far.alternative` must be a distant container (e.g. ON_PLAIN vs
    STAGE_MIXED)
  - both `rejection_reason` values must cite either a `facts.<key>` value or a
    row of the legality table in `references/container-decision.md`
- `Window Model Decision` → `rejected_near.{alternative, rejection_reason}` and
  `rejected_far.{alternative, rejection_reason}`
  - `rejected_near` is the closest competing model
  - `rejected_far` is the most-different competing model
  - both `rejection_reason` values must cite either a `facts.<key>` value, an
    escalation rule (#1 / #2 / #3), or a hard rule from
    `references/window-model-decision.md`
- `Spatial Layout Contract` → `evidence_trace[]` must contain at least one
  entry per primary window, AND each entry must include a `fact_ref` field
  citing a concrete `Evidence Packet.facts.<key>` or Phase 4 decision field
- `Legality Check Result` → read `failures_or_explicit_none` directly from
  `.scratch/legality_check_result.json` (written by
  `scripts/check_layout_structure.py`). Either a list of concrete failures, or
  the literal string `"none"`. A bare PASS is not allowed.
- `Implementation Scan Result` → read `failures_or_explicit_none` directly from
  `.scratch/implementation_scan_result.json` (written by
  `scripts/scan_implementation.py`). Same shape as legality check.

### Gate 4 — Contract completeness

Before coding, `Spatial Layout Contract` must include at least:

- `container`
- `container_reason`
- `window_model`
- `window_reason`
- `windows`
- `regions`
- `repeated_structures`
- `states`

If Gate 4 fails:

- do not generate code
- finish the missing fields first

### Gate 5 — Implementation legality

Before build:

- allowed component names only
- correct root node for the chosen container
- Stage-only APIs stay in Stage flows

If Gate 5 fails:

- fix the implementation plan before running the build
- never use the build step to discover avoidable architecture illegality

### Gate 6 — Structural review

After build:

- compare implementation against input evidence
- verify assumptions are disclosed in the final handoff

If Gate 6 fails:

- revise the generated structure or handoff summary
- do not declare success yet

## Final self-review checklist

Before handoff, verify all of the following:

- Did I classify the input mode explicitly?
- Did I emit an `Input Envelope`?
- Did I emit an `Evidence Packet` before spatial decisions?
- Did I emit one coherent `Normalized Spatial Spec`?
- Did I record ambiguity in an `Assumption Ledger` when needed?
- Did I choose exactly one primary container?
- Did I choose exactly one primary window model?
- Did I emit a complete `Spatial Layout Contract`?
- In existing module mode, did I preserve the current root architecture unless justified otherwise?
- Did I avoid inventing `multi_window`, Stage behavior, or unsupported SDK components?
- Did I run legality checks before build?
- Did I compare the output back to the input evidence before handoff?

## Hard-fail conditions

Any of the following is a workflow failure even if the build passes:

1. skips normalization and jumps straight from input to code
2. does not emit a `Spatial Layout Contract`
3. silently switches root container in existing module mode
4. invents `multi_window` without disconnected-surface evidence
5. uses Stage-only API from a WindowContainer flow
6. hides critical assumptions instead of recording them explicitly

## Golden workflow examples

Use the following examples as execution-shape references. They are not rigid
content templates, but they demonstrate the expected order, artifact style, and
gate discipline.

### Example A — `visual_design`

Scenario: professional design link for a workspace-style file app.

#### Step 0

```json
{
  "input_mode": "visual_design",
  "generation_mode": "new_project",
  "target_module": null,
  "input_sources": [
    { "type": "figma_url", "trust_level": "high" }
  ]
}
```

```text
Step Output
- Artifact: Input Envelope
- Summary: one design-link source is present and no existing module target is specified.
- Key fields: input_mode=visual_design / generation_mode=new_project / input_sources=[figma_url]
- Gate result: PASS
- Next action: proceed to Evidence Packet
```

#### Step 1

```json
{
  "facts": {
    "app_type_candidates": ["file_manager", "workspace_dashboard"],
    "regions": ["sidebar", "search_bar", "content_grid"],
    "repeated_structures": ["nav_item", "file_card"],
    "visible_states": ["selected_nav_item"],
    "spatial_cues": ["flat_panel"]
  },
  "unknowns": ["contextual_popup_persistence"],
  "conflicts": [],
  "confidence": {
    "layout": 0.92,
    "interaction": 0.64,
    "spatial_mode": 0.79
  }
}
```

```text
Step Output
- Artifact: Evidence Packet
- Summary: the design clearly supports a windowed productivity layout.
- Key fields: sidebar / search_bar / content_grid / flat_panel
- Gate result: PASS
- Next action: proceed to Normalized Spatial Spec
```

#### Step 2-7 summary

```json
{
  "request_context": {
    "generation_mode": "new_project",
    "target_module": null,
    "output_dir": "./fig-workspace"
  },
  "product_intent": {
    "app_type": "file_manager",
    "primary_user_goal": "browse and open project files",
    "core_tasks": ["navigate", "search", "preview"]
  },
  "spatial_intent": {
    "container_candidate": "ON_PLAIN",
    "container_confidence": 0.79,
    "spatial_features": [],
    "immersion_need": "none"
  },
  "window_intent": {
    "window_model_candidate": "sidebar_content",
    "window_confidence": 0.88,
    "surfaces": [
      { "id": "main", "role": "primary_panel" }
    ]
  },
  "layout_intent": {
    "regions": [
      { "id": "sidebar", "type": "nav_region" },
      { "id": "content", "type": "content_region" },
      { "id": "search_bar", "type": "toolbar_region" }
    ],
    "repeated_structures": ["nav_item", "file_card"],
    "states": ["selected_nav_item"]
  },
  "ambiguities": [
    {
      "key": "contextual_popup_persistence",
      "default_decision": "treat_as_overlay",
      "reason": "smallest explanation first"
    }
  ],
  "evidence_trace": [
    {
      "claim": "ON_PLAIN",
      "because": "design shows a flat coordinated business panel with no immersive cues"
    }
  ]
}
```

```text
Step Output
- Artifact: Normalized Spatial Spec
- Summary: the polished design is normalized into a conservative windowed workspace app.
- Key fields: container_candidate=ON_PLAIN / window_model_candidate=sidebar_content / ambiguities=[popup overlay]
- Gate result: PASS
- Next action: proceed to Assumption Ledger and layout contract
```

Good outcome:

- keep `ON_PLAIN`
- keep one coordinated window
- do not upgrade to Stage just because the design looks polished

### Example B — `product_doc`

Scenario: PRD-only request for a workspace files app.

#### Step 0-1 summary

```json
{
  "input_mode": "product_doc",
  "generation_mode": "new_project",
  "target_module": null,
  "input_sources": [
    { "type": "prd_markdown", "trust_level": "high" }
  ]
}
```

```json
{
  "facts": {
    "app_type_candidates": ["file_manager"],
    "regions": ["persistent_navigation", "search_area", "content_area"],
    "interaction_cues": ["search", "list_grid_switch", "file_selection"],
    "spatial_cues": []
  },
  "unknowns": ["exact visual density", "card vs row layout"],
  "conflicts": [],
  "confidence": {
    "layout": 0.58,
    "interaction": 0.81,
    "spatial_mode": 0.42
  }
}
```

```text
Step Output
- Artifact: Evidence Packet
- Summary: the PRD strongly defines tasks and structure, but visual form is sparse.
- Key fields: persistent_navigation / content_area / no spatial_cues
- Gate result: PASS
- Next action: proceed to Normalized Spatial Spec
```

#### Step 2-4 summary

```json
{
  "request_context": {
    "generation_mode": "new_project",
    "target_module": null
  },
  "product_intent": {
    "app_type": "file_manager",
    "primary_user_goal": "browse workspace files",
    "core_tasks": ["navigate", "search", "select"]
  },
  "spatial_intent": {
    "container_candidate": "ON_PLAIN",
    "container_confidence": 0.42,
    "spatial_features": [],
    "immersion_need": "none"
  },
  "window_intent": {
    "window_model_candidate": "sidebar_content",
    "window_confidence": 0.73,
    "surfaces": [
      { "id": "main", "role": "primary_panel" }
    ]
  },
  "layout_intent": {
    "regions": ["sidebar", "search_bar", "content"],
    "repeated_structures": ["nav_item"],
    "states": []
  },
  "ambiguities": [
    {
      "key": "visual_density",
      "default_decision": "use_simple_business_layout",
      "reason": "PRD provides tasks but not high-fidelity visual detail"
    }
  ],
  "evidence_trace": [
    {
      "claim": "ON_PLAIN",
      "because": "the PRD does not justify immersive or volumetric behavior"
    }
  ]
}
```

```json
[
  {
    "assumption": "Use a simple coordinated panel layout because the PRD does not specify visual depth or detached surfaces",
    "impact": "container_choice,window_model,layout_density",
    "confidence": 0.69
  }
]
```

```text
Step Output
- Artifact: Assumption Ledger
- Summary: weak visual evidence is handled explicitly instead of silently guessed.
- Key fields: simple coordinated panel / no immersive cues / no detached surfaces
- Gate result: PASS
- Next action: proceed to container and window decisions
```

Good outcome:

- default to `ON_PLAIN`
- one coordinated window
- assumptions are visible
- no invented Stage, anchors, or multi-window behavior

### Example C — `intent_only`

Scenario: one-line intent for a media room app.

#### Step 0-4 summary

```json
{
  "input_mode": "intent_only",
  "generation_mode": "new_project",
  "target_module": null,
  "input_sources": [
    { "type": "text_prompt", "trust_level": "high" }
  ]
}
```

```json
{
  "request_context": {
    "generation_mode": "new_project",
    "target_module": null
  },
  "product_intent": {
    "app_type": "media_room",
    "primary_user_goal": "browse a small video library and play one item",
    "core_tasks": ["browse", "select", "watch"]
  },
  "spatial_intent": {
    "container_candidate": "ON_PLAIN",
    "container_confidence": 0.67,
    "spatial_features": [],
    "immersion_need": "none"
  },
  "window_intent": {
    "window_model_candidate": "single_panel",
    "window_confidence": 0.71,
    "surfaces": [
      { "id": "main", "role": "primary_panel" }
    ]
  },
  "layout_intent": {
    "regions": ["library_strip", "player_surface"],
    "repeated_structures": ["media_item"],
    "states": ["selected_media_item"]
  },
  "ambiguities": [
    {
      "key": "player_layout",
      "default_decision": "single main viewing surface",
      "reason": "the prompt asks for one main panel and rejects multi-window workspace behavior"
    }
  ],
  "evidence_trace": [
    {
      "claim": "single_panel",
      "because": "the intent describes one viewing experience rather than multiple coordinated work surfaces"
    }
  ]
}
```

```text
Step Output
- Artifact: Normalized Spatial Spec
- Summary: sparse intent is converted into one simple viewing-centered app shape.
- Key fields: ON_PLAIN / single_panel / library_strip + player_surface
- Gate result: PASS
- Next action: proceed to layout contract
```

Good outcome:

- simple, conservative app shape
- explicit assumptions
- no extra spatial richness unless explicitly requested

### Example D — `hybrid`

Scenario: stage-like concept image + explicit user request to preserve the existing `myapp` windowed module.

#### Step 0-3 summary

```json
{
  "input_mode": "hybrid",
  "generation_mode": "existing_module",
  "target_module": "myapp",
  "input_sources": [
    { "type": "concept_image", "trust_level": "medium" },
    { "type": "prd_markdown", "trust_level": "medium" },
    { "type": "text_prompt", "trust_level": "high" }
  ]
}
```

```json
{
  "facts": {
    "app_type_candidates": ["file_manager", "workspace_tool"],
    "regions": ["sidebar", "content"],
    "spatial_cues": ["concept_image_suggests_stage_like_scene"],
    "interaction_cues": ["navigate", "search", "select"]
  },
  "unknowns": ["whether the concept image is literal runtime architecture or only mood"],
  "conflicts": [
    {
      "key": "root_architecture",
      "source_a": "concept image suggests stage-like richness",
      "source_b": "explicit user requirement says preserve existing windowed architecture in myapp"
    }
  ],
  "confidence": {
    "layout": 0.75,
    "interaction": 0.68,
    "spatial_mode": 0.49
  }
}
```

```json
{
  "request_context": {
    "generation_mode": "existing_module",
    "target_module": "myapp"
  },
  "product_intent": {
    "app_type": "file_manager",
    "primary_user_goal": "update the existing workspace-like file panel",
    "core_tasks": ["navigate", "search", "preview"]
  },
  "spatial_intent": {
    "container_candidate": "ON_PLAIN",
    "container_confidence": 0.74,
    "spatial_features": [],
    "immersion_need": "none"
  },
  "window_intent": {
    "window_model_candidate": "sidebar_content",
    "window_confidence": 0.81,
    "surfaces": [
      { "id": "main", "role": "primary_panel" }
    ]
  },
  "layout_intent": {
    "regions": ["sidebar", "content"],
    "repeated_structures": ["nav_item", "file_card"],
    "states": []
  },
  "ambiguities": [
    {
      "key": "concept_image_literalness",
      "default_decision": "treat_as_visual_tone_not_root_architecture",
      "reason": "explicit user requirement and existing module architecture outrank concept-image mood"
    }
  ],
  "evidence_trace": [
    {
      "claim": "preserve DefaultWindowContainer",
      "because": "explicit user requirement + existing myapp module architecture outrank concept-image richness"
    }
  ]
}
```

```text
Step Output
- Artifact: Normalized Spatial Spec
- Summary: the workflow resolves the hybrid conflict in favor of explicit user requirement and existing module architecture.
- Key fields: existing_module=myapp / container_candidate=ON_PLAIN / concept_image_literalness handled explicitly
- Gate result: PASS
- Next action: proceed to window model and layout contract
```

Good outcome:

- preserve `myapp` module architecture
- preserve `DefaultWindowContainer`
- use the concept image only as a tone/visual reference, not as forced Stage evidence
