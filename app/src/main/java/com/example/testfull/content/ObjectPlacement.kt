package com.example.testfull.content

import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
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
import com.pico.spatial.core.ecs.simulation.CollisionGroup
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val AIM_RAY_LENGTH_METERS = 30f
private const val SUPPORT_RAY_LIFT_METERS = 0.5f
private const val SUPPORT_RAY_LENGTH_METERS = 12f

/** Fallback half-extent used when the selected model's mesh cannot be inspected. */
private const val FALLBACK_HALF_SIZE_METERS = 0.15f

private val DOWN = Vector3(0f, -1f, 0f)
private val CONTROLLER_FORWARD = Vector3(0f, 0f, -1f)

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
        get() = placedEntities.size

    /** True while the ghost preview is visible, i.e. the aim currently hits room geometry. */
    val hasAimTarget: Boolean
        get() = ghost?.enabled == true

    private var navigationRoot: Entity? = null
    private var scene: Scene? = null
    private var modelFile: File? = null

    /** Distance from the model pivot to its bottom, in unscaled model units. */
    private var bottomOffset: Float = 0f

    private var collisionShape: ShapeResource? = null
    private var physicsMaterial: PhysicsMaterialResource? = null
    private var ghostMaterial: PhysicallyBasedMaterial? = null
    private var ghost: Entity? = null
    private val placedEntities = mutableListOf<Entity>()
    private var dropInFlight = false

    /** Binds the controller to a live room navigation root. */
    fun bind(navigationRoot: Entity) {
        this.navigationRoot = navigationRoot
        this.scene = navigationRoot.scene
    }

    /**
     * Called when the owning room is about to be destroyed (plan re-apply). Dropped objects and
     * the ghost die with the room's entity tree, so only resource handles are released here. The
     * model selection survives; call [reloadSelection] after [bind] to restore the ghost.
     */
    fun onRoomDestroyed() {
        placedEntities.clear()
        ghost = null
        collisionShape?.close()
        collisionShape = null
        physicsMaterial?.close()
        physicsMaterial = null
    }

    /**
     * Loads [file] for placement: builds the transparent ghost preview, the collision shape, and
     * the pivot-to-bottom offset used to compute the resting pose. Replaces any previous
     * selection; dropped objects from earlier selections stay in the room.
     */
    suspend fun selectModel(file: File, displayName: String): Boolean {
        releaseSelectionResources()
        modelFile = file
        selectedModelName = displayName
        return reloadSelection()
    }

    /** (Re)builds the ghost and collision resources for the current selection, if any. */
    suspend fun reloadSelection(): Boolean {
        val file = modelFile ?: return false
        val loaded = withContext(Dispatchers.IO) { runCatching { Entity.loadSuspend(file) } }
        val ghostEntity = loaded.getOrNull() ?: return false

        val mesh = firstMeshOf(ghostEntity)
        val shape =
            mesh?.let { runCatching { ShapeResource.createConvexMesh(it) }.getOrNull() }
                ?: ShapeResource.createBox(
                    Vector3(
                        FALLBACK_HALF_SIZE_METERS * 2f,
                        FALLBACK_HALF_SIZE_METERS * 2f,
                        FALLBACK_HALF_SIZE_METERS * 2f,
                    )
                )
        val boundsMinY = mesh?.let { runCatching { it.getBoundingBox().min.y }.getOrNull() }

        releaseSelectionResources()
        applyGhostMaterial(ghostEntity)
        ghostEntity.enabled = false
        navigationRoot?.addChild(ghostEntity)
        ghost = ghostEntity
        collisionShape = shape
        // Bottom-pivot models get offset 0; centered pivots sit half a height up.
        bottomOffset = boundsMinY?.let { -it }?.coerceAtLeast(0f) ?: 0f
        physicsMaterial =
            PhysicsMaterialResource(
                staticFriction = 0.6f,
                dynamicFriction = 0.5f,
                restitution = 0.05f,
            )
        return true
    }

    /**
     * Per-frame aim update. [originScene] and [rotationScene] are the active controller's pose in
     * scene space. The ghost is placed where a dropped object will come to rest: ray-cast from the
     * controller to find the aim anchor, then ray-cast straight down from above the anchor to find
     * the supporting surface (floor, or a previously dropped object), and lift by the pivot
     * offset.
     */
    fun updateAim(originScene: Vector3, rotationScene: Quat) {
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

        // A wall anchor still lands on the floor: support is whatever is directly below the
        // anchor point.
        val supportOrigin =
            Vector3(
                anchor.position.x,
                anchor.position.y + SUPPORT_RAY_LIFT_METERS,
                anchor.position.z,
            )
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

        val restScene = computeRestPosition(anchor.position, supportY, bottomOffset, scale)
        val navOffset =
            navRoot.components[TransformComponent::class.java]?.position ?: Vector3.ZERO
        val restLocal = navigationLocalPoint(restScene, navOffset)

        ghostEntity.components[TransformComponent::class.java]?.apply {
            setPosition(restLocal)
            setScaleVector(Vector3(scale, scale, scale))
        }
        ghostEntity.enabled = true
    }

    /** Hides the preview, e.g. when tracking is temporarily lost. */
    fun hideGhost() {
        ghost?.enabled = false
    }

    /**
     * Drops a fresh copy of the selected model at the ghost's pose as a fully dynamic rigid body.
     * The model file is loaded again off the main thread (a loaded entity tree cannot be
     * parented twice). Returns true if the object was placed.
     */
    suspend fun drop(): Boolean {
        val file = modelFile ?: return false
        val navRoot = navigationRoot ?: return false
        val ghostEntity = ghost ?: return false
        if (!ghostEntity.enabled || dropInFlight) return false
        val ghostTransform = ghostEntity.components[TransformComponent::class.java]
        val position = ghostTransform?.position ?: return false
        val scaleVector = ghostTransform?.scaleVector ?: Vector3(scale, scale, scale)
        val shape = collisionShape ?: return false
        val material = physicsMaterial ?: return false

        dropInFlight = true
        return try {
            val entity = withContext(Dispatchers.IO) { Entity.loadSuspend(file) }
            entity.components[TransformComponent::class.java]?.apply {
                setPosition(position)
                setScaleVector(scaleVector)
            }
            // Approximation: the collider wraps the first mesh of the model in root space; for
            // multi-node models it may not hug every sub-mesh. The drop is still fully simulated.
            entity.components.set(
                CollisionComponent(collisionShape = listOf(shape), physicsMaterial = material)
            )
            entity.components.set(RigidBodyComponent())
            navRoot.addChild(entity)
            entity.enabled = true
            placedEntities += entity
            true
        } catch (_: Exception) {
            false
        } finally {
            dropInFlight = false
        }
    }

    /** Removes every dropped object from the room. */
    fun clearPlaced() {
        placedEntities.forEach { it.destroy(recursively = true) }
        placedEntities.clear()
    }

    /** Releases the ghost, collision resources, placed objects, and the model selection. */
    fun dispose() {
        clearPlaced()
        releaseSelectionResources()
        modelFile = null
        selectedModelName = null
        ghostMaterial?.close()
        ghostMaterial = null
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
        collisionShape?.close()
        collisionShape = null
        physicsMaterial?.close()
        physicsMaterial = null
        bottomOffset = 0f
    }

    private fun ghostMaterial(): PhysicallyBasedMaterial =
        ghostMaterial
            ?: PhysicallyBasedMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(Color4(0.35f, 0.85f, 0.65f, 0.45f))
                setOpacity(0.45f)
                setRoughness(0.35f)
                setDepthWrite(false)
            }
                .also { ghostMaterial = it }

    private fun applyGhostMaterial(entity: Entity) {
        val preview = ghostMaterial()
        traverse(entity) { current ->
            current.components[ModelComponent::class.java]?.let { model ->
                while (model.materials.size > 0) {
                    model.materials.removeLast()
                }
                model.materials.add(preview)
            }
        }
    }

    private fun firstMeshOf(entity: Entity): MeshResource? {
        var found: MeshResource? = null
        traverse(entity) { current ->
            if (found == null) {
                found = current.components[ModelComponent::class.java]?.mesh
            }
        }
        return found
    }

    private fun traverse(entity: Entity, visit: (Entity) -> Unit) {
        visit(entity)
        entity.getChildren().forEach { traverse(it, visit) }
    }
}
