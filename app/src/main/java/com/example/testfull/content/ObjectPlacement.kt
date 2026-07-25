package com.example.testfull.content

import android.util.Log
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.LoadType
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.RigidBodyComponent
import com.pico.spatial.core.ecs.Scene
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.simulation.CollisionCastHitMode
import com.pico.spatial.core.ecs.simulation.CollisionDetectionMode
import com.pico.spatial.core.ecs.simulation.CollisionGroup
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val AIM_RAY_LENGTH_METERS = 30f
private const val SUPPORT_RAY_LIFT_METERS = 0.5f
private const val SUPPORT_RAY_LENGTH_METERS = 12f

/**
 * Small gap the physics solver leaves between resting contacts. The preview lifts the ghost by
 * this amount so it matches where the dropped body actually comes to rest (otherwise the ghost
 * looks slightly sunk into the floor).
 */
private const val PREVIEW_CONTACT_SKIN_METERS = 0.02f

/**
 * Height above the previewed rest pose at which a dropped object spawns. Spawning exactly at the
 * rest pose risks initial collider penetration (the solver's contact skin), which the solver
 * resolves by violently ejecting the body — the visible "jumping out". A small air gap
 * lets physics settle the object gently onto the support instead.
 */
private const val DROP_SPAWN_LIFT_METERS = 0.08f

/** Fallback half-extent used when the selected model's mesh cannot be inspected. */
private const val FALLBACK_HALF_SIZE_METERS = 0.15f

private val GHOST_FREE_COLOR = Color4(0.35f, 0.85f, 0.65f, 0.45f)
private val GHOST_BLOCKED_COLOR = Color4(0.95f, 0.3f, 0.22f, 0.5f)

private val DOWN = Vector3(0f, -1f, 0f)
private val CONTROLLER_FORWARD = Vector3(0f, 0f, -1f)
private const val TAG = "PlacementController"

/**
 * Converts a point in scene (stage) coordinates into the room navigation root's local space.
 * The navigation root only ever translates (virtual walk), never rotates, so a subtraction is
 * exact.
 */
internal fun navigationLocalPoint(scenePoint: Vector3, navigationOffset: Vector3): Vector3 =
    scenePoint - navigationOffset

/**
 * The scene-space position where an upright model comes to rest: aimed at [anchor], supported by
 * the surface at [supportY], with the model pivot sitting [bottomOffset] (unscaled) above its
 * bottom, uniformly scaled by [scale].
 */
internal fun computeRestPosition(
    anchor: Vector3,
    supportY: Float,
    bottomOffset: Float,
    scale: Float,
): Vector3 = Vector3(anchor.x, supportY + bottomOffset * scale, anchor.z)

/**
 * Yaw in degrees that rotates a model's local +Z axis (glTF forward) to point horizontally from
 * [from] toward [user], keeping the model upright. Returns 0 when the two points coincide.
 */
internal fun yawFacingUserDegrees(from: Vector3, user: Vector3): Float {
    val dx = user.x - from.x
    val dz = user.z - from.z
    if (dx * dx + dz * dz < 1e-8f) return 0f
    return Math.toDegrees(atan2(dx, dz).toDouble()).toFloat()
}

/**
 * An upright box with yaw-only rotation, used to test whether the ghost preview overlaps an
 * already-placed object. Centers and half-extents are in the same (navigation-root-local)
 * space, already scaled.
 */
internal data class YawBox(
    val centerX: Float,
    val centerY: Float,
    val centerZ: Float,
    val halfX: Float,
    val halfY: Float,
    val halfZ: Float,
    val yawDegrees: Float,
)

/**
 * True when two yaw-rotated boxes interpenetrate: a Y-interval test plus SAT on the ground
 * plane. [tolerance] shrinks both boxes so surfaces merely touching (resting contact) do not
 * count as overlapping.
 */
internal fun yawBoxesOverlap(a: YawBox, b: YawBox, tolerance: Float): Boolean {
    if (abs(a.centerY - b.centerY) >= a.halfY + b.halfY - tolerance) return false
    val axesA = groundAxes(a.yawDegrees)
    val axesB = groundAxes(b.yawDegrees)
    val dx = b.centerX - a.centerX
    val dz = b.centerZ - a.centerZ
    // Separating axes: both local ground axes of each box.
    for (k in 0 until 4) {
        val axisX = if (k < 2) axesA[k * 2] else axesB[(k - 2) * 2]
        val axisZ = if (k < 2) axesA[k * 2 + 1] else axesB[(k - 2) * 2 + 1]
        val distance = abs(dx * axisX + dz * axisZ)
        val radiusA =
            a.halfX * abs(axesA[0] * axisX + axesA[1] * axisZ) +
                a.halfZ * abs(axesA[2] * axisX + axesA[3] * axisZ)
        val radiusB =
            b.halfX * abs(axesB[0] * axisX + axesB[1] * axisZ) +
                b.halfZ * abs(axesB[2] * axisX + axesB[3] * axisZ)
        if (distance >= radiusA + radiusB - tolerance) return false
    }
    return true
}

/**
 * Builds the world-space [YawBox] of a model whose pivot sits at [position], yawed by
 * [yawDegrees] and uniformly scaled by [scale], given the model's unscaled bounding-box
 * [center] and [halfExtents].
 */
internal fun yawBoxFor(
    position: Vector3,
    yawDegrees: Float,
    scale: Float,
    center: Vector3,
    halfExtents: Vector3,
): YawBox {
    val radians = Math.toRadians(yawDegrees.toDouble())
    val cos = cos(radians).toFloat()
    val sin = sin(radians).toFloat()
    val cx = center.x * scale
    val cz = center.z * scale
    // Rotate the bbox-center offset by the yaw (R_Y: x' = x·cos + z·sin, z' = -x·sin + z·cos).
    return YawBox(
        centerX = position.x + cx * cos + cz * sin,
        centerY = position.y + center.y * scale,
        centerZ = position.z - cx * sin + cz * cos,
        halfX = halfExtents.x * scale,
        halfY = halfExtents.y * scale,
        halfZ = halfExtents.z * scale,
        yawDegrees = yawDegrees,
    )
}

/**
 * Yaw in degrees of a quaternion applied to the glTF forward axis (+Z), matching the
 * convention of [yawFacingUserDegrees]. Upright (yaw-only) bodies are exact; tipped bodies
 * get their ground-plane heading.
 */
internal fun yawOfQuaternionDegrees(rotation: Quat): Float {
    val forward = rotation.rotateVector(Vector3(0f, 0f, 1f))
    if (forward.x * forward.x + forward.z * forward.z < 1e-8f) return 0f
    return Math.toDegrees(atan2(forward.x.toDouble(), forward.z.toDouble())).toFloat()
}

/** Local ground-plane axes of a yaw rotation: [ux, uz, vx, vz] (u = local X, v = local Z). */
private fun groundAxes(yawDegrees: Float): FloatArray {
    val radians = Math.toRadians(yawDegrees.toDouble())
    val cos = cos(radians).toFloat()
    val sin = sin(radians).toFloat()
    return floatArrayOf(cos, -sin, sin, cos)
}

/** A currently placed object as reported to the AI (navigation-root-local space). */
internal data class PlacedSummary(
    val modelName: String,
    val x: Float,
    val z: Float,
    val yawDegrees: Float,
)

/**
 * Drives the "aim → ghost preview → click to drop" flow for placing local 3D models into the
 * generated room.
 *
 * Lifecycle: [bind] once a room exists → [selectModel] when the user picks a file →
 * [updateAim] per frame with the latest controller pose → [drop] on a trigger press edge.
 * When the room is rebuilt, call [onRoomDestroyed] then [bind] with the new navigation root and
 * re-run [reloadSelection] to rebuild the ghost against the new room.
 *
 * All non-suspending methods must be called on the main thread.
 */
internal class PlacementController {
    /** Uniform scale applied to the ghost and to dropped objects. */
    var scale: Float = 1f

    var selectedModelName: String? = null
        private set

    val placedCount: Int
        get() = placedObjects.size

    /** True while the ghost preview is visible, i.e. the aim currently hits room geometry. */
    val hasAimTarget: Boolean
        get() = ghost?.enabled == true

    /** True when no overlap-free spot exists near the aim; dropping is blocked. */
    var isGhostBlocked: Boolean = false
        private set

    private var navigationRoot: Entity? = null
    private var scene: Scene? = null
    private var modelFile: File? = null

    /** Room footprint in navigation-root-local space; placement is clamped to stay inside it. */
    private var footprintWalls: List<PlanWall> = emptyList()
    private var footprintMargin: Float = 0f

    /** Distance from the model pivot to its bottom, in unscaled model units. */
    private var bottomOffset: Float = 0f

    private var collisionMesh: MeshResource? = null
    private var modelHalfExtents =
        Vector3(FALLBACK_HALF_SIZE_METERS, FALLBACK_HALF_SIZE_METERS, FALLBACK_HALF_SIZE_METERS)

    /** Unscaled bounding-box center of the selected model (bbox center is rarely the pivot). */
    private var modelCenter = Vector3(0f, FALLBACK_HALF_SIZE_METERS, 0f)

    private var ghost: Entity? = null

    /**
     * Monotonic counter making selection changes race-safe: two quick selections interleave
     * their loads, and without a staleness check the losing load would get parented into the
     * scene but never tracked — an undeletable "stuck ghost".
     */
    private var selectionGeneration = 0

    // Created fresh with each ghost and dies with it (the SDK closes a destroyed entity's
    // materials). Never re-add it to another entity: that crashes on a closed material.
    private var ghostPreviewMaterial: PhysicallyBasedMaterial? = null
    private var ghostTintBlocked = false

    /** A placed object plus the data needed to overlap-test the ghost against it. */
    private class PlacedObject(
        val entity: Entity,
        /** Display name of the placed model (reported back to the AI prompt). */
        val modelName: String,
        /** Unscaled bounding-box center of its model. */
        val center: Vector3,
        /** Unscaled bounding-box half-extents of its model. */
        val halfExtents: Vector3,
        /** Uniform scale the object was dropped at. */
        val scale: Float,
    )

    private val placedObjects = mutableListOf<PlacedObject>()

    // Every drop gets its own shape/material: sharing one ShapeResource across dynamic bodies and
    // then destroying some of them can invalidate the shared handle (the engine logs
    // "not shape resource id: 0" and the bodies fall through the floor).
    private val placedShapes = mutableListOf<ShapeResource>()
    private val placedMaterials = mutableListOf<PhysicsMaterialResource>()
    private var dropInFlight = false

    /** Binds the controller to a live room navigation root. */
    fun bind(navigationRoot: Entity) {
        this.navigationRoot = navigationRoot
        this.scene = navigationRoot.scene
    }

    /**
     * Sets the building footprint (wall segments in navigation-root-local coordinates) that
     * placement is clamped to. [margin] is the minimum clearance kept from every wall.
     */
    fun setFootprint(walls: List<PlanWall>, margin: Float) {
        footprintWalls = walls
        footprintMargin = margin
    }

    /**
     * Called when the owning room is about to be destroyed (plan re-apply). Dropped objects and
     * the ghost die with the room's entity tree, so only resource handles are released here. The
     * model selection survives; call [reloadSelection] after [bind] to restore the ghost.
     */
    fun onRoomDestroyed() {
        placedObjects.clear()
        placedShapes.forEach { it.close() }
        placedShapes.clear()
        placedMaterials.forEach { it.close() }
        placedMaterials.clear()
        ghost = null
        ghostPreviewMaterial = null
        ghostTintBlocked = false
        isGhostBlocked = false
        collisionMesh?.close()
        collisionMesh = null
    }

    /**
     * Loads [file] for placement: builds the transparent ghost preview and the collision mesh
     * used for drops. Replaces any previous selection; dropped objects from earlier selections
     * stay in the room.
     */
    suspend fun selectModel(file: File, displayName: String): Boolean {
        selectionGeneration += 1
        releaseSelectionResources()
        modelFile = file
        selectedModelName = displayName
        return reloadSelection()
    }

    /** (Re)builds the ghost and collision resources for the current selection, if any. */
    suspend fun reloadSelection(): Boolean {
        // A spawn (manual drop or AI placement) is loading a model right now — loading the
        // ghost concurrently is exactly the kind of native-memory spike that kills the
        // process on the emulator. The caller retries on the next selection/rebuild.
        if (dropInFlight) {
            Log.d(TAG, "reloadSelection: skipped, spawn in flight")
            return false
        }
        val generation = selectionGeneration
        val file = modelFile ?: return false
        // Release the old ghost BEFORE loading: while the new model loads, the SDK peaks at
        // roughly 2× its size in native memory — keeping the old ghost alive through the
        // load kills the process on large models on the emulator.
        releaseSelectionResources()
        val loaded = withContext(Dispatchers.IO) { runCatching { Entity.loadSuspend(file) } }
        val ghostEntity = loaded.getOrNull() ?: return false
        if (generation != selectionGeneration) {
            // A newer selection superseded this load: destroy it here so it can never
            // leak into the scene as an untracked, stuck ghost.
            Log.d(TAG, "reloadSelection: superseded, discarding stale load")
            ghostEntity.destroy(recursively = true)
            return false
        }

        // Build the collider from a first-class mesh resource loaded from storage — the
        // MeshResource pulled from a loaded entity's ModelComponent can be an invalid proxy
        // (observed on-device as "not shape resource id: 0" and objects falling through floors).
        val mesh =
            withContext(Dispatchers.IO) {
                runCatching { MeshResource.load(file.absolutePath, LoadType.FROM_STORAGE) }
                    .getOrNull()
            }
        Log.d(TAG, "reloadSelection: mesh=${mesh != null} valid=${mesh?.valid}")
        // Bounds come from the loaded ghost entity's visual bounds: the standalone MeshResource
        // loader does not support GLB (FORMAT_UNSUPPORTED), which silently produced
        // fallback-size colliders and previews.
        val bounds =
            runCatching { ghostEntity.getVisualBounds(relativeTo = ghostEntity, recursive = true) }
                .getOrNull()
                ?.takeUnless { it.isEmpty() }
        Log.w(TAG, "reloadSelection: bounds=${bounds?.size} minY=${bounds?.min?.y}")

        if (generation != selectionGeneration) {
            // Superseded while the mesh/bounds were loading — same leak prevention.
            ghostEntity.destroy(recursively = true)
            return false
        }
        // And release whatever a superseding selection may have assigned in the meantime.
        releaseSelectionResources()
        val previewMaterial = createGhostMaterial()
        applyGhostMaterial(ghostEntity, previewMaterial)
        ghostEntity.enabled = false
        navigationRoot?.addChild(ghostEntity)
        ghost = ghostEntity
        ghostPreviewMaterial = previewMaterial
        ghostTintBlocked = false
        isGhostBlocked = false
        collisionMesh = mesh
        modelHalfExtents =
            bounds?.size?.let { Vector3(it.x / 2f, it.y / 2f, it.z / 2f) }
                ?: Vector3(
                    FALLBACK_HALF_SIZE_METERS,
                    FALLBACK_HALF_SIZE_METERS,
                    FALLBACK_HALF_SIZE_METERS,
                )
        modelCenter = bounds?.center ?: Vector3(0f, FALLBACK_HALF_SIZE_METERS, 0f)
        // Bottom-pivot models get offset 0; centered pivots sit half a height up.
        bottomOffset = bounds?.min?.y?.let { -it }?.coerceAtLeast(0f) ?: 0f
        return true
    }

    /**
     * Per-frame aim update. [originScene] and [rotationScene] are the active aim pose in scene
     * space; [userPositionScene] is the headset position used to yaw the model so it faces the
     * user. The ghost is placed where a dropped object will come to rest: ray-cast from the aim
     * pose to find the anchor, then ray-cast straight down from above the anchor to find the
     * supporting surface (floor, or a previously dropped object), and lift by the pivot offset.
     * If the aimed spot overlaps an already-placed object on the ground plane, the ghost slides
     * to the nearest spot where the bounding boxes no longer intersect (see separateFromBoxes);
     * only when no free space exists nearby is the ghost tinted red and [drop] blocked.
     */
    fun updateAim(originScene: Vector3, rotationScene: Quat, userPositionScene: Vector3?) {
        val scene = this.scene ?: return hideGhost()
        val navRoot = this.navigationRoot ?: return hideGhost()
        val ghostEntity = this.ghost ?: return

        val direction = rotationScene.rotateVector(CONTROLLER_FORWARD)
        val anchor =
            scene
                .rayCast(
                    origin = originScene,
                    direction = direction,
                    length = AIM_RAY_LENGTH_METERS,
                    hitMode = CollisionCastHitMode.NEAREST,
                    group = CollisionGroup(CollisionGroup.COLLISION_GROUP_ALL),
                )
                .results
                .minByOrNull { it.distance }
        if (anchor == null) {
            hideGhost()
            return
        }

        // Keep placement inside the building: an aim point that escapes through a door/window
        // (or past a wall) is pulled back to the nearest allowed spot inside the footprint.
        // The margin includes the object's own horizontal half-extent so the whole model —
        // not just its pivot — stays clear of the walls.
        val navOffset =
            navRoot.components[TransformComponent::class.java]?.position ?: Vector3.ZERO
        val anchorLocal = navigationLocalPoint(anchor.position, navOffset)
        val objectRadius = max(modelHalfExtents.x, modelHalfExtents.z) * scale
        val clampedPlan =
            clampToFootprint(
                footprintWalls,
                PlanPoint(anchorLocal.x, anchorLocal.z),
                footprintMargin + objectRadius,
            )
        val anchorX = clampedPlan.x + navOffset.x
        val anchorZ = clampedPlan.z + navOffset.z

        // A wall anchor still lands on the floor: support is whatever is directly below the
        // (clamped) anchor point.
        val supportOrigin =
            Vector3(anchorX, anchor.position.y + SUPPORT_RAY_LIFT_METERS, anchorZ)
        val support =
            scene
                .rayCast(
                    origin = supportOrigin,
                    direction = DOWN,
                    length = SUPPORT_RAY_LENGTH_METERS,
                    hitMode = CollisionCastHitMode.NEAREST,
                    group = CollisionGroup(CollisionGroup.COLLISION_GROUP_ALL),
                )
                .results
                .minByOrNull { it.distance }
        val supportY = support?.position?.y ?: anchor.position.y

        val restScene =
            computeRestPosition(
                Vector3(anchorX, anchor.position.y, anchorZ),
                supportY + PREVIEW_CONTACT_SKIN_METERS,
                bottomOffset,
                scale,
            )
        var restLocal = navigationLocalPoint(restScene, navOffset)
        val yaw =
            userPositionScene?.let { yawFacingUserDegrees(restScene, it) } ?: 0f

        // If the aimed spot overlaps placed furniture on the ground plane, slide the ghost to
        // the nearest free spot (its bounding box must not intersect any placed one). Only
        // when no free space exists nearby does the ghost stay red and dropping get blocked.
        val others = placedBoxes()
        if (others.isNotEmpty()) {
            val halfDiagonal =
                hypot(modelHalfExtents.x.toDouble(), modelHalfExtents.z.toDouble()).toFloat() *
                    scale
            val separated =
                separateFromBoxes(
                    point = PlanPoint(restLocal.x, restLocal.z),
                    pivotY = restLocal.y,
                    yawDegrees = yaw,
                    scale = scale,
                    center = modelCenter,
                    halfExtents = modelHalfExtents,
                    others = others,
                    walls = footprintWalls,
                    margin = footprintMargin + halfDiagonal,
                )
            if (separated == null) {
                setGhostBlocked(true)
            } else {
                restLocal = Vector3(separated.x, restLocal.y, separated.z)
                setGhostBlocked(false)
            }
        } else {
            setGhostBlocked(false)
        }

        ghostEntity.components[TransformComponent::class.java]?.apply {
            setPosition(restLocal)
            setScaleVector(Vector3(scale, scale, scale))
            setEulerAngles(EulerAngles(yaw = yaw))
        }
        ghostEntity.enabled = true
    }

    /** Current ground-plane boxes of all placed objects (re-read each frame; they settle). */
    private fun placedBoxes(): List<YawBox> =
        placedObjects.mapNotNull { placed ->
            val transform =
                placed.entity.components[TransformComponent::class.java] ?: return@mapNotNull null
            yawBoxFor(
                transform.position,
                yawOfQuaternionDegrees(transform.quaternion),
                placed.scale,
                placed.center,
                placed.halfExtents,
            )
        }

    /** Updates the blocked flag and retints the ghost (green = free, red = no free space). */
    private fun setGhostBlocked(blocked: Boolean) {
        isGhostBlocked = blocked
        if (blocked == ghostTintBlocked) return
        ghostTintBlocked = blocked
        ghostPreviewMaterial?.setBaseColor(if (blocked) GHOST_BLOCKED_COLOR else GHOST_FREE_COLOR)
    }

    /** Hides the preview, e.g. when tracking is temporarily lost. */
    fun hideGhost() {
        ghost?.enabled = false
        isGhostBlocked = false
    }

    /**
     * Destroys the ghost and closes collision resources while KEEPING the model selection.
     * Used before an AI arrange spawns a full room of furniture: a large ghost plus the
     * spawn sequence has tripped the kernel low-memory killer on the emulator.
     */
    fun releaseSelectionGhost() {
        releaseSelectionResources()
    }

    /**
     * Drops a fresh copy of the selected model at the ghost's pose as a fully dynamic rigid body.
     * The ghost is destroyed before the (heavy) model load and rebuilt after — otherwise the
     * ghost and the new entity peak together at ~2× the model's native size, which kills the
     * process on large models on the emulator. Returns true if the object was placed.
     */
    suspend fun drop(): Boolean {
        val file = modelFile ?: return false
        val ghostEntity = ghost ?: return false
        if (!ghostEntity.enabled || dropInFlight || isGhostBlocked) return false
        val ghostTransform = ghostEntity.components[TransformComponent::class.java]
        val position = ghostTransform?.position ?: return false
        val rotation = ghostTransform?.quaternion
        val scaleVector = ghostTransform?.scaleVector ?: Vector3(scale, scale, scale)

        releaseSelectionResources()
        val placed =
            spawnPlaced(
                file = file,
                displayName = selectedModelName ?: file.nameWithoutExtension,
                positionLocal = position,
                scaleVector = scaleVector,
                center = modelCenter,
                halfExtents = modelHalfExtents,
                rotation = rotation,
                yawDegrees = null,
                allowConvexFallback = true,
            )
        // Restore the preview for the next drop (keeps the model selection).
        reloadSelection()
        return placed
    }

    /**
     * Spawns [model] at an AI-chosen pose: pivot at (x, bottomOffset·scale, z) — resting on the
     * floor — yawed by [yawDegrees]. Uses the catalog's measured bounds for the collider, so the
     * model does not need to be the current ghost selection. Returns true if it was placed.
     */
    suspend fun placeFromAi(
        model: CatalogModel,
        x: Float,
        z: Float,
        yawDegrees: Float,
        scale: Float,
    ): Boolean {
        if (navigationRoot == null) return false
        val position = Vector3(x, model.bottomOffset * scale, z)
        return spawnPlaced(
            file = model.file,
            displayName = model.displayName,
            positionLocal = position,
            scaleVector = Vector3(scale, scale, scale),
            center = model.center,
            halfExtents = model.halfExtents,
            rotation = null,
            yawDegrees = yawDegrees,
            allowConvexFallback = false,
        )
    }

    /** Snapshot of the placed furniture, for the AI prompt. */
    fun placedSummaries(): List<PlacedSummary> =
        placedObjects.mapNotNull { placed ->
            val transform =
                placed.entity.components[TransformComponent::class.java] ?: return@mapNotNull null
            PlacedSummary(
                modelName = placed.modelName,
                x = transform.position.x,
                z = transform.position.z,
                yawDegrees = yawOfQuaternionDegrees(transform.quaternion),
            )
        }

    /**
     * Shared spawn core of [drop] and [placeFromAi]. The model file is loaded again off the main
     * thread (a loaded entity tree cannot be parented twice), and the collision shape/material
     * are created fresh for each spawn (shared handles get invalidated, see placedShapes). The
     * body spawns [DROP_SPAWN_LIFT_METERS] above the rest pose so physics settles it gently.
     * Exactly one of [rotation] / [yawDegrees] is applied. [allowConvexFallback] permits the
     * current selection's convex mesh as a fallback shape — only meaningful for [drop].
     */
    private suspend fun spawnPlaced(
        file: File,
        displayName: String,
        positionLocal: Vector3,
        scaleVector: Vector3,
        center: Vector3,
        halfExtents: Vector3,
        rotation: Quat?,
        yawDegrees: Float?,
        allowConvexFallback: Boolean,
    ): Boolean {
        val navRoot = navigationRoot ?: return false
        if (dropInFlight) return false
        dropInFlight = true
        return try {
            val shape =
                createBoundingBoxShape(center, halfExtents)
                    ?: if (allowConvexFallback) {
                        collisionMesh
                            ?.let { runCatching { ShapeResource.createConvexMesh(it) }.getOrNull() }
                            ?.takeIf { it.valid }
                    } else {
                        null
                    }
                    ?: ShapeResource.createBox(
                        Vector3(
                            (halfExtents.x * 2f).coerceIn(0.02f, 10f),
                            (halfExtents.y * 2f).coerceIn(0.02f, 10f),
                            (halfExtents.z * 2f).coerceIn(0.02f, 10f),
                        )
                    )
            val material =
                PhysicsMaterialResource(
                    staticFriction = 0.6f,
                    dynamicFriction = 0.5f,
                    restitution = 0.05f,
                )
            val entity = withContext(Dispatchers.IO) { Entity.loadSuspend(file) }
            entity.components[TransformComponent::class.java]?.apply {
                setPosition(
                    Vector3(
                        positionLocal.x,
                        positionLocal.y + DROP_SPAWN_LIFT_METERS,
                        positionLocal.z,
                    )
                )
                setScaleVector(scaleVector)
                when {
                    rotation != null -> setQuaternion(rotation)
                    yawDegrees != null -> setEulerAngles(EulerAngles(yaw = yawDegrees))
                }
            }
            entity.components.set(
                CollisionComponent(collisionShape = listOf(shape), physicsMaterial = material)
            )
            entity.components.set(
                RigidBodyComponent().apply {
                    // CCD against the static room so a falling object can't tunnel through the
                    // thin walls or floor.
                    collisionDetectionMode = CollisionDetectionMode.CONTINUOUS
                }
            )
            navRoot.addChild(entity)
            entity.enabled = true
            placedObjects += PlacedObject(entity, displayName, center, halfExtents, scaleVector.x)
            placedShapes += shape
            placedMaterials += material
            Log.w(
                TAG,
                "spawnPlaced: $displayName at $positionLocal scale=$scaleVector " +
                    "shapeValid=${shape.valid}",
            )
            true
        } catch (error: Exception) {
            Log.w(TAG, "spawnPlaced failed", error)
            false
        } finally {
            dropInFlight = false
        }
    }

    /**
     * Full-bounding-box collider, translated so it wraps the model exactly where the mesh sits
     * (bbox center is rarely the pivot, and [CollisionComponent] has no per-shape offset).
     * Thick boxes give stable, gap-free contacts between furniture and walls; the previous
     * first-mesh convex hull could be much smaller than the visible model on multi-node
     * files, which let objects visibly pass through each other.
     */
    private fun createBoundingBoxShape(center: Vector3, halfExtents: Vector3): ShapeResource? {
        val size =
            Vector3(
                (halfExtents.x * 2f).coerceIn(0.02f, 10f),
                (halfExtents.y * 2f).coerceIn(0.02f, 10f),
                (halfExtents.z * 2f).coerceIn(0.02f, 10f),
            )
        return runCatching {
                val base = ShapeResource.createBox(size)
                try {
                    base.offsetByTranslation(center)
                } finally {
                    base.close()
                }
            }
            .getOrNull()
            ?.takeIf { it.valid }
    }

    /** Removes every dropped object from the room and releases their collision resources. */
    fun clearPlaced() {
        placedObjects.forEach { it.entity.destroy(recursively = true) }
        placedObjects.clear()
        isGhostBlocked = false
        placedShapes.forEach { it.close() }
        placedShapes.clear()
        placedMaterials.forEach { it.close() }
        placedMaterials.clear()
    }

    /** Releases the ghost, collision resources, placed objects, and the model selection. */
    fun dispose() {
        clearPlaced()
        releaseSelectionResources()
        modelFile = null
        selectedModelName = null
        navigationRoot = null
        scene = null
    }

    /** Destroys the ghost and closes per-selection resources; keeps the file/name selection. */
    private fun releaseSelectionResources() {
        ghost?.let { ghostEntity ->
            ghostEntity.enabled = false
            ghostEntity.destroy(recursively = true)
        }
        ghost = null
        // The preview material was closed by the SDK together with the ghost entity.
        ghostPreviewMaterial = null
        ghostTintBlocked = false
        isGhostBlocked = false
        collisionMesh?.close()
        collisionMesh = null
        bottomOffset = 0f
    }

    // A fresh material per ghost: the SDK closes a ModelComponent's materials when its
    // entity is destroyed, so re-adding a cached one on the next selection crashes with
    // "can't add material ... with a closed PhysicallyBasedMaterial".
    private fun createGhostMaterial(): PhysicallyBasedMaterial =
        PhysicallyBasedMaterial.create(BlendingMode.TRANSPARENT).apply {
            setBaseColor(GHOST_FREE_COLOR)
            setOpacity(0.45f)
            setRoughness(0.35f)
            setDepthWrite(false)
        }

    private fun applyGhostMaterial(entity: Entity, preview: PhysicallyBasedMaterial) {
        traverse(entity) { current ->
            current.components[ModelComponent::class.java]?.let { model ->
                // WARNING: the `materials` getter re-pulls from native on EVERY access —
                // call it once per component. Looping on `model.materials.size` re-reads the
                // getter each iteration and hangs the main thread (observed as an ANR).
                val materials = model.materials
                while (materials.size > 0) {
                    materials.removeLast()
                }
                materials.add(preview)
            }
        }
    }

    private fun traverse(entity: Entity, visit: (Entity) -> Unit) {
        visit(entity)
        entity.getChildren().forEach { traverse(it, visit) }
    }
}
