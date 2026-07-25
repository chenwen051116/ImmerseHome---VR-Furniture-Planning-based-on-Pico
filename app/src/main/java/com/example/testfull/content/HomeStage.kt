package com.example.testfull.content

import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.testfull.BuildConfig
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.AssetBundle
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import com.pico.spatial.ui.design.Text
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

/** Smoothed pose of the view-following UI rig (main panels + launcher). */
private class UiRigFollowState {
    var eye: Vector3? = null
    var forward: Vector3? = null
    var lastUpdateAt: Long = 0L
    var lastUiOpen: Boolean = true
    var loggedHmdPose: Boolean = false
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
// The main panel rig updates faster than the placement HUD so rotation tracking
// feels tight — at 150ms the panel visibly lags behind head turns (up to ~9° at
// 60°/s), which reads as an elliptical swing. 50ms (20 Hz) is smooth enough for
// yaw following without tripping the emulator ANR watchdog.
private const val UI_RIG_UPDATE_INTERVAL_MS = 50L
private const val HUD_SNAP_DISTANCE_METERS = 0.6f
private const val HUD_MIN_MOVE_METERS = 0.02f
private const val HUD_MIN_YAW_DEGREES = 2f

// The whole UI rig glues itself to the user's view (yaw only, like an AR panel). Every moved
// panel redraws its surface, so the rig gets a slightly larger dead zone than the single
// placement HUD, and snaps instantly on big turns instead of lagging behind.
private const val UI_RIG_MIN_MOVE_METERS = 0.03f
private const val UI_RIG_MIN_YAW_DEGREES = 3f
private const val UI_RIG_SNAP_YAW_DEGREES = 40f

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

private const val AIM_UPDATE_INTERVAL_MS = 33L
private const val AIM_SOURCE_STALE_MS = 600L
private const val TAG = "HomeStage"

/**
 * Cap on ITERATE-mode self-improvement turns after the seed layout. Each turn asks the AI to
 * evaluate the just-placed furniture and either confirm (satisfied=true) or return a full
 * revised layout. Three gives the AI room to course-correct without runaway cost.
 */
private const val ITERATE_MAX_ITERATIONS = 3

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

private enum class MainPanel { RoomPlan, FurnitureLibrary, AiArrange }

@Composable
fun HomeStage() {
    var selectedEnvironment by remember { mutableStateOf(AppEnvironment.ROOM) }
    var showcaseScene by remember { mutableStateOf<Entity?>(null) }
    var roomAvailable by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Building room…") }
    var editorExpanded by remember { mutableStateOf(false) }
    // The launcher icon folds every main panel away (or opens them all) with one tap.
    var uiOpen by remember { mutableStateOf(true) }
    // Single active panel in the main switcher (room plan / furniture / AI arrange).
    var activePanel by remember { mutableStateOf(MainPanel.RoomPlan) }
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
    val uiRig = remember { UiRigFollowState() }
    // Drives the view-following rig: SpatialView's update lambda only re-runs when a state it
    // reads changes, so this ticker keeps the rig tracking the head between clicks.
    var rigTick by remember { mutableIntStateOf(0) }
    val controllerProvider = remember { ControllerTrackingProvider() }
    val hmdProvider = remember { HMDTrackingProvider() }
    var availableModels by remember { mutableStateOf<List<LibraryModel>>(emptyList()) }
    var selectedModelName by remember { mutableStateOf<String?>(null) }
    var placementActive by remember { mutableStateOf(false) }
    var modelScale by remember { mutableStateOf(1f) }
    var modelRotation by remember { mutableStateOf(0f) }
    var placedCount by remember { mutableIntStateOf(0) }
    var aimStatus by remember { mutableStateOf("Aim: idle") }
    // AI Arrange option toggles. Advanced Thinking prepends the AdventureX fengshui
    // config JSON as a pre-prompt; Plan Mode makes the AI return analysis/suggestions
    // only without placing any furniture; Iterate makes the AI self-evaluate and
    // re-arrange up to ITERATE_MAX_ITERATIONS times until it declares satisfaction.
    var advancedThinking by remember { mutableStateOf(false) }
    var planMode by remember { mutableStateOf(false) }
    var iterateMode by remember { mutableStateOf(false) }

    // --- AI Arrange state ---
    var modelCatalog by remember { mutableStateOf<List<CatalogModel>>(emptyList()) }
    var aiPrompt by remember { mutableStateOf("") }
    var aiBusy by remember { mutableStateOf(false) }
    var aiStatus by remember { mutableStateOf("") }
    // Selected AI model — defaults to the BuildConfig value (local.properties ai.api.model)
    // but can be switched at runtime from the Arrange panel. Lets the user fall back to
    // gpt-4o when the flagship 524s on the relay's Cloudflare edge.
    var aiModel by remember { mutableStateOf(BuildConfig.AI_API_MODEL) }

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
            aiStatus = "Asking AI ($aiModel)…"
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
                // Segment the floor plan into separate rooms (zones) so the AI can place
                // furniture per-room instead of clustering at the global center — critical
                // for divided plans like the demo (living room + bedroom with an open
                // passage). Computed in the same navigation-root-local coords as
                // footprintWalls, so room bounds/centroids line up with the AI's coordinate
                // system. Empty for single-room or open plans (the AI falls back to global).
                val detectedRooms = detectRooms(FloorPlan(walls = footprintWalls))
                // Load the AdventureX fengshui/国学 config from assets when Advanced
                // Thinking is on, so it can be prepended to the system prompt.
                val prePrompt =
                    if (advancedThinking) {
                        runCatching {
                            val raw = context.assets
                                .open("adventurex_fengshui_ai_config_v1.json")
                                .bufferedReader()
                                .use { it.readText() }
                            // Distill the 1300-line fengshui JSON into a concise, schema-
                            // compatible prompt. The raw config has its own conflicting
                            // output_schema (scores/issues/recommendations) that confuses
                            // the AI into returning an analysis instead of placements —
                            // distilling keeps the cultural rules but explicitly tells the
                            // AI to still output {placements, notes, textures}.
                            distillFengshuiPrompt(raw)
                        }.getOrElse {
                            Log.w(TAG, "could not load advanced pre-prompt: ${it.message}")
                            null
                        }
                    } else {
                        null
                    }

                // Shared spawn helper: resolves the AI placements against the catalog,
                // frees the ghost, wipes the room, and spawns the new layout. Reports
                // progress via aiStatus while spawning and returns a one-line summary
                // (without notes) the caller can fold into its final status. Used for
                // both the seed turn and each ITERATE turn.
                val spawnLayout: suspend (AiLayout) -> String = { layout ->
                    val resolved =
                        resolveAiPlacements(
                            layout = layout,
                            catalog = modelCatalog,
                            walls = footprintWalls,
                            baseMargin = footprintMargin,
                            rooms = detectedRooms,
                        )
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
                    buildString {
                        append("AI placed $spawned of ${layout.placements.size}.")
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
                    }
                }

                // Helper: apply any texture reskins the AI returned. Rebuilds the room
                // (which destroys placed furniture — always call BEFORE spawnLayout).
                // Returns a "Textures skipped: …" note when entries were ignored.
                val applyTextures: suspend (AiLayout) -> String = { layout ->
                    if (layout.textures.isEmpty()) {
                        ""
                    } else {
                        val resolvedTextures =
                            resolveAiTextures(layout.textures, availableTextures)
                        if (resolvedTextures.resolved.isNotEmpty()) {
                            aiStatus = "Applying textures…"
                            selectedTextures =
                                selectedTextures +
                                    resolvedTextures.resolved.mapValues { it.value.displayName }
                            roomTextures =
                                resolveRoomTextures(
                                    selectedTextures,
                                    availableTextures,
                                    textureCache,
                                )
                            val oldRoom = generatedRoom.room
                            applyRevision += 1
                            var waited = 0
                            while (generatedRoom.room === oldRoom && waited < 200) {
                                delay(100)
                                waited++
                            }
                        }
                        if (resolvedTextures.skipped.isNotEmpty()) {
                            " Textures skipped: " +
                                resolvedTextures.skipped.joinToString(", ") + "."
                        } else {
                            ""
                        }
                    }
                }

                val layout =
                    requestAiLayout(
                        userPrompt = aiPrompt,
                        walls = footprintWalls,
                        openings = openings,
                        catalog = modelCatalog,
                        currentPlacements = placementController.placedSummaries(),
                        textureCatalog = availableTextures,
                        zoneNotes = appliedPlan.zoneNotes,
                        prePrompt = prePrompt,
                        planMode = planMode,
                        iterate = iterateMode && !planMode,
                        iteration = 0,
                        maxIterations = ITERATE_MAX_ITERATIONS,
                        rooms = detectedRooms,
                        aiModel = aiModel,
                    )
                // Plan Mode: the AI returned analysis/suggestions only (no placements).
                // Surface them in the status and skip the spawn/texture steps entirely.
                if (planMode) {
                    val suggestionCount = layout.suggestions.size
                    aiStatus =
                        buildString {
                            append("Plan mode — $suggestionCount suggestion(s).")
                            layout.notes?.let { append(" $it") }
                            if (layout.suggestions.isNotEmpty()) {
                                append(" • ")
                                append(layout.suggestions.joinToString(" | "))
                            }
                        }
                    return@launch
                }
                // Surface reskins requested by the AI: rebuild the room FIRST (a rebuild
                // destroys placed furniture — which we're about to replace anyway) and wait
                // for it, so the spawns below land in the new room.
                val textureNote = applyTextures(layout)
                val spawnNote = spawnLayout(layout)
                aiStatus =
                    buildString {
                        append(spawnNote)
                        append(textureNote)
                        layout.notes?.let { append(" $it") }
                    }

                // ITERATE mode: re-invoke the AI with the now-placed layout and let it
                // self-evaluate. Stops when the AI returns satisfied=true (or an empty
                // revision), or after ITERATE_MAX_ITERATIONS turns. Each turn replaces the
                // room's furniture with the AI's revised layout.
                if (iterateMode) {
                    var lastNotes = layout.notes
                    var iter = 1
                    while (iter <= ITERATE_MAX_ITERATIONS) {
                        aiStatus = "Iterating $iter/$ITERATE_MAX_ITERATIONS — evaluating layout…"
                        val iterLayout =
                            requestAiLayout(
                                userPrompt = aiPrompt,
                                walls = footprintWalls,
                                openings = openings,
                                catalog = modelCatalog,
                                currentPlacements = placementController.placedSummaries(),
                                textureCatalog = availableTextures,
                                zoneNotes = appliedPlan.zoneNotes,
                                prePrompt = prePrompt,
                                planMode = false,
                                iterate = true,
                                iteration = iter,
                                maxIterations = ITERATE_MAX_ITERATIONS,
                                previousNotes = lastNotes,
                                rooms = detectedRooms,
                                aiModel = aiModel,
                            )
                        if (iterLayout.satisfied) {
                            aiStatus =
                                buildString {
                                    append("AI satisfied after $iter iteration(s).")
                                    iterLayout.notes?.let { append(" $it") }
                                }
                            break
                        }
                        // Defensive stop: AI didn't say "satisfied" but also returned no
                        // revision — accept the current layout rather than wiping the room.
                        if (iterLayout.placements.isEmpty()) {
                            aiStatus =
                                buildString {
                                    append("AI stopped at iteration $iter (no revision).")
                                    iterLayout.notes?.let { append(" $it") }
                                }
                            break
                        }
                        val iterTextureNote = applyTextures(iterLayout)
                        val iterSpawnNote = spawnLayout(iterLayout)
                        aiStatus =
                            buildString {
                                append("Iter $iter/$ITERATE_MAX_ITERATIONS: ")
                                append(iterSpawnNote)
                                append(iterTextureNote)
                                iterLayout.notes?.let { append(" $it") }
                            }
                        lastNotes = iterLayout.notes
                        iter += 1
                    }
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

    // HMD tracking runs all the time: the whole UI rig follows the user's view, not just the
    // placement ghost. Controller tracking is only needed while placing.
    DisposableEffect(Unit) {
        hmdProvider.start()
        onDispose { hmdProvider.stop() }
    }

    // Rig ticker: nudges the SpatialView update loop ~10x/s so the view-following rig keeps
    // tracking the head. The rig itself rate-limits and dead-zones actual panel moves.
    LaunchedEffect(Unit) {
        while (true) {
            rigTick += 1
            delay(100)
        }
    }

    DisposableEffect(placementActive) {
        if (placementActive) {
            controllerProvider.start()
        }
        onDispose {
            controllerProvider.stop()
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
                        prompt == "ui:toggle" -> {
                            uiOpen = !uiOpen
                            Log.w(TAG, "debug hook: uiOpen -> $uiOpen")
                        }
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
                                        modelRotation = 0f
                                        placementActive = true
                                        uiOpen = false
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

            listOf("main-panel", "placement-hud", "ui-launcher").forEach {
                Log.w(TAG, "attachment entity($it) = ${attachments.entity(id = it) != null}")
            }
            // Single main panel hosts the switcher (room plan / furniture / AI arrange).
            // Closer to the user since only one panel shows at a time.
            attachments.entity(id = "main-panel")?.apply {
                components[TransformComponent::class.java]?.setPosition(
                    Vector3(0f, 1.3f, -0.5f)
                )
                content.addEntity(this)
            }
            attachments.entity(id = "ui-launcher")?.apply {
                components[TransformComponent::class.java]?.setPosition(
                    Vector3(0f, 0.82f, -1.05f)
                )
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
            // The launcher folds/opens the main panel (which hosts the switcher). The placement
            // HUD keeps its own visibility rules (it only exists while placing) and the launcher
            // itself stays visible so the interface can always be reopened.
            attachments.entity(id = "main-panel")?.let { panel ->
                if (panel.enabled != uiOpen) panel.enabled = uiOpen
            }

            // --- View-following UI rig ---
            // The single main panel (hosting the switcher) stays centered on the user's gaze.
            // Yaw only — following pitch would be nauseating. When the interface is folded,
            // only the launcher follows.
            // Reading rigTick subscribes this block to the 100 ms ticker — without it the
            // rig would only re-evaluate when some unrelated state changes (e.g. a click).
            val rigTickObserved = rigTick
            val rigHmdPos = aimState.hmdPosition
            val rigHmdRot = aimState.hmdRotation
            val rigNow = System.currentTimeMillis()
            if (rigTickObserved % 50 == 0) {
                Log.w(
                    TAG,
                    "ui rig: alive tick=$rigTickObserved" +
                        " hmdAge=${rigNow - aimState.hmdUpdatedAt}ms anchored=${uiRig.eye != null}",
                )
            }
            if (uiRig.lastUiOpen != uiOpen) {
                uiRig.lastUiOpen = uiOpen
                // Force a snap so reopened panels land in front of the user instead of where
                // they were when folded away.
                uiRig.eye = null
                uiRig.forward = null
            }
            if (rigHmdPos != null && rigHmdRot != null && !uiRig.loggedHmdPose) {
                uiRig.loggedHmdPose = true
                Log.w(TAG, "ui rig: hmd pose flowing pos=$rigHmdPos")
            }
            // Freshness is required only for *tracking*; the very first anchor is allowed to
            // use the last known pose, however old, so the rig always lands in front of the
            // user at least once.
            if (
                rigHmdPos != null && rigHmdRot != null &&
                    (rigNow - aimState.hmdUpdatedAt <= AIM_SOURCE_STALE_MS || uiRig.eye == null) &&
                    rigNow - uiRig.lastUpdateAt >= UI_RIG_UPDATE_INTERVAL_MS
            ) {
                val rawFwd = rigHmdRot.rotateVector(Vector3(0f, 0f, -1f))
                val flatLen = kotlin.math.hypot(rawFwd.x.toDouble(), rawFwd.z.toDouble()).toFloat()
                if (flatLen > 1e-4f) {
                    val gazeFwd = Vector3(rawFwd.x / flatLen, 0f, rawFwd.z / flatLen)
                    val curEye = uiRig.eye
                    val curFwd = uiRig.forward
                    val moveDist =
                        if (curEye == null) Float.MAX_VALUE else (rigHmdPos - curEye).length()
                    val turnDeg =
                        if (curFwd == null) {
                            Float.MAX_VALUE
                        } else {
                            val dot =
                                (curFwd.x * gazeFwd.x + curFwd.z * gazeFwd.z).coerceIn(-1f, 1f)
                            Math.toDegrees(kotlin.math.acos(dot).toDouble()).toFloat()
                        }
                    // No yaw dead zone: a 3° yaw dead zone (the old UI_RIG_MIN_YAW_DEGREES)
                    // left the panel fixed in world space during small rotations, so it
                    // swung to the side (appearing farther) then jumped back to center
                    // (appearing closer) once the threshold was crossed — reading as an
                    // ellipse. Now we always update when the timer fires. A tiny position
                    // dead zone stays to avoid sub-millimeter walking jitter.
                    if (moveDist > UI_RIG_MIN_MOVE_METERS || true) {
                        val snap =
                            curEye == null || curFwd == null ||
                                moveDist > HUD_SNAP_DISTANCE_METERS || turnDeg > UI_RIG_SNAP_YAW_DEGREES
                        if (snap && curEye == null) {
                            Log.w(TAG, "ui rig: anchored to view")
                        }
                        // Position AND yaw track the head 1:1 — no smoothing. Smoothing
                        // either one causes problems: lerping position makes the panel
                        // lag behind walking (changing apparent distance), and lerping
                        // yaw while tracking position 1:1 makes the panel trace an
                        // ellipse when rotating (head sways, forward lags).
                        val eye = rigHmdPos
                        val fwd = gazeFwd
                        uiRig.lastUpdateAt = rigNow
                        uiRig.eye = eye
                        uiRig.forward = fwd
                        // left = up × forward (x points right when facing -z)
                        val left = Vector3(fwd.z, 0f, -fwd.x)

                        fun placePanel(id: String, ahead: Float, side: Float, dy: Float) {
                            attachments.entity(id = id)?.let { panel ->
                                if (!panel.enabled) return@let
                                val target =
                                    Vector3(
                                        eye.x + fwd.x * ahead + left.x * side,
                                        eye.y + dy,
                                        eye.z + fwd.z * ahead + left.z * side,
                                    )
                                panel.components[TransformComponent::class.java]?.apply {
                                    setPosition(target)
                                    setEulerAngles(
                                        EulerAngles(yaw = yawFacingUserDegrees(target, eye))
                                    )
                                }
                            }
                        }

                        // Single main panel centered and closer (only one shows at a time),
                        // so the UI is easier to read. Launcher stays below.
                        if (uiOpen) {
                            placePanel("main-panel", 0.5f, 0f, -0.2f)
                        }
                        placePanel("ui-launcher", 0.55f, 0f, -0.78f)
                    }
                }
            }

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
                        // Snap the HUD to the target the first time it's shown (when
                        // hudFollow.position is null) so it appears where the user is
                        // looking immediately, instead of lingering at its initial
                        // world-origin spawn position for a few frames.
                        val firstShow = hudFollow.position == null
                        if (firstShow || now - hudFollow.lastUpdateAt >= HUD_UPDATE_INTERVAL_MS) {
                            val forward = hmdRot.rotateVector(Vector3(0f, 0f, -1f))
                            val target =
                                Vector3(
                                    hmdPos.x + forward.x * 0.5f,
                                    hmdPos.y - 0.2f,
                                    hmdPos.z + forward.z * 0.5f,
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
                            if (firstShow ||
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
                } else {
                    // Reset follow state when hiding so the next show snaps fresh.
                    hudFollow.position = null
                    hudFollow.yaw = 0f
                    hudFollow.lastUpdateAt = 0L
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
            AttachmentPanel(id = "ui-launcher") {
                UiLauncherPanel(
                    uiOpen = uiOpen,
                    onToggle = {
                        uiOpen = !uiOpen
                        Log.w(TAG, "ui launcher: uiOpen -> $uiOpen")
                    },
                )
            }
            AttachmentPanel(id = "main-panel") {
                MainPanelSwitcher(
                    activePanel = activePanel,
                    onPanelChange = { activePanel = it },
                ) { panel ->
                    when (panel) {
                        MainPanel.RoomPlan -> FloorPlanExperiencePanel(
                            plan = draftPlan,
                            appliedPlan = appliedPlan,
                            selectedEnvironment = selectedEnvironment,
                            showcaseAvailable = showcaseScene != null,
                            roomAvailable = roomAvailable,
                            expanded = editorExpanded,
                            status = status,
                            availableTextures = availableTextures,
                            selectedTextures = selectedTextures,
                            onPlanChange = { draftPlan = it },
                            onApplyPlan = {
                                roomNavigationPosition = RoomNavigationPosition()
                                appliedPlan = draftPlan.normalized()
                                applyRevision += 1
                            },
                            onEnvironmentSelected = { selectedEnvironment = it },
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
                            onExpandedChange = { editorExpanded = it },
                        )
                        MainPanel.FurnitureLibrary -> FurnitureLibraryPanel(
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
                                    if (!roomAvailable) {
                                        status = "Generate a room first before placing furniture."
                                        return@launch
                                    }
                                    try {
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
                                            modelRotation = 0f
                                            placementActive = true
                                            // Auto-hide the main panel so the placement HUD is unobstructed.
                                            uiOpen = false
                                        } else {
                                            selectedModelName = null
                                            status = "Could not load model ${model.displayName}."
                                        }
                                    } catch (e: Exception) {
                                        selectedModelName = null
                                        status = "Error loading model: ${e.message}"
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
                        MainPanel.AiArrange -> AiArrangePanel(
                            aiPrompt = aiPrompt,
                            aiBusy = aiBusy,
                            aiStatus = aiStatus,
                            roomAvailable = roomAvailable,
                            advancedThinking = advancedThinking,
                            planMode = planMode,
                            iterateMode = iterateMode,
                            aiModel = aiModel,
                            onAdvancedThinkingChange = { advancedThinking = it },
                            onPlanModeChange = { planMode = it },
                            onIterateModeChange = { iterateMode = it },
                            onAiPromptChange = { aiPrompt = it },
                            onAiModelChange = { aiModel = it },
                            onArrangeWithAi = runAiArrange,
                            onResetRoom = {
                                placementController.clearPlaced()
                                placedCount = 0
                                selectedModelName = null
                                placementActive = false
                                placementController.hideGhost()
                                draftPlan = demoFloorPlan()
                                appliedPlan = draftPlan.normalized()
                                roomNavigationPosition = RoomNavigationPosition()
                                selectedTextures = emptyMap()
                                roomTextures = RoomTextures()
                                applyRevision += 1
                                status = "Room reset to default layout."
                            },
                        )
                    }
                }
            }
            AttachmentPanel(id = "placement-hud") {
                PlacementHudPanel(
                    selectedModelName = selectedModelName,
                    placementActive = placementActive,
                    placedCount = placedCount,
                    aimStatus = aimStatus,
                    roomAvailable = roomAvailable,
                    rotationDegrees = modelRotation,
                    onPlacementActiveChange = { active ->
                        placementActive = active
                        if (!active) {
                            placementController.hideGhost()
                            // Reopen the main panel when the user stops placing.
                            uiOpen = true
                        }
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
                    onRotationChange = { degrees ->
                        modelRotation = degrees
                        placementController.setManualYaw(degrees)
                    },
                    onUndo = {
                        if (placementController.undoLast()) {
                            placedCount = placementController.placedCount
                        }
                    },
                )
            }
        },
    )
}

private fun roomStatus(plan: FloorPlan): String =
    "Room ready: ${plan.walls.size} walls, ${plan.openings.size} openings."

/**
 * Hosts one main panel at a time with semi-transparent arrow buttons on both sides and a
 * sliding transition between panels. Cycles RoomPlan -> FurnitureLibrary -> AiArrange.
 */
@Composable
private fun MainPanelSwitcher(
    activePanel: MainPanel,
    onPanelChange: (MainPanel) -> Unit,
    content: @Composable (MainPanel) -> Unit,
) {
    // Row places arrows beside the panel (not overlapping it), so the panel's glass
    // material surface can't cover the arrows. The AnimatedContent slides between
    // the two arrows which stay fixed during the transition.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PanelArrow(
            direction = -1,
            onClick = {
                onPanelChange(
                    when (activePanel) {
                        MainPanel.RoomPlan -> MainPanel.AiArrange
                        MainPanel.FurnitureLibrary -> MainPanel.RoomPlan
                        MainPanel.AiArrange -> MainPanel.FurnitureLibrary
                    },
                )
            },
        )

        Box(contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = activePanel,
                transitionSpec = {
                    if (targetState.ordinal > initialState.ordinal) {
                        slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300)) togetherWith
                            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300))
                    } else {
                        slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) togetherWith
                            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300))
                    }
                },
                contentKey = { it },
            ) { panel ->
                content(panel)
            }
        }

        PanelArrow(
            direction = 1,
            onClick = {
                onPanelChange(
                    when (activePanel) {
                        MainPanel.RoomPlan -> MainPanel.FurnitureLibrary
                        MainPanel.FurnitureLibrary -> MainPanel.AiArrange
                        MainPanel.AiArrange -> MainPanel.RoomPlan
                    },
                )
            },
        )
    }
}

/** Semi-transparent circular arrow button used to cycle the main panels. */
@Composable
private fun PanelArrow(
    direction: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0x44FFFFFF))
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (direction < 0) "‹" else "›",
            color = Color.White,
            fontSize = 32.sp,
        )
    }
}
