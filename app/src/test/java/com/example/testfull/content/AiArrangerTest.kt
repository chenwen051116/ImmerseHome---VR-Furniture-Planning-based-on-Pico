package com.example.testfull.content

import com.pico.spatial.core.math.Vector3
import java.io.File
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiArrangerTest {
    /** Simple 15×8 rectangle for placement tests (independent of the demo plan). */
    private val walls =
        listOf(
            PlanWall(1, PlanPoint(-7.5f, -4f), PlanPoint(7.5f, -4f)),
            PlanWall(2, PlanPoint(7.5f, -4f), PlanPoint(7.5f, 4f)),
            PlanWall(3, PlanPoint(7.5f, 4f), PlanPoint(-7.5f, 4f)),
            PlanWall(4, PlanPoint(-7.5f, 4f), PlanPoint(-7.5f, -4f)),
        )

    /** Half wall thickness + clearance, matching HomeStage.localFootprint for the demo plan. */
    private val baseMargin = 0.16f / 2f + 0.05f

    private val chair =
        CatalogModel(
            file = File("dining-chair.glb"),
            displayName = "dining-chair",
            center = Vector3(0f, 0.45f, 0f),
            halfExtents = Vector3(0.25f, 0.45f, 0.25f),
            bottomOffset = 0f,
            details = "{\"color\":\"oak\",\"style\":\"modern\"}",
        )
    private val sofa =
        CatalogModel(
            file = File("mid-century-sofa.glb"),
            displayName = "mid-century-sofa",
            center = Vector3(0f, 0.425f, 0f),
            halfExtents = Vector3(1.0f, 0.425f, 0.45f),
            bottomOffset = 0f,
        )
    private val catalog = listOf(chair, sofa)

    @Test
    fun parseAiLayoutReadsPlacementsWithDefaults() {
        val content =
            """
            {"placements":[
              {"model":"dining-chair","x":1.5,"z":-2.0},
              {"model":"mid-century-sofa","x":0,"z":3,"yaw":90,"scale":1.5}
            ],"notes":"done"}
            """.trimIndent()

        val layout = parseAiLayout(content)

        assertEquals(2, layout.placements.size)
        assertEquals("done", layout.notes)
        val first = layout.placements[0]
        assertEquals("dining-chair", first.modelName)
        assertEquals(1.5f, first.x, 0.0001f)
        assertEquals(-2f, first.z, 0.0001f)
        assertEquals(0f, first.yawDegrees, 0.0001f)
        assertEquals(1f, first.scale, 0.0001f)
        val second = layout.placements[1]
        assertEquals(90f, second.yawDegrees, 0.0001f)
        assertEquals(1.5f, second.scale, 0.0001f)
    }

    @Test
    fun parseAiLayoutSkipsEntriesWithoutModelOrNumbers() {
        val content =
            """
            {"placements":[
              {"x":1,"z":2},
              {"model":"dining-chair"},
              {"model":"dining-chair","x":1,"z":2}
            ]}
            """.trimIndent()

        val layout = parseAiLayout(content)

        assertEquals(1, layout.placements.size)
        assertNull(layout.notes)
    }

    @Test
    fun parseAiLayoutRejectsMalformedAnswers() {
        assertThrows(AiArrangeException::class.java) { parseAiLayout("not json at all") }
        assertThrows(AiArrangeException::class.java) { parseAiLayout("{\"foo\":1}") }
    }

    @Test
    fun resolveCatalogModelMatchesExactThenSubstring() {
        assertEquals(chair, resolveCatalogModel("Dining-Chair", catalog))
        assertEquals(sofa, resolveCatalogModel("sofa", catalog))
        assertEquals(chair, resolveCatalogModel("chair", catalog))
        assertNull(resolveCatalogModel("dragon", catalog))
        assertNull(resolveCatalogModel("", catalog))
    }

    @Test
    fun resolveAiPlacementsClampsOutsidePointsIntoFootprint() {
        val layout = AiLayout(listOf(AiPlacement("dining-chair", x = 100f, z = 0f, 0f, 1f)), null)

        val resolved = resolveAiPlacements(layout, catalog, walls, baseMargin)

        assertEquals(1, resolved.accepted.size)
        assertTrue(resolved.skipped.isEmpty())
        val placed = resolved.accepted.single()
        val point = PlanPoint(placed.x, placed.z)
        assertTrue(isInsideFootprint(walls, point))
        val halfDiagonal = hypot(0.25, 0.25).toFloat()
        assertTrue(
            "clearance ${distanceToWalls(walls, point)} >= ${baseMargin + halfDiagonal}",
            distanceToWalls(walls, point) >= baseMargin + halfDiagonal - 0.01f,
        )
    }

    @Test
    fun resolveAiPlacementsScalesTheWallClearance() {
        val layout = AiLayout(listOf(AiPlacement("dining-chair", x = 100f, z = 0f, 0f, 2f)), null)

        val placed = resolveAiPlacements(layout, catalog, walls, baseMargin).accepted.single()

        val halfDiagonal = hypot(0.25, 0.25).toFloat() * 2f
        assertTrue(
            distanceToWalls(walls, PlanPoint(placed.x, placed.z)) >=
                baseMargin + halfDiagonal - 0.01f,
        )
    }

    @Test
    fun resolveAiPlacementsNudgesOverlappingItemAside() {
        val layout =
            AiLayout(
                listOf(
                    AiPlacement("dining-chair", x = 0f, z = 0f, 0f, 1f),
                    // Nearly the same spot: overlaps the chair and must be nudged, not skipped.
                    AiPlacement("mid-century-sofa", x = 0.1f, z = 0f, 0f, 1f),
                    AiPlacement("dragon", x = 3f, z = 0f, 0f, 1f),
                ),
                null,
            )

        val resolved = resolveAiPlacements(layout, catalog, walls, baseMargin)

        assertEquals(2, resolved.accepted.size)
        assertEquals(1, resolved.skipped.size)
        assertTrue(resolved.skipped.single().contains("not in the model library"))
        assertEquals(1, resolved.adjusted.size)
        assertTrue(resolved.adjusted.single().contains("mid-century-sofa"))

        // The nudged sofa moved from the requested spot, and the boxes no longer intersect.
        val sofa = resolved.accepted[1]
        assertTrue(kotlin.math.abs(sofa.x - 0.1f) > 0.001f || kotlin.math.abs(sofa.z) > 0.001f)
        val chairBox = boxOf(resolved.accepted[0])
        val sofaBox = boxOf(sofa)
        assertFalse(yawBoxesOverlap(chairBox, sofaBox, 0f))
    }

    @Test
    fun resolveAiPlacementsSkipsWhenNoFreeSpace() {
        val giant =
            CatalogModel(
                file = File("giant.glb"),
                displayName = "giant",
                center = Vector3(0f, 1f, 0f),
                halfExtents = Vector3(7f, 1f, 3.9f),
                bottomOffset = 0f,
            )
        val giants = listOf(giant)
        val layout =
            AiLayout(
                listOf(
                    AiPlacement("giant", x = 0f, z = 0f, 0f, 1f),
                    AiPlacement("giant", x = 0f, z = 0f, 0f, 1f),
                ),
                null,
            )

        val resolved = resolveAiPlacements(layout, giants, walls, baseMargin)

        assertEquals(1, resolved.accepted.size)
        assertEquals(1, resolved.skipped.size)
        assertTrue(resolved.skipped.single().contains("no free space"))
    }

    @Test
    fun parseAiLayoutReadsTextureChoices() {
        val content =
            """
            {"placements":[{"model":"dining-chair","x":1,"z":2}],
             "textures":{"wall":"Red Brick Wall","window":"white-frame-window","ceiling":" "}}
            """.trimIndent()

        val layout = parseAiLayout(content)

        assertEquals("Red Brick Wall", layout.textures[SurfaceSlot.WALL])
        assertEquals("white-frame-window", layout.textures[SurfaceSlot.WINDOW])
        // Blank values are ignored.
        assertFalse(layout.textures.containsKey(SurfaceSlot.CEILING))
    }

    @Test
    fun resolveAiTexturesEnforcesNamesAndSurfaces() {
        val textures =
            listOf(
                TextureSpec(
                    file = File("red-brick-wall.jpg"),
                    displayName = "red-brick-wall",
                    surfaces = listOf(SurfaceSlot.WALL),
                    styles = listOf("industrial"),
                    roughness = 0.95f,
                    metallic = 0f,
                    normalMap = null,
                    details = null,
                ),
                TextureSpec(
                    file = File("oak-wood-floor.jpg"),
                    displayName = "oak-wood-floor",
                    surfaces = listOf(SurfaceSlot.FLOOR),
                    styles = emptyList(),
                    roughness = null,
                    metallic = null,
                    normalMap = null,
                    details = null,
                ),
            )

        val resolved =
            resolveAiTextures(
                mapOf(
                    SurfaceSlot.WALL to "Red Brick Wall", // case-insensitive exact
                    SurfaceSlot.FLOOR to "red-brick-wall", // wall-only texture on the floor
                    SurfaceSlot.DOOR to "ghost", // unknown
                ),
                textures,
            )

        assertEquals(1, resolved.resolved.size)
        assertEquals("red-brick-wall", resolved.resolved[SurfaceSlot.WALL]?.displayName)
        assertEquals(2, resolved.skipped.size)
        assertTrue(resolved.skipped.any { it.contains("meant for wall") })
        assertTrue(resolved.skipped.any { it.contains("unknown texture") })
    }

    @Test
    fun resolveAiPlacementsAppliesDefaultScaleToClearance() {
        val bigChair = chair.copy(defaultScale = 2f)
        val layout = AiLayout(listOf(AiPlacement("dining-chair", x = 100f, z = 0f, 0f, 1f)), null)

        val placed =
            resolveAiPlacements(layout, listOf(bigChair), walls, baseMargin).accepted.single()

        // The wall clearance uses the effective scale (AI scale × defaultScale), and the
        // accepted placement carries the effective scale for spawning.
        val halfDiagonal = hypot(0.25, 0.25).toFloat() * 2f
        assertTrue(
            distanceToWalls(walls, PlanPoint(placed.x, placed.z)) >=
                baseMargin + halfDiagonal - 0.01f,
        )
        assertEquals(2f, placed.scale, 0.0001f)
    }

    @Test
    fun librarySizesIncludeDefaultScale() {
        val scaledSofa = sofa.copy(defaultScale = 2f)
        val (_, user) =
            buildArrangementMessages(
                "anything",
                walls,
                emptyList(),
                listOf(scaledSofa),
                emptyList(),
                emptyList(),
            )
        assertTrue(user.contains("4.00 x 1.80"))
    }

    private fun boxOf(placement: ResolvedAiPlacement): YawBox =
        yawBoxFor(
            Vector3(
                placement.x,
                placement.model.bottomOffset * placement.scale,
                placement.z,
            ),
            placement.yawDegrees,
            placement.scale,
            placement.model.center,
            placement.model.halfExtents,
        )

    @Test
    fun buildArrangementMessagesContainsRoomCatalogPromptAndSchema() {
        val openings =
            listOf(
                OpeningDesc(OpeningType.DOOR, wallId = 1, x = 0f, z = -4f, width = 0.9f),
                OpeningDesc(OpeningType.WINDOW, wallId = 2, x = 7.5f, z = 0f, width = 1.4f),
            )
        val (system, user) =
            buildArrangementMessages(
                userPrompt = "cozy living room",
                walls = walls,
                openings = openings,
                catalog = catalog,
                currentPlacements = listOf(PlacedSummary("mid-century-sofa", 1f, -2f, 90f)),
                textureCatalog =
                    listOf(
                        TextureSpec(
                            file = File("red-brick-wall.jpg"),
                            displayName = "red-brick-wall",
                            surfaces = listOf(SurfaceSlot.WALL),
                            styles = listOf("industrial"),
                            roughness = 0.95f,
                            metallic = 0f,
                            normalMap = null,
                            details = null,
                        )
                    ),
                zoneNotes = "West half is the living room, east half is the bedroom.",
            )

        // The preprompt teaches vocabulary, semantics, style rules and the details schema.
        assertTrue(system.contains("next to / beside"))
        assertTrue(system.contains("around X"))
        assertTrue(system.contains("bedroom"))
        assertTrue(system.contains("bathroom"))
        assertTrue(system.contains("modern"))
        assertTrue(system.contains("room_types"))
        assertTrue(system.contains("style_assessment"))
        assertTrue(system.contains("style.nordic"))
        assertTrue(system.contains("\"placements\""))

        // The user message carries the full structured room descriptor.
        assertTrue(user.contains("\"walls\":[{\"id\":1"))
        assertTrue(user.contains("\"type\":\"door\""))
        assertTrue(user.contains("\"type\":\"window\""))
        assertTrue(user.contains("\"bounds\":{\"x\":[-7.5,7.5],\"z\":[-4.0,4.0]}"))
        // Existing furniture is listed with its measured size from the catalog.
        assertTrue(
            user.contains(
                "{\"model\":\"mid-century-sofa\",\"x\":1.00,\"z\":-2.00,\"yaw\":90," +
                    "\"size\":[2.00,0.85,0.90]}"
            )
        )
        // Sidecar details are included when present, flagged as inferred when absent.
        assertTrue(user.contains("DETAILS: {\"color\":\"oak\",\"style\":\"modern\"}"))
        assertTrue(user.contains("(no details file"))
        assertTrue(user.contains("cozy living room"))
        // The texture catalog is offered with its supported surfaces and styles.
        assertTrue(user.contains("TEXTURES"))
        assertTrue(user.contains("red-brick-wall — for surfaces: wall; styles: industrial"))
        // Zone notes flow into the prompt so "living room"/"bedroom" requests land correctly.
        assertTrue(user.contains("ZONES: West half is the living room, east half is the bedroom."))
        assertNotNull(user)
    }
}
