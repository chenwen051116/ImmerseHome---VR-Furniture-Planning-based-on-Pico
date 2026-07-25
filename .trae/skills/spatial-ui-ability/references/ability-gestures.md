# Spatial Gestures

Capability: tap / drag / rotate / scale / combined transform on Composables targeting 3D entities.

## Prerequisite: Make The Entity Interactable

```kotlin
import com.pico.spatial.core.Entity
import com.pico.spatial.core.component.InteractableComponent
import com.pico.spatial.core.component.CollisionComponent
import com.pico.spatial.core.resource.ShapeResource
import com.pico.spatial.core.resource.PhysicsMaterialResource

val entity = Entity()
entity.components.set(InteractableComponent())
entity.components.set(
    CollisionComponent(
        collisionShape = listOf(ShapeResource.createSphere(radius = 0.3f)),
        physicsMaterial = PhysicsMaterialResource(),
    )
)
content.addEntity(entity)
```

## Spatial Tap

```kotlin
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import com.pico.spatial.ui.foundation.gesture.TargetEntity

val context = LocalContext.current
Box(
    Modifier.size(200.dp).pointerInput(Unit) {
        detectSpatialTapGesture(context, targetedToEntity = TargetEntity.any()) { tapValue ->
            // tapValue.position: Offset3D - 3D hit position
            // tapValue.targetEntity: Entity? - the tapped entity
            // tapValue.interactionKind - input source type
        }
    }
)
```

## Spatial Drag (Single Pointer, Including Z)

```kotlin
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.math.Offset3D

val context = LocalContext.current
var offset3D by remember { mutableStateOf(Offset3D.Zero) }

Box(
    Modifier
        .offset { IntOffset(offset3D.x.roundToInt(), offset3D.y.roundToInt()) }
        .zOffset { offset3D.z }
        .size(200.dp)
        .pointerInput(Unit) {
            detectSpatialDragGesture(context, targetedToEntity = TargetEntity.any()) {
                offset3D += it.dragAmount  // accumulate incremental deltas
            }
        }
)
```

## Two-Finger Rotation

```kotlin
import com.pico.spatial.ui.foundation.gesture.detectSpatialRotateGesture
import com.pico.spatial.math.Rotation3D
import com.pico.spatial.math.RotationAxis3D

val context = LocalContext.current
var rotation by remember { mutableStateOf(Rotation3D.identity()) }

Box(
    Modifier
        .size(200.dp)
        .pointerInput(Unit) {
            detectSpatialRotateGesture(context, constraintsRotationAxis = RotationAxis3D.Y) {
                rotation = rotation.rotateBy(it.rotation.toQuaternion())
            }
        }
        .rotate3D { rotation }
)
```

## Two-Finger Scale

```kotlin
import com.pico.spatial.ui.foundation.gesture.detectSpatialScaleGesture
import com.pico.spatial.math.NormalizedPoint3D

val context = LocalContext.current
var scale by remember { mutableFloatStateOf(1f) }
var pivot by remember { mutableStateOf(NormalizedPoint3D.Center) }

Box(
    Modifier
        .size(200.dp)
        .pointerInput(Unit) {
            detectSpatialScaleGesture(context) {
                scale *= it.scaleValue
                pivot = it.centroid
            }
        }
        .scale3D(scale = scale, pivot = pivot)
)
```

## Combined Transform (Drag + Rotate + Scale)

```kotlin
import com.pico.spatial.ui.foundation.gesture.detectSpatialTransformGesture
import com.pico.spatial.ui.SpatialModelView
import com.pico.spatial.core.resource.Source

val context = LocalContext.current
var rotation by remember { mutableStateOf(Rotation3D.identity()) }
var pan by remember { mutableStateOf(Offset3D.Zero) }
var scale by remember { mutableFloatStateOf(1f) }

SpatialModelView(
    modifier = Modifier
        .offset { IntOffset(pan.x.roundToInt(), pan.y.roundToInt()) }
        .zOffset { pan.z }
        .scale3D(scale)
        .rotate3D { rotation }
        .size(200.dp)
        .pointerInput(Unit) {
            detectSpatialTransformGesture(context, targetedToEntity = TargetEntity.any()) {
                rotation = rotation.rotateBy(it.rotation)
                pan += it.dragAmount
                scale *= it.scaleValue
            }
        },
    source = Source.assets("model.usdz"),
)
```

## Notes

- All callback values are incremental; accumulate translation, rotation, and scale yourself.
- `dragAmount` is delivered in pixels. Convert to meters before applying it to ECS entities.
- Rotation and scaling require at least two touch points.

## Gestures Not Responding? Check This

1. Does the entity have both `InteractableComponent` and `CollisionComponent`?
2. Is `pointerInput` attached to the actual rendered Composable?
3. Is the content hosted inside `WindowContainer` or `SpatialScene`?
4. Is another window or control covering the target on the device?
5. Does `targetedToEntity` match your intent (`TargetEntity.any()` or a specific entity)?

## Imports

```kotlin
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialRotateGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialScaleGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTransformGesture
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.math.Offset3D
import com.pico.spatial.math.Rotation3D
import com.pico.spatial.math.RotationAxis3D
import com.pico.spatial.math.NormalizedPoint3D
```

---

See also: [`troubleshooting.md`](troubleshooting.md) for cross-cutting checks (Spatial platform, `WindowContainer`, modifier order).
