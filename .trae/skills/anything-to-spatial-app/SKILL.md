---
name: anything-to-spatial-app
description: Use when creating or materially updating a PICO Spatial Android/Kotlin app from Figma, screenshot/mockup, PRD, intent, hybrid sources, or a bounded panel patch, and the task requires choosing or preserving container, window model, panel hierarchy, and layout regions. NOT for SDK upgrades, empty-dir bootstrap, legacy Android porting, pure code review/refactor, old-baseline D2C A/B evaluation, or performance diagnosis.
license: 'Apache-2.0'
---

# Anything → PICO Spatial App

> 🔴 **Contract touchpoints** (progressive loading; read at the phase that needs them):
>
> 1. `references/workflow-contract.md` — Phase 1 / 4 / 5 / 7 artifact schemas, reflection fields, Backtrack table.
> 2. `references/architecture-conventions.md` — Phase 6 before Kotlin/Compose code: layered packages + ViewModel/UseCase/Repository + unit-test floor.
> 3. `../spatial-ui-design-style/SKILL.md` — Phase 6 before Compose UI, especially screenshot/visual codegen: PicoTheme / Material / hover / click+haptics / R1–R8 rules. This is a generation-time contract, not a final lint suggestion.

## Boundary

| Situation                             | Use instead                 |
| ------------------------------------- | --------------------------- |
| Upgrade SDK / migrate deprecated APIs | `spatial-sdk-update`        |
| First-time scaffold from empty dir    | `spatial-app-onboarding`    |
| Migrate 2D Android app to spatial     | `porting-android-app`       |
| Performance diagnosis                 | `spatial-app-perf-diagnose` |
| 3D bbox / placement planning          | `spatial-sdk-scene-builder` |

## Reference index (read on demand, never preemptively)

| Read when …                                                                         | File                                                                       |
| ----------------------------------------------------------------------------------- | -------------------------------------------------------------------------- |
| Phase 1 / 3 — input mode + evidence                                                 | `references/input-normalization.md`, `references/evidence-extraction.md`   |
| Phase 3 / 5 — visual layout inference, region decomposition, repeated/state mapping | `references/layout-inference.md`                                           |
| Phase 1.5 — adapter dispatch                                                        | `adapters/_registry.json` + the selected `adapters/*-adapter.md`           |
| Phase 1.5 — validating/editing adapter contract or registry                         | `ADAPTER_ROADMAP.md`                                                       |
| Phase 4 — container + window model                                                  | `references/container-decision.md`, `references/window-model-decision.md`  |
| Phase 4 — `spatial_features` includes anchor / env_mesh                             | `references/spatial-anchor.md` _(BLOCK if anchor in WindowContainer)_      |
| Phase 5 — layout schema                                                             | `references/layout-schema.md`                                              |
| Phase 6 — entry chain + manifest                                                    | `references/manifest-and-entry.md` + (`window-container.md` OR `stage.md`) |
| Phase 6 — gradle setup errors                                                       | `references/gradle-setup.md`                                               |
| Phase 6 — SpatialUI component whitelist                                             | `references/spatial-ui-components.md`                                      |
| Phase 6 / figma-adapter — Figma tokens + visual feature mapping                     | `references/figma-mapping.md`                                              |
| Phase 6 / figma-adapter — SpatialUI import lookup                                   | `references/spatial-api-imports.md`                                        |
| Phase 4 / 6 — window-level components, Subwindow, floating layers                   | `references/spatial-windows-guide.md`                                      |
| Phase 6 — architecture + tests                                                      | `references/architecture-conventions.md`                                   |

## 7-phase flow

| #   | Name              | Artifact                                                                     | Gate                                                                  |
| --- | ----------------- | ---------------------------------------------------------------------------- | --------------------------------------------------------------------- |
| 1   | Frame             | `Input Envelope`                                                             | inputs explicit                                                       |
| 1.5 | Adapter Selection | selected adapter + hook plan; execution may wait for Phase 2 workspace facts | one adapter max per current input_mode                                |
| 2   | Read              | inspection notes                                                             | (none)                                                                |
| 3   | Spec              | `Evidence Packet` + `Normalized Spec` + `Assumption Ledger`                  | normalization complete                                                |
| 4   | Decide            | `Container Decision` + `Window Model Decision`                               | legality + singularity                                                |
| 5   | Plan              | `Spatial Layout Contract` (or `Patch Contract`)                              | contract complete                                                     |
| 6   | Build             | code edits / scaffold                                                        | (verified in 7)                                                       |
| 7   | Verify            | 11-step gate / adapter hooks (see Phase 7)                                   | machine-driven + Gradle sync + runtime launch + agent-owned MCP hooks |

`incremental_patch` mode skips Phase 4 (inherit from existing module) and Phase 5 emits a `Patch Contract`.

## Operating protocol

- **Sequential & gated.** No skipping artifacts; no proceeding past a failed gate. On failure: fix the artifact, apply a conservative default and log it in `Assumption Ledger`, or ask the user only if the unresolved issue materially changes the architecture.
- **Output language.** Match the user's natural language for prose; artifact JSON keys, command names, and `Step Output` labels stay in canonical English.
- **Persistence.** Artifacts live under `<target>/.scratch/` with canonical filenames (`input_envelope.json`, `evidence_packet.json`, `normalized_spatial_spec.json`, `assumption_ledger.json`, `spatial_layout_contract.json` or `patch_contract.json`). `assumption_ledger.json` is always present; use `[]` only when no assumptions exist.
- **Resume.** If `<target>/.scratch/` already contains valid artifacts when the skill starts, do NOT re-emit them — re-run the scripts (cheap) and resume at the first missing/failing artifact. Delete `.scratch/` only on explicit "redo from scratch".
- **Step Output (Phases 1, 4, 5, 7 only).**

  ```text
  Step Output
  - Artifact: <name>
  - Summary: <1-3 lines>
  - Key fields: <bullets>
  - Reflection: <citation per workflow-contract.md>
  - Gate result: PASS | BLOCKED
  - Next action: proceed | revise | conservative default | ask user
  ```

---

## Phase 1 — Frame

Apply input-mode routing in order, first match wins:

```
Step A — incremental_patch?
  IF user names a specific file/panel/module
     AND scope ≤ 1–2 regions or components
     AND root container + window model do NOT need to change
  THEN input_mode = incremental_patch.   STOP.

Step B — otherwise classify by strongest source:
  Figma URL                     → visual_design
  screenshot / mockup image     → visual_reference
  PRD / long-form spec          → product_doc
  one-line ask only             → intent_only
  more than one of the above    → hybrid
```

| User says                                               | Routing             |
| ------------------------------------------------------- | ------------------- |
| "add a search field to `MainPanel.kt` in `myapp`"       | `incremental_patch` |
| "use this Figma to redesign `myapp`'s home page"        | `visual_design`     |
| "use this Figma to add a close button to `DetailPanel`" | `incremental_patch` |

Decide `generation_mode` (`existing_module` / `new_project`) and emit `Input Envelope` per workflow-contract.md §1. Gate: `input_mode`, `generation_mode`, target / output, `input_sources[]` all explicit.

## Phase 1.5 — Adapter Selection

Read `adapters/_registry.json`, select exactly one active adapter for the current `input_mode`, then read only that adapter. Zero / multiple active matches = BLOCKED. This phase selects the adapter and installs any `hooks.verify` / `hooks.cleanup` plan for Phase 7; it does not force evidence extraction before workspace facts exist. If the selected adapter requires target platform, root container, or existing window model, execute that adapter after Phase 2 inspection and feed its output into Phase 3. Adapters are limited to existing `evidence_packet.json` / `normalized_spatial_spec.json` / `assumption_ledger.json` schema fields; their seven-field contract lives in `ADAPTER_ROADMAP.md`.

Adapter `failure_mode` may explicitly reroute to another `input_mode` (for example Figma → screenshot fallback). In that case, revise `input_envelope.json`, return to Phase 1.5, and select exactly one adapter for the new mode. Do not chain a second adapter under the old `input_mode`, and do not silently fall back.

## Phase 2 — Read (workspace inspection, only in `existing_module` / `incremental_patch`)

Inspect: module `build.gradle.kts`, `AndroidManifest.xml`, `Main.kt`, `platform/SpatialApplication.kt`, `platform/LaunchActivity.kt`, existing `res/`. Prefer editing existing files; reuse package, manifest wiring, resources. Preserve current root container unless user explicitly asks otherwise or the requested feature is impossible. No JSON artifact — carry notes into Phase 3.

## Phase 3 — Spec

Emit, in order:

1. **Evidence Packet** — `facts` / `unknowns` / `conflicts` / `confidence`. Facts only.
2. **Normalized Spatial Spec** — `request_context` / `product_intent` / `spatial_intent` / `window_intent` / `layout_intent` / `ambiguities` / `evidence_trace`. On disagreement, pick one and explain the tie-break in `evidence_trace` (no parallel truths).
3. **Assumption Ledger** — every architecture-impacting default with `assumption` / `impact` / `confidence`.

### Visual input guardrails (HARD)

For screenshots/mockups, keep the main skill path concise and classify evidence by responsibility before planning. Detailed schemas and examples live in `references/workflow-contract.md` §5.
For `visual_reference` work, read `references/layout-inference.md` before finalizing `layout_intent`, `content_layout_metrics`, or `visual_content_contract`; use it to decompose regions, catch repeated/stateful structures, and sanity-check overlay relationships.

- Split visual evidence into app-owned window, window ornaments, page content, temporary floating layer, and spatial environment context.
- Do not treat passthrough / skybox / floor / scenery / system safety lines as app content unless facts prove the app owns them.
- Edge-pinned long-lived rails / tabs / toolbars are `window_chrome_ornaments[]` (`TabBar` / `Toolbar` / `Subwindow`), not page children. Not `multi_window` does not mean page content.
- `visual_reference` must carry `reference_frame`, `content_layout_metrics`, and `visual_content_contract`; derive window size from `app_owned_bbox_px`, not the full screenshot.
- Bind measured sizes and visual semantics to Phase-6 constants/components (`SideNavigation`, `SearchField`, fixed grids, asset-backed image cards) instead of magic dp or placeholder UI.

Gate: enough evidence to choose `generation_mode`, propose one `container_candidate`, compare ≥1 `window_model_candidate`. No hidden assumptions.

## Phase 4 — Decide (skip in `incremental_patch`)

Read `references/container-decision.md` and `references/window-model-decision.md`. If `spatial_features` includes anchor / env_mesh, also read `references/spatial-anchor.md` and resolve legality now.

### 4a. Container Decision

Required: `container` / `container_reason` / `container_evidence[]` / `rejected_near.{alternative, rejection_reason}` / `rejected_far.{alternative, rejection_reason}`.

### 4b. Window Model Decision

Choose one of: `single_panel`, `single_panel_with_popup`, `sidebar_content`, `master_detail`, `window_plus_subwindow`, `multi_window`. Same field requirements as 4a. Apply the Subwindow-vs-`multi_window` escalation rule from `window-model-decision.md`.

**Reflection (HARD):** `rejected_near` neighbouring, `rejected_far` distant; both `rejection_reason` MUST cite a concrete `Evidence Packet.facts.<key>`, a row of the legality table, or an escalation rule number. "not needed" / "not applicable" / "no evidence" = BLOCK. Apply legality inline — do NOT defer to Phase 7.

## Phase 5 — Plan

The contract IS the layout tree (no separate "internal" step). Required fields per workflow-contract.md §5:

If the input is screenshot/mockup-driven, consult `references/layout-inference.md` to sanity-check (do not re-derive) `regions[]`, `repeated_structures[]`, `states[]`, and `window_chrome_ornaments[]`; `layout_intent` stays frozen from Phase 3.

- `container` / `container_reason` / `window_model` / `window_reason`
- `reference_frame` (`visual_reference` only) — screenshot px, app-owned bbox, target window dp, scale policy
- `content_layout_metrics` (`visual_reference` only) — panel padding, measured region rects, repeated item sizes/gaps. This is mandatory when generating screenshot-based page content.
- `visual_content_contract` (`visual_reference` only) — sidebar surface/search/chip semantics, tab visible count/style, card content/overlay/asset policy.
- `window_chrome_ornaments[]` (when present) — `id`, `type` (`TabBar` / `Toolbar` / `Subwindow`), `placement`, `role`; ornaments are siblings of the main page, not main-page children.
- `windows[]` — `id`, `role`, `anchor`, `default_visibility`, `children`
- `regions[]` — hierarchy / states / alignment / size
- `repeated_structures[]`, `states[]`
- `evidence_trace[]` — ≥1 entry per primary window, each `fact_ref` citing a concrete `Evidence Packet.facts.<key>` or a Phase 4 decision field. "because the design says so" = BLOCK.

Persist to `<target>/.scratch/spatial_layout_contract.json`. For `incremental_patch`, emit `Patch Contract` instead.

## Phase 6 — Build

### Module mode rules

- **Existing module:** keep namespace/package, manifest wiring, entry chain. Allowed: new Compose files, new drawables/strings, new state holders. NOT allowed without explicit escalation: switching root container, changing `pico.spatial.windowcontainer.*` meta, introducing Stage-only APIs (anchor / ECS / env_mesh) inside a WindowContainer.
- **New project:** scaffold the project with `pico-cli project create` (it owns the bundled template, entry chain, and manifest), then refine the Stage variant if needed.

  ```bash
  pico-cli project create \
    --dir <output-dir> --name <name> --package <com.example.foo> \
    --template <planar|volumetric|stage>
  # Stage only: pick the immersion variant (the three templates can't express it)
  python3 -m scripts.inject_container \
    --output <output-dir> \
    --container <STAGE_MIXED|STAGE_PROGRESSIVE|STAGE_FULL>
  ```

  Map the Phase-4 container to a CLI template: `ON_PLAIN → planar`, `IN_VOLUME → volumetric`, `STAGE_MIXED|STAGE_PROGRESSIVE|STAGE_FULL → stage`. `pico-cli project create` produces a complete, runnable base project — package layout, `Main.kt` entry chain, and a fully-populated `AndroidManifest.xml` with the container meta-data already in place. Do NOT re-scaffold, rewrite `mainApp`, or re-insert manifest meta; build your UI on top of the generated entry point. The only post-CLI step is for Stage: all three `STAGE_*` variants share `--template stage`, so run `inject_container --container STAGE_*` to set the `pico.spatial.stage.style` / `immersion` values (it edits the meta already present, and is a no-op for the WindowContainer cases).

Entry chain: `Application.onCreate { launch(::mainApp) }` → `mainApp(scope: SpatialAppScope)` → `DefaultWindowContainer {}` or `DefaultStage {}` → `SpatialLaunchActivity`.

**Android Studio sync is mandatory for new modules.** After creating or including
a new module (`settings.gradle.kts` changed), trigger Android Studio
**Sync Project with Gradle Files** before claiming the app can be run from the
IDE. If no IDE-sync API is available to the agent, run the Gradle project
discovery proxy in Phase 7 and explicitly tell the user that Android Studio sync
is still required before the first IDE run/configuration selection.

### UI rules (layered + minimal)

- Generate UI from the Phase-5 contract only: root container → window ornaments → windows → regions → reusable components.
- Read `../spatial-ui-design-style/SKILL.md` before Compose UI; generated code must pass design-style admission without skip/degraded mode.
- Prefer SpatialUI built-ins and documented imports; never invent SDK names. `PicoTheme {}` wraps windowed UI; `windowConstraints(...)` is resize bounds, not first-open size.
- Keep business UI 2D unless Phase 5 justifies 3D / Stage behavior; Stage-only APIs stay out of WindowContainer flows.
- Implement `window_chrome_ornaments[]` with window-level fittings, and implement `reference_frame` / `content_layout_metrics` / `visual_content_contract` through named constants, state, and components.
- Custom interactive components must follow spatial-ui-design-style indication + haptics rules, or use a built-in component that provides them.

### Architecture rules (HARD)

Read `references/architecture-conventions.md` before code. The checker enforces layered packages, thin `Main.kt`, MVI-lite state, repository boundaries, mandatory ViewModel tests, and UseCase + UseCase tests when the screen has non-trivial business rules, filtering, sorting, selection, or data transformation.

## Phase 7 — Verify (machine-driven)

Run `bash scripts/validate_workflow_and_build.sh <target>`. The canonical 11-step order, skip semantics, JSON outputs, and Figma hook ordering are in `references/workflow-contract.md`; smoke-build diagnosis is in `references/gradle-setup.md`. `spatial-ui-design-style` admission is non-optional for generated Compose UI: missing verifier, missing source root, verifier failure, or `--skip-design-style` must fail the run.

Do NOT paraphrase JSON results — read `passed` / `summary.errors` / `failures_or_explicit_none` literally. `verification_summary.json.clean: false` means a degraded run; it exits non-zero unless `--allow-degraded` was explicit. Disclose every `warnings[]` / `skips[]` entry and never imply skipped gates passed.

**Backtrack:** after 2 consecutive failures at the same check, return to the originating phase per the Backtrack table in `references/workflow-contract.md`. Edit the offending artifact first; do not silently rewrite code that contradicts an unchanged contract.

**Structural review** (LLM-owned, semantic): repeated structures preserved as templates; selected/disabled/highlighted states represented in state holders; no UI added beyond input; in `existing_module` mode, reuse module resources before adding new ones.

---

## Exit checklist

Run is complete only when ALL hold:

1. `validate_workflow_and_build.sh <target>` exits 0 **and** `<target>/.scratch/verification_summary.json.clean == true`. Only Gradle sync / runtime launch may be environment-degraded, and degraded runs are not complete unless explicitly accepted in handoff.
2. For new modules, Android Studio **Sync Project with Gradle Files** has been triggered, or the final handoff explicitly states that the user must trigger it before first IDE run because no IDE sync API was available.
3. `legality_check_result.json`, `implementation_scan_result.json`, `gradle_sync_result.json`, `architecture_check_result.json`, `unit_tests_result.json` all → `"passed": true`.
4. `design_style_result.json.passed == true` and design-style verifier → 0 errors; no `--skip-design-style` / degraded bypass.
5. If the selected adapter declares hooks, `adapter_hooks_result.json.passed == true` and verify / cleanup hooks ran in the registry order.
6. None of the hard-fail conditions in `workflow-contract.md` triggered.

Final handoff:

```text
- Container: <chosen + why>
- Window model: <chosen + why>
- Mode: <existing module update | new scaffold | incremental_patch>
- Path: <module path | output path>
- Android Studio sync: <done | user must run Sync Project with Gradle Files>
- Assumptions: <explicit list or 'none'>
- Remaining inferred/mock parts: <list>
- Workflow artifacts: Input Envelope / Evidence Packet / Normalized Spec / Assumption Ledger / Spatial Layout Contract (or Patch Contract)
```

## Pitfalls (do not)

- skip workspace inspection when the user names a module (default to `existing_module`)
- restate Phase 3 in Phase 5 (the contract IS the layout tree)
- defer container × feature legality to Phase 7 (decide inline in Phase 4)
- confuse overlay with window — popup menus stay in one panel
- escalate to `multi_window` without independent launcher / lifecycle / placement memory evidence
- invent SDK names; only the whitelist
- over-spatialize a 2D settings UI (most belong in `ON_PLAIN`)
- implement screenshot spatial background, passthrough, floor/trees/skybox, or system safety lines as app content
- fill Step Output with mechanical PASS — Reflection must cite a fact-key or legality-table row
- run the full 7-phase flow on a small patch; use `incremental_patch` mode
- emit code before reading `architecture-conventions.md` and `spatial-ui-design-style/SKILL.md`

**Honesty:** A 2D reference under-specifies a spatial app. State explicitly when passthrough / skybox / depth / hover / haptics / gestures were inferred; flag that anchors and Full Space behaviors require a device.

**Clarification:** ask the user only when one of these is truly unresolved — no visual reference is available, target module / output cannot be inferred, multiple window interpretations are equally plausible and materially change the app structure, or package / namespace conflict cannot be resolved safely. Otherwise proceed with the safest default and state the assumption.
