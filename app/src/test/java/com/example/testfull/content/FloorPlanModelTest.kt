package com.example.testfull.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloorPlanModelTest {
    @Test
    fun defaultRoomIsNinePointSixBySevenPointSixMeters() {
        val bounds = demoFloorPlan().bounds()

        assertEquals(9.6f, bounds.width, 0.001f)
        assertEquals(7.6f, bounds.depth, 0.001f)
    }

    @Test
    fun startingNewWallClearsOnlyTheUntouchedStarterPlan() {
        val starter = demoFloorPlan()
        val modifiedStarter = starter.resizedFootprint(targetWidth = 14f)

        assertTrue(starter.withoutUntouchedStarterForNewWall().walls.isEmpty())
        assertEquals(
            modifiedStarter,
            modifiedStarter.withoutUntouchedStarterForNewWall(),
        )
    }

    @Test
    fun doorSplitsWallAndLeavesHeader() {
        val plan = demoFloorPlan()
        // The main entrance lives on the south outer wall (id 3), 3.8 m along it.
        val solids = plan.wallSolids(plan.walls.first { it.id == 3 })

        assertTrue(solids.any { it.bottom >= 2.09f })
        assertFalse(
            solids.any {
                it.start < 4.2f &&
                    it.end > 3.4f &&
                    it.bottom == 0f
            }
        )
    }

    @Test
    fun windowLeavesSillAndLintel() {
        val plan = demoFloorPlan()
        // The middle bedroom's window sits 4.8 m along the north wall (id 1).
        val solids = plan.wallSolids(plan.walls.first { it.id == 1 })

        assertTrue(
            solids.any {
                it.start < 4.8f &&
                    it.end > 4.8f &&
                    it.bottom == 0f &&
                    kotlin.math.abs(it.top - 0.9f) < 0.001f
            }
        )
        assertTrue(
            solids.any {
                it.start < 4.8f &&
                    it.end > 4.8f &&
                    kotlin.math.abs(it.bottom - 2f) < 0.001f &&
                    kotlin.math.abs(it.top - 2.8f) < 0.001f
            }
        )
    }

    @Test
    fun normalizationClampsOpeningsToTheirWall() {
        val plan =
            FloorPlan(
                    walls =
                        listOf(
                            PlanWall(
                                id = 1,
                                start = PlanPoint(0f, 0f),
                                end = PlanPoint(2f, 0f),
                            )
                        ),
                    openings =
                        listOf(
                            PlanOpening(
                                id = 1,
                                wallId = 1,
                                type = OpeningType.WINDOW,
                                position = 0f,
                                width = 8f,
                                height = 8f,
                                sill = -1f,
                            )
                        ),
                )
                .normalized()

        assertEquals(2f, plan.openings.single().width, 0.001f)
        assertEquals(0.5f, plan.openings.single().position, 0.001f)
        assertEquals(0f, plan.openings.single().sill, 0.001f)
        assertEquals(2.8f, plan.openings.single().height, 0.001f)
    }

    @Test
    fun changingWallLengthMovesItsConnectedEndpoint() {
        val plan = demoFloorPlan().updateWallGeometry(wallId = 1, length = 8f)

        assertEquals(8f, plan.walls[0].length(), 0.001f)
        assertEquals(plan.walls[0].end.x, plan.walls[1].start.x, 0.001f)
        assertEquals(plan.walls[0].end.z, plan.walls[1].start.z, 0.001f)
    }

    @Test
    fun wholePlanScaleChangesGeometryAndOpeningDimensions() {
        val plan = demoFloorPlan().scaledTo(2f)

        assertEquals(2f, plan.scale, 0.001f)
        assertEquals(19.2f, plan.bounds().width, 0.001f)
        assertEquals(15.2f, plan.bounds().depth, 0.001f)
        assertEquals(5.6f, plan.walls.first().height, 0.001f)
        assertEquals(1.9f, plan.openings.first().width, 0.001f)
    }

    @Test
    fun footprintCanBeSetToExplicitWidthAndDepth() {
        val plan = demoFloorPlan().resizedFootprint(targetWidth = 10f, targetDepth = 7f)

        assertEquals(10f, plan.bounds().width, 0.001f)
        assertEquals(7f, plan.bounds().depth, 0.001f)
    }

    @Test
    fun draggingConnectionPointMovesEveryConnectedWallAndSnapsToHalfMeterGrid() {
        val plan = demoFloorPlan()
        val oldCorner = PlanPoint(4.8f, -3.8f)
        val moved =
            plan.moveConnectionPoint(
                from = oldCorner,
                target = PlanPoint(5.23f, -3.74f),
            )

        val expected = PlanPoint(5f, -3.5f)
        assertEquals(expected, moved.walls[0].end)
        assertEquals(expected, moved.walls[1].start)
        assertEquals(PLAN_GRID_STEP_METERS, 0.5f, 0.001f)
    }
}
