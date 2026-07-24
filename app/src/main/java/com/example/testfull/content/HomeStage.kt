package com.example.testfull.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.AssetBundle
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class GeneratedRoomHolder {
    var room: GeneratedRoom? = null
    var revision: Int = -1
}

private class AttachedEnvironmentHolder {
    var environment: AppEnvironment? = null
}

private data class RoomNavigationPosition(
    val x: Float = 0f,
    val z: Float = 0f,
)

private const val ROOM_NAVIGATION_LIMIT_METERS = 30f

@Composable
fun HomeStage() {
    var selectedEnvironment by remember { mutableStateOf(AppEnvironment.ROOM) }
    var showcaseScene by remember { mutableStateOf<Entity?>(null) }
    var roomAvailable by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Building room…") }
    var editorExpanded by remember { mutableStateOf(true) }
    var draftPlan by remember { mutableStateOf(demoFloorPlan()) }
    var appliedPlan by remember { mutableStateOf(draftPlan) }
    var applyRevision by remember { mutableIntStateOf(0) }
    var roomNavigationPosition by remember { mutableStateOf(RoomNavigationPosition()) }
    val generatedRoom = remember { GeneratedRoomHolder() }
    val attachedEnvironment = remember { AttachedEnvironmentHolder() }

    SpatialView(
        initial = { content, attachments ->
            val failures = mutableListOf<String>()

            try {
                val room = generateRoom(appliedPlan)
                room.setVirtualUserPosition(
                    roomNavigationPosition.x,
                    roomNavigationPosition.z,
                )
                room.root.enabled = true
                content.addEntity(room.root)
                generatedRoom.room = room
                generatedRoom.revision = applyRevision
                attachedEnvironment.environment = AppEnvironment.ROOM
                roomAvailable = true
            } catch (_: Exception) {
                failures += "generated room"
            }

            try {
                val bundle =
                    withContext(Dispatchers.IO) {
                        AssetBundle.load("asset://editor-asset.bundle")
                    }
                try {
                    showcaseScene =
                        runCatching {
                                Entity.loadSuspend(modelName = "MyScene", bundle = bundle)
                            }
                            .onFailure { failures += "showcase" }
                            .getOrNull()
                            ?.apply {
                                components[TransformComponent::class.java]?.setPosition(
                                    Vector3(0f, 1.5f, -1f)
                                )
                                enabled = true
                            }
                } finally {
                    bundle.close()
                }
            } catch (_: Exception) {
                failures += "asset bundle"
            }

            if (!roomAvailable && showcaseScene != null) {
                selectedEnvironment = AppEnvironment.SHOWCASE
                showcaseScene?.let { content.addEntity(it) }
                attachedEnvironment.environment = AppEnvironment.SHOWCASE
            }
            status =
                when {
                    roomAvailable && failures.isEmpty() ->
                        roomStatus(appliedPlan)
                    roomAvailable ->
                        "${roomStatus(appliedPlan)} Missing ${failures.distinct().joinToString()}."
                    else -> "The room could not be generated."
                }

            attachments.entity(id = "floor-plan-designer")?.apply {
                components[TransformComponent::class.java]?.setPosition(
                    Vector3(0f, 1.35f, -0.85f)
                )
                content.addEntity(this)
            }
        },
        update = { content, _ ->
            generatedRoom.room?.setVirtualUserPosition(
                roomNavigationPosition.x,
                roomNavigationPosition.z,
            )

            if (generatedRoom.revision != applyRevision) {
                generatedRoom.revision = applyRevision
                runCatching { generateRoom(appliedPlan) }
                    .onSuccess { nextRoom ->
                        nextRoom.setVirtualUserPosition(
                            roomNavigationPosition.x,
                            roomNavigationPosition.z,
                        )
                        // Detach whichever scene is currently active before replacing the room.
                        // Previously, applying while Showcase was active added the new room and
                        // overwrote this tracker, leaving the Showcase root visible underneath.
                        when (attachedEnvironment.environment) {
                            AppEnvironment.SHOWCASE ->
                                showcaseScene?.let {
                                    it.enabled = false
                                    content.removeEntity(it)
                                }
                            AppEnvironment.ROOM ->
                                generatedRoom.room?.let {
                                    it.root.enabled = false
                                    content.removeEntity(it.root)
                                }
                            null -> Unit
                        }
                        attachedEnvironment.environment = null

                        generatedRoom.room?.let { previousRoom ->
                            previousRoom.destroy()
                        }
                        generatedRoom.room = nextRoom
                        if (selectedEnvironment == AppEnvironment.ROOM) {
                            nextRoom.root.enabled = true
                            content.addEntity(nextRoom.root)
                            attachedEnvironment.environment = AppEnvironment.ROOM
                        }
                        roomAvailable = true
                        status = roomStatus(appliedPlan)
                    }
                    .onFailure {
                        roomAvailable = generatedRoom.room != null
                        status = "Could not rebuild the room. The previous room is still active."
                    }
            }

            if (attachedEnvironment.environment != selectedEnvironment) {
                when (attachedEnvironment.environment) {
                    AppEnvironment.SHOWCASE ->
                        showcaseScene?.let {
                            it.enabled = false
                            content.removeEntity(it)
                        }
                    AppEnvironment.ROOM ->
                        generatedRoom.room?.let {
                            it.root.enabled = false
                            content.removeEntity(it.root)
                        }
                    null -> Unit
                }
                attachedEnvironment.environment = null

                when (selectedEnvironment) {
                    AppEnvironment.SHOWCASE ->
                        showcaseScene?.let {
                            it.enabled = true
                            content.addEntity(it)
                            attachedEnvironment.environment = AppEnvironment.SHOWCASE
                        }
                    AppEnvironment.ROOM ->
                        generatedRoom.room?.let {
                            it.root.enabled = true
                            content.addEntity(it.root)
                            attachedEnvironment.environment = AppEnvironment.ROOM
                        }
                }
            }
        },
        attachments = {
            AttachmentPanel(id = "floor-plan-designer") {
                FloorPlanExperiencePanel(
                    plan = draftPlan,
                    appliedPlan = appliedPlan,
                    selectedEnvironment = selectedEnvironment,
                    showcaseAvailable = showcaseScene != null,
                    roomAvailable = roomAvailable,
                    expanded = editorExpanded,
                    status = status,
                    roomPositionX = roomNavigationPosition.x,
                    roomPositionZ = roomNavigationPosition.z,
                    onPlanChange = { draftPlan = it },
                    onApplyPlan = {
                        roomNavigationPosition = RoomNavigationPosition()
                        appliedPlan = draftPlan.normalized()
                        applyRevision += 1
                    },
                    onEnvironmentSelected = { selectedEnvironment = it },
                    onMoveInRoom = { deltaX, deltaZ ->
                        roomNavigationPosition =
                            RoomNavigationPosition(
                                x =
                                    (roomNavigationPosition.x + deltaX)
                                        .coerceIn(
                                            -ROOM_NAVIGATION_LIMIT_METERS,
                                            ROOM_NAVIGATION_LIMIT_METERS,
                                        ),
                                z =
                                    (roomNavigationPosition.z + deltaZ)
                                        .coerceIn(
                                            -ROOM_NAVIGATION_LIMIT_METERS,
                                            ROOM_NAVIGATION_LIMIT_METERS,
                                        ),
                            )
                        selectedEnvironment = AppEnvironment.ROOM
                    },
                    onResetRoomPosition = {
                        roomNavigationPosition = RoomNavigationPosition()
                        selectedEnvironment = AppEnvironment.ROOM
                    },
                    onExpandedChange = { editorExpanded = it },
                )
            }
        },
    )
}

private fun roomStatus(plan: FloorPlan): String =
    "Room ready: ${plan.walls.size} walls, ${plan.openings.size} openings."
