---
name: spatial-sdk-guideline
description: Day-to-day PICO Spatial SDK 3D development guide (Android/Kotlin + Spatial ECS). Covers Stage/WindowContainer, ECS entities/components/systems, resources, materials/lighting, animation, physics, interaction, coordinates/units, and performance budgets.
license: 'Apache-2.0'
---

# PICO Spatial SDK 3D Developer

## Description and Goals

Use this skill when building or debugging 3D content with **PICO Spatial SDK on PICO OS 6** (Android + Kotlin + Jetpack Compose + Spatial ECS).

Goals:

- Pick the right container (**WindowContainer** vs **Stage**) and understand space-state constraints.
- Build scenes with **ECS** (entities/components/systems) that are maintainable and performant.
- Load resources correctly (**USD preferred**, **glTF/GLB supported**, **AssetBundle** workflow) and release them safely.
- Make interaction actually work (**programmatic hit testing** needs `CollisionComponent`; **user interaction** needs `CollisionComponent + InteractableComponent`, plus targeting/gestures patterns).
- Avoid common pitfalls (threading, coordinate-handedness, unit conversions, physics-world scoping).
- Keep content within typical **performance budgets** for 90 fps.

## What This Skill Should Do

When asked a concrete question, respond with:

1. **Recommended approach** (what to use, where it lives, why).
2. **Minimal implementation pattern** (small Kotlin snippet or pseudocode).
3. **Checklist** to validate assumptions and catch common gotchas.
4. Links to the most relevant curated pages under `reference/`, plus any deeper support retrieved via `pico-spatial-knowledge` MCP when the question needs broader or version-specific lookup.

For **model loading** answers, do not stop at the API call. Explicitly cover all of these points in the prose:

- Whether the requested model format is supported (`glTF/GLB` supported; `USD` preferred).
- The recommended loading path (`Entity.loadSuspend("asset://...")`, or synchronous `Entity.load(...)` inside `withContext(Dispatchers.IO)`).
- In Compose / `SpatialView` contexts, one-off loading and scene attachment should happen in `SpatialView(initial = { content, _ -> ... })`, then use `content.addEntity(root)` after loading succeeds. Do not frame this as an `update`-time operation.
- The returned value is the **root `Entity`** of the loaded hierarchy and should be attached to the scene after loading succeeds.
- Mesh-bearing child entities automatically expose `ModelComponent`.

## Core Concepts

### ECS: Entity / Component / System

- **Entity**: node in a hierarchy with a set of components.
- **Component**: data/config attached to an entity.
- **System**: per-frame logic (`update(SceneUpdateContext)`), usually driven by queries.
- **Scene**: owned by a container; queries and event subscriptions are scene-scoped.

Practical rule:

- Put one-off setup in spawn/initialization.
- Put continuous behavior in a **system**.

### Containers and Space States

**WindowContainer**

- Bounded content area; anything outside is clipped.
- Runs in **Shared Space** (multitasking) or can exist alongside Stage in Full Space.

**Stage**

- Unbounded immersive 3D container.
- **Full Space only**; opening Stage transitions the app to Full Space.
- **Only one Stage open at a time** per app.
- Right-handed coordinate system (+X right, +Y up, **+Z toward the user**). Origin at the user's feet.
- Configure a custom skybox + IBL before opening Stage; otherwise the environment appears black.
- `style` is chosen when calling `openStage(...)`; it cannot be changed dynamically after the Stage is open.

### Resources and Rendering

- **Models**: USD (`.usd/.usda/.usdc/.usdz`) preferred; **glTF/GLB supported**.
- **AssetBundle**: Editor-authored `.bundle` stored in APK assets; load via `AssetBundle.load("asset://...")`.
- First-class resource types: `MeshResource`, `Material` variants, `TextureResource`, `AnimationResource`, `ShapeResource`, etc.

### Coordinates and Units

- 3D spaces (Entity/Stage/SpatialView): generally **right-handed, meters**.
- 2D view space (Compose/View in a WindowContainer): **left-handed, virtual pixels** with +Y down.
- Mixing 2D + 3D requires **explicit conversions** (space + unit).

## Components Reference (By Topic)

> Detailed, self-contained references are bundled under `reference/`.

### ECS & Scene

- Entity hierarchy, components, systems, queries, cloning, events:
  - [ECS and Entities](reference/ecs-and-entities.md)

### Resources Loading

- USD/glTF loading, AssetBundle workflows, resource lifecycle:
  - [Resources Loading](reference/resources-loading.md)

### Materials, Lighting, Effects

- Unlit/PBR, blending, IBL, shadows, opacity, portal, transparent sorting:
  - [Materials, Lighting, and Effects](reference/materials-lighting-effects.md)

### Animation

- Skeletal/blendshape/tween/timeline, playback control, main-thread rules:
  - [Animation Playback and Control](reference/animation-playback.md)

### Physics & Collision

- Rigid bodies, collision shapes, physics world scoping, raycasts, events:
  - [Physics and Collision](reference/physics-collision.md)

### Interaction & Hit Testing

- Collision + Interactable prerequisites, targeting, gestures, hover, unit mapping:
  - [Interaction and Hit Testing](reference/interaction-hit-testing.md)

### Coordinates & Units

- Handedness, origins, conversion APIs, dp/px/meter helpers:
  - [Coordinates and Units](reference/coordinates-and-units.md)

### Performance

- Budgets, optimization checklists, common bottlenecks:
  - [Performance Budgets and Optimization Checklist](reference/performance-budgets.md)

## Playbooks

Sub-flows that compose this skill for specific scenarios live under `playbooks/`:

- [Scene Surface Placement](playbooks/scene-surface-placement.md) — placing entities on real-world planes (wall/table/floor) using `PlaneTrackingManager` and Stage.

## Implementation Patterns

### Pattern: Load and place a model asynchronously

- Prefer `Entity.loadSuspend("asset://...")` for the recommended async path.
- If you show synchronous loading, wrap `Entity.load(...)` in `withContext(Dispatchers.IO)`.
- `Entity.loadSuspend(...)` may be called directly on the main thread; after loading completes, entity/component operations return to the main thread.
- If the user asks about `.gltf` / `.glb`, explicitly say that **glTF/GLB is supported**, while **USD is still the preferred format** when the pipeline is under your control.
- In Compose container examples, prefer `SpatialView(initial = { content, _ -> ... })` for one-time loading/setup and use `content.addEntity(root)` there.
- Do not tell users to add freshly loaded entities from `update`; `update` is for reacting to state changes after initialization.
- Treat the result as a root entity hierarchy; mesh-bearing child entities expose `ModelComponent` automatically.
- Do not invent wrapper APIs such as `GLTFResource.load` or `ModelResource` for standard model loading answers.
- Add the loaded root entity to the scene/content only after success.
- Keep a clear ownership model for resources (who closes what, and when).

Recommended wording points for asset-loading answers:

- “`Entity.loadSuspend("asset://robot.glb")` is the recommended async loading path.”
- “In a `SpatialView`, load once in `initial` and call `content.addEntity(robotRoot)` after the load succeeds.”
- “The returned value is the root `Entity` of the loaded hierarchy, so add that root to the scene/content rather than only operating on a child.”
- “Child entities always have `TransformComponent`, and mesh-bearing child entities automatically expose `ModelComponent`.”

Minimal Kotlin pattern:

```kotlin
SpatialView(initial = { content, _ ->
  val robotRoot = Entity.loadSuspend("asset://robot.glb")
  content.addEntity(robotRoot)
})

// If you need mesh/material access later, inspect mesh-bearing child entities via ModelComponent.
```

### Pattern: Make an entity hittable and interactive

- Distinguish **programmatic hit testing** from **user interaction**.
- For `scene.rayCast(...)` / `scene.convexCast(...)`, the target only needs `CollisionComponent`.
- Add `CollisionComponent` with shapes.
- Add `InteractableComponent` when the user needs gaze/tap/drag interaction.
- If using gaze feedback: add `HoverEffectComponent`.
- Use targeting (e.g., `TargetEntity.any()` or `targetedToEntity = ...`) to scope gestures.

### Pattern: Control animations safely

- In Compose examples, prefer loading the animated entity once in `SpatialView.initial`, adding it with `content.addEntity(...)`, then starting playback on the main thread.
- For skeletal-animation answers, follow the official pattern: load the animated model root, then call `findSkinnedMeshEntity()` to get the skinned-mesh entity array/list, iterate it, and call `getAnimationResources()` on each skinned mesh entity before playing animation.
- For minimal looping examples, prefer the documented explicit-repeat form `val repeat = clip.repeat(3)` and then `repeat.use { skinnedMeshEntity.playAnimation(it) }` instead of inventing an “infinite repeat” helper.
- Make Timeline answers explicit: Timeline entities are played with `playTimeline()`, not `playAnimation()`.
- Mention `AnimationPlaybackController` when the user needs pause/resume/stop control, but do not force it into the smallest “just start looping” example.
- Keep the `AnimationPlaybackController` handle.
- Call animation APIs on the **main thread**.
- Close controllers when no longer needed.

### Pattern: Configure physics correctly

- Ensure interacting objects share the same `PhysicsWorldComponent` ancestor (or none).
- For physical collision response (blocking/bouncing), both sides must use `CollisionResponseMode.COLLIDER_FULL`.
- If the object should respond to gravity/forces, use `RigidBodyMode.DYNAMIC`.
- For minimal collision demos, explicitly show the shared `PhysicsWorldComponent` setup in code instead of only mentioning the default global world.
- Prefer simple collision shapes.
- Use raycasts for selection and interaction debugging.

### Pattern: Convert spaces and units explicitly

- Always state (in code and comments) the **source space** and **target space**.
- Convert **full transforms** (position + rotation + scale) when moving across containers.
- Convert units (dp/px ↔ meters) using the provided converters.

## Pitfalls and Checks

Use this as a pre-flight checklist:

- [ ] Am I in the right container? Stage requires Full Space; WindowContainers clip content.
- [ ] Stage rules: unbounded, **Full Space only**, **only one Stage at a time**, needs custom skybox/IBL, and `style` is not dynamically mutable.
- [ ] Programmatic hit testing needs `CollisionComponent`; user interaction needs **both** `CollisionComponent` and `InteractableComponent`.
- [ ] Gesture input: do not call multiple `detectSpatial*` recognizers in the same `pointerInput` block.
- [ ] Entity/component work after loading returns to the **main thread**; animation APIs are also **@MainThread**.
- [ ] Physics: all colliding bodies share the same physics world, and physical-response colliders use `COLLIDER_FULL` on both sides.
- [ ] Coordinates: Stage/Entity/SpatialView are right-handed meters; View space is left-handed pixels.
- [ ] SpatialView teardown is manual: entities are not auto-destroyed when the composable leaves composition.
- [ ] Performance: avoid blocking the main thread; keep lights/transparency/physics/system queries under budget.

## References

- [ECS and Entities](reference/ecs-and-entities.md)
- [Resources Loading](reference/resources-loading.md)
- [Materials, Lighting, and Effects](reference/materials-lighting-effects.md)
- [Animation Playback and Control](reference/animation-playback.md)
- [Physics and Collision](reference/physics-collision.md)
- [Interaction and Hit Testing](reference/interaction-hit-testing.md)
- [Coordinates and Units](reference/coordinates-and-units.md)
- [Performance Budgets and Optimization Checklist](reference/performance-budgets.md)

For deeper dives, prefer querying `pico-spatial-knowledge` MCP for broader or version-specific documentation, and when source validation is needed, follow the file paths returned in `pico-spatial-knowledge` results.
