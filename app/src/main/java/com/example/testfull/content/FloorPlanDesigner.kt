package com.example.testfull.content

import android.graphics.Paint as NativePaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.NumberField
import com.pico.spatial.ui.design.NumberFieldDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.ToggleButton
import com.pico.spatial.ui.design.ToggleButtonDefaults
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal enum class AppEnvironment {
    SHOWCASE,
    ROOM,
}

private enum class DesignerMode {
    SELECT,
    WALL,
    DOOR,
    WINDOW,
}

private enum class SelectionKind {
    WALL,
    OPENING,
}

private data class PlanSelection(val kind: SelectionKind, val id: Int)

private data class PlanCanvasTransform(
    val size: Size,
    val centerX: Float,
    val centerZ: Float,
    val pixelsPerMeter: Float,
) {
    fun toScreen(point: PlanPoint): Offset =
        Offset(
            x = size.width / 2f + (point.x - centerX) * pixelsPerMeter,
            y = size.height / 2f + (point.z - centerZ) * pixelsPerMeter,
        )

    fun toWorld(point: Offset): PlanPoint =
        PlanPoint(
            x = centerX + (point.x - size.width / 2f) / pixelsPerMeter,
            z = centerZ + (point.y - size.height / 2f) / pixelsPerMeter,
        )
}

@Composable
internal fun FloorPlanExperiencePanel(
    plan: FloorPlan,
    appliedPlan: FloorPlan,
    selectedEnvironment: AppEnvironment,
    showcaseAvailable: Boolean,
    roomAvailable: Boolean,
    expanded: Boolean,
    status: String,
    roomPositionX: Float,
    roomPositionZ: Float,
    onPlanChange: (FloorPlan) -> Unit,
    onApplyPlan: () -> Unit,
    onEnvironmentSelected: (AppEnvironment) -> Unit,
    onMoveInRoom: (deltaX: Float, deltaZ: Float) -> Unit,
    onResetRoomPosition: () -> Unit,
    onExpandedChange: (Boolean) -> Unit,
) {
    if (!expanded) {
        CompactEnvironmentPanel(
            selectedEnvironment = selectedEnvironment,
            showcaseAvailable = showcaseAvailable,
            roomAvailable = roomAvailable,
            status = status,
            roomPositionX = roomPositionX,
            roomPositionZ = roomPositionZ,
            onEnvironmentSelected = onEnvironmentSelected,
            onMoveInRoom = onMoveInRoom,
            onResetRoomPosition = onResetRoomPosition,
            onEditPlan = { onExpandedChange(true) },
        )
        return
    }

    FloorPlanDesigner(
        plan = plan,
        hasUnappliedChanges = plan != appliedPlan,
        selectedEnvironment = selectedEnvironment,
        showcaseAvailable = showcaseAvailable,
        roomAvailable = roomAvailable,
        status = status,
        onPlanChange = onPlanChange,
        onApplyPlan = {
            onApplyPlan()
            onEnvironmentSelected(AppEnvironment.ROOM)
            onExpandedChange(false)
        },
        onEnvironmentSelected = onEnvironmentSelected,
        onCollapse = { onExpandedChange(false) },
    )
}

@Composable
private fun CompactEnvironmentPanel(
    selectedEnvironment: AppEnvironment,
    showcaseAvailable: Boolean,
    roomAvailable: Boolean,
    status: String,
    roomPositionX: Float,
    roomPositionZ: Float,
    onEnvironmentSelected: (AppEnvironment) -> Unit,
    onMoveInRoom: (deltaX: Float, deltaZ: Float) -> Unit,
    onResetRoomPosition: () -> Unit,
    onEditPlan: () -> Unit,
) {
    Column(
        modifier =
            Modifier.size(560.dp, 430.dp)
                .clip(RoundedCornerShape(20.dp))
                .backgroundMaterial(true, Material.Regular)
                .padding(26.dp),
    ) {
        Text(
            text = "Environment",
            style = PicoTheme.typography.displaySmall,
            fontSize = 25.sp,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EnvironmentButton(
                label = "Showcase",
                selected = selectedEnvironment == AppEnvironment.SHOWCASE,
                enabled = showcaseAvailable,
                onClick = { onEnvironmentSelected(AppEnvironment.SHOWCASE) },
            )
            EnvironmentButton(
                label = "Generated room",
                selected = selectedEnvironment == AppEnvironment.ROOM,
                enabled = roomAvailable,
                onClick = { onEnvironmentSelected(AppEnvironment.ROOM) },
            )
            Button(
                onClick = onEditPlan,
                size = ButtonDefaults.Max,
                modifier = Modifier.width(142.dp),
            ) {
                Text("Edit plan")
            }
        }
        Spacer(Modifier.height(13.dp))
        Text(
            text = status,
            style = PicoTheme.typography.titleMedium,
            fontSize = 15.sp,
            color = Color(0x99000000),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Virtual walk · 0.5 m per step",
            style = PicoTheme.typography.displaySmall,
            fontSize = 20.sp,
        )
        Text(
            text =
                "Room position  X ${formatMeters(roomPositionX)}  ·  Z ${formatMeters(roomPositionZ)}",
            style = PicoTheme.typography.titleMedium,
            fontSize = 14.sp,
            color = Color(0x99000000),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NavigationButton(
                label = "← Left",
                enabled = roomAvailable,
                onClick = { onMoveInRoom(-PLAN_GRID_STEP_METERS, 0f) },
            )
            NavigationButton(
                label = "Forward ↑",
                enabled = roomAvailable,
                onClick = { onMoveInRoom(0f, -PLAN_GRID_STEP_METERS) },
            )
            NavigationButton(
                label = "Right →",
                enabled = roomAvailable,
                onClick = { onMoveInRoom(PLAN_GRID_STEP_METERS, 0f) },
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NavigationButton(
                label = "Back ↓",
                enabled = roomAvailable,
                onClick = { onMoveInRoom(0f, PLAN_GRID_STEP_METERS) },
            )
            NavigationButton(
                label = "Reset center",
                enabled = roomAvailable,
                onClick = onResetRoomPosition,
            )
        }
        Spacer(Modifier.height(9.dp))
        Text(
            text =
                "Use these controls to explore. Emulator W/A/S/D moves the headset and triggers PICO's safeguard fade.",
            style = PicoTheme.typography.titleMedium,
            fontSize = 13.sp,
            color = Color(0x99000000),
        )
    }
}

@Composable
private fun FloorPlanDesigner(
    plan: FloorPlan,
    hasUnappliedChanges: Boolean,
    selectedEnvironment: AppEnvironment,
    showcaseAvailable: Boolean,
    roomAvailable: Boolean,
    status: String,
    onPlanChange: (FloorPlan) -> Unit,
    onApplyPlan: () -> Unit,
    onEnvironmentSelected: (AppEnvironment) -> Unit,
    onCollapse: () -> Unit,
) {
    var mode by remember { mutableStateOf(DesignerMode.SELECT) }
    var selection by remember { mutableStateOf<PlanSelection?>(null) }
    var pendingWallStart by remember { mutableStateOf<PlanPoint?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var canvasZoom by remember { mutableStateOf(1f) }

    fun changeMode(next: DesignerMode) {
        mode = next
        pendingWallStart = null
    }

    fun replacePlan(next: FloorPlan) {
        onPlanChange(next.normalized())
    }

    Column(
        modifier =
            Modifier.size(920.dp, 720.dp)
                .clip(RoundedCornerShape(22.dp))
                .backgroundMaterial(true, Material.Regular)
                .padding(24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.width(300.dp)) {
                Text(
                    text = "Room plan",
                    style = PicoTheme.typography.displaySmall,
                    fontSize = 26.sp,
                )
                Text(
                    text =
                        if (hasUnappliedChanges) {
                            "Draft changed — apply to rebuild the room"
                        } else {
                            status
                        },
                    style = PicoTheme.typography.titleMedium,
                    fontSize = 14.sp,
                    color = Color(0x99000000),
                )
            }
            EnvironmentButton(
                label = "Showcase",
                selected = selectedEnvironment == AppEnvironment.SHOWCASE,
                enabled = showcaseAvailable,
                onClick = { onEnvironmentSelected(AppEnvironment.SHOWCASE) },
            )
            EnvironmentButton(
                label = "Room",
                selected = selectedEnvironment == AppEnvironment.ROOM,
                enabled = roomAvailable,
                onClick = { onEnvironmentSelected(AppEnvironment.ROOM) },
            )
            Button(
                onClick = onCollapse,
                size = ButtonDefaults.Regular,
                modifier = Modifier.width(126.dp),
            ) {
                Text("Hide editor")
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(modifier = Modifier.width(590.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeButton("Select", mode == DesignerMode.SELECT) {
                        changeMode(DesignerMode.SELECT)
                    }
                    ModeButton("Wall", mode == DesignerMode.WALL) {
                        changeMode(DesignerMode.WALL)
                    }
                    ModeButton("Door", mode == DesignerMode.DOOR) {
                        changeMode(DesignerMode.DOOR)
                    }
                    ModeButton("Window", mode == DesignerMode.WINDOW) {
                        changeMode(DesignerMode.WINDOW)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "View zoom",
                        style = PicoTheme.typography.titleMedium,
                        fontSize = 14.sp,
                        modifier = Modifier.width(90.dp),
                    )
                    NumberField(
                        value = canvasZoom * 100f,
                        onValueChange = { canvasZoom = it / 100f },
                        stepLength = 25f,
                        valueRange = 50f..300f,
                        size = NumberFieldDefaults.smallSize(),
                        unit = "%",
                        fractionDigits = 0,
                    )
                    Button(
                        onClick = { canvasZoom = 1f },
                        size = ButtonDefaults.Small,
                        modifier = Modifier.width(110.dp),
                    ) {
                        Text("Fit")
                    }
                    Text(
                        text = "Grid 0.50 m · drag corner points · units: meters",
                        style = PicoTheme.typography.titleMedium,
                        fontSize = 13.sp,
                        color = Color(0x99000000),
                    )
                }
                Spacer(Modifier.height(8.dp))
                PlanCanvas(
                    plan = plan,
                    mode = mode,
                    selection = selection,
                    pendingWallStart = pendingWallStart,
                    canvasSize = canvasSize,
                    zoom = canvasZoom,
                    onCanvasSizeChange = { canvasSize = it },
                    onMoveConnectionPoint = { from, target ->
                        replacePlan(plan.moveConnectionPoint(from, target))
                    },
                    onTap = { point ->
                        when (mode) {
                            DesignerMode.SELECT -> {
                                selection = findSelection(plan, point, canvasSize, canvasZoom)
                            }
                            DesignerMode.WALL -> {
                                val snapped = point.snapped()
                                val start = pendingWallStart
                                if (start == null) {
                                    // Starting a new wall is treated as creating a custom plan when
                                    // the untouched 15 x 8 starter is still present. Otherwise the
                                    // starter walls would be rebuilt together with the new room.
                                    val planForNewWall =
                                        plan.withoutUntouchedStarterForNewWall()
                                    if (planForNewWall !== plan) {
                                        replacePlan(planForNewWall)
                                        selection = null
                                    }
                                    pendingWallStart = snapped
                                } else if (start.distanceTo(snapped) >= 0.2f) {
                                    val wall =
                                        PlanWall(
                                            id = plan.nextWallId(),
                                            start = start,
                                            end = snapped,
                                        )
                                    replacePlan(plan.copy(walls = plan.walls + wall))
                                    selection =
                                        PlanSelection(SelectionKind.WALL, wall.id)
                                    pendingWallStart = snapped
                                }
                            }
                            DesignerMode.DOOR,
                            DesignerMode.WINDOW -> {
                                val nearest =
                                    plan.walls
                                        .map { it to it.project(point) }
                                        .minByOrNull { it.second.distance }
                                if (nearest != null && nearest.second.distance <= 0.4f) {
                                    val (wall, projection) = nearest
                                    val opening =
                                        if (mode == DesignerMode.DOOR) {
                                            PlanOpening(
                                                id = plan.nextOpeningId(),
                                                wallId = wall.id,
                                                type = OpeningType.DOOR,
                                                position = projection.position,
                                                width = 0.9f,
                                                height = 2.1f,
                                                sill = 0f,
                                            )
                                        } else {
                                            PlanOpening(
                                                id = plan.nextOpeningId(),
                                                wallId = wall.id,
                                                type = OpeningType.WINDOW,
                                                position = projection.position,
                                                width = 1.4f,
                                                height = 1.1f,
                                                sill = 0.9f,
                                            )
                                        }
                                    replacePlan(
                                        plan.copy(openings = plan.openings + opening)
                                    )
                                    selection =
                                        PlanSelection(SelectionKind.OPENING, opening.id)
                                    changeMode(DesignerMode.SELECT)
                                }
                            }
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text =
                        when (mode) {
                            DesignerMode.SELECT ->
                                "Drag a corner point to reshape connected walls. Points snap to the nearest 0.5 m grid."
                            DesignerMode.WALL ->
                                if (pendingWallStart == null) {
                                    "Tap the first point, then tap each wall endpoint."
                                } else {
                                    "Tap the next endpoint. Select another tool to finish."
                                }
                            DesignerMode.DOOR -> "Tap near a wall to insert a door."
                            DesignerMode.WINDOW -> "Tap near a wall to insert a window."
                        },
                    style = PicoTheme.typography.titleMedium,
                    fontSize = 14.sp,
                    color = Color(0x99000000),
                )
            }

            Inspector(
                plan = plan,
                selection = selection,
                onPlanChange = ::replacePlan,
                onSelectionChange = { selection = it },
                onDemo = {
                    replacePlan(demoFloorPlan())
                    selection = null
                    pendingWallStart = null
                },
                onClear = {
                    onPlanChange(FloorPlan())
                    selection = null
                    pendingWallStart = null
                },
                applyEnabled = plan.walls.isNotEmpty(),
                onApply = onApplyPlan,
            )
        }
    }
}

@Composable
private fun PlanCanvas(
    plan: FloorPlan,
    mode: DesignerMode,
    selection: PlanSelection?,
    pendingWallStart: PlanPoint?,
    canvasSize: IntSize,
    zoom: Float,
    onCanvasSizeChange: (IntSize) -> Unit,
    onMoveConnectionPoint: (PlanPoint, PlanPoint) -> Unit,
    onTap: (PlanPoint) -> Unit,
) {
    val fittedTransform =
        remember(plan, canvasSize, zoom) {
            planCanvasTransform(
                plan,
                Size(canvasSize.width.toFloat(), canvasSize.height.toFloat()),
                zoom,
            )
        }
    var draggedConnectionPoint by remember { mutableStateOf<PlanPoint?>(null) }
    var dragTransform by remember { mutableStateOf<PlanCanvasTransform?>(null) }
    val transform = dragTransform ?: fittedTransform
    val currentPlan by rememberUpdatedState(plan)
    val currentTransform by rememberUpdatedState(transform)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnMoveConnectionPoint by rememberUpdatedState(onMoveConnectionPoint)

    Canvas(
        modifier =
            Modifier.size(590.dp, 420.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF090C10))
                .onSizeChanged(onCanvasSizeChange)
                .pointerInput(mode, canvasSize, zoom) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val gestureTransform = currentTransform
                        val connectionRadius = 24.dp.toPx()
                        val connectionPoint =
                            if (mode == DesignerMode.SELECT) {
                                currentPlan
                                    .connectionPoints()
                                    .minByOrNull {
                                        gestureTransform.toScreen(it).squaredDistanceTo(
                                            down.position
                                        )
                                    }
                                    ?.takeIf {
                                        gestureTransform.toScreen(it).squaredDistanceTo(
                                            down.position
                                        ) <= connectionRadius * connectionRadius
                                    }
                            } else {
                                null
                            }

                        if (connectionPoint == null) {
                            val up = waitForUpOrCancellation()
                            if (up != null) {
                                currentOnTap(gestureTransform.toWorld(up.position))
                            }
                            return@awaitEachGesture
                        }

                        val dragStart =
                            awaitTouchSlopOrCancellation(down.id) { change, _ ->
                                change.consume()
                            }
                        if (dragStart == null) {
                            currentOnTap(gestureTransform.toWorld(down.position))
                            return@awaitEachGesture
                        }

                        var previousPoint: PlanPoint = connectionPoint
                        draggedConnectionPoint = connectionPoint
                        dragTransform = gestureTransform

                        fun moveTo(screenPosition: Offset) {
                            val snappedTarget =
                                gestureTransform.toWorld(screenPosition).snapped()
                            if (previousPoint.distanceTo(snappedTarget) > 0.001f) {
                                currentOnMoveConnectionPoint(previousPoint, snappedTarget)
                                previousPoint = snappedTarget
                                draggedConnectionPoint = snappedTarget
                            }
                        }

                        moveTo(dragStart.position)
                        dragStart.consume()
                        drag(down.id) { change ->
                            moveTo(change.position)
                            change.consume()
                        }
                        draggedConnectionPoint = null
                        dragTransform = null
                    }
                }
    ) {
        val gridColor = Color(0xFF1A2027)
        val originColor = Color(0xFF313B45)
        val worldStart = transform.toWorld(Offset.Zero)
        val worldEnd = transform.toWorld(Offset(size.width, size.height))
        val minGridX =
            floor(min(worldStart.x, worldEnd.x) / PLAN_GRID_STEP_METERS).toInt()
        val maxGridX =
            ceil(max(worldStart.x, worldEnd.x) / PLAN_GRID_STEP_METERS).toInt()
        val minGridZ =
            floor(min(worldStart.z, worldEnd.z) / PLAN_GRID_STEP_METERS).toInt()
        val maxGridZ =
            ceil(max(worldStart.z, worldEnd.z) / PLAN_GRID_STEP_METERS).toInt()

        for (grid in minGridZ..maxGridZ) {
            val gridPosition = grid * PLAN_GRID_STEP_METERS
            val horizontalStart =
                transform.toScreen(PlanPoint(worldStart.x, gridPosition))
            val horizontalEnd =
                transform.toScreen(PlanPoint(worldEnd.x, gridPosition))
            drawLine(
                color = if (grid == 0) originColor else gridColor,
                start = horizontalStart,
                end = horizontalEnd,
                strokeWidth = if (grid == 0) 2f else 1f,
            )
        }
        for (grid in minGridX..maxGridX) {
            val gridPosition = grid * PLAN_GRID_STEP_METERS
            val verticalStart =
                transform.toScreen(PlanPoint(gridPosition, worldStart.z))
            val verticalEnd =
                transform.toScreen(PlanPoint(gridPosition, worldEnd.z))
            drawLine(
                color = if (grid == 0) originColor else gridColor,
                start = verticalStart,
                end = verticalEnd,
                strokeWidth = if (grid == 0) 2f else 1f,
            )
        }

        plan.walls.forEach { wall ->
            val selected =
                selection?.kind == SelectionKind.WALL && selection.id == wall.id
            drawLine(
                color = if (selected) Color(0xFFFF7457) else Color(0xFFD4D9DF),
                start = transform.toScreen(wall.start),
                end = transform.toScreen(wall.end),
                strokeWidth = max(5f, wall.thickness * transform.pixelsPerMeter),
                cap = StrokeCap.Square,
            )
        }

        plan.openings.forEach { opening ->
            val wall = plan.walls.firstOrNull { it.id == opening.wallId } ?: return@forEach
            val length = wall.length()
            if (length < 0.0001f) return@forEach
            val center = opening.worldPosition(wall)
            val dx = (wall.end.x - wall.start.x) / length
            val dz = (wall.end.z - wall.start.z) / length
            val half = min(opening.width, length) / 2f
            val start = PlanPoint(center.x - dx * half, center.z - dz * half)
            val end = PlanPoint(center.x + dx * half, center.z + dz * half)
            val selected =
                selection?.kind == SelectionKind.OPENING && selection.id == opening.id
            drawLine(
                color = Color(0xFF090C10),
                start = transform.toScreen(start),
                end = transform.toScreen(end),
                strokeWidth = max(10f, wall.thickness * transform.pixelsPerMeter + 5f),
                cap = StrokeCap.Square,
            )
            drawLine(
                color =
                    if (selected) {
                        Color.White
                    } else if (opening.type == OpeningType.DOOR) {
                        Color(0xFF5CE0C0)
                    } else {
                        Color(0xFF72AAFF)
                    },
                start = transform.toScreen(start),
                end = transform.toScreen(end),
                strokeWidth = if (selected) 5f else 3f,
                cap = StrokeCap.Square,
            )
        }

        if (mode == DesignerMode.SELECT) {
            plan.connectionPoints().forEach { point ->
                val isDragged =
                    (draggedConnectionPoint?.distanceTo(point) ?: Float.MAX_VALUE) < 0.001f
                val center = transform.toScreen(point)
                drawCircle(
                    color = Color(0xFF090C10),
                    radius = if (isDragged) 12f else 10f,
                    center = center,
                )
                drawCircle(
                    color = if (isDragged) Color(0xFFFF7457) else Color(0xFFF1F4F7),
                    radius = if (isDragged) 8f else 6f,
                    center = center,
                )
            }
        }

        pendingWallStart?.let {
            drawCircle(
                color = Color(0xFFFF7457),
                radius = 8f,
                center = transform.toScreen(it),
            )
        }

        drawIntoCanvas { canvas ->
            val textPaint =
                NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.WHITE
                    textSize = 12f * density
                    textAlign = NativePaint.Align.CENTER
                }
            val backgroundPaint =
                NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.argb(220, 22, 28, 35)
                    style = NativePaint.Style.FILL
                }

            fun drawLabel(text: String, center: Offset) {
                val horizontalPadding = 7f * density
                val verticalPadding = 4f * density
                val halfWidth = textPaint.measureText(text) / 2f + horizontalPadding
                val halfHeight = textPaint.textSize / 2f + verticalPadding
                canvas.nativeCanvas.drawRoundRect(
                    center.x - halfWidth,
                    center.y - halfHeight,
                    center.x + halfWidth,
                    center.y + halfHeight,
                    6f * density,
                    6f * density,
                    backgroundPaint,
                )
                val baseline = center.y - (textPaint.ascent() + textPaint.descent()) / 2f
                canvas.nativeCanvas.drawText(text, center.x, baseline, textPaint)
            }

            plan.walls.forEach { wall ->
                val start = transform.toScreen(wall.start)
                val end = transform.toScreen(wall.end)
                val dx = end.x - start.x
                val dy = end.y - start.y
                val screenLength = hypot(dx, dy).coerceAtLeast(0.001f)
                val normalX = -dy / screenLength
                val normalY = dx / screenLength
                val selected =
                    selection?.kind == SelectionKind.WALL && selection.id == wall.id
                val label =
                    if (selected) {
                        "${formatMeters(wall.length())} · H ${formatMeters(wall.height)} · T ${formatMeters(wall.thickness)}"
                    } else {
                        formatMeters(wall.length())
                    }
                drawLabel(
                    label,
                    Offset(
                        x = (start.x + end.x) / 2f + normalX * 18f * density,
                        y = (start.y + end.y) / 2f + normalY * 18f * density,
                    ),
                )
            }

            plan.openings.forEach { opening ->
                val wall =
                    plan.walls.firstOrNull { it.id == opening.wallId }
                        ?: return@forEach
                val start = transform.toScreen(wall.start)
                val end = transform.toScreen(wall.end)
                val dx = end.x - start.x
                val dy = end.y - start.y
                val screenLength = hypot(dx, dy).coerceAtLeast(0.001f)
                val normalX = dy / screenLength
                val normalY = -dx / screenLength
                val center = transform.toScreen(opening.worldPosition(wall))
                val prefix = if (opening.type == OpeningType.DOOR) "D" else "W"
                drawLabel(
                    "$prefix ${formatMeters(opening.width)} × ${formatMeters(opening.height)}",
                    Offset(
                        x = center.x + normalX * 18f * density,
                        y = center.y + normalY * 18f * density,
                    ),
                )
            }
        }
    }
}

@Composable
private fun Inspector(
    plan: FloorPlan,
    selection: PlanSelection?,
    onPlanChange: (FloorPlan) -> Unit,
    onSelectionChange: (PlanSelection?) -> Unit,
    onDemo: () -> Unit,
    onClear: () -> Unit,
    applyEnabled: Boolean,
    onApply: () -> Unit,
) {
    val wall =
        if (selection?.kind == SelectionKind.WALL) {
            plan.walls.firstOrNull { it.id == selection.id }
        } else {
            null
        }
    val opening =
        if (selection?.kind == SelectionKind.OPENING) {
            plan.openings.firstOrNull { it.id == selection.id }
        } else {
            null
        }

    Column(
        modifier =
            Modifier.width(264.dp)
                .height(585.dp)
                .verticalScroll(rememberScrollState())
    ) {
        Text(
            text =
                when {
                    wall != null -> "Wall ${wall.id}"
                    opening != null ->
                        if (opening.type == OpeningType.DOOR) {
                            "Door ${opening.id}"
                        } else {
                            "Window ${opening.id}"
                        }
                    else -> "Plan"
                },
            style = PicoTheme.typography.displaySmall,
            fontSize = 21.sp,
        )
        Spacer(Modifier.height(10.dp))

        if (wall != null) {
            DimensionField(
                label = "Length",
                value = wall.length(),
                step = 0.1f,
                range = 0.2f..50f,
                onChange = { value ->
                    onPlanChange(plan.updateWallGeometry(wall.id, length = value))
                },
            )
            DimensionField(
                label = "Direction",
                value = wall.angleDegrees(),
                step = 5f,
                range = -180f..180f,
                unit = "deg",
                fractionDigits = 0,
                onChange = { value ->
                    onPlanChange(plan.updateWallGeometry(wall.id, angleDegrees = value))
                },
            )
            DimensionField(
                label = "Height",
                value = wall.height,
                step = 0.1f,
                range = 0.2f..20f,
                onChange = { value ->
                    onPlanChange(
                        plan.copy(
                            walls =
                                plan.walls.map {
                                    if (it.id == wall.id) it.copy(height = value) else it
                                }
                        )
                    )
                },
            )
            DimensionField(
                label = "Thickness",
                value = wall.thickness,
                step = 0.01f,
                range = 0.02f..2f,
                fractionDigits = 2,
                onChange = { value ->
                    onPlanChange(
                        plan.copy(
                            walls =
                                plan.walls.map {
                                    if (it.id == wall.id) {
                                        it.copy(thickness = value)
                                    } else {
                                        it
                                    }
                                }
                        )
                    )
                },
            )
        } else if (opening != null) {
            val openingWall = plan.walls.firstOrNull { it.id == opening.wallId }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OpeningTypeButton(
                    label = "Door",
                    selected = opening.type == OpeningType.DOOR,
                    onClick = {
                        onPlanChange(
                            plan.updateOpening(opening.id) {
                                it.copy(type = OpeningType.DOOR, sill = 0f)
                            }
                        )
                    },
                )
                OpeningTypeButton(
                    label = "Window",
                    selected = opening.type == OpeningType.WINDOW,
                    onClick = {
                        onPlanChange(
                            plan.updateOpening(opening.id) {
                                it.copy(type = OpeningType.WINDOW, sill = max(0.8f, it.sill))
                            }
                        )
                    },
                )
            }
            DimensionField(
                label = "Offset from start",
                value = (openingWall?.length() ?: 0f) * opening.position,
                step = 0.1f,
                range = 0f..(openingWall?.length() ?: 0f).coerceAtLeast(0.1f),
                onChange = { value ->
                    val wallLength = openingWall?.length()?.coerceAtLeast(0.001f) ?: return@DimensionField
                    onPlanChange(
                        plan.updateOpening(opening.id) {
                            it.copy(position = value / wallLength)
                        }
                    )
                },
            )
            DimensionField(
                label = "Width",
                value = opening.width,
                step = 0.1f,
                range = 0.1f..(openingWall?.length() ?: 20f).coerceAtLeast(0.1f),
                onChange = { value ->
                    onPlanChange(plan.updateOpening(opening.id) { it.copy(width = value) })
                },
            )
            DimensionField(
                label = "Height",
                value = opening.height,
                step = 0.1f,
                range = 0.1f..(openingWall?.height ?: 20f).coerceAtLeast(0.1f),
                onChange = { value ->
                    onPlanChange(plan.updateOpening(opening.id) { it.copy(height = value) })
                },
            )
            if (opening.type == OpeningType.WINDOW) {
                DimensionField(
                    label = "Sill",
                    value = opening.sill,
                    step = 0.1f,
                    range = 0f..(openingWall?.height ?: 20f).coerceAtLeast(0.1f),
                    onChange = { value ->
                        onPlanChange(plan.updateOpening(opening.id) { it.copy(sill = value) })
                    },
                )
            }
            DimensionField(
                label = "Panel depth",
                value = opening.depth,
                step = 0.01f,
                range = 0.01f..0.5f,
                fractionDigits = 2,
                onChange = { value ->
                    onPlanChange(plan.updateOpening(opening.id) { it.copy(depth = value) })
                },
            )
        } else {
            Text(
                text = "${plan.walls.size} walls · ${plan.openings.size} openings",
                style = PicoTheme.typography.titleMedium,
                fontSize = 15.sp,
                color = Color(0x99000000),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Draw connected wall segments, then place doors and windows directly on them.",
                style = PicoTheme.typography.titleMedium,
                fontSize = 14.sp,
                color = Color(0x99000000),
            )
            Spacer(Modifier.height(8.dp))
            DimensionField(
                label = "Whole plan scale",
                value = plan.scale * 100f,
                step = 10f,
                range = 10f..500f,
                unit = "%",
                fractionDigits = 0,
                onChange = { value -> onPlanChange(plan.scaledTo(value / 100f)) },
            )
            if (plan.walls.isNotEmpty()) {
                val bounds = plan.bounds()
                DimensionField(
                    label = "Footprint width",
                    value = bounds.width,
                    step = 0.1f,
                    range = 0.2f..100f,
                    onChange = { value ->
                        onPlanChange(plan.resizedFootprint(targetWidth = value))
                    },
                )
                DimensionField(
                    label = "Footprint depth",
                    value = bounds.depth,
                    step = 0.1f,
                    range = 0.2f..100f,
                    onChange = { value ->
                        onPlanChange(plan.resizedFootprint(targetDepth = value))
                    },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        if (wall != null || opening != null) {
            Button(
                onClick = {
                    val next =
                        if (wall != null) {
                            plan.copy(
                                walls = plan.walls.filterNot { it.id == wall.id },
                                openings =
                                    plan.openings.filterNot { it.wallId == wall.id },
                            )
                        } else {
                            plan.copy(
                                openings =
                                    plan.openings.filterNot { it.id == opening?.id }
                            )
                        }
                    onPlanChange(next)
                    onSelectionChange(null)
                },
                size = ButtonDefaults.Small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Delete selected")
            }
            Spacer(Modifier.height(9.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onDemo,
                size = ButtonDefaults.Small,
                modifier = Modifier.width(126.dp),
            ) {
                Text("Reset 15×8")
            }
            Button(
                onClick = onClear,
                size = ButtonDefaults.Small,
                modifier = Modifier.width(126.dp),
            ) {
                Text("Clear")
            }
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onApply,
            enabled = applyEnabled,
            size = ButtonDefaults.Max,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Apply & preview room")
        }
    }
}

@Composable
private fun DimensionField(
    label: String,
    value: Float,
    step: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    unit: String = "m",
    fractionDigits: Int = 1,
) {
    Column {
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = PicoTheme.typography.titleMedium,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(4.dp))
        NumberField(
            value = value,
            onValueChange = onChange,
            stepLength = step,
            valueRange = range,
            size = NumberFieldDefaults.smallSize(),
            unit = unit,
            fractionDigits = fractionDigits,
        )
    }
}

@Composable
private fun OpeningTypeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ToggleButton(
        checked = selected,
        onCheckedChange = { checked -> if (checked) onClick() },
        size = ToggleButtonDefaults.Small,
        modifier = Modifier.width(126.dp),
    ) {
        Text(label)
    }
}

@Composable
private fun EnvironmentButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ToggleButton(
        checked = selected,
        onCheckedChange = { checked -> if (checked) onClick() },
        enabled = enabled,
        size = ToggleButtonDefaults.Max,
        modifier = Modifier.width(142.dp),
    ) {
        Text(label)
    }
}

@Composable
private fun NavigationButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        size = ButtonDefaults.Small,
        modifier = Modifier.width(158.dp),
    ) {
        Text(label)
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    ToggleButton(
        checked = selected,
        onCheckedChange = { checked -> if (checked) onClick() },
        size = ToggleButtonDefaults.Regular,
        modifier = Modifier.width(140.dp),
    ) {
        Text(label)
    }
}

private fun planCanvasTransform(
    plan: FloorPlan,
    size: Size,
    zoom: Float = 1f,
): PlanCanvasTransform {
    val bounds = plan.bounds()
    val usableWidth = max(1f, size.width - 72f)
    val usableHeight = max(1f, size.height - 72f)
    val worldWidth = max(3f, bounds.width + 1.2f)
    val worldDepth = max(3f, bounds.depth + 1.2f)
    return PlanCanvasTransform(
        size = size,
        centerX = bounds.centerX,
        centerZ = bounds.centerZ,
        pixelsPerMeter =
            min(usableWidth / worldWidth, usableHeight / worldDepth) *
                zoom.coerceIn(0.5f, 3f),
    )
}

private fun findSelection(
    plan: FloorPlan,
    point: PlanPoint,
    canvasSize: IntSize,
    zoom: Float,
): PlanSelection? {
    val transform =
        planCanvasTransform(
            plan,
            Size(canvasSize.width.toFloat(), canvasSize.height.toFloat()),
            zoom,
        )
    val threshold = 16f / transform.pixelsPerMeter
    val opening =
        plan.openings
            .mapNotNull { candidate ->
                val wall = plan.walls.firstOrNull { it.id == candidate.wallId }
                    ?: return@mapNotNull null
                candidate to candidate.worldPosition(wall).distanceTo(point)
            }
            .filter { it.second <= threshold }
            .minByOrNull { it.second }
            ?.first
    if (opening != null) {
        return PlanSelection(SelectionKind.OPENING, opening.id)
    }
    val wall =
        plan.walls
            .map { it to it.project(point).distance }
            .filter { it.second <= threshold }
            .minByOrNull { it.second }
            ?.first
    return wall?.let { PlanSelection(SelectionKind.WALL, it.id) }
}

private fun Offset.squaredDistanceTo(other: Offset): Float {
    val dx = x - other.x
    val dy = y - other.y
    return dx * dx + dy * dy
}

private fun FloorPlan.updateOpening(
    id: Int,
    transform: (PlanOpening) -> PlanOpening,
): FloorPlan =
    copy(openings = openings.map { if (it.id == id) transform(it) else it })

private fun formatMeters(value: Float): String =
    String.format(Locale.US, "%.2f m", value)
