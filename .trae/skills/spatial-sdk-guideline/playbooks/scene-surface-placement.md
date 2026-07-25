# Scene Surface Placement Playbook

Use this playbook when the user wants to place digital content onto a real-world wall, table, floor, or other detected surface in mixed reality. Typical requests sound like everyday placement goals such as hanging a picture on the wall, putting an object on a table, or pinning a panel to a surface.

This playbook is a sub-flow of the `spatial-sdk-guideline` skill. Read the main SKILL.md first to ground container choice, ECS, coordinates, and resources, then use this playbook for the placement-specific decision path.

## When to use

- The user wants to place content onto a detected real-world surface.
- The user describes a wall, table, floor, desk, fridge, or room surface as the target.
- The user wants content to stay attached to the environment rather than float in front of the user.
- The user may also want the placement to persist or remain stable as they move.

Do not use when:

- The user only wants a floating window or a free-space object with no real-world attachment.
- The user wants UI attached to a moving 3D entity (use a world-attached panel pattern instead).
- The user mainly wants raw room scanning or mesh visualization.
- The user is asking for debugging rather than implementation.

## Decision path (read in order)

1. Determine whether the user wants **real-world attachment** or just **nearby floating placement**.
   - If the content must attach to a wall, table, floor, desk, or other room surface, continue with this playbook.
   - If the user only wants content floating in front of them, route away from this playbook.
2. Detect the current Spatial SDK BOM version from the project first.
   - Prefer `gradle/libs.versions.toml` when the project uses a version catalog.
   - Otherwise inspect the module build file for `implementation(platform("com.pico.spatial:bom:..."))` or an equivalent version variable.
3. Confirm the app should use `Stage` for room-aware placement. Stage rules: unbounded, **Full Space only**, **only one Stage at a time**, custom skybox/IBL required, `style` is not dynamically mutable.
4. Start from `examples/Plane.kt` to establish the known-good MVP path: `Stage` + `SpatialView` + root entity + `PlaneTrackingManager.subscribeAnchorUpdate` + transform conversion through the root entity.
5. Choose the implementation branch explicitly.
   - **Plane-only MVP:** detect the target surface, place visible content once, and verify orientation and stability.
   - **Persistent placement:** after the plane-only MVP works, add the persistence branch by storing and restoring the anchor identifier with a predictable rebind flow.
6. Verify whether detected-surface placement matches the request before adding anchors.
7. Use the curated `reference/coordinates-and-units.md` and `reference/interaction-hit-testing.md` pages, plus `pico-spatial-knowledge` MCP, to finalize transform and facing logic.
8. Implement in this order.
   - get content visible in `Stage`
   - subscribe to plane updates and identify the intended surface
   - place and orient content against that surface
   - validate visual stability while the user moves
   - if persistence is requested, save the anchor identifier, restore on next launch, and avoid double-placement between plane discovery and anchor restore
   - if restore fails, clear or rebind predictably instead of spawning duplicates

## Key APIs

- `com.pico.spatial.runtime.Stage` (required)
- `com.pico.spatial.sense.plane.PlaneTrackingManager` (required when using detected surfaces)
- `com.pico.spatial.sense.world.WorldTrackingManager` (optional for persistent anchors)
- `com.pico.spatial.core.ecs.Entity` (required)
- `com.pico.spatial.core.ecs.TransformComponent` (required)

> Note: package names can vary across Spatial SDK versions. Prefer the repo's `examples/Plane.kt` imports and the bundled API reference (or `pico-spatial-knowledge` MCP) over copying these paths verbatim.

## Recommended Examples (by complexity)

- `examples/Plane.kt`: verified local sample showing the core surface-detection path. It opens a `Stage`, starts `PlaneTrackingManager`, subscribes to plane anchor updates, loads plane meshes from anchors, and converts anchor transforms into entity placement.
- **Known-good architecture template:** use `examples/Plane.kt` as `Stage` + `SpatialView` + root entity + plane subscription. For production adaptation, keep one root entity that owns placement children, add optional world-anchor restore logic as a separate branch, and persist only the minimal identifier needed to rebind on relaunch.
- Use `examples/Plane.kt` as the starter pattern for first visible wall/table/floor placement: keep its `Stage` + plane-tracking + transform-conversion flow, then replace the debug plane mesh with the user's actual content.
- Add spatial-anchor persistence only after the base `examples/Plane.kt` placement flow is working.

## Required knowledge sources

Pull these via curated `reference/` pages first, then `pico-spatial-knowledge` MCP for broader/version-specific lookup:

- plane detection
- spatial anchors
- coordinate-space conversion
- entity orientation control
- Stage lifecycle and state management

## Common Pitfalls (MUST avoid)

- Using a `WindowContainer` for real-world wall or table attachment → plane detection and environment-attached placement will not match the user's intent.
- Treating a detected surface placement task as free-space spawning → content will drift from the intended wall, table, or floor workflow.
- Ignoring the surface normal when orienting content → the content may face sideways, clip into the surface, or face away from the user.
- Mixing coordinate spaces without explicit conversion → placement offsets will be wrong or unstable.
- Adding anchors before validating basic placement → debugging becomes harder and failures are harder to isolate.
- Making persistence the default when the user only asked for visible placement → unnecessary complexity and more failure points.
- Updating placement directly from mixed coordinate contexts without going through the root entity conversion flow used in `examples/Plane.kt` → transforms can drift or be applied in the wrong space.
- Restoring a world anchor and also continuing first-time plane placement logic → duplicate placement or double-binding can occur.
- Persisting more state than needed for rebind → recovery becomes fragile and harder to debug than restoring from a minimal anchor identifier.
- Using invalid or unstable entity names for restore/rebind bookkeeping → later lookup and cleanup flows become unreliable.

## Acceptance checklist

- [ ] First launch uses `Stage` and reaches visible placement on the intended detected wall, table, or floor.
- [ ] Surface detection path matches the requested target surface.
- [ ] Content is aligned to the detected surface with correct orientation.
- [ ] Placement remains visually stable as the user moves.
- [ ] If the user only asked for visible placement, no persistence branch is added.
- [ ] If persistence is requested, a second launch restores from the saved anchor path without requiring first-time plane rediscovery.
- [ ] If anchor restore fails or the anchor is lost, the app clears or rebinds predictably instead of duplicating content.
- [ ] Runtime behavior is verified for the intended placement flow.
