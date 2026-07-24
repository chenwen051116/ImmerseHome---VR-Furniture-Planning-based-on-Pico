package com.example.testfull.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.AssetBundle
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import com.pico.spatial.ui.foundation.content.SpatialView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

/** Thread-safe slot for the latest tracking data; providers post off the main thread. */
private class AimState {
    @Volatile var controllerPosition: Vector3? = null
    @Volatile var controllerRotation: Quat? = null
    @Volatile var controllerUpdatedAt: Long = 0L
    @Volatile var hmdPosition: Vector3? = null
    @Volatile var hmdRotation: Quat? = null
    @Volatile var hmdUpdatedAt: Long = 0L
    @Volatile var dropRequested: Boolean = false
    @Volatile var lastTriggerPressed: Boolean = false
    @Volatile var triggerEventCount: Int = 0
}

private const val ROOM_NAVIGATION_LIMIT_METERS = 30f
private const val AIM_UPDATE_INTERVAL_MS = 33L
private const val AIM_SOURCE_STALE_MS = 600L

/** Extra clearance (beyond half the wall thickness) kept between placed objects and walls. */
private const val FOOTPRINT_CLEARANCE_METERS = 0.05f

/**
 * The room's wall segments translated into navigation-root-local coordinates (the generated room
 * recenters the plan around its bounds center), plus the clearance margin for placement.
 */
private fun localFootprint(plan: FloorPlan): Pair<List<PlanWall>, Float> {
    val normalized = plan.normalized()
    if (normalized.walls.isEmpty()) return emptyList<PlanWall>() to 0f
    val bounds = normalized.bounds()
    val walls =
        normalized.walls.map { wall ->
            wall.copy(
                start =
                    PlanPoint(
                        wall.start.x - bounds.centerX,
                        wall.start.z - bounds.centerZ,
                    ),
                end =
                    PlanPoint(
                        wall.end.x - bounds.centerX,
                        wall.end.z - bounds.centerZ,
                    ),
            )
        }
    val margin = normalized.walls.maxOf { it.thickness } / 2f + FOOTPRINT_CLEARANCE_METERS
    return walls to margin
}

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

    // --- Model placement state ---
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val placementController = remember { PlacementController() }
    val aimState = remember { AimState() }
    val controllerProvider = remember { ControllerTrackingProvider() }
    val hmdProvider = remember { HMDTrackingProvider() }
    var availableModels by remember { mutableStateOf<List<LibraryModel>>(emptyList()) }
    var selectedModelName by remember { mutableStateOf<String?>(null) }
    var placementActive by remember { mutableStateOf(false) }
    var modelScale by remember { mutableStateOf(1f) }
    var placedCount by remember { mutableIntStateOf(0) }
    var aimStatus by remember { mutableStateOf("Aim: idle") }

    // Tracking listeners post into aimState; the main-thread loop below consumes them.
    remember {
        controllerProvider.addListener { data ->
            val pose = data.right ?: data.left
            if (pose != null) {
                aimState.controllerPosition = pose.position
                aimState.controllerRotation = pose.rotation
                aimState.controllerUpdatedAt = System.currentTimeMillis()
            }
        }
        controllerProvider.addControllerActionListener { action ->
            val trigger = action.right.triggerPressed || action.left.triggerPressed
            if (trigger && !aimState.lastTriggerPressed) {
                aimState.dropRequested = true
                aimState.triggerEventCount += 1
            }
            aimState.lastTriggerPressed = trigger
        }
        hmdProvider.addListener { data ->
            aimState.hmdPosition = data.hmdPose.position
            aimState.hmdRotation = data.hmdPose.rotation
            aimState.hmdUpdatedAt = System.currentTimeMillis()
        }
        true
    }

    DisposableEffect(placementActive) {
        if (placementActive) {
            controllerProvider.start()
            hmdProvider.start()
        }
        onDispose {
            controllerProvider.stop()
            hmdProvider.stop()
            placementController.hideGhost()
        }
    }

    // Main-thread aim/drop loop while placement mode is on. Uses whichever tracking source
    // reported most recently: controllers in headset mode, head gaze otherwise.
    LaunchedEffect(placementActive) {
        if (!placementActive) return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            val useController =
                aimState.controllerPosition != null &&
                    aimState.controllerRotation != null &&
                    now - aimState.controllerUpdatedAt <= AIM_SOURCE_STALE_MS
            val useHmd =
                !useController &&
                    aimState.hmdPosition != null &&
                    aimState.hmdRotation != null &&
                    now - aimState.hmdUpdatedAt <= AIM_SOURCE_STALE_MS

            val position: Vector3?
            val rotation: Quat?
            val source: String
            when {
                useController -> {
                    position = aimState.controllerPosition
                    rotation = aimState.controllerRotation
                    source = "controller"
                }
                useHmd -> {
                    position = aimState.hmdPosition
                    rotation = aimState.hmdRotation
                    source = "headset gaze"
                }
                else -> {
                    position = null
                    rotation = null
                    source = "none"
                }
            }

            if (position != null && rotation != null) {
                val userPosition = aimState.hmdPosition ?: position
                placementController.updateAim(position, rotation, userPosition)
            } else {
                placementController.hideGhost()
            }
            if (aimState.dropRequested) {
                aimState.dropRequested = false
                if (placementController.drop()) {
                    placedCount = placementController.placedCount
                }
            }
            val nextStatus =
                "Aim: $source · target: " +
                    (if (placementController.hasAimTarget) "yes" else "no") +
                    (if (placementController.isGhostBlocked) " · BLOCKED (overlaps furniture)" else "") +
                    " · trigger hits: ${aimState.triggerEventCount}"
            if (nextStatus != aimStatus) {
                aimStatus = nextStatus
            }
            delay(AIM_UPDATE_INTERVAL_MS)
        }
    }

    DisposableEffect(Unit) {
        onDispose { placementController.dispose() }
    }

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
                placementController.bind(room.navigationRoot)
                val (footprintWalls, footprintMargin) = localFootprint(appliedPlan)
                placementController.setFootprint(footprintWalls, footprintMargin)
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
                            // The ghost and dropped objects die with the old room's entity tree.
                            placementController.onRoomDestroyed()
                            placedCount = 0
                            previousRoom.destroy()
                        }
                        generatedRoom.room = nextRoom
                        if (selectedEnvironment == AppEnvironment.ROOM) {
                            nextRoom.root.enabled = true
                            content.addEntity(nextRoom.root)
                            attachedEnvironment.environment = AppEnvironment.ROOM
                        }
                        placementController.bind(nextRoom.navigationRoot)
                        val (footprintWalls, footprintMargin) = localFootprint(appliedPlan)
                        placementController.setFootprint(footprintWalls, footprintMargin)
                        if (selectedModelName != null) {
                            scope.launch { placementController.reloadSelection() }
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
                    availableModels = availableModels,
                    selectedModelName = selectedModelName,
                    placementActive = placementActive,
                    modelScale = modelScale,
                    placedCount = placedCount,
                    onScanModels = { availableModels = scanModels(context) },
                    onModelSelected = { model ->
                        scope.launch {
                            if (placementController.selectModel(model.file, model.displayName)) {
                                selectedModelName = model.displayName
                                placementController.scale = modelScale
                                placementActive = true
                            } else {
                                selectedModelName = null
                                status = "Could not load model ${model.displayName}."
                            }
                        }
                    },
                    onPlacementActiveChange = { active ->
                        placementActive = active
                        if (!active) placementController.hideGhost()
                    },
                    onModelScaleChange = { newScale ->
                        modelScale = newScale
                        placementController.scale = newScale
                    },
                    onClearPlaced = {
                        placementController.clearPlaced()
                        placedCount = 0
                    },
                    aimStatus = aimStatus,
                    onDropNow = {
                        scope.launch {
                            if (placementController.drop()) {
                                placedCount = placementController.placedCount
                            }
                        }
                    },
                )
            }
        },
    )
}

private fun roomStatus(plan: FloorPlan): String =
    "Room ready: ${plan.walls.size} walls, ${plan.openings.size} openings."
