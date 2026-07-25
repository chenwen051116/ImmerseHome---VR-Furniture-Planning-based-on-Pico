# Physics and Collision

## What this covers
- Core physics components (rigid bodies, collisions, physics world)
- Collision shapes and cost tradeoffs
- Physics world scoping (shared-world requirement)
- Raycasts/convex casts
- Events and debugging checklists

## Core building blocks

### CollisionComponent
Defines:
- Collision geometry (`ShapeResource` list)
- Physics material (`PhysicsMaterialResource`)
- Response mode (trigger vs collider)
- Filters/groups

### RigidBodyComponent
Modes:
- **Static**: default for non-simulated objects.
- **Kinematic**: user-driven motion.
- **Dynamic**: simulated by physics.

### PhysicsWorldComponent (localized physics world)
Defines a custom physics world with parameters such as:
- `gravity`
- `solverIterations`
- `simulationClock` (fixed timestep, max time step, time speed)
- Kinematic collision report behavior

## The shared-world requirement (most common physics bug)

To get collision response/events:
- All interacting bodies must be in the **same physics world**.

That means:
- Either **none** of them are under a `PhysicsWorldComponent`, or
- They share a common ancestor entity that has a `PhysicsWorldComponent`.

Symptom:
- Objects pass through each other even though colliders and rigid bodies look correct.

Checklist:
- [ ] Do both entities share the same `PhysicsWorldComponent` ancestor?
- [ ] If I added `PhysicsWorldComponent` to a moving object, did I accidentally isolate it?

## Collision response mode (easy to miss)

To get **physical collision response** (blocking / bouncing / movement prevention), set
`collisionResponseMode = CollisionResponseMode.COLLIDER_FULL` on **both** colliding entities.

If either side is not `COLLIDER_FULL`, the collision effect can be ignored even when shapes and worlds look correct.

If the moving object should respond to gravity/forces, also set `RigidBodyMode.DYNAMIC`.

## Collision shapes: choose the simplest that works

Preferred order (cheapest → most expensive):
1) Box / Sphere / Capsule
2) Convex mesh
3) Mesh collider

Interaction-oriented guidance:
- Use bounding-box-based shapes for most interactables.
- Use convex mesh only when interaction precision truly matters.

## Mass properties and resource consumption

Some mass-property helpers consume shape resources.
If you need the `ShapeResource` after generating mass properties:
- Persist it first (e.g., `toGlobal()`) or regenerate it.

## Raycasting and convex casting

Use raycasts for:
- Selection
- Debugging interaction/collision coverage
- Line-of-sight checks

Common API shape:
```kotlin
val hit = scene.rayCast(
  origin = origin,
  dir = direction,
  maxDistance = 10f,
  queryType = queryType,
  group = group,
  referenceEntity = referenceEntity,
)
```

Checklist for raycasts:
- [ ] `dir` is normalized.
- [ ] `origin/dir` are expressed in the intended space (often world space).
- [ ] `maxDistance` is reasonable (avoid huge casts).
- [ ] If the ray origin is inside a collider, that collider will not be returned as a hit.

## Collision events

Subscribe to collision events to respond to:
- Enter
- Update
- Exit

Event checklist:
- [ ] Both objects have `CollisionComponent`.
- [ ] Response mode matches the behavior you want (trigger vs collider).
- [ ] Filters allow the pair to interact.
- [ ] Objects share the same physics world.

## Performance notes

Physics cost grows with:
- Number of active rigid bodies
- Complexity of collision shapes
- Solver iterations

Keep physics stable and cheap:
- Prefer fewer dynamic bodies.
- Prefer simple shapes.
- Avoid per-frame allocation in physics-related systems.
