# Animation Playback and Control

## What this covers
- Animation types you will encounter
- Composing animations (repeat / group / sequence)
- Playback control via `AnimationPlaybackController`
- Blending/transitions via `AnimationPlayConfig`
- Threading and resource-lifetime pitfalls

## Animation types and where they come from

### Skeletal animation
- Load a rigged model.
- Find skinned mesh entities and call `getAnimationResources()`.

### BlendShape animation
- Driven by `BlendShapeControllerComponent`.

### Tween / procedural animation
- Create a `TweenAnimation`, then generate an `AnimationResource`.
- Common targets: position, rotation, scale, transform, some material properties.

### Orbit animation
- Create orbit animation, generate `AnimationResource`.

### Timeline
- Timeline animations are loaded as named entities from Editor-authored scenes.
- Play them with `entity.playTimeline()`, **not** `entity.playAnimation()`.

## Compose animations: repeat / group / sequence

### Repeat
Repeat a resource N times after initial playback:
```kotlin
val repeated = anim.repeat(count = 3) // plays 4 times total
```

### Group (parallel)
```kotlin
val combined = AnimationResource.group(listOf(move, wave))
```

### Sequence (serial)
```kotlin
val combined = AnimationResource.sequence(listOf(move, wave))
```

Important notes:
- Skeletal animations obtained from `getAnimationResources()` **loop by default**.
  - If you need “play once” semantics (especially in `sequence(...)`), use `repeat(0)`.
- **Target conflicts**:
  - If multiple animations in a `group(...)` drive the same target/property, later ones can override earlier ones.
- Input restriction (SDK behavior):
  - Do **not** include the same `AnimationResource` instance more than once in the input `listOf(...)` passed to `group(...)` / `sequence(...)` (it throws).

## Play and control animations

For Compose-based container code, the recommended shape is: load the animated model once in `SpatialView(initial = { content, _ -> ... })`, attach it with `content.addEntity(...)`, then start playback on the main thread.

Minimal pattern:
```kotlin
SpatialView(initial = { content, _ ->
  val robot = Entity.loadSuspend("asset://model/pico_robot_animated.glb")
  content.addEntity(robot)

  val skinnedMeshEntityArray = robot.findSkinnedMeshEntity()
  for (skinnedMeshEntity in skinnedMeshEntityArray) {
    val skeletalAnimationResources = skinnedMeshEntity.getAnimationResources()
    val repeat = skeletalAnimationResources[0].repeat(3)
    repeat.use { skinnedMeshEntity.playAnimation(it) }
  }
})
```

### Playback handle
`playAnimation(...)` returns an `AnimationPlaybackController`.

Typical flow:
```kotlin
val controller = entity.playAnimation(resource, config)
controller.pause()
controller.setSpeed(0.5f)
controller.setTime(1.2f)
controller.resume()
controller.stop()
controller.close()
```

Controller checklist:
- [ ] Check `controller.valid` before using.
- [ ] Close controllers when no longer needed.
- [ ] If you destroy the entity, controllers are closed automatically.

## Blending and transitions

Use `AnimationPlayConfig` for smooth transitions and overlays:
- `transitionDuration` (seconds)
- `transitionMode` (Default/Crossfade/Compose/StopAndCrossfade)
- `blendLayer` and `blendWeight`

Example pattern:
```kotlin
val cfg = AnimationPlayConfig(
  transitionDuration = 0.5f,
  transitionMode = AnimationTransitionMode.CROSSFADE,
)
entity.playAnimation(run, cfg)
```

## Timeline playback is different

Minimal pattern:
```kotlin
val root = withContext(Dispatchers.IO) { Entity.load("SceneName", bundle) }
val timelineEntity = root.findEntity("Timeline_bird")
val controller = timelineEntity.playTimeline()
```

Notes:
- Timeline entities come from the loaded scene hierarchy and are identified by name.
- `playAnimation()` does not work for Timeline animations.

## Threading (hard rule)

All animation-related functions on `Entity` and `AnimationPlaybackController` are annotated with **@MainThread**.

Checklist:
- [ ] Calls to `playAnimation/pause/resume/stop/setSpeed/setTime/close` happen on the main thread.
- [ ] Model/resource loading can happen off-thread, but playback control returns to main.
- [ ] In `SpatialView`, entity creation/loading belongs in `initial`; do not repeatedly add the same entity from `update`.

## Resource management tips

- If you create temporary composed `AnimationResource` instances (repeat/group/sequence), ensure they are released.
- A good pattern is `use { ... }` on resources that support it:
```kotlin
AnimationResource.sequence(listOf(a, b)).use { res ->
  entity.playAnimation(res)
}
```
