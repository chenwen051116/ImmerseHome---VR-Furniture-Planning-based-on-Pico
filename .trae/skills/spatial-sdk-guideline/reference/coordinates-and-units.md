# Coordinates and Units

## What this covers
- Coordinate handedness and axes for common spaces
- Where origins are for Stage, SpatialView, and WindowContainer
- Converting between coordinate spaces (entity ↔ entity, view ↔ entity)
- Converting between dp / px / meters
- Common pitfalls (rotation/scale alignment, floating-point tolerance)

## Coordinate systems you will encounter

### Stage (immersive)
- **Right-handed**, units in **meters**.
- Origin (0,0,0): where the HMD vertical centerline meets the ground.
- Axes: +X right, +Y up, **+Z toward the user**.

Constraints:
- Stage is **unbounded**, **Full Space only**, and only one Stage can be open at a time.

### SpatialView
- **Right-handed**, meters.
- Origin at the geometric center of its bounding box.
- +Z points toward the observer.

### WindowContainer
WindowContainer has two relevant spaces:

1) **View/Compose coordinate space**
- **Left-handed**, virtual pixels.
- Origin at the top-left of the back plane.
- +X right, +Y down.

2) **Entity coordinate space**
- **Right-handed**, meters.
- Origin at the geometric center of the container volume.
- +Z extends outward perpendicular to the screen.

## Local vs global transforms

- `TransformComponent` stores **local transform**.
- When you add an entity to a `SpatialView`, its local transform becomes its transform in the SpatialView space.
- When you parent an entity under another entity, its local transform becomes relative to the parent.

Checklist:
- [ ] Is my transform intended to be relative to the parent, or absolute in the container?

## Convert coordinate spaces

### Entity ↔ Entity (including across containers)
Use conversion helpers:
- `convertPositionTo/From`
- `convertRotationTo`
- `convertScaleTo`
- `convertTransformTo`

Pattern: move an entity across containers while preserving pose:
```kotlin
private fun Entity.moveAcrossContainersTo(destination: Entity) {
  val p = convertPositionTo(Vector3.ZERO, destination)
  val r = convertRotationTo(Quat.identity(), destination)
  val s = convertScaleTo(Vector3.ONE, destination)

  setParent(destination)
  components[TransformComponent::class.java]?.apply {
    position = p
    rotation = r
    scaleVector = s
  }
}
```

Important:
- Converting only position is usually not enough; rotation and scale often matter.

### View ↔ Entity
When placing entities based on view offsets:
- Convert from `ViewCoordinateSpace` (px) to a spatial coordinate space (meters).

## Unit conversions (dp / px / meters)

### dp ↔ px
Use Compose `Density`:
```kotlin
val density = LocalDensity.current
with(density) {
  val px = 16.dp.toPx()
  val dp = px.toDp()
}
```

### dp ↔ meters
Use `LocalPhysicalLengthConverter.current`:
```kotlin
val c = LocalPhysicalLengthConverter.current
// Note: The enum constant name can vary by SDK version/docs.
// Prefer the one your SDK actually exposes (IDE autocomplete / compiler error will tell you).
val metersUnit = LengthUnit.METERS // Some versions/docs use: LengthUnit.Meters

val dpValue = 200.dp
val metersValue = c.dpToLength(dpValue, metersUnit)
val dpBack = c.lengthToDp(metersValue, metersUnit)
```

### px ↔ meters
Combine the two:
- px → dp via `Density`
- dp → meters via `PhysicalLengthConverter`

## Known behaviors and pitfalls

### Rendering order: WindowContainer vs Stage
- Content inside `WindowContainer` is always rendered before `Stage`.
- If you observe unexpected occlusion between them, treat it as a known compositing behavior rather than a random layering bug.
- In most cross-container alignment cases, converting only position is not enough; convert rotation and scale too (or use `convertTransformTo(...)`).

### Floating-point tolerance
Rotation/scale conversions can produce small floating-point errors.
Avoid strict equality checks; use an epsilon:
- `abs(a - b) < ε`
