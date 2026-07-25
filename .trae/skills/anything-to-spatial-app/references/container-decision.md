# Container Decision Tree

This is the most consequential decision when generating a PICO Spatial App.
Read this FIRST before producing the intermediate structure.

## Terminology mapping (skill-internal vs official SDK)

The skill uses 5 internal enums for decision-making purposes. They map to
official PICO concepts as follows (verified against `pico-cli project create`
v0.12.2 generated output):

| Skill enum | pico-cli template | Root DSL | Authoritative manifest meta-data |
|---|---|---|---|
| `ON_PLAIN` | `--template planar` | `DefaultWindowContainer` | `pico.spatial.windowcontainer.style="1"` (Form.Planar) |
| `IN_VOLUME` | `--template volumetric` | `DefaultWindowContainer` | `pico.spatial.windowcontainer.style="2"` (Form.Volumetric); 3D `defaultsize=WxHxD`; extra `volumealignment` + `volumebasepanel` |
| `STAGE_MIXED` | `--template stage` | `DefaultStage` | `pico.spatial.stage.style="1"` (StageStyle.Mixed) |
| `STAGE_PROGRESSIVE` | `--template stage` | `DefaultStage` | `pico.spatial.stage.style="2"` (StageStyle.Progressive) + `immersion` / `immersion_min` / `immersion_max` (range 0–100) |
| `STAGE_FULL` | `--template stage` | `DefaultStage` | `pico.spatial.stage.style="3"` (StageStyle.Full) |

Important caveats:

- `ON_PLAIN` / `IN_VOLUME` are **not** official SDK class names — they are
  this skill's decision shorthand for two manifest-`style` variants of
  `DefaultWindowContainer`. The official SDK distinguishes them through the
  `pico.spatial.windowcontainer.style` value (Form.Planar = 1, Form.Volumetric = 2).
- `MIXED` / `PROGRESSIVE` / `FULL` correspond to `StageStyle` and are chosen
  via the **`pico.spatial.stage.style` manifest meta-data** (NOT via a code
  parameter to `openStage(...)` at runtime). All three `STAGE_*` enums share
  the same `--template stage` base project; only the `style` meta-data value differs.
- `pico.spatial.stage.style="0"` exists in the manifest as Automatic and
  currently defaults to Mixed; this skill does not expose it as a separate
  enum because the runtime behaviour collapses to STAGE_MIXED.
- See `references/spatial-anchor.md` for the authority status of specific
  world-tracking class names referenced in the legality table below.

## Decision priority

Apply these signals in order:

1. **explicit user requirement** — if the user clearly asks for immersive stage / passthrough / anchors / skybox, honor that
2. **existing module architecture** — in existing module mode, keep the current root container unless there is a real reason to change it
3. **input semantics** — use visual or textual cues only after checking 1 and 2
4. **safe default** — if still ambiguous, choose the least disruptive option

## Decision tree

```
Start with the target module, not just the raw reference input.

Q1: Did the user explicitly ask for immersive / stage-only behavior
    (anchors, environment mesh, boundless scene, skybox, passthrough scene)?
├─ YES → choose Stage that matches the requested experience
└─ NO
   Q2: In existing module mode, does the module already use DefaultWindowContainer or DefaultStage?
   ├─ YES → keep that root unless the input clearly requires a different container
   └─ NO
      Q3: Does the input show or describe a real-world / passthrough background behind free spatial content?
      ├─ YES
      │  ├─ content is primarily free 3D scene content → Stage · MIXED
      │  ├─ content is mostly a single flat panel → WindowContainer · ON_PLAIN
      │  └─ content is a boxed panel with visible depth/front-face UI → WindowContainer · IN_VOLUME
      └─ NO
         ├─ virtual environment with adjustable immersion → Stage · PROGRESSIVE
         ├─ fully immersive world with no real-world background → Stage · FULL
         └─ otherwise → WindowContainer · ON_PLAIN
```

## Why the decision is so consequential

The container choice determines four things that are difficult to fix later
without rewriting large parts of the project:

1. **`AndroidManifest.xml` meta-data** — the default container is registered
   at install time.
2. **`mainApp(scope: SpatialAppScope)` root** — `DefaultWindowContainer` vs
   `DefaultStage` are different app shapes.
3. **Space state** — Stage forces the app into Full Space, hiding other apps'
   windows. ON_PLAIN/IN_VOLUME run in Shared Space and coexist with other
   apps.
4. **API legality** — spatial anchors (world-tracking APIs), `scene.rayCast`,
   environment scanning, and Stage-only entities all throw
   `IllegalStateException` if called from a WindowContainer.

## Cheat sheet: visual cues

| Input cue | Strong signal for... |
|---|---|
| Toolbar bar with icons at the bottom of a flat panel | ON_PLAIN |
| Toolbar at the bottom of a 3D cube's front face | IN_VOLUME |
| 3D model rendered inside a rectangular panel boundary | IN_VOLUME, or ON_PLAIN + `SpatialModelView` if the rest of the app is still mostly 2D |
| Real room visible (chairs, floor, walls) behind UI | Stage MIXED |
| Skybox / starfield / virtual room behind UI | Stage PROGRESSIVE or FULL |
| Slider or icon implying "see-through level" | Stage PROGRESSIVE |
| HUD-style overlay locked to head | Possibly Stage FULL with head-locked content |
| Multiple disconnected panels | Multiple `WindowContainer`s, not multiple default roots |

## Container × feature legality (inline, do NOT defer to Step 13)

This table must be consulted during the container decision itself, not at build
time. If the requested feature is illegal under the chosen container, either
revise the container choice, drop the feature, or explicitly escalate the
architecture change with justification.

| Feature | ON_PLAIN | IN_VOLUME | STAGE_MIXED | STAGE_PROGRESSIVE | STAGE_FULL |
|---|---|---|---|---|---|
| 2D Compose UI | ✅ | ✅ | ✅ (panels inside stage) | ✅ | ✅ |
| `SpatialModelView` 3D inside panel | ✅ | ✅ | ✅ | ✅ | ✅ |
| Free 3D entities in space (Spatial ECS) | ❌ | ❌ | ✅ | ✅ | ✅ |
| World-tracking / plane / mesh anchor APIs † | ❌ | ❌ | ✅ | ✅ | ✅ |
| Environment mesh / `scene.rayCast` | ❌ | ❌ | ✅ | ✅ | ✅ |
| Passthrough background | ✅ (default) | ✅ (default) | ✅ | partial (immersion slider) | ❌ |
| Skybox / virtual environment | ❌ | ❌ | ❌ (real world only) | ✅ | ✅ |
| Hand gesture / controller haptics | ✅ | ✅ | ✅ | ✅ | ✅ |
| Multiple coexisting apps in shared space | ✅ | ✅ | ❌ (Full Space) | ❌ | ❌ |

† Verified against the PICO SpatialSDK source. World
anchors live under `com.pico.spatial.sense.world.*` (`WorldTrackingManager` /
`WorldAnchor`); plane sensing under `com.pico.spatial.sense.plane.*`
(`PlaneTrackingManager` / `PlaneAnchor`); environment mesh under
`com.pico.spatial.sense.mesh.*` (`MeshTrackingManager` / `MeshAnchor`).
ECS-side `AnchorEntity` / `AnchorComponent` live in
`com.pico.spatial.core.ecs`. All Stage-only APIs are marked with
`@com.pico.spatial.core.annotation.RequiredFullSpace` in source. Full
shapes & code samples: `references/spatial-anchor.md`.

Hard implications:

- `anchor`, `env_mesh`, free 3D scene → requires Stage. If the input asks for
  any of these, do NOT pick `ON_PLAIN` / `IN_VOLUME`.
- `skybox` / fully virtual environment → requires `STAGE_PROGRESSIVE` or
  `STAGE_FULL`. `STAGE_MIXED` keeps the real-world background.
- "Coexists with other apps" → requires WindowContainer. Stage forces Full
  Space and hides other apps.

## Subwindow vs multiple WindowContainer (placement-vs-launcher rule)

When the input shows more than one panel, do not jump to `multi_window`. Apply
this escalation rule:

1. **Layered UI inside one panel** (popup / dropdown / contextual menu) → stay
   in `single_panel` or `single_panel_with_popup`. No new window.
2. **One persistent auxiliary tool panel that lives in the same SpatialApp
   session** → use `Subwindow` (`window_plus_subwindow`). One launcher, one
   manifest entry, the auxiliary window shares lifecycle with the main window.
3. **Multiple panels that need independent open / close lifecycles, or
   independent sizes/positions remembered across launches** → declare
   additional `WindowContainer(...)` blocks alongside `DefaultWindowContainer`
   (`multi_window`). Each `WindowContainer(...)` is configured via DSL
   parameters (`id` / `form` / `defaultSize` / `targetActivity` / …), is
   bound to its own Activity, and is opened by
   `openWindowContainer(id)` / `closeWindowContainer(id)` from Kotlin —
   it is **not** declared as additional `<activity>` meta-data in the
   manifest. See `references/window-container.md` § DefaultWindowContainer
   vs WindowContainer for full mechanism.

If you cannot point to a concrete reason from rule #3 (independent launcher /
independent lifecycle / independent placement memory), the correct answer is
`Subwindow`, not `multi_window`.

## After deciding

Record the decision in the intermediate structure, either inline or in `.scratch/spatial_layout_contract.json` (legacy `spatial_layout.json` is still accepted by the checker but new runs must use the canonical name):

```json
{
  "container": "ON_PLAIN",
  "container_reason": "Existing module is windowed and the input suggests a flat business panel with no immersive cues."
}
```

`container_reason` is required — it forces explicit reasoning and lets the
handoff message explain the choice to the user in one sentence.

## Hard rule for existing module mode

If the target module already works and the input is just a new UI or app variant for that
module, do **not** switch from `DefaultWindowContainer` to `DefaultStage`
(or vice versa) unless one of these is true:

- the user explicitly asks for that spatial mode
- the requested feature is impossible in the current root mode
- the input clearly depicts or requests a fundamentally different spatial experience
