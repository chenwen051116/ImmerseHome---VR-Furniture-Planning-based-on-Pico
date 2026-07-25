# Interaction and Hit Testing

## What this covers
- Minimal prerequisites for 3D interaction
- Hit testing and targeting patterns
- Spatial gesture recognizers and common pitfalls
- Hover feedback for gaze/selection
- Unit/coordinate mapping for drag/transform

## Minimal prerequisites (if interaction “does not work”)

Distinguish these two cases:

1) **Programmatic hit testing** (`scene.rayCast(...)`, `scene.convexCast(...)`)
- The target needs `CollisionComponent` with at least one shape.

2) **User interaction** (gaze / tap / drag / transform gestures)
- The target needs both `CollisionComponent` and `InteractableComponent`.

Without collision shapes, the system has nothing to hit.

Checklist:
- [ ] Does the entity have `CollisionComponent`?
- [ ] Are there shapes in `collisionShape`?
- [ ] If this is user interaction rather than a raycast, does the entity have `InteractableComponent`?
- [ ] Is the collision boundary aligned with the visible mesh?

## Collider choice for interaction

Two common approaches:
- **Bounding-box collider** (recommended default): cheap, may be less precise.
- **Mesh-based collider**: precise, higher cost.

Rule:
- Prefer bounding-box/sphere/capsule unless precision is required.

## Spatial gestures (Compose)

Common recognizers:
- `detectSpatialTapGesture(...)`
- `detectSpatialDragGesture(...)`
- `detectSpatialScaleGesture(...)`
- `detectSpatialRotateGesture(...)`
- `detectSpatialTransformGesture(...)`
- `detectSpatialPointerEvent(...)` (lower-level)

### Critical pitfall: one recognizer per pointerInput
Do **not** call multiple `detectSpatial*` functions inside the same `pointerInput { ... }` block.
They are mutually exclusive and can block each other.

Correct pattern:
```kotlin
Modifier
  .pointerInput(Unit) {
    detectSpatialDragGesture(context) { /* ... */ }
  }
  .pointerInput(Unit) {
    detectSpatialScaleGesture(context) { /* ... */ }
  }
```

## Targeting: limit what can be interacted with

Use `TargetEntity` to scope interactions:
- `TargetEntity.hit(entity)` → only that entity and its children.
- `TargetEntity.any { predicate }` → any entity matching a condition.

This is essential when multiple interactables exist.

## Programmatic hit testing gotchas

- `scene.rayCast(...)` and `scene.convexCast(...)` use the coordinate system of `referenceEntity`; if `referenceEntity == null`, the container space is used.
- If the ray origin starts **inside** a collider, that object will not be returned as a raycast hit.
- Use `convexCast(...)` when you need volume-based detection rather than an infinitely thin ray.

## Hover feedback

For gaze/selection highlighting on 3D entities:
- Add `HoverEffectComponent`.

Minimal recipe:
- `CollisionComponent` + `InteractableComponent` + `HoverEffectComponent`

## Drag/transform mapping: units and handedness

### Unit mismatch
- Gesture deltas (e.g., `dragAmount`) can be reported in **pixels**.
- 3D transforms are in **meters**.

Convert px/dp → meters via:
- `LocalDensity.current`
- `LocalPhysicalLengthConverter.current`

### Coordinate mismatch
- View-space +Y is **down**.
- 3D (right-handed) +Y is **up**.

So mapping a drag delta to a world offset usually requires **inverting Y**.

Checklist when dragging feels wrong:
- [ ] Am I converting to meters before applying to `TransformComponent`?
- [ ] Did I invert Y when mapping view deltas to 3D space?
- [ ] Am I applying deltas in the correct reference space (local vs world)?

## InteractionKind (advanced routing)

Some gesture callbacks expose an `InteractionKind` indicating how input was performed.
Use it to differentiate behaviors (e.g., drag vs poke) within the same recognizer.
