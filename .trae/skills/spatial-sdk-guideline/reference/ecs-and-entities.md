# ECS and Entities (Quick Reference)

## What this covers
- ECS mental model (Entity / Component / System / Scene)
- Entity hierarchy and scene ownership
- Query patterns and update-loop patterns
- Cloning semantics (what is and is not cloned)
- Event subscriptions and cleanup

## Mental model (in one minute)
- **Scene**: owned by a spatial container (WindowContainer or Stage). Most operations (queries, events) are scene-scoped.
- **Entity**: a node in a tree with a unique ID, a name, and a `ComponentSet`.
- **Component**: structured data/config attached to an entity (e.g., `TransformComponent`, `ModelComponent`).
- **System**: per-frame logic with `update(SceneUpdateContext)`.

Rule of thumb:
- Use entities + components to describe *state*.
- Use systems to describe *behavior over time*.

## Scene ownership and where entities live
- Your app hosts content in containers:
  - `WindowContainer` (bounded) → owns a Scene
  - `Stage` (unbounded, Full Space only) → owns a Scene
- A typical structure:
  - App → Container → Scene → RootEntity → entity tree

Practical implication:
- Queries and event subscriptions are attached to a Scene (or scoped content object).
- Avoid holding long-lived entity references across container lifetimes unless you also handle teardown.

## Entity creation and hierarchy

### Create empty entities
- `Entity()` creates a basic entity that always has a `TransformComponent`.

### Load entity hierarchies
- Load models/scene entities from URIs (e.g., assets):
  - `Entity.load("asset://...")`
  - `Entity.loadSuspend("asset://...")` (recommended for async)

### Parent/child transforms
- `TransformComponent` stores **local transform**.
- A child entity's transform is relative to its parent.

Checklist when transforms look wrong:
- [ ] Did I set `TransformComponent` on the right entity (root vs mesh child)?
- [ ] Did I accidentally parent the entity under something with a non-identity transform?

## Components: adding, reading, updating

Common operations:
- Add or replace a component:
  - `entity.components.set(SomeComponent(...))`
- Read a component:
  - `entity.components[TransformComponent::class.java]`
- Mutate a component instance (when the API returns a mutable component):
  - update properties, then set it back if required by the API pattern.

Pitfall:
- If you cache a component reference, confirm whether it stays valid after entity changes.

## Systems and update loops

### When to write a system
Use systems for:
- Per-frame motion, constraints, simulation glue
- Cross-entity coordination
- Incremental processing (e.g., streaming loads, LOD decisions)

Avoid systems for:
- One-time setup (spawn time)
- UI-only state that should live in Compose

### Ordering
- System execution order is typically the **registration order**.

## Query patterns (fast and safe)

### Prefer component-based queries
Instead of traversing the entire tree every frame:
- Query for entities that have specific components.

Example (pseudocode):
```kotlin
override fun update(context: SceneUpdateContext) {
  val scene = context.scene
  val movers = scene.queryEntity(hasComponent(MoverComponent::class.java))
  for (e in movers) { /* update */ }
}
```

Performance checklist:
- [ ] Is my query constrained by component(s) rather than name searches?
- [ ] Am I allocating new lists every frame that could be reused?
- [ ] Can I skip work if the Scene/Stage is unfocused?

## Cloning entities

### Use cases
- Spawn repeated props efficiently.
- Create variants from a template.

### Key semantics
- `Entity.clone(CloneOptions)` supports options such as:
  - `recursive` (clone children)
  - `shouldShareMaterialInstance` (share vs duplicate material state)

Not cloned (important):
- **Runtime state** such as current animation playback or physics runtime state.

Custom components:
- If you have custom components with internal data, implement/override clone behavior so copies are correct.

Cloning checklist:
- [ ] Do instances need independent material changes? If yes, do **not** share material instances.
- [ ] After cloning, do I need to re-add physics/interaction state or restart animations?

## Events and subscriptions

### Subscribe and cancel
- Subscriptions return a `Cancellable`.
- Always cancel on teardown.

Common event families:
- Scene/entity lifecycle events
- Collision events
- Animation events

Pitfall:
- Leaking subscriptions can keep entities alive and cause unexpected callbacks.

## Cleanup and lifetime

### Destroy entities to release resources
- `entity.destroy()` releases the entity and, via reference counting, associated resources.

### SpatialView lifetime rule
- When a `SpatialView` leaves composition, its associated entity instances are **not** automatically destroyed.
- If you want to reuse an entity across `SpatialView` lifetimes, keep a strong external reference (for example in a `ViewModel`) and rebind it later.
- If the entity will no longer be used, explicitly destroy it from teardown code such as `DisposableEffect { onDispose { entity.destroy() } }`.

### Don’t rely on GC
- Many heavy resources are native/GPU-backed; prefer explicit cleanup.
