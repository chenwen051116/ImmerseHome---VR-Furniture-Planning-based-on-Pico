package com.example.testfull.content

import kotlin.math.hypot
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

internal const val PLAN_GRID_STEP_METERS = 0.5f

internal data class PlanPoint(val x: Float, val z: Float)

internal data class PlanWall(
    val id: Int,
    val start: PlanPoint,
    val end: PlanPoint,
    val height: Float = 2.8f,
    val thickness: Float = 0.16f,
)

internal enum class OpeningType {
    DOOR,
    WINDOW,
}

internal data class PlanOpening(
    val id: Int,
    val wallId: Int,
    val type: OpeningType,
    val position: Float,
    val width: Float,
    val height: Float,
    val sill: Float,
    val depth: Float = 0.04f,
)

internal data class FloorPlan(
    val walls: List<PlanWall> = emptyList(),
    val openings: List<PlanOpening> = emptyList(),
    val scale: Float = 1f,
    /**
     * Optional human/AI-readable description of the plan's zones (e.g. "west half is the
     * living room, east half is the bedroom"). Included in the AI prompt when present;
     * does not affect geometry.
     */
    val zoneNotes: String? = null,
)

internal data class PlanBounds(
    val minX: Float,
    val minZ: Float,
    val maxX: Float,
    val maxZ: Float,
) {
    val width: Float = max(0.001f, maxX - minX)
    val depth: Float = max(0.001f, maxZ - minZ)
    val centerX: Float = (minX + maxX) / 2f
    val centerZ: Float = (minZ + maxZ) / 2f
}

internal data class WallProjection(
    val point: PlanPoint,
    val position: Float,
    val distance: Float,
)

internal data class WallOpeningRange(
    val opening: PlanOpening,
    val start: Float,
    val end: Float,
)

internal data class WallSolid(
    val start: Float,
    val end: Float,
    val bottom: Float,
    val top: Float,
)

/**
 * The starter plan: a single open studio, 12 m × 6 m. No internal divider — the AI gets one
 * open space to arrange freely, which avoids the multi-room passage-placement problem. Main
 * entrance on the south wall, a 3 m picture window on the south wall for cross light, plus
 * west and north windows. The user can draw their own divider walls in the Room Plan panel if
 * they want separate zones; the multi-room detection (detectRooms + passage-aware clamp)
 * handles that case when it arises.
 */
internal fun demoFloorPlan(): FloorPlan =
    FloorPlan(
        walls =
            listOf(
                PlanWall(1, PlanPoint(-6f, -3f), PlanPoint(6f, -3f)),
                PlanWall(2, PlanPoint(6f, -3f), PlanPoint(6f, 3f)),
                PlanWall(3, PlanPoint(6f, 3f), PlanPoint(-6f, 3f)),
                PlanWall(4, PlanPoint(-6f, 3f), PlanPoint(-6f, -3f)),
            ),
        openings =
            listOf(
                // Main entrance — south wall, west end (x = -4.5).
                PlanOpening(1, 3, OpeningType.DOOR, 0.875f, 0.95f, 2.1f, 0f),
                // 3 m picture window on the south wall (x = -2), low sill.
                PlanOpening(2, 3, OpeningType.WINDOW, 0.667f, 3.0f, 1.6f, 0.6f),
                // West window for cross light (z = 0).
                PlanOpening(3, 4, OpeningType.WINDOW, 0.5f, 1.6f, 1.2f, 0.9f),
                // North window (x = 3.5).
                PlanOpening(4, 1, OpeningType.WINDOW, 0.792f, 1.6f, 1.2f, 0.9f),
            ),
        zoneNotes = null,
    )

internal fun PlanPoint.distanceTo(other: PlanPoint): Float =
    hypot(other.x - x, other.z - z)

internal fun PlanPoint.snapped(step: Float = PLAN_GRID_STEP_METERS): PlanPoint =
    PlanPoint(
        x = round(x / step) * step,
        z = round(z / step) * step,
    )

internal fun PlanWall.length(): Float = start.distanceTo(end)

internal fun PlanWall.angleDegrees(): Float =
    Math.toDegrees(atan2(end.z - start.z, end.x - start.x).toDouble()).toFloat()

internal fun FloorPlan.bounds(): PlanBounds {
    if (walls.isEmpty()) {
        return PlanBounds(-1f, -1f, 1f, 1f)
    }
    val points = walls.flatMap { listOf(it.start, it.end) }
    return PlanBounds(
        minX = points.minOf { it.x },
        minZ = points.minOf { it.z },
        maxX = points.maxOf { it.x },
        maxZ = points.maxOf { it.z },
    )
}

internal fun PlanWall.project(point: PlanPoint): WallProjection {
    val dx = end.x - start.x
    val dz = end.z - start.z
    val lengthSquared = dx * dx + dz * dz
    if (lengthSquared < 0.000001f) {
        return WallProjection(start, 0f, start.distanceTo(point))
    }
    val position =
        (((point.x - start.x) * dx + (point.z - start.z) * dz) / lengthSquared)
            .coerceIn(0f, 1f)
    val projected = PlanPoint(start.x + dx * position, start.z + dz * position)
    return WallProjection(projected, position, projected.distanceTo(point))
}

internal fun PlanOpening.worldPosition(wall: PlanWall): PlanPoint =
    PlanPoint(
        x = wall.start.x + (wall.end.x - wall.start.x) * position,
        z = wall.start.z + (wall.end.z - wall.start.z) * position,
    )

internal fun FloorPlan.normalized(): FloorPlan {
    val normalizedWalls =
        walls
            .filter { it.length() >= 0.2f }
            .map {
                it.copy(
                    height = it.height.coerceAtLeast(0.2f),
                    thickness = it.thickness.coerceAtLeast(0.02f),
                )
            }
    val wallById = normalizedWalls.associateBy { it.id }
    val normalizedOpenings =
        openings.mapNotNull { opening ->
            val wall = wallById[opening.wallId] ?: return@mapNotNull null
            val length = wall.length()
            val width = opening.width.coerceIn(0.1f, length)
            val halfPosition = (width / 2f / length).coerceIn(0f, 0.5f)
            val sill =
                if (opening.type == OpeningType.DOOR) {
                    0f
                } else {
                    opening.sill.coerceIn(0f, (wall.height - 0.1f).coerceAtLeast(0f))
                }
            opening.copy(
                position = opening.position.coerceIn(halfPosition, 1f - halfPosition),
                width = width,
                height = opening.height.coerceIn(0.1f, (wall.height - sill).coerceAtLeast(0.1f)),
                sill = sill,
                depth = opening.depth.coerceIn(0.01f, 0.5f),
            )
        }
    return FloorPlan(
        walls = normalizedWalls,
        openings = normalizedOpenings,
        scale = scale.coerceIn(0.1f, 5f),
    )
}

internal fun FloorPlan.openingRanges(wall: PlanWall): List<WallOpeningRange> {
    val length = wall.length()
    if (length < 0.0001f) return emptyList()
    return openings
        .filter { it.wallId == wall.id }
        .map { opening ->
            val halfWidth = min(opening.width / 2f, length / 2f)
            val center =
                (opening.position * length).coerceIn(halfWidth, length - halfWidth)
            WallOpeningRange(
                opening = opening,
                start = (center - halfWidth).coerceIn(0f, length),
                end = (center + halfWidth).coerceIn(0f, length),
            )
        }
        .filter { it.end - it.start > 0.0001f }
}

internal fun FloorPlan.wallSolids(wallInput: PlanWall): List<WallSolid> {
    val plan = normalized()
    val wall = plan.walls.firstOrNull { it.id == wallInput.id } ?: return emptyList()
    val length = wall.length()
    if (length < 0.0001f) return emptyList()

    val openings = plan.openingRanges(wall)
    val breakpoints =
        (listOf(0f, length) + openings.flatMap { listOf(it.start, it.end) })
            .distinct()
            .sorted()
    val solids = mutableListOf<WallSolid>()

    breakpoints.zipWithNext().forEach { (start, end) ->
        if (end - start < 0.0001f) return@forEach
        val midpoint = (start + end) / 2f
        val verticalHoles =
            openings
                .filter { midpoint >= it.start - 0.0001f && midpoint <= it.end + 0.0001f }
                .map {
                    val bottom =
                        if (it.opening.type == OpeningType.DOOR) 0f else it.opening.sill
                    bottom.coerceIn(0f, wall.height) to
                        (bottom + it.opening.height).coerceIn(0f, wall.height)
                }
                .filter { it.second - it.first > 0.0001f }
                .sortedBy { it.first }

        val mergedHoles = mutableListOf<Pair<Float, Float>>()
        verticalHoles.forEach { hole ->
            val previous = mergedHoles.lastOrNull()
            if (previous == null || hole.first > previous.second + 0.0001f) {
                mergedHoles += hole
            } else {
                mergedHoles[mergedHoles.lastIndex] =
                    previous.first to max(previous.second, hole.second)
            }
        }

        var cursor = 0f
        mergedHoles.forEach { hole ->
            if (hole.first > cursor + 0.0001f) {
                solids += WallSolid(start, end, cursor, hole.first)
            }
            cursor = max(cursor, hole.second)
        }
        if (cursor < wall.height - 0.0001f) {
            solids += WallSolid(start, end, cursor, wall.height)
        }
    }
    return solids
}

internal fun FloorPlan.nextWallId(): Int = (walls.maxOfOrNull { it.id } ?: 0) + 1

internal fun FloorPlan.nextOpeningId(): Int = (openings.maxOfOrNull { it.id } ?: 0) + 1

internal fun FloorPlan.withoutUntouchedStarterForNewWall(): FloorPlan =
    if (this == demoFloorPlan()) FloorPlan() else this

internal fun FloorPlan.connectionPoints(tolerance: Float = 0.001f): List<PlanPoint> {
    val uniquePoints = mutableListOf<PlanPoint>()
    walls.flatMap { listOf(it.start, it.end) }.forEach { point ->
        if (uniquePoints.none { it.distanceTo(point) <= tolerance }) {
            uniquePoints += point
        }
    }
    return uniquePoints
}

internal fun FloorPlan.moveConnectionPoint(
    from: PlanPoint,
    target: PlanPoint,
    tolerance: Float = 0.001f,
): FloorPlan {
    val snappedTarget = target.snapped()
    if (from.distanceTo(snappedTarget) <= tolerance) return this

    fun moveIfConnected(point: PlanPoint): PlanPoint =
        if (point.distanceTo(from) <= tolerance) snappedTarget else point

    val movedWalls =
        walls.map { wall ->
            wall.copy(
                start = moveIfConnected(wall.start),
                end = moveIfConnected(wall.end),
            )
        }
    if (movedWalls.any { it.length() < 0.2f }) return this
    return copy(walls = movedWalls).normalized()
}

internal fun FloorPlan.updateWallGeometry(
    wallId: Int,
    length: Float? = null,
    angleDegrees: Float? = null,
): FloorPlan {
    val wall = walls.firstOrNull { it.id == wallId } ?: return this
    val nextLength = (length ?: wall.length()).coerceAtLeast(0.2f)
    val nextAngle = Math.toRadians((angleDegrees ?: wall.angleDegrees()).toDouble())
    val oldEnd = wall.end
    val nextEnd =
        PlanPoint(
            x = wall.start.x + cos(nextAngle).toFloat() * nextLength,
            z = wall.start.z + sin(nextAngle).toFloat() * nextLength,
        )

    fun moveConnected(point: PlanPoint): PlanPoint =
        if (point.distanceTo(oldEnd) < 0.001f) nextEnd else point

    return copy(
            walls =
                walls.map { candidate ->
                    if (candidate.id == wallId) {
                        candidate.copy(end = nextEnd)
                    } else {
                        candidate.copy(
                            start = moveConnected(candidate.start),
                            end = moveConnected(candidate.end),
                        )
                    }
                }
        )
        .normalized()
}

internal fun FloorPlan.scaledTo(targetScale: Float): FloorPlan {
    val nextScale = targetScale.coerceIn(0.1f, 5f)
    val currentScale = scale.coerceIn(0.1f, 5f)
    val ratio = nextScale / currentScale
    if (kotlin.math.abs(ratio - 1f) < 0.0001f) return copy(scale = nextScale)
    val bounds = bounds()

    fun scalePoint(point: PlanPoint): PlanPoint =
        PlanPoint(
            x = bounds.centerX + (point.x - bounds.centerX) * ratio,
            z = bounds.centerZ + (point.z - bounds.centerZ) * ratio,
        )

    return copy(
            walls =
                walls.map {
                    it.copy(
                        start = scalePoint(it.start),
                        end = scalePoint(it.end),
                        height = it.height * ratio,
                        thickness = it.thickness * ratio,
                    )
                },
            openings =
                openings.map {
                    it.copy(
                        width = it.width * ratio,
                        height = it.height * ratio,
                        sill = it.sill * ratio,
                        depth = it.depth * ratio,
                    )
                },
            scale = nextScale,
        )
        .normalized()
}

internal fun FloorPlan.resizedFootprint(
    targetWidth: Float = bounds().width,
    targetDepth: Float = bounds().depth,
): FloorPlan {
    if (walls.isEmpty()) return this
    val bounds = bounds()
    val scaleX = targetWidth.coerceAtLeast(0.2f) / bounds.width
    val scaleZ = targetDepth.coerceAtLeast(0.2f) / bounds.depth

    fun resizePoint(point: PlanPoint): PlanPoint =
        PlanPoint(
            x = bounds.centerX + (point.x - bounds.centerX) * scaleX,
            z = bounds.centerZ + (point.z - bounds.centerZ) * scaleZ,
        )

    return copy(
            walls =
                walls.map {
                    it.copy(
                        start = resizePoint(it.start),
                        end = resizePoint(it.end),
                    )
                }
        )
        .normalized()
}

/* --- Footprint containment: keep placed furniture inside the building --- */

/** Horizontal distance from [point] to the nearest wall segment, in meters. */
internal fun distanceToWalls(walls: List<PlanWall>, point: PlanPoint): Float =
    walls.minOfOrNull { it.project(point).distance } ?: Float.MAX_VALUE

/**
 * Even-odd point-in-polygon test across all wall segments (a horizontal ray toward +X).
 * Works for closed loops and disjoint loops; returns false for an empty plan.
 */
internal fun isInsideFootprint(walls: List<PlanWall>, point: PlanPoint): Boolean {
    if (walls.isEmpty()) return false
    var crossings = 0
    walls.forEach { wall ->
        val a = wall.start
        val b = wall.end
        if ((a.z > point.z) != (b.z > point.z)) {
            val xCross = a.x + (point.z - a.z) * (b.x - a.x) / (b.z - a.z)
            if (xCross > point.x) crossings += 1
        }
    }
    return crossings % 2 == 1
}

private fun footprintConstraintOk(walls: List<PlanWall>, point: PlanPoint, margin: Float): Boolean =
    isInsideFootprint(walls, point) && distanceToWalls(walls, point) >= margin

/**
 * Clamps [point] to the building footprint: the result is inside the wall polygon and at least
 * [margin] meters away from every wall. Points already satisfying the constraint are returned
 * unchanged; others are moved toward the wall centroid in small steps until they satisfy it.
 * With no walls the point is returned unchanged (no constraint).
 */
internal fun clampToFootprint(walls: List<PlanWall>, point: PlanPoint, margin: Float): PlanPoint {
    if (walls.isEmpty()) return point
    if (footprintConstraintOk(walls, point, margin)) return point

    val centroidX =
        walls.sumOf { (it.start.x + it.end.x).toDouble() }.toFloat() / (walls.size * 2)
    val centroidZ =
        walls.sumOf { (it.start.z + it.end.z).toDouble() }.toFloat() / (walls.size * 2)

    var current = point
    repeat(500) {
        if (footprintConstraintOk(walls, current, margin)) return current
        current =
            PlanPoint(
                x = current.x + (centroidX - current.x) * 0.02f,
                z = current.z + (centroidZ - current.z) * 0.02f,
            )
    }
    return PlanPoint(centroidX, centroidZ)
}

/* --- Multi-room segmentation: detect separate zones even with open passages --- */

/**
 * One zone segmented from the floor plan. A "room" is a region mostly enclosed by walls —
 * it does NOT need to be fully closed. An open passage in a divider (e.g. a 2.4 m gap between
 * two divider wall segments) still splits the plan into two rooms, because the gap is treated
 * as a soft boundary. Each room carries its own bounds, centroid and bounding walls so the AI
 * can place furniture per-zone instead of using the global center.
 */
internal data class DetectedRoom(
    val id: Int,
    val bounds: PlanBounds,
    val centroid: PlanPoint,
    /** Footprint area in square meters (approximate, from grid cells). */
    val areaSqm: Float,
    /** Wall IDs that form this room's perimeter (outer + divider walls touching the zone). */
    val boundingWallIds: List<Int>,
)

private const val ROOM_GRID_STEP_METERS = 0.25f
private const val ROOM_MIN_AREA_SQM = 1.5f
private const val ROOM_WALL_MARGIN = 0.09f
/** Max gap between two dangling wall endpoints that still counts as a room divider. */
private const val PASSAGE_MAX_METERS = 4.0f

/**
 * Computes the "virtual passage" segments that bridge open gaps in dividers. A passage
 * segment connects two dangling wall endpoints (endpoints shared by exactly one wall) that
 * lie inside the footprint and are within [PASSAGE_MAX_METERS] of each other. These segments
 * represent the open doorway/passage between two zones of a multi-room plan.
 *
 * Used both by [detectRooms] (to block flood-fill across the gap so the two zones split) and
 * by the multi-room [clampToFootprint] (to reject furniture placed in the passage, since a
 * point in the passage passes the standard footprint + wall-distance checks).
 */
internal fun computePassageSegments(walls: List<PlanWall>): List<Pair<PlanPoint, PlanPoint>> {
    if (walls.size < 3) return emptyList()
    val endpointUsage = mutableMapOf<PlanPoint, Int>()
    walls.forEach { wall ->
        endpointUsage[wall.start] = (endpointUsage[wall.start] ?: 0) + 1
        endpointUsage[wall.end] = (endpointUsage[wall.end] ?: 0) + 1
    }
    val dangling = endpointUsage.keys.filter { endpointUsage[it]!! == 1 }
    val passages = mutableListOf<Pair<PlanPoint, PlanPoint>>()
    val usedDangling = mutableSetOf<PlanPoint>()
    // Greedily pair each dangling endpoint with its nearest unused dangling neighbor whose
    // midpoint is inside the footprint (avoids connecting two points across an outer wall).
    dangling.forEach { a ->
        if (a in usedDangling) return@forEach
        var bestB: PlanPoint? = null
        var bestDist = PASSAGE_MAX_METERS
        dangling.forEach { b ->
            if (b == a || b in usedDangling) return@forEach
            val d = a.distanceTo(b)
            if (d <= 0.1f || d >= bestDist) return@forEach
            val mid = PlanPoint((a.x + b.x) / 2f, (a.z + b.z) / 2f)
            if (!isInsideFootprint(walls, mid)) return@forEach
            bestDist = d
            bestB = b
        }
        bestB?.let { b ->
            passages += a to b
            usedDangling += a
            usedDangling += b
        }
    }
    return passages
}

/** Distance from [point] to the segment [a]→[b], clamped to the segment's extent. */
private fun distanceToSegment(point: PlanPoint, a: PlanPoint, b: PlanPoint): Float {
    val dx = b.x - a.x
    val dz = b.z - a.z
    val lenSq = dx * dx + dz * dz
    if (lenSq < 1e-6f) return point.distanceTo(a)
    val t = (((point.x - a.x) * dx + (point.z - a.z) * dz) / lenSq).coerceIn(0f, 1f)
    val px = a.x + dx * t
    val pz = a.z + dz * t
    return hypot(point.x - px, point.z - pz)
}

/**
 * True when [point] is within [margin] meters of any passage segment — i.e. the point sits in
 * an open doorway/passage between rooms and should NOT host furniture.
 */
private fun isNearPassage(
    passages: List<Pair<PlanPoint, PlanPoint>>,
    point: PlanPoint,
    margin: Float,
): Boolean = passages.any { (a, b) -> distanceToSegment(point, a, b) < margin }

/**
 * Segments the floor plan into individual rooms using flood-fill on a uniform grid. A cell is
 * blocked when it lies on a wall OR on a "virtual passage" — a synthetic segment joining two
 * dangling wall endpoints (endpoints not shared by another wall) that lie inside the footprint
 * and are within [PASSAGE_MAX_METERS] of each other. The virtual passage is what lets an open
 * gap in a divider (like the demo plan's 2.4 m passage at x = 1) still split the two zones.
 *
 * Regions smaller than [ROOM_MIN_AREA_SQM] are discarded as noise. The result is sorted by
 * area (largest first) so callers can treat index 0 as the primary room. Returns an empty
 * list when the plan is too small or the grid would be too large for the emulator.
 */
internal fun detectRooms(plan: FloorPlan): List<DetectedRoom> {
    val walls = plan.walls
    if (walls.size < 3) return emptyList()
    val bounds = plan.bounds()
    val step = ROOM_GRID_STEP_METERS
    val margin = ROOM_WALL_MARGIN
    val minX = bounds.minX - margin
    val maxX = bounds.maxX + margin
    val minZ = bounds.minZ - margin
    val maxZ = bounds.maxZ + margin
    val cols = ((maxX - minX) / step).toInt().coerceAtLeast(1)
    val rows = ((maxZ - minZ) / step).toInt().coerceAtLeast(1)
    // Cap grid size so flood-fill stays cheap on the emulator.
    if (cols > 400 || rows > 400) return emptyList()

    // Blocking segments: real walls + virtual passages (shared computation with the clamp).
    val blockingSegments = walls.map { it.start to it.end }.toMutableList()
    blockingSegments += computePassageSegments(walls)

    // --- Mark blocked cells. ---
    val blocked = BooleanArray(cols * rows)
    for (row in 0 until rows) {
        for (col in 0 until cols) {
            val x = minX + (col + 0.5f) * step
            val z = minZ + (row + 0.5f) * step
            val point = PlanPoint(x, z)
            if (!isInsideFootprint(walls, point)) {
                blocked[row * cols + col] = true
                continue
            }
            val onSegment = blockingSegments.any { seg ->
                val dx = seg.second.x - seg.first.x
                val dz = seg.second.z - seg.first.z
                val lenSq = dx * dx + dz * dz
                if (lenSq < 1e-6f) {
                    point.distanceTo(seg.first) < margin
                } else {
                    val t = (((point.x - seg.first.x) * dx + (point.z - seg.first.z) * dz) / lenSq)
                        .coerceIn(0f, 1f)
                    val px = seg.first.x + dx * t
                    val pz = seg.first.z + dz * t
                    hypot(point.x - px, point.z - pz) < margin
                }
            }
            if (onSegment) blocked[row * cols + col] = true
        }
    }

    // --- Flood-fill unblocked cells; collect each connected region. ---
    val visited = BooleanArray(cols * rows)
    val rooms = mutableListOf<DetectedRoom>()
    var roomId = 1
    for (startIdx in 0 until blocked.size) {
        if (visited[startIdx] || blocked[startIdx]) continue
        val stack = ArrayDeque<Int>()
        stack.addLast(startIdx)
        val cells = ArrayList<Int>(256)
        while (stack.isNotEmpty()) {
            val idx = stack.removeLast()
            if (visited[idx] || blocked[idx]) continue
            visited[idx] = true
            cells += idx
            val col = idx % cols
            val row = idx / cols
            if (col > 0) stack.addLast(idx - 1)
            if (col < cols - 1) stack.addLast(idx + 1)
            if (row > 0) stack.addLast(idx - cols)
            if (row < rows - 1) stack.addLast(idx + cols)
        }
        if (cells.isEmpty()) continue

        // Bounds + centroid of the region (in world meters).
        var minXc = Float.MAX_VALUE
        var maxXc = -Float.MAX_VALUE
        var minZc = Float.MAX_VALUE
        var maxZc = -Float.MAX_VALUE
        var sumX = 0f
        var sumZ = 0f
        val cellSet = HashSet<Int>(cells.size).apply { cells.forEach { add(it) } }
        cells.forEach { idx ->
            val col = idx % cols
            val row = idx / cols
            val x = minX + (col + 0.5f) * step
            val z = minZ + (row + 0.5f) * step
            if (x < minXc) minXc = x
            if (x > maxXc) maxXc = x
            if (z < minZc) minZc = z
            if (z > maxZc) maxZc = z
            sumX += x
            sumZ += z
        }
        val area = cells.size * step * step
        if (area < ROOM_MIN_AREA_SQM) continue  // discard tiny regions
        val centroid = PlanPoint(sumX / cells.size, sumZ / cells.size)

        // Identify bounding walls: any wall that passes within `margin + step` of any region
        // cell. We sample along each wall and check a small neighborhood in the cell set.
        val nearRadius = 2  // cells (~0.5 m at 0.25 step)
        fun nearRegion(x: Float, z: Float): Boolean {
            val col = ((x - minX) / step).toInt()
            val row = ((z - minZ) / step).toInt()
            for (dr in -nearRadius..nearRadius) {
                for (dc in -nearRadius..nearRadius) {
                    val r = row + dr
                    val c = col + dc
                    if (r in 0 until rows && c in 0 until cols && cellSet.contains(r * cols + c)) {
                        return true
                    }
                }
            }
            return false
        }
        val boundingWallIds = walls.mapNotNull { wall ->
            val len = wall.length()
            val samples = max(2, (len / step).toInt() + 1)
            for (i in 0..samples) {
                val t = i.toFloat() / samples
                val x = wall.start.x + (wall.end.x - wall.start.x) * t
                val z = wall.start.z + (wall.end.z - wall.start.z) * t
                if (nearRegion(x, z)) return@mapNotNull wall.id
            }
            null
        }
        rooms += DetectedRoom(
            id = roomId++,
            bounds = PlanBounds(minXc, minZc, maxXc, maxZc),
            centroid = centroid,
            areaSqm = area,
            boundingWallIds = boundingWallIds,
        )
    }
    return rooms.sortedByDescending { it.areaSqm }
}

/**
 * Clamps [point] to the building footprint using the nearest detected room's centroid as the
 * pull target (instead of the global wall centroid). This prevents furniture placed in one
 * zone from being dragged through a divider wall into another zone when the AI's coordinates
 * land on or near a wall. Also rejects points that sit in an open passage between rooms (the
 * gap in a divider), pushing them toward the nearest room's centroid — a point in the passage
 * passes the standard footprint + wall-distance checks because the passage is inside the outer
 * polygon and far from real walls, but furniture should never block the walkway between zones.
 * Falls back to the global centroid when [rooms] is empty.
 */
internal fun clampToFootprint(
    walls: List<PlanWall>,
    point: PlanPoint,
    margin: Float,
    rooms: List<DetectedRoom>,
): PlanPoint {
    if (walls.isEmpty()) return point
    val passages = computePassageSegments(walls)
    // A point is "ok" when it's inside the footprint, far enough from walls, AND not sitting
    // in an open passage between rooms. The passage check uses the same margin so large
    // furniture is kept further from the walkway (its edge must clear the passage, just as
    // it must clear a wall).
    fun constraintOk(p: PlanPoint): Boolean =
        footprintConstraintOk(walls, p, margin) && !isNearPassage(passages, p, margin)

    if (constraintOk(point)) return point
    if (rooms.isEmpty()) return clampToFootprint(walls, point, margin)
    // Pick the room whose centroid is closest to the requested point — that's the zone the
    // AI most likely intended. A small bias toward larger rooms breaks ties in favor of the
    // primary zone when the point sits exactly on a divider.
    val nearest = rooms.minByOrNull { it.centroid.distanceTo(point) - it.areaSqm * 0.01f }
        ?: return clampToFootprint(walls, point, margin)
    var current = point
    repeat(500) {
        if (constraintOk(current)) return current
        current =
            PlanPoint(
                x = current.x + (nearest.centroid.x - current.x) * 0.02f,
                z = current.z + (nearest.centroid.z - current.z) * 0.02f,
            )
    }
    return PlanPoint(nearest.centroid.x, nearest.centroid.z)
}
