---
adapter: figma-adapter
input_mode: visual_design
status: active
owner: anything-to-spatial-app
---

# Figma Adapter

Use this adapter when `Input Envelope.input_mode == "visual_design"` and at
least one input source is a Figma URL.

## 1. trigger

Match only when:

- `input_mode == "visual_design"`
- `input_sources[]` contains `type == "figma_url"`
- the request is page / app level rather than a bounded one-component patch

If the user uses a Figma URL only to patch one named component in an existing
file, route to `incremental_patch` instead.

## 2. inputs

Read from `input_envelope.json`:

- `generation_mode`
- `target_module` / `output_dir`
- `input_sources[].url` for the Figma URL
- target platform inferred from workspace inspection; for this skill the default
  implementation target is Android / Kotlin / SpatialUI Compose

## 3. produces

This adapter writes only existing workflow fields:

- `evidence_packet.json`
  - `facts.frame_hierarchy`
  - `facts.regions`
  - `facts.repeated_structures`
  - `facts.visible_states`
  - `facts.spatial_cues`
  - `facts.interaction_cues`
  - `facts.design_tokens`
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
- `assumption_ledger.json` when design evidence is ambiguous

Do not add new top-level schema fields.

## 4. required_tools

- `mcp__codin-d2c-figma-to-code__d2c_get_figma_data`
- `mcp__codin-d2c-figma-to-code__d2c_download_icons` when XML contains `<Icon>`
- `mcp__codin-d2c-figma-to-code__d2c_verify_code` after generated code is complete
- `mcp__codin-d2c-figma-to-code__d2c_cleanup_temp` after verification completes

The adapter itself only prepares evidence and normalized spec. Code verification
and cleanup are Phase 7 obligations, but the adapter declares them so wrapper /
orchestrator code can install hooks.

Hook order for Figma runs:

1. `validate_workflow_and_build.sh <target>` gates pass.
2. `adapter.verify`: call `d2c_verify_code` exactly once with generated /
   significantly modified code files for this design.
3. Apply targeted fixes for Critical / Moderate verifier issues only; do not
   call `d2c_verify_code` again.
4. `adapter.cleanup`: call `d2c_cleanup_temp` exactly once after those targeted
   fixes so XML / preview temp files remain available during repair.

## Fidelity contract for Figma / screenshot inputs

When a Figma XML DSL or screenshot is available, the generated app is expected
to be a visual reproduction, not merely a semantic approximation. Use this
priority order:

1. **1:1 visible geometry first** — preserve frame sizes, absolute offsets,
   gaps, radii, selected/disabled/hidden opacity, and repeated counts from XML
   / screenshot. Do not replace a fixed 4-column grid with a responsive layout
   when the design gives exact card widths.
2. **Window semantics second** — still map true window-level structures to
   SpatialUI (`Subwindow`, `TabBar`, `Toolbar`) instead of drawing fake panels
   inside the page. If exact pixels conflict with window-level placement, keep
   the window-level API and document the offset/size approximation.
3. **Theme rules third** — use `PicoTheme` for named semantic roles, but preserve
   explicit fixed decorative colors from the design (brand colors, chips,
   translucent badges, rating stars, artwork swatches) with
   `// design-style: fixed-figma-color <source>` so the verifier knows the
   literal is intentional. Never use this exception on the root window glass.
4. **Assets before placeholders** — icons, bitmap images, avatars, thumbnails,
   and Figma placeholder art are part of the visual contract. Scan every
   `<Icon download-url>` and `<Image src>`; download/export them before coding
   and bind the generated UI to local assets. Do not replace design images with
   gradients, initials, generic shapes, placeholder URLs, or invented artwork.
   If an asset cannot be fetched after retries, block or record the exact failed
   URL in `assumption_ledger.json` and use a visibly marked fallback only for
   that asset.
5. **Verification is visual** — pass a `ruleContext` to `d2c_verify_code` that
   says exact geometry/colors/states should be checked against XML/screenshot;
   apply all Critical and Moderate fidelity findings unless they violate a
   stronger SpatialSDK legality rule.

## 5. required_references

- `references/figma-mapping.md`
- `references/spatial-api-imports.md`
- `references/spatial-windows-guide.md`
- `../spatial-ui-design-style/references/vibrant-guide.md`
- `../spatial-ui-design-style/SKILL.md`

## 6. side_effects

- `d2c_get_figma_data` writes temporary XML / preview files under
  `.codin_d2c_temp/` in the target code directory.
- The coding agent must read both returned XML and preview image before
  producing evidence.
- Icon download may write assets under the target module resources directory.
- Adapter verify hook: after generated-project validation and design-style
  admission, call `d2c_verify_code` with the same Figma URL, target platform,
  and generated / significantly modified code files. Exclude config, lock,
  unmodified, utility-only, test, and mock files.
- Adapter cleanup hook: after verification and targeted Critical / Moderate
  fixes complete, call `d2c_cleanup_temp` with the same Figma URL and the same
  target directory used for `d2c_get_figma_data`.
- Cleanup must never run before `d2c_verify_code`; verification reuses the
  temporary XML / preview files written by `d2c_get_figma_data`.

## 7. failure_mode

- If Figma fetch fails but a preview image or screenshot is available, this is an
  explicit reroute: revise `input_envelope.json` so
  `Input Envelope.input_mode = "visual_reference"`, return to Phase 1.5, select
  `screenshot-adapter`, and record the degradation in `assumption_ledger.json`.
  Do not run `screenshot-adapter` as a second adapter under `visual_design`.
- If Figma fetch fails and no visual fallback exists, return `BLOCKED` to Phase 1
  with the failing tool name and URL.
- If XML and preview conflict, trust explicit Figma token / hierarchy names for
  component identity, but trust the preview for visible state and gross layout;
  record the conflict in `conflicts[]` or `evidence_trace[]`.

## Procedure

1. Determine platform before fetching. For SpatialSDK Android modules, call
   `d2c_get_figma_data(platform = "android", directory = <target code dir>)`.
2. Read the returned XML DSL and preview image. Do not infer from XML alone.
3. Extract facts only:
   - frame hierarchy and visible regions
   - repeated structures and selected / disabled / highlighted states
   - window-level candidates (`TabBar`, `Toolbar`, `Subwindow`, modal layers)
   - token names, Vibrant annotations, typography roles, assets / icons / images
   - spatial cues: flat panel, visible depth, passthrough, 3D scene, virtual env
4. Use `figma-mapping.md` for token / component / visual feature mapping.
5. Use `spatial-windows-guide.md` to classify floating / edge UI cues as
   evidence; Phase 4 owns deciding the final window-level fitting.
6. Normalize to a single `Normalized Spatial Spec`; do not preserve parallel
   truths for product/layout semantics. For ambiguous floating UI, record the
   visual cues that may support `overlay in main panel`, `SpatialPopup`,
   `Subwindow`, or `multi_window` and leave the final fitting to Phase 4.
7. If XML contains icons that generated code will use, call `d2c_download_icons`
   with the same Figma URL and target platform. If XML contains `<Image src>`,
   download the raster image into an Android drawable resource (`drawable-nodpi`
   for bitmap fidelity) and use `painterResource` / local resource references.
   Network image URLs are acceptable only when explicitly required by the user;
   generated gradients/initials are not acceptable substitutes for Figma images.
8. Pass design-style context forward: generated Compose code must still satisfy
   `../spatial-ui-design-style/SKILL.md` and its verifier, using the annotated
   fixed-color exception only for explicit Figma / screenshot fidelity colors.
9. During Phase 7, call `d2c_verify_code` exactly once with generated / modified
   code files.
10. Apply targeted fixes for Critical / Moderate verifier issues only. Do not
    call `d2c_verify_code` again.
11. Run `adapter.cleanup`: call `d2c_cleanup_temp` exactly once after targeted
    fixes complete. Do not run cleanup before repair because the verifier output
    and temp XML / preview files are needed for visual repair.

## Evidence extraction template

```json
{
  "facts": {
    "frame_hierarchy": ["root", "header", "content"],
    "regions": ["header", "content"],
    "repeated_structures": ["card"],
    "visible_states": ["selected_tab"],
    "spatial_cues": ["flat_panel"],
    "interaction_cues": ["tab_switch", "search"],
    "design_tokens": ["Label Primary", "Fill Tertiary", "Darkest (Vibrant)"],
    "asset_requirements": ["icons"]
  },
  "unknowns": ["runtime data source"],
  "conflicts": [],
  "confidence": {
    "layout": 0.9,
    "interaction": 0.65,
    "spatial_mode": 0.7
  }
}
```
