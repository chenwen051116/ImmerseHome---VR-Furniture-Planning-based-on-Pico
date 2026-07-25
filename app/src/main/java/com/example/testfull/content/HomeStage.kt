package com.example.testfull.content

import android.util.Log
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
import com.example.testfull.BuildConfig
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.AssetBundle
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import com.pico.spatial.ui.foundation.content.SpatialView
import java.io.File
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

/** Smoothed pose of the view-following placement HUD. */
private class HudFollowState {
    var position: Vector3? = null
    var yaw: Float = 0f
    var lastUpdateAt: Long = 0L
}

/** Shortest-arc interpolation between two yaw angles, in degrees. */
private fun lerpAngleDegrees(from: Float, to: Float, t: Float): Float {
    var delta = (to - from) % 360f
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    return from + delta * t
}

private fun angleDeltaDegrees(a: Float, b: Float): Float {
    var delta = (b - a) % 360f
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    return kotlin.math.abs(delta)
}

// The HUD redraws its whole surface on every transform change — on the emulator a
// per-frame move is enough to trip the ANR watchdog. Rate-limit updates and ignore
// sub-centimeter jitter; snap instantly when the user turned far away instead.
private const val HUD_UPDATE_INTERVAL_MS = 150L
private const val HUD_SNAP_DISTANCE_METERS = 0.6f
private const val HUD_MIN_MOVE_METERS = 0.02f
private const val HUD_MIN_YAW_DEGREES = 2f

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
private const val TAG = "HomeStage"

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

/** The plan's doors/windows translated into navigation-root-local coordinates, for the AI. */
private fun localOpenings(plan: FloorPlan): List<OpeningDesc> {
    val normalized = plan.normalized()
    if (normalized.walls.isEmpty()) return emptyList()
    val bounds = normalized.bounds()
    return normalized.openings.mapNotNull { opening ->
        val wall =
            normalized.walls.firstOrNull { it.id == opening.wallId } ?: return@mapNotNull null
        val center = opening.worldPosition(wall)
        OpeningDesc(
            type = opening.type,
            wallId = opening.wallId,
            x = center.x - bounds.centerX,
            z = center.z - bounds.centerZ,
            width = opening.width,
        )
    }
}

private fun defaultRoughness(slot: SurfaceSlot): Float =
    when (slot) {
        SurfaceSlot.WALL -> 0.82f
        SurfaceSlot.FLOOR -> 0.9f
        SurfaceSlot.CEILING -> 0.92f
        SurfaceSlot.DOOR -> 0.68f
        SurfaceSlot.WINDOW -> 0.1f
    }

/**
 * Resolves the per-slot texture selections into loaded [TexturedSurface]s for [generateRoom].
 * Loads happen through [cache] (shared across rebuilds); slots with no selection, an unknown
 * name, or a failed load stay at their default flat material.
 */
private suspend fun resolveRoomTextures(
    selection: Map<SurfaceSlot, String?>,
    catalog: List<TextureSpec>,
    cache: TextureCache,
): RoomTextures {
    suspend fun slot(slot: SurfaceSlot): TexturedSurface? {
        val name = selection[slot] ?: return null
        val spec = catalog.firstOrNull { it.displayName == name } ?: return null
        val base = cache.load(spec.file) ?: return null
        val normal = spec.normalMap?.let { cache.load(it) }
        return TexturedSurface(
            baseColor = base,
            normal = normal,
            roughness = spec.roughness ?: defaultRoughness(slot),
            metallic = spec.metallic ?: 0f,
        )
    }
    return RoomTextures(
        wall = slot(SurfaceSlot.WALL),
        floor = slot(SurfaceSlot.FLOOR),
        ceiling = slot(SurfaceSlot.CEILING),
        door = slot(SurfaceSlot.DOOR),
        window = slot(SurfaceSlot.WINDOW),
    )
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
    val hudFollow = remember { HudFollowState() }
    val controllerProvider = remember { ControllerTrackingProvider() }
    val hmdProvider = remember { HMDTrackingProvider() }
    var availableModels by remember { mutableStateOf<List<LibraryModel>>(emptyList()) }
    var selectedModelName by remember { mutableStateOf<String?>(null) }
    var placementActive by remember { mutableStateOf(false) }
    var modelScale by remember { mutableStateOf(1f) }
    var placedCount by remember { mutableIntStateOf(0) }
    var aimStatus by remember { mutableStateOf("Aim: idle") }

    // --- AI Arrange state ---
    var modelCatalog by remember { mutableStateOf<List<CatalogModel>>(emptyList()) }
    var aiPrompt by remember { mutableStateOf("") }
    var aiBusy by remember { mutableStateOf(false) }
    var aiStatus by remember { mutableStateOf("") }

    // --- Surface texture state ---
    var availableTextures by remember { mutableStateOf<List<TextureSpec>>(emptyList()) }
    var selectedTextures by remember { mutableStateOf<Map<SurfaceSlot, String?>>(emptyMap()) }
    var roomTextures by remember { mutableStateOf(RoomTextures()) }
    val textureCache = remember { TextureCache() }

    // Single entry point for AI arranging, used by the panel button and the debug hook.
    val runAiArrange: () -> Unit = runAiArrange@{
        Log.w(
            TAG,
            "runAiArrange: busy=$aiBusy promptLen=${aiPrompt.length} " +
                "models=${availableModels.size} catalog=${modelCatalog.size}",
        )
        if (aiBusy) return@runAiArrange
        scope.launch {
            aiBusy = true
            aiStatus = "Asking AI…"
            try {
                // Scan/measure on demand so the button works even when the user never pressed
                // "Scan models folder".
                if (availableModels.isEmpty()) {
                    availableModels = scanModels(context)
                }
                if (availableTextures.isEmpty()) {
                    availableTextures = scanTextures(context)
                }
                if (modelCatalog.isEmpty()) {
                    aiStatus = "Measuring models…"
                    modelCatalog = buildCatalog(availableModels)
                }
                if (modelCatalog.isEmpty()) {
                    aiStatus =
                        "No usable models — push .glb files to the app's " +
                            "files/models folder first."
                    return@launch
                }
                val (footprintWalls, footprintMargin) = localFootprint(appliedPlan)
                val openings = localOpenings(appliedPlan)
                val layout =
                    requestAiLayout(
                        userPrompt = aiPrompt,
                        walls = footprintWalls,
                        openings = openings,
                        catalog = modelCatalog,
                        currentPlacements = placementController.placedSummaries(),
                        textureCatalog = availableTextures,
                    )
                val resolved =
                    resolveAiPlacements(
                        layout = layout,
                        catalog = modelCatalog,
                        walls = footprintWalls,
                        baseMargin = footprintMargin,
                    )
                // Surface reskins requested by the AI: rebuild the room FIRST (a rebuild
                // destroys placed furniture — which we're about to replace anyway) and wait
                // for it, so the spawns below land in the new room.
                var textureNote = ""
                if (layout.textures.isNotEmpty()) {
                    val resolvedTextures = resolveAiTextures(layout.textures, availableTextures)
                    if (resolvedTextures.skipped.isNotEmpty()) {
                        textureNote =
                            " Textures skipped: " +
                                resolvedTextures.skipped.joinToString(", ") + "."
                    }
                    if (resolvedTextures.resolved.isNotEmpty()) {
                        aiStatus = "Applying textures…"
                        selectedTextures =
                            selectedTextures +
                                resolvedTextures.resolved.mapValues { it.value.displayName }
                        roomTextures =
                            resolveRoomTextures(selectedTextures, availableTextures, textureCache)
                        val oldRoom = generatedRoom.room
                        applyRevision += 1
                        var waited = 0
                        while (generatedRoom.room === oldRoom && waited < 200) {
                            delay(100)
                            waited++
                        }
                    }
                }
                // Apply = replace: the AI's layout is the full desired state, which also
                // covers "move the existing furniture". Free the ghost too: a large one
                // plus the spawn sequence trips the kernel low-memory killer. It is
                // rebuilt after the run (see finally).
                placementController.releaseSelectionGhost()
                placementController.clearPlaced()
                var spawned = 0
                resolved.accepted.forEach { placement ->
                    val placed =
                        placementController.placeFromAi(
                            model = placement.model,
                            x = placement.x,
                            z = placement.z,
                            yawDegrees = placement.yawDegrees,
                            scale = placement.scale,
                        )
                    if (placed) {
                        spawned += 1
                        aiStatus = "Placing… $spawned/${resolved.accepted.size}"
                    }
                    // Let frames through between physics spawns (ANR guard for the emulator).
                    delay(200)
                }
                placedCount = placementController.placedCount
                aiStatus =
                    buildString {
                        append("AI placed $spawned of ${layout.placements.size}.")
                        append(textureNote)
                        if (resolved.adjusted.isNotEmpty()) {
                            append(" Adjusted: ")
                            append(resolved.adjusted.joinToString(", "))
                            append(".")
                        }
                        if (resolved.skipped.isNotEmpty()) {
                            append(" Skipped: ")
                            append(resolved.skipped.joinToString(", "))
                            append(".")
                        }
                        layout.notes?.let { append(" $it") }
                    }
            } catch (error: AiArrangeException) {
                aiStatus = "AI error: ${error.message}"
            } catch (error: Exception) {
                aiStatus = "AI arrange failed: ${error.message}"
            } finally {
                aiBusy = false
                // The ghost was released to keep memory low during the spawn sequence;
                // rebuild it now so manual placing continues where it left off.
                if (selectedModelName != null) {
                    placementController.reloadSelection()
                }
            }
        }
    }

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
                    (if (placementController.isGhostBlocked) " · BLOCKED (no free space)" else "") +
                    " · trigger hits: ${aimState.triggerEventCount}"
            if (nextStatus != aimStatus) {
                aimStatus = nextStatus
            }
            delay(AIM_UPDATE_INTERVAL_MS)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            placementController.dispose()
            textureCache.close()
        }
    }

    // Debug-only test hook: an ai_test_prompt.txt pushed into the app's files dir runs the AI
    // arrange flow with that prompt once, then deletes the file. Lets the full pipeline be
    // driven end-to-end from adb without UI input.
    if (BuildConfig.DEBUG) {
        LaunchedEffect(Unit) {
            val triggerFile = File(context.getExternalFilesDir(null), "ai_test_prompt.txt")
            while (true) {
                if (triggerFile.exists() && !aiBusy) {
                    val prompt = runCatching { triggerFile.readText().trim() }.getOrDefault("")
                    runCatching { triggerFile.delete() }
                    Log.w(TAG, "debug hook: prompt file -> \"$prompt\"")
                    when {
                        prompt.isEmpty() -> Unit
                        // "place:<name>" simulates picking a model: selects it and turns
                        // placing on (drives the view-following placement HUD without a
                        // controller).
                        prompt.startsWith("place:") -> {
                            val query = prompt.removePrefix("place:")
                            val model =
                                scanModels(context).firstOrNull {
                                    it.displayName.contains(query, ignoreCase = true)
                                } ?: scanModels(context).firstOrNull()
                            if (model == null) {
                                Log.w(TAG, "debug hook: no model for '$query'")
                            } else {
                                availableModels = scanModels(context)
                                scope.launch {
                                    if (
                                        placementController.selectModel(
                                            model.file,
                                            model.displayName,
                                        )
                                    ) {
                                        selectedModelName = model.displayName
                                        placementActive = true
                                        Log.w(TAG, "debug hook: placing ${model.displayName}")
                                    }
                                }
                            }
                        }
                        else -> {
                            aiPrompt = prompt
                            runAiArrange()
                        }
                    }
                }
                delay(1000)
            }
        }
    }

    SpatialView(
        initial = { content, attachments ->
            val failures = mutableListOf<String>()

            try {
                val room = generateRoom(appliedPlan, roomTextures)
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

            listOf("room-plan", "furniture-library", "ai-arrange", "placement-hud").forEach {
                Log.w(TAG, "attachment entity($it) = ${attachments.entity(id = it) != null}")
            }
            attachments.entity(id = "room-plan")?.apply {
                components[TransformComponent::class.java]?.setPosition(
                    Vector3(0f, 1.4f, -1.3f)
                )
                content.addEntity(this)
            }
            attachments.entity(id = "furniture-library")?.apply {
                components[TransformComponent::class.java]?.apply {
                    setPosition(Vector3(-0.62f, 1.3f, -1.05f))
                    setEulerAngles(EulerAngles(yaw = 30f))
                }
                content.addEntity(this)
            }
            attachments.entity(id = "ai-arrange")?.apply {
                components[TransformComponent::class.java]?.apply {
                    setPosition(Vector3(0.62f, 1.3f, -1.05f))
                    setEulerAngles(EulerAngles(yaw = -30f))
                }
                content.addEntity(this)
            }
            // The placement HUD starts hidden; the update loop shows it and glues it to the
            // user's view while placing.
            attachments.entity(id = "placement-hud")?.apply {
                enabled = false
                components[TransformComponent::class.java]?.setPosition(
                    Vector3(0f, 1.2f, -0.7f)
                )
                content.addEntity(this)
            }
        },
        update = { content, attachments ->
            // Placement HUD: visible only while placing, floating ahead of the headset so the
            // Drop button is always within reach. Updates are rate-limited with a jitter dead
            // zone — re-posing it every frame keeps its surface redrawing, which trips the
            // ANR watchdog on the emulator.
            attachments.entity(id = "placement-hud")?.let { hud ->
                val show = placementActive && selectedModelName != null
                if (hud.enabled != show) hud.enabled = show
                if (show) {
                    val hmdPos = aimState.hmdPosition
                    val hmdRot = aimState.hmdRotation
                    if (hmdPos != null && hmdRot != null) {
                        val now = System.currentTimeMillis()
                        if (now - hudFollow.lastUpdateAt >= HUD_UPDATE_INTERVAL_MS) {
                            val forward = hmdRot.rotateVector(Vector3(0f, 0f, -1f))
                            val target =
                                Vector3(
                                    hmdPos.x + forward.x * 1.1f,
                                    hmdPos.y - 0.2f,
                                    hmdPos.z + forward.z * 1.1f,
                                )
                            val targetYaw = yawFacingUserDegrees(target, hmdPos)
                            val current = hudFollow.position
                            val distance =
                                if (current == null) {
                                    Float.MAX_VALUE
                                } else {
                                    (target - current).length()
                                }
                            val next =
                                if (current == null || distance > HUD_SNAP_DISTANCE_METERS) {
                                    target
                                } else {
                                    current + (target - current) * 0.35f
                                }
                            val nextYaw = lerpAngleDegrees(hudFollow.yaw, targetYaw, 0.35f)
                            if (
                                distance > HUD_MIN_MOVE_METERS ||
                                    angleDeltaDegrees(hudFollow.yaw, targetYaw) >
                                        HUD_MIN_YAW_DEGREES
                            ) {
                                hudFollow.lastUpdateAt = now
                                hudFollow.position = next
                                hudFollow.yaw = nextYaw
                                hud.components[TransformComponent::class.java]?.apply {
                                    setPosition(next)
                                    setEulerAngles(EulerAngles(yaw = nextYaw))
                                }
                            }
                        }
                    }
                }
            }

            generatedRoom.room?.setVirtualUserPosition(
                roomNavigationPosition.x,
                roomNavigationPosition.z,
            )

            if (generatedRoom.revision != applyRevision) {
                generatedRoom.revision = applyRevision
                runCatching { generateRoom(appliedPlan, roomTextures) }
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
                        // While an AI arrange is spawning into the new room, don't also
                        // reload the ghost — concurrent heavy loads killed the process.
                        if (selectedModelName != null && !aiBusy) {
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
            AttachmentPanel(id = "room-plan") {
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
            AttachmentPanel(id = "furniture-library") {
                FurnitureLibraryPanel(
                    availableModels = availableModels,
                    selectedModelName = selectedModelName,
                    modelScale = modelScale,
                    placedCount = placedCount,
                    roomAvailable = roomAvailable,
                    onScanModels = {
                        availableModels = scanModels(context)
                        availableTextures = scanTextures(context)
                        scope.launch {
                            modelCatalog = buildCatalog(availableModels)
                            aiStatus =
                                "Models: ${availableModels.size} found, " +
                                    "${modelCatalog.size} AI-ready."
                        }
                    },
                    onModelSelected = { model ->
                        scope.launch {
                            if (placementController.selectModel(model.file, model.displayName)) {
                                selectedModelName = model.displayName
                                // Start the slider at the model's intended real-world scale
                                // (sidecar geometry vs measured bounds); 1 when unknown.
                                val defaultScale =
                                    modelCatalog
                                        .firstOrNull { it.displayName == model.displayName }
                                        ?.defaultScale ?: 1f
                                modelScale = defaultScale
                                placementController.scale = defaultScale
                                placementActive = true
                            } else {
                                selectedModelName = null
                                status = "Could not load model ${model.displayName}."
                            }
                        }
                    },
                    onModelScaleChange = { newScale ->
                        modelScale = newScale
                        placementController.scale = newScale
                    },
                    onClearPlaced = {
                        placementController.clearPlaced()
                        placedCount = 0
                    },
                )
            }
            AttachmentPanel(id = "placement-hud") {
                PlacementHudPanel(
                    selectedModelName = selectedModelName,
                    placementActive = placementActive,
                    placedCount = placedCount,
                    aimStatus = aimStatus,
                    roomAvailable = roomAvailable,
                    onPlacementActiveChange = { active ->
                        placementActive = active
                        if (!active) placementController.hideGhost()
                    },
                    onDropNow = {
                        scope.launch {
                            if (placementController.drop()) {
                                placedCount = placementController.placedCount
                            }
                        }
                    },
                    onClearPlaced = {
                        placementController.clearPlaced()
                        placedCount = 0
                    },
                )
            }
            AttachmentPanel(id = "ai-arrange") {
                AiArrangePanel(
                    aiPrompt = aiPrompt,
                    aiBusy = aiBusy,
                    aiStatus = aiStatus,
                    roomAvailable = roomAvailable,
                    onAiPromptChange = { aiPrompt = it },
                    onArrangeWithAi = runAiArrange,
                    availableTextures = availableTextures,
                    selectedTextures = selectedTextures,
                    onTextureSlotChange = { slot, name ->
                        selectedTextures =
                            if (name == null) {
                                selectedTextures - slot
                            } else {
                                selectedTextures + (slot to name)
                            }
                    },
                    onApplyTextures = {
                        scope.launch {
                            aiStatus = "Applying textures…"
                            roomTextures =
                                resolveRoomTextures(
                                    selectedTextures,
                                    availableTextures,
                                    textureCache,
                                )
                            applyRevision += 1
                        }
                    },
                )
            }
        },
    )
}

private fun roomStatus(plan: FloorPlan): String =
    "Room ready: ${plan.walls.size} walls, ${plan.openings.size} openings."
