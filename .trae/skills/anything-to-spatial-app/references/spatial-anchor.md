# Spatial Anchors

> ✅ **API authority status (verified against the PICO SpatialSDK source)**:
> All class / method names below were cross-checked against the official source.
> The package is **`com.pico.spatial.sense.world`** (under `sensepack`, NOT
> `trackingpack` and NOT `com.pico.spatial.tracking`). `WorldTrackingManager`
> is annotated `@RequiredFullSpace` — calling it outside Full Space is a
> contract violation. Sibling APIs `PlaneTrackingManager` /
> `MeshTrackingManager` live alongside it for plane / environment-mesh
> sensing.

Bind a virtual position to a real-world location. Anchors persist across
sessions (stored on device) and can optionally be uploaded as **Shared
Spatial Anchors** by UUID.

## Authoritative imports

| Symbol | Fully-qualified name |
|---|---|
| `WorldTrackingManager` (object) | `com.pico.spatial.sense.world.WorldTrackingManager` |
| `WorldAnchor` | `com.pico.spatial.sense.world.WorldAnchor` |
| `WorldTrackingResult<T>` (sealed: Success / Error) | `com.pico.spatial.sense.world.WorldTrackingResult` |
| `PlaneTrackingManager` (object) | `com.pico.spatial.sense.plane.PlaneTrackingManager` |
| `PlaneAnchor` | `com.pico.spatial.sense.plane.PlaneAnchor` |
| `MeshTrackingManager` (object) | `com.pico.spatial.sense.mesh.MeshTrackingManager` |
| `MeshAnchor` | `com.pico.spatial.sense.mesh.MeshAnchor` |
| `AnchorEntity(target: AnchorTarget)` | `com.pico.spatial.core.ecs.AnchorEntity` |
| `AnchorComponent` | `com.pico.spatial.core.ecs.AnchorComponent` |
| `AnchorTarget` | `com.pico.spatial.core.ecs.anchor.AnchorTarget` |
| `@RequiredFullSpace` annotation | `com.pico.spatial.core.annotation.RequiredFullSpace` |
| `Vector3` / `EulerAngles` | `com.pico.spatial.core.math.*` |

## Hard prerequisites

- **Container must be `DefaultStage` (Full Space).** `WorldTrackingManager`
  itself carries `@RequiredFullSpace`; calling it from a WindowContainer
  flow violates the runtime contract.
- **Dependency**: `sensepack` artifact (`com.pico.spatial.sense.*`). Do NOT
  reach for `com.pico.spatial.tracking:tracking` — that package handles
  hand / eye / body / controller / HMD / motion tracking (per
  `SpatialSDK/AGENTS.md`), not anchors.
- The launching Activity / `mainApp` must hand off to a Stage via
  `openStage(...)` (see `com.pico.spatial.ui.platform.containers.openStage`).

## Codegen rule (verified shape)

Whenever `spatial_features` includes `"anchor"`, every
`WorldTrackingManager.createAnchor(...)` call site MUST be inside a
suspend / coroutine context (the API is `public suspend fun`):

```kotlin
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.sense.world.WorldTrackingManager
import com.pico.spatial.sense.world.WorldTrackingResult

suspend fun placeAnchor(): String? {
    val result: WorldTrackingResult<WorldAnchor> = WorldTrackingManager.createAnchor(
        position = Vector3(0F, 0F, 0F),
        rotation = EulerAngles(0F, 0F, 0F),
        name = "anchor1",                       // optional, default ""
    )
    return when (result) {
        is WorldTrackingResult.Success -> result.data.anchorUUID.toString()
        is WorldTrackingResult.Error   -> { /* result.errorCode / result.errorMessage */ null }
    }
}
```

`WorldTrackingResult` is **sealed**, exhaust both branches.

## Loading anchors

```kotlin
import java.util.UUID

val uuids: Array<UUID> = loadSavedUuids()
val loadResult = WorldTrackingManager.loadAnchor(uuids)   // suspend
when (loadResult) {
    is WorldTrackingResult.Success -> {
        val anchors: Array<WorldAnchor> = loadResult.data
        anchors.forEach { renderAt(it /* .transform / .anchorUUID / .name */) }
    }
    is WorldTrackingResult.Error -> { /* handle */ }
}
```

Empty / no `uuids` argument → loads **all** anchors saved by this app
(`loadAnchor()` defaults `uuids = arrayOf()`).

## Removing anchors

```kotlin
val removeResult = WorldTrackingManager.removeAnchor(anchorUuid)   // suspend, returns WorldTrackingResult<Unit>
```

## Anchor update events

`WorldTrackingManager` exposes a subscriber API; subscribe once and cancel
the returned `Cancellable` when no longer needed:

```kotlin
import com.pico.spatial.core.lifecycle.Cancellable
import com.pico.spatial.sense.base.AnchorUpdate

val subscription: Cancellable = WorldTrackingManager.subscribeAnchorUpdate { event ->
    when (event.anchorUpdateEvent) {
        AnchorUpdate.Event.ADDED   -> { /* new anchor placed */ }
        AnchorUpdate.Event.LOADED  -> { /* anchor re-loaded after restart */ }
        AnchorUpdate.Event.REMOVED -> { /* anchor removed */ }
        // (verify the full enum against AnchorUpdate.kt — UPDATED may exist)
    }
}

// Later:
subscription.cancel()
```

(The `subscribeAnchorUpdate` shape and `AnchorUpdate.Event` constants come
directly from `WorldTrackingManager.kt` KDoc and source.)

## ECS anchor entities (alternative to manager-based anchors)

For ECS-style integration, use `AnchorEntity` + `AnchorComponent` with an
`AnchorTarget`:

```kotlin
import com.pico.spatial.core.ecs.AnchorEntity
import com.pico.spatial.core.ecs.AnchorComponent
import com.pico.spatial.core.ecs.anchor.AnchorTarget

val anchorEntity = AnchorEntity(AnchorTarget.createCameraTarget())
anchorEntity.components.get<AnchorComponent>()?.apply { /* configure */ }
```

This pattern is used in the official `SpatialAppSample/.../raycast/RayCastSample.kt`.

## Plane / Mesh sensing (siblings of WorldTrackingManager)

Same API shape, different anchor type:

| Manager | Returns | Typical use |
|---|---|---|
| `PlaneTrackingManager` | `Array<PlaneAnchor>` | Detect floors / tables / walls |
| `MeshTrackingManager` | `Array<MeshAnchor>` | Environment mesh for collision / occlusion |

Both expose `loadAllAnchors()`, `subscribeAnchorUpdate(...)`, `start()` /
`stop()` — symmetric to `WorldTrackingManager`.

## UX guidance to embed in generated code (as comments)

- Place anchors within ≤ 3m of the user.
- After placement, prompt user to look around so the system can map
  features for re-localization.
- Re-acquisition radius ≤ 5m from the original placement; beyond that,
  recovery may fail.
- Save UUIDs persistently — without them, you can't reload anchors after
  app restart.

## When `spatial_features` includes `anchor` but `container` is a WindowContainer

This is a hard violation of `@RequiredFullSpace`. The skill rejects this
combination during Phase 4 (legality table) — the only valid fixes are:

- Switch container to `STAGE_MIXED` (passthrough + anchor) and inform the
  user, OR
- Drop the anchor feature and emit only the panel UI

Don't try to "fake" anchors inside a WindowContainer — there's no API path.
