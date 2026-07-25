package com.example.testfull.content

import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.EnvironmentLightingSettingsComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.PhysicsWorldComponent
import com.pico.spatial.core.ecs.PointLightComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

internal class GeneratedRoom(
    val root: Entity,
    val navigationRoot: Entity,
    private val mesh: MeshResource,
    private val materials: List<Material>,
    private val collisionShapes: List<ShapeResource>,
    private val physicsMaterials: List<PhysicsMaterialResource>,
) {
    fun setVirtualUserPosition(x: Float, z: Float) {
        navigationRoot.components[TransformComponent::class.java]?.setPosition(
            Vector3(-x, 0f, -z)
        )
    }

    fun destroy() {
        root.destroy(recursively = true)
        collisionShapes.forEach { it.close() }
        physicsMaterials.forEach { it.close() }
        materials.forEach { it.close() }
        mesh.close()
    }
}

/** A loaded texture set for one room surface, owned by the caller's TextureCache. */
internal data class TexturedSurface(
    val baseColor: TextureResource,
    val normal: TextureResource?,
    val roughness: Float,
    val metallic: Float,
)

/** Optional per-surface textures for [generateRoom]; null keeps the default flat material. */
internal data class RoomTextures(
    val wall: TexturedSurface? = null,
    val floor: TexturedSurface? = null,
    val ceiling: TexturedSurface? = null,
    val door: TexturedSurface? = null,
    val window: TexturedSurface? = null,
)

internal fun generateRoom(planInput: FloorPlan, textures: RoomTextures = RoomTextures()): GeneratedRoom {
    val plan = planInput.normalized()
    val bounds = plan.bounds()
    val root = Entity()
    val navigationRoot =
        Entity().apply {
            components.set(TransformComponent())
        }
    val cube = MeshResource.createBox(Vector3.ONE)
    val boxCollisionShape = ShapeResource.createBox(Vector3.ONE)
    val roomPhysicsMaterial =
        PhysicsMaterialResource(
            staticFriction = 0.85f,
            dynamicFriction = 0.68f,
            restitution = 0.02f,
        )
    val wallMaterial =
        textures.wall?.let { texturedMaterial(it) }
            ?: material(Color4(0.76f, 0.79f, 0.82f, 1f), 0.82f)
    val floorMaterial =
        textures.floor?.let { texturedMaterial(it) }
            ?: material(Color4(0.34f, 0.38f, 0.42f, 1f), 0.9f)
    val ceilingMaterial =
        textures.ceiling?.let { texturedMaterial(it) }
            ?: material(Color4(0.9f, 0.91f, 0.92f, 1f), 0.92f)
    val doorMaterial =
        textures.door?.let { texturedMaterial(it) }
            ?: material(Color4(0.43f, 0.24f, 0.12f, 1f), 0.68f)
    val windowMaterial =
        textures.window?.let { texturedGlassMaterial(it) } ?: glassMaterial()
    val worldMaterial = worldShellMaterial()
    val materials =
        listOf(
            wallMaterial,
            floorMaterial,
            ceilingMaterial,
            doorMaterial,
            windowMaterial,
            worldMaterial,
        )

    try {
        root.components.set(PhysicsWorldComponent())
        root.addChild(navigationRoot)
        addRoomLighting(navigationRoot, plan, bounds)

        if (plan.walls.isNotEmpty()) {
            val ceilingHeight =
                plan.walls.maxOfOrNull { it.height }?.coerceAtLeast(2f) ?: 2.8f
            // A large inside-facing shell replaces the PICO default visual environment even if
            // the user physically walks through a designed wall or doorway.
            addBox(
                root = navigationRoot,
                mesh = cube,
                material = worldMaterial,
                collisionShape = boxCollisionShape,
                physicsMaterial = roomPhysicsMaterial,
                position = Vector3(0f, 10f, 0f),
                scale = Vector3(80f, 40f, 80f),
                yaw = 0f,
                collidable = false,
            )
            addBox(
                root = navigationRoot,
                mesh = cube,
                material = floorMaterial,
                collisionShape = boxCollisionShape,
                physicsMaterial = roomPhysicsMaterial,
                position = Vector3(0f, -0.08f, 0f),
                scale = Vector3(60f, 0.1f, 60f),
                yaw = 0f,
            )
            addBox(
                root = navigationRoot,
                mesh = cube,
                material = floorMaterial,
                collisionShape = boxCollisionShape,
                physicsMaterial = roomPhysicsMaterial,
                position = Vector3(0f, -0.03f, 0f),
                scale = Vector3(bounds.width + 0.3f, 0.06f, bounds.depth + 0.3f),
                yaw = 0f,
            )
            addBox(
                root = navigationRoot,
                mesh = cube,
                material = ceilingMaterial,
                collisionShape = boxCollisionShape,
                physicsMaterial = roomPhysicsMaterial,
                position = Vector3(0f, ceilingHeight + 0.04f, 0f),
                scale = Vector3(bounds.width + 0.3f, 0.08f, bounds.depth + 0.3f),
                yaw = 0f,
            )
        }

        plan.walls.forEach { wall ->
            val length = wall.length()
            if (length < 0.0001f) return@forEach
            val dx = (wall.end.x - wall.start.x) / length
            val dz = (wall.end.z - wall.start.z) / length
            val yaw = -Math.toDegrees(atan2(dz, dx).toDouble()).toFloat()

            plan.wallSolids(wall).forEach { solid ->
                val along = (solid.start + solid.end) / 2f
                addBox(
                    root = navigationRoot,
                    mesh = cube,
                    material = wallMaterial,
                    collisionShape = boxCollisionShape,
                    physicsMaterial = roomPhysicsMaterial,
                    position =
                        Vector3(
                            wall.start.x + dx * along - bounds.centerX,
                            (solid.bottom + solid.top) / 2f,
                            wall.start.z + dz * along - bounds.centerZ,
                        ),
                    scale =
                        Vector3(
                            solid.end - solid.start,
                            solid.top - solid.bottom,
                            wall.thickness,
                        ),
                    yaw = yaw,
                )
            }
        }

        plan.openings.forEach { opening ->
            val wall = plan.walls.firstOrNull { it.id == opening.wallId } ?: return@forEach
            val length = wall.length()
            if (length < 0.0001f) return@forEach
            val dx = (wall.end.x - wall.start.x) / length
            val dz = (wall.end.z - wall.start.z) / length
            val yaw = -Math.toDegrees(atan2(dz, dx).toDouble()).toFloat()
            val halfWidth = min(opening.width / 2f, length / 2f)
            val along =
                (opening.position * length).coerceIn(halfWidth, length - halfWidth)
            val fixtureHeight =
                min(opening.height, max(0.1f, wall.height - opening.sill))

            addBox(
                root = navigationRoot,
                mesh = cube,
                material =
                    if (opening.type == OpeningType.DOOR) {
                        doorMaterial
                    } else {
                        windowMaterial
                    },
                collisionShape = boxCollisionShape,
                physicsMaterial = roomPhysicsMaterial,
                position =
                    Vector3(
                        wall.start.x + dx * along - bounds.centerX,
                        opening.sill + fixtureHeight / 2f,
                        wall.start.z + dz * along - bounds.centerZ,
                    ),
                scale =
                    Vector3(
                        max(0.05f, min(opening.width, length) - 0.05f),
                        fixtureHeight,
                        min(opening.depth, max(0.01f, wall.thickness)),
                    ),
                yaw = yaw,
            )
        }
    } catch (error: Exception) {
        root.destroy(recursively = true)
        boxCollisionShape.close()
        roomPhysicsMaterial.close()
        materials.forEach { it.close() }
        cube.close()
        throw error
    }

    return GeneratedRoom(
        root = root,
        navigationRoot = navigationRoot,
        mesh = cube,
        materials = materials,
        collisionShapes = listOf(boxCollisionShape),
        physicsMaterials = listOf(roomPhysicsMaterial),
    )
}

private fun material(color: Color4, roughness: Float): PhysicallyBasedMaterial =
    PhysicallyBasedMaterial.create().apply {
        setBaseColor(color)
        setRoughness(roughness)
    }

private fun texturedMaterial(surface: TexturedSurface): PhysicallyBasedMaterial =
    PhysicallyBasedMaterial.create().apply {
        setBaseColorTexture(surface.baseColor)
        surface.normal?.let { setNormalTexture(it) }
        setRoughness(surface.roughness)
        setMetallic(surface.metallic)
    }

/** Glass driven by an RGBA texture: the texture's alpha decides frame-vs-glass transparency. */
private fun texturedGlassMaterial(surface: TexturedSurface): PhysicallyBasedMaterial =
    PhysicallyBasedMaterial.create(BlendingMode.TRANSPARENT).apply {
        setBaseColorTexture(surface.baseColor)
        surface.normal?.let { setNormalTexture(it) }
        setOpacity(1f)
        setRoughness(surface.roughness)
        setMetallic(surface.metallic)
        setDepthWrite(false)
    }

private fun glassMaterial(): PhysicallyBasedMaterial =
    PhysicallyBasedMaterial.create(BlendingMode.TRANSPARENT).apply {
        setBaseColor(Color4(0.58f, 0.82f, 0.96f, 0.2f))
        setOpacity(0.18f)
        setRoughness(0.08f)
        setMetallic(0.05f)
        setDepthWrite(false)
    }

private fun worldShellMaterial(): UnlitMaterial =
    UnlitMaterial.create().apply {
        setBaseColor(Color4(0.055f, 0.072f, 0.095f, 1f))
        setCullingMode(MaterialCullingMode.FRONT)
    }

private fun addRoomLighting(
    root: Entity,
    plan: FloorPlan,
    bounds: PlanBounds,
) {
    root.components.set(EnvironmentLightingSettingsComponent(scale = 1.35f))

    val ceilingHeight =
        plan.walls.maxOfOrNull { it.height }?.coerceAtLeast(2f) ?: 2.8f
    val lightRadius = max(4f, max(bounds.width, bounds.depth) * 1.2f)
    val lightOffset = min(1.8f, bounds.width * 0.22f)

    listOf(-lightOffset, lightOffset).forEach { x ->
        val light =
            Entity().apply {
                components.set(
                    TransformComponent().apply {
                        setPosition(Vector3(x, ceilingHeight - 0.25f, 0f))
                    }
                )
                components.set(
                    PointLightComponent(
                        color = Color4(1f, 0.94f, 0.84f, 1f),
                        intensity = 1800f,
                        attenuationRadius = lightRadius,
                    )
                )
            }
        root.addChild(light)
    }

    val daylight =
        Entity().apply {
            components.set(
                TransformComponent().apply {
                    setEulerAngles(EulerAngles(pitch = -42f, yaw = -28f))
                }
            )
            components.set(
                DirectionalLightComponent(
                    color = Color4(0.88f, 0.94f, 1f, 1f),
                    intensity = 850f,
                    castsShadowEnabled = true,
                )
            )
        }
    root.addChild(daylight)
}

private fun addBox(
    root: Entity,
    mesh: MeshResource,
    material: Material,
    collisionShape: ShapeResource,
    physicsMaterial: PhysicsMaterialResource,
    position: Vector3,
    scale: Vector3,
    yaw: Float,
    collidable: Boolean = true,
) {
    val entity = ModelEntity(mesh, material)
    entity.components[TransformComponent::class.java]?.apply {
        setPosition(position)
        setScaleVector(scale)
        setEulerAngles(EulerAngles(yaw = yaw))
    }
    if (collidable) {
        // No RigidBodyComponent is intentional: PICO treats colliders without one as static.
        // Dynamic furniture can therefore collide with this room without moving the structure.
        entity.components.set(
            CollisionComponent(
                collisionShape = listOf(collisionShape),
                physicsMaterial = physicsMaterial,
            )
        )
    }
    root.addChild(entity)
}
