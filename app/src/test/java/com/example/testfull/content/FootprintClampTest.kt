package com.example.testfull.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FootprintClampTest {
    // 15 × 8 m rectangle centered at the origin (the demo room, navigation-local).
    private val roomWalls =
        listOf(
            PlanWall(1, PlanPoint(-7.5f, -4f), PlanPoint(7.5f, -4f)),
            PlanWall(2, PlanPoint(7.5f, -4f), PlanPoint(7.5f, 4f)),
            PlanWall(3, PlanPoint(7.5f, 4f), PlanPoint(-7.5f, 4f)),
            PlanWall(4, PlanPoint(-7.5f, 4f), PlanPoint(-7.5f, -4f)),
        )

    @Test
    fun insideDetectionMatchesRectangle() {
        assertTrue(isInsideFootprint(roomWalls, PlanPoint(0f, 0f)))
        assertTrue(isInsideFootprint(roomWalls, PlanPoint(7.4f, 3.9f)))
        assertFalse(isInsideFootprint(roomWalls, PlanPoint(8f, 0f)))
        assertFalse(isInsideFootprint(roomWalls, PlanPoint(0f, 5f)))
        assertFalse(isInsideFootprint(roomWalls, PlanPoint(-8f, -5f)))
        assertFalse(isInsideFootprint(emptyList(), PlanPoint(0f, 0f)))
    }

    @Test
    fun insidePointsAreReturnedUnchanged() {
        val point = PlanPoint(2f, -1f)
        assertEquals(point, clampToFootprint(roomWalls, point, margin = 0.2f))
    }

    @Test
    fun outsidePointsArePulledInsideWithMargin() {
        // Aimed 5 m beyond the +X wall (e.g. through a window).
        val clamped = clampToFootprint(roomWalls, PlanPoint(12.5f, 1f), margin = 0.13f)

        assertTrue(isInsideFootprint(roomWalls, clamped))
        assertTrue(distanceToWalls(roomWalls, clamped) >= 0.13f - 0.001f)
        // Pulled back roughly toward the wall it escaped through, not teleported elsewhere.
        assertEquals(1f, clamped.z, 0.6f)
        assertTrue(clamped.x in 6.8f..7.37f)
    }

    @Test
    fun pointsTooCloseToAWallArePushedOffIt() {
        // 2 cm from the -Z wall: inside, but violates the margin.
        val clamped = clampToFootprint(roomWalls, PlanPoint(0f, -3.98f), margin = 0.13f)

        assertTrue(isInsideFootprint(roomWalls, clamped))
        assertTrue(distanceToWalls(roomWalls, clamped) >= 0.13f - 0.001f)
    }

    @Test
    fun emptyPlanImposesNoConstraint() {
        val point = PlanPoint(50f, 50f)
        assertEquals(point, clampToFootprint(emptyList(), point, margin = 1f))
    }
}
