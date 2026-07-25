# Performance Budgets and Optimization Checklist

## What this covers
- Typical “budget envelopes” for Shared Space vs Full Space (Stage)
- What usually breaks 90 fps first
- Practical, repeatable optimization checklists

## First principles

- Target frame rate is typically **90 fps**.
- That implies roughly **11 ms per frame** for everything:
  - rendering
  - ECS systems
  - Compose recomposition
  - animation
  - physics

Treat performance as a design constraint, not a late-stage fix.

## Typical budgets (rule of thumb)

These are practical guidelines, not guarantees:

| Metric | Shared Space (Planar/Volumetric) | Full Space (Stage) |
|---|---:|---:|
| Triangles | ~175k | ~350k |
| Draw calls | ~80 | ~90 |
| Active entities | ~30 | ~60 |
| Texture size guidance | ≤ 2048 (ASTC 6x6) | ≤ 4096 |
| Skinned mesh bones | ≤ 72 | ≤ 120 |
| Bone weights / vertex | 4 | 4 |
| Skinned meshes | ≤ 15 | ≤ 15 |

## Common performance killers (in order)

### 1) Main-thread stalls
- Blocking loads (models/textures/bundles) on main thread
- Heavy allocations inside per-frame systems

Fixes:
- Use async loading (IO/background dispatcher).
- Incremental work in systems; avoid per-frame list allocations.

### 2) Too many draw calls
- Many unique materials
- Many small meshes that could be instanced

Fixes:
- Use `MeshInstancesResource` for repeated geometry.
- Merge/instance props; reuse materials.

### 3) Transparency overdraw and sorting
- Stacked transparent surfaces
- Large transparent panels

Fixes:
- Reduce overlap; prefer opaque.
- Use draw-order grouping only when necessary.

### 4) Dynamic lights and shadows
- Many dynamic lights
- Real-time shadows

Fixes:
- Prefer IBL as baseline.
- Keep dynamic lights low (rule of thumb: ≤ 3).
- Use grounding shadows where possible.

### 5) Physics complexity
- Too many dynamic bodies
- Complex mesh colliders
- High solver iterations

Fixes:
- Prefer simple shapes.
- Reduce dynamic bodies; keep triggers cheap.
- Tune solver iterations conservatively.

### 6) ECS complexity
- Too many entities
- Unconstrained queries every frame

Fixes:
- Query by component, not by name traversal.
- Reduce entity counts for repeated details (instancing).

## Optimization checklist (copy/paste)

### Loading and memory
- [ ] Load models/bundles off the main thread.
- [ ] Explicitly `close()`/`destroy()` resources and entities.
- [ ] Avoid persisting resources (`toGlobal()`) unless you have a clear release point.

### Rendering
- [ ] Opaque first: minimize transparent materials.
- [ ] Reuse materials; reduce unique material instances.
- [ ] Use instancing for repeated meshes.
- [ ] Use LOD for far objects.

### Lighting
- [ ] Prefer IBL.
- [ ] Limit dynamic lights and shadows.

### Animation
- [ ] Keep skeletal rigs within practical bone limits.
- [ ] Avoid playing many skinned animations simultaneously without profiling.

### Physics
- [ ] Prefer simple colliders.
- [ ] Keep dynamic body count low.

### ECS
- [ ] Systems do minimal work per frame.
- [ ] Queries are constrained.
- [ ] Avoid per-frame allocations.

