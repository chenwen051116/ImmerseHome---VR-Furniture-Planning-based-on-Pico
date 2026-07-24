package com.example.testfull.content

import com.pico.spatial.core.math.Vector3
import org.junit.Assert.assertEquals
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
}
