# Materials, Lighting, and Effects

## What this covers
- Material types and when to use them
- Blending, depth, and transparency pitfalls
- Image-based lighting (IBL) and Stage environment lighting
- Shadows and cost control
- Opacity, portals, and draw-order fixes

## Material selection

### UnlitMaterial
Use when:
- You want stable appearance (UI-like), minimal cost.
- Lighting/shadows are not required.

### PhysicallyBasedMaterial (PBR)
Use when:
- You need realistic response to lights/IBL.
- You accept higher cost.

### Other common materials
- `ShaderGraphMaterial`: authored in editor pipeline (bundle).
- `VideoMaterial`: video as a surface.
- `PortalMaterial`: portal surfaces.

## Blending and depth settings

### Blending modes (high-level)
- `OPAQUE`: best performance and sorting stability.
- `TRANSPARENT`: standard alpha transparency (keeps highlights).
- `FADE`: transparency where highlights/reflections fade too.
- `ADD`: additive effects.
- `MASKED`: hard cutout (alpha test).

### Depth testing and writing
Transparent rendering issues often show up as:
- Wrong front/back ordering
- “Pop” when camera moves
- Overdraw cost explosions

Checklist:
- [ ] Keep as much content `OPAQUE` as possible.
- [ ] Avoid many overlapping transparent surfaces.
- [ ] If using transparent materials, be deliberate about draw-order control.

## Transparent sorting: DrawOrderGroupComponent

Use `DrawOrderGroupComponent` when overlapping semi-transparent entities sort incorrectly.

Key rules:
- Entities share a `DrawOrderGroup` instance.
- Within the group, smaller `order` renders later and appears in front.
- Prefer stable `order` values that are easy to reason about.

```kotlin
val g = DrawOrderGroup.create()
glassA.components.set(DrawOrderGroupComponent(g, order = 1))
glassB.components.set(DrawOrderGroupComponent(g, order = 2))
```

Prerequisites:
- Entity has `ModelComponent` or `ParticleComponent`.
- Material is actually semi-transparent.

## Lighting: keep it simple

### Dynamic lights
- `PointLightComponent` (no shadows)
- `DirectionalLightComponent` (shadows)
- `SpotLightComponent` (shadows)

Practical guidance:
- Prefer IBL as baseline lighting.
- Add only a small number of dynamic lights (rule of thumb: **≤ 3**).

### Grounding shadows
- Use `GroundingShadowComponent` for cheap “contact” shadows.

## Image-Based Lighting (IBL)

### Local IBL
- Add an `ImageBasedLightComponent` to an entity.
- Add `ImageBasedLightReceiverComponent` to entities that should receive it.

### Environment IBL (Stage-only customization)
- Custom environment IBL maps are effective **only in Stage**.
- Use `StageEnvironmentLightingComponent`.

StageStyle interaction (important):
- `FULL`: Stage environment lighting defines the virtual world.
- `MIXED`: Stage environment lighting is inactive; system prioritizes real-world derived lighting.
- `PROGRESSIVE`: blend between stage environment lighting and system IBL by immersion.

### Mixing weights
- `EnvironmentLightingSettingsComponent` controls the weight of environment IBL (default is 0.5).

## Opacity control

### Hierarchical opacity
- `OpacityControllerComponent(opacity)` multiplies through hierarchy (parent * child).
- Useful for fading groups of entities.

Pitfall:
- If an entity looks “too transparent”, verify parent opacity and any view alpha.

## Portals (high level)

Typical portal building blocks:
- `PortalWorldComponent`
- `PortalComponent`
- `PortalMaterial`

Checklist:
- [ ] Make portal surface and portal world consistent.
- [ ] Consider whether entity crossing is desired (and validate it explicitly).
