# Template Playbook

This file is a supplementary reference for Spatial app development.
Use it only when the main skill reaches a decision branch.

It contains the domain-specific protocol for:

- post-scaffold architecture decisions
- `SpatialModelView` vs `SpatialView + ECS`
- migration choices in later turns
- deciding whether to ask a clarifying question at all

## 1. Decision protocol

### 1.1 Ask only when a real branch exists

Do not ask generic discovery questions.

- If the path is obvious, recommend it and start immediately
- Ask at most **one** clarifying question only when the answer would materially change the project shape

Real branch points include:

- `SpatialModelView` vs `SpatialView + ECS`
- fast MVP now vs laying groundwork for physics / interaction / tracking now
- later migration to `FullStage` after the generated project is working

Bad questions include:

- “What kind of spatial app do you want?”
- “Please describe your requirements.”
- “What demo do you want?”

Good questions explicitly name the choices, explain the impact, and preferably include a recommendation.

### 1.2 Decision protocol for template selection

For first-run onboarding, follow this workflow:

1. Inspect the user prompt to classify the intent.
2. Run `pico-cli project create --help` to discover the currently supported `--template` modes and any other scaffold options.
3. Map the intent to a supported template using the routing table in section 2 below.
4. Pass `--template <mode>` explicitly to `pico-cli project create`, along with other user-provided options (`--dir`, `--name`, `--package`, `--sdk`, `--force`).

Use `--template` on every first-run scaffold so the generated project shape is deliberate and reproducible.

After the project is generated, use the generated structure as the blueprint and make minimal changes.
Do not invent a fresh project structure.

## 2. Routing defaults

Follow these defaults after the project has been generated, unless the evidence strongly suggests otherwise:

- Show a model quickly with minimal behavior → `SpatialModelView`
- Need entity behavior, collision, physics, or custom components → `SpatialView + ECS`
- Mostly 2D panels with light 3D decoration → keep the generated window-container shape
- Physics, room-scale context, tracking, or immersive interaction from the start → migrate toward `FullStage` only after the generated baseline is working

If the user has no clear requirement, keep the first capability narrow and build on the generated scaffold.

## 3. Template modes

Map the user intent to one of the template modes advertised by `pico-cli project create --help`.
If the advertised set does not match the list below, trust the output of `--help` and pick the closest fit.

### `planar`

Use when the app is mostly 2D UI with only light 3D decoration.

### `volumetric`

Use when the user wants to show a 3D model or medium-complexity 3D content quickly.

### `stage`

Use when the app needs immersive space, room-scale context, tracking, physics, or spatial interaction from the start.

## 4. `SpatialModelView` vs `SpatialView + ECS`

### Prefer `SpatialModelView`

Use when the goal is to show a model quickly and entity-level behavior is not yet required.

### Prefer `SpatialView + ECS`

Use when the project needs entity behavior, collision, rigid body physics, interaction, or a clear path toward those capabilities.

## 5. Common migrations

### `VolumetricWindowContainer` → `FullStage`

Typical trigger: the user wants floor contact, bounce, grab/throw behavior, or room-scale context.

Migration rule: use the `FullStage` template as the reference, preserve confirmed assets and package naming, and do not improvise a new Stage project from scratch.

### `SpatialModelView` → `SpatialView + ECS`

Typical trigger: the user needs finer interaction, manipulation, or physics.

Migration rule: preserve the visible result first, then introduce ECS behavior.

## 6. Boundary reminder

- Template selection is a deliberate decision; inspect `pico-cli project create --help` first, then pass `--template`.
- The generated project is the blueprint
- Keep first-pass changes minimal
- Avoid over-abstracting for future possibilities
