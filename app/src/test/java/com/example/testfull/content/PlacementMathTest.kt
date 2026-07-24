package com.example.testfull.content

import com.pico.spatial.core.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacementMathTest {
    @Test
    fun navigationLocalPointSubtractsTheVirtualWalkOffset() {
        // Virtual walk at (x=2, z=-3) places the navigation root at (-2, 0, 3) in scene space.
        val navOffset = Vector3(-2f, 0f, 3f)
        val scenePoint = Vector3(1.5f, 0.4f, -1f)

        val local = navigationLocalPoint(scenePoint, navOffset)

        assertEquals(3.5f, local.x, 0.0001f)
        assertEquals(0.4f, local.y, 0.0001f)
        assertEquals(-4f, local.z, 0.0001f)
    }

    @Test
    fun restPositionKeepsAimXZAndLiftsPivotAboveSupport() {
        val anchor = Vector3(1f, 1.6f, -2f)

        // Centered-pivot model (bottom 0.25 below pivot) resting on the floor at y=0.
        val centered = computeRestPosition(anchor, supportY = 0f, bottomOffset = 0.25f, scale = 1f)
        assertEquals(1f, centered.x, 0.0001f)
        assertEquals(0.25f, centered.y, 0.0001f)
        assertEquals(-2f, centered.z, 0.0001f)

        // Bottom-pivot model at 200% scale stacked on a 0.8 m table: offset scales too.
        val stacked = computeRestPosition(anchor, supportY = 0.8f, bottomOffset = 0.25f, scale = 2f)
        assertEquals(1.3f, stacked.y, 0.0001f)

        val bottomPivot = computeRestPosition(anchor, supportY = 0.8f, bottomOffset = 0f, scale = 2f)
        assertEquals(0.8f, bottomPivot.y, 0.0001f)
    }

    @Test
    fun yawFacingUserTurnsModelForwardAxisTowardTheUser() {
        val from = Vector3(1f, 0f, 1f)

        // User due +Z of the object: no rotation needed (glTF forward is +Z).
        assertEquals(0f, yawFacingUserDegrees(from, Vector3(1f, 0f, 5f)), 0.001f)
        // User due +X: rotate 90° so +Z swings to +X.
        assertEquals(90f, yawFacingUserDegrees(from, Vector3(5f, 0f, 1f)), 0.001f)
        // User behind (-Z): face backwards.
        assertEquals(180f, kotlin.math.abs(yawFacingUserDegrees(from, Vector3(1f, 0f, -5f))), 0.001f)
        // Coincident points: stable 0.
        assertEquals(0f, yawFacingUserDegrees(from, from), 0.001f)
    }

    @Test
    fun overlappingBoxesAreDetectedOnlyWhenTheyInterpenetrate() {
        val box = YawBox(0f, 0.5f, 0f, 0.5f, 0.5f, 0.5f, 0f)

        // Identical pose: fully overlapping.
        assertTrue(yawBoxesOverlap(box, box, 0.01f))
        // 1.2 m apart on X (each reaches ±0.5): separated.
        assertFalse(yawBoxesOverlap(box, box.copy(centerX = 1.2f), 0.01f))
        // 0.9 m apart: interpenetrating.
        assertTrue(yawBoxesOverlap(box, box.copy(centerX = 0.9f), 0.01f))
        // Exactly touching (1.0 m apart): contact, not overlap — dropping on top must stay legal.
        assertFalse(yawBoxesOverlap(box, box.copy(centerX = 1.0f), 0.01f))
    }

    @Test
    fun stackedBoxesRestingOnEachOtherDoNotOverlap() {
        val bottom = YawBox(0f, 0.25f, 0f, 0.5f, 0.25f, 0.5f, 0f)
        val restingOnTop = YawBox(0f, 0.75f, 0f, 0.4f, 0.25f, 0.4f, 30f)

        assertFalse(yawBoxesOverlap(bottom, restingOnTop, 0.01f))
        // Sunk 10 cm into the lower box: overlap.
        assertTrue(yawBoxesOverlap(bottom, restingOnTop.copy(centerY = 0.65f), 0.01f))
    }

    @Test
    fun rotatedBoxesUseTheirTrueFootprint() {
        val square = YawBox(0f, 0.5f, 0f, 0.5f, 0.5f, 0.5f, 0f)
        val neighborAt1m1 = YawBox(1.1f, 0.5f, 0f, 0.5f, 0.5f, 0.5f, 0f)

        // Axis-aligned: 0.5 + 0.5 = 1.0 < 1.1 → separated.
        assertFalse(yawBoxesOverlap(square, neighborAt1m1, 0.01f))
        // Rotated 45° the square reaches |x| = √0.5 ≈ 0.707 → the same neighbor now overlaps.
        assertTrue(yawBoxesOverlap(square.copy(yawDegrees = 45f), neighborAt1m1, 0.01f))
    }

    @Test
    fun yawBoxForRotatesAndScalesTheBoundingBoxCenterAroundThePivot() {
        // Model bbox center 1 m ahead of the pivot on +Z, pivot at the world origin.
        val center = Vector3(0f, 0.5f, 1f)
        val half = Vector3(0.2f, 0.5f, 0.4f)

        val straight = yawBoxFor(Vector3.ZERO, 0f, 1f, center, half)
        assertEquals(0f, straight.centerX, 0.0001f)
        assertEquals(0.5f, straight.centerY, 0.0001f)
        assertEquals(1f, straight.centerZ, 0.0001f)

        // Yawed 90° the +Z offset swings to +X; at 200% scale everything doubles.
        val turned = yawBoxFor(Vector3.ZERO, 90f, 2f, center, half)
        assertEquals(2f, turned.centerX, 0.001f)
        assertEquals(1f, turned.centerY, 0.001f)
        assertEquals(0f, turned.centerZ, 0.001f)
        assertEquals(0.4f, turned.halfX, 0.0001f)
        assertEquals(0.8f, turned.halfZ, 0.0001f)
    }
}
