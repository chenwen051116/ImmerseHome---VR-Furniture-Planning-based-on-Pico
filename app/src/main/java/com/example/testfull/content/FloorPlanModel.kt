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
 * The starter plan: a normal 三室一厅 (three bedrooms + living room) apartment,
 * 9.6 m × 7.6 m. North row: three bedrooms over a corridor; south half: kitchen
 * (west) and living room (east). Main entrance is on the south wall.
 */
internal fun demoFloorPlan(): FloorPlan =
    FloorPlan(
        walls =
            listOf(
                PlanWall(1, PlanPoint(-4.8f, -3.8f), PlanPoint(4.8f, -3.8f)),
                PlanWall(2, PlanPoint(4.8f, -3.8f), PlanPoint(4.8f, 3.8f)),
                PlanWall(3, PlanPoint(4.8f, 3.8f), PlanPoint(-4.8f, 3.8f)),
                PlanWall(4, PlanPoint(-4.8f, 3.8f), PlanPoint(-4.8f, -3.8f)),
                // Bedroom row / corridor divider.
                PlanWall(5, PlanPoint(-4.8f, -0.6f), PlanPoint(4.8f, -0.6f)),
                PlanWall(6, PlanPoint(-1.6f, -3.8f), PlanPoint(-1.6f, -0.6f)),
                PlanWall(7, PlanPoint(1.6f, -3.8f), PlanPoint(1.6f, -0.6f)),
                // Corridor → kitchen wall, and kitchen / living partition.
                PlanWall(8, PlanPoint(-4.8f, 0.6f), PlanPoint(-0.8f, 0.6f)),
                PlanWall(9, PlanPoint(-0.8f, 0.6f), PlanPoint(-0.8f, 3.8f)),
            ),
        openings =
            listOf(
                // Main entrance (south wall, x = 1.0).
                PlanOpening(1, 3, OpeningType.DOOR, 0.396f, 0.95f, 2.1f, 0f),
                // Bedroom doors (from the corridor).
                PlanOpening(2, 5, OpeningType.DOOR, 0.167f, 0.85f, 2.05f, 0f),
                PlanOpening(3, 5, OpeningType.DOOR, 0.5f, 0.85f, 2.05f, 0f),
                PlanOpening(4, 5, OpeningType.DOOR, 0.833f, 0.85f, 2.05f, 0f),
                // Kitchen ↔ corridor, kitchen ↔ living.
                PlanOpening(5, 8, OpeningType.DOOR, 0.5f, 0.85f, 2.05f, 0f),
                PlanOpening(6, 9, OpeningType.DOOR, 0.5f, 0.85f, 2.05f, 0f),
                // Bedroom windows (north wall).
                PlanOpening(7, 1, OpeningType.WINDOW, 0.167f, 1.2f, 1.1f, 0.9f),
                PlanOpening(8, 1, OpeningType.WINDOW, 0.5f, 1.2f, 1.1f, 0.9f),
                PlanOpening(9, 1, OpeningType.WINDOW, 0.833f, 1.2f, 1.1f, 0.9f),
                // Living-room picture window (south wall, x = 2.0) and kitchen window
                // (west wall, z = 2.2).
                PlanOpening(10, 3, OpeningType.WINDOW, 0.292f, 2.2f, 1.2f, 0.8f),
                PlanOpening(11, 4, OpeningType.WINDOW, 0.21f, 1.2f, 1.1f, 0.9f),
            ),
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
