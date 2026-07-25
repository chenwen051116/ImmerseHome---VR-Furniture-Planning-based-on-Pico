package com.example.testfull.content

import com.pico.spatial.core.math.Vector3
import java.io.File
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLibraryTest {
    @Test
    fun distillModelDetailsStripsNullsAndKeepsDecisionSections() {
        val raw =
            """
            {
              "schema_version": 1,
              "identity": {"id": "chair-001", "name": "蓝椅子", "description": null, "status": "approved"},
              "classification": {"category": "dining_chair", "room_types": ["living_room", "dining_room"]},
              "asset": {"format": "glb", "glb_url": null, "sha256": null},
              "appearance": {"colors": [], "materials": []},
              "style_assessment": {
                "score_scale": "0_to_1",
                "scores": {
                  "style.nordic": {"score": 0.9, "score_origin": null},
                  "style.chinese": {"score": null, "variants": {"variant.neo_chinese": {"score": 0.7}}}
                },
                "assessment_notes": null
              },
              "placement": {"support_surface": "floor", "against_wall": null},
              "notes": null
            }
            """.trimIndent()

        val distilled = distillModelDetails(raw)

        // Decision-relevant facts survive.
        assertTrue(distilled.contains("蓝椅子"))
        assertTrue(distilled.contains("dining_chair"))
        assertTrue(distilled.contains("living_room"))
        assertTrue(distilled.contains("0.9"))
        assertTrue(distilled.contains("neo_chinese"))
        assertTrue(distilled.contains("support_surface"))
        // Null fields, empty arrays, and non-whitelisted sections are stripped.
        assertFalse(distilled.contains("description"))
        assertFalse(distilled.contains("score_origin"))
        assertFalse(distilled.contains("against_wall"))
        assertFalse(distilled.contains("appearance"))
        assertFalse(distilled.contains("asset"))
        assertFalse(distilled.contains("\"notes\""))
    }

    @Test
    fun distillModelDetailsPassesThroughNonSchemaJson() {
        val raw = """{"color":"warm oak","style":"modern"}"""
        assertEquals(raw, distillModelDetails(raw))
    }

    @Test
    fun distillModelDetailsFallsBackOnGarbage() {
        assertEquals("not json", distillModelDetails("not json"))
    }

    @Test
    fun parseIntendedSizeReadsGeometry() {
        val raw = """{"geometry":{"width_m":2.35,"depth_m":0.95,"height_m":0.9}}"""
        val size = parseIntendedSize(raw)!!
        assertEquals(2.35f, size.x, 0.0001f)
        assertEquals(0.9f, size.y, 0.0001f)
        assertEquals(0.95f, size.z, 0.0001f)

        assertNull(parseIntendedSize(null))
        assertNull(parseIntendedSize("""{"geometry":{"width_m":null}}"""))
        assertNull(parseIntendedSize("""{"geometry":{"width_m":-1,"depth_m":1,"height_m":1}}"""))
    }

    @Test
    fun computeDefaultScaleUsesMeanForConsistentAndWidthForSkewed() {
        // Clean, uniformly-scaled export: the mean ratio.
        assertEquals(
            2f,
            computeDefaultScale(Vector3(1f, 1f, 1f), Vector3(2f, 2f, 2f)),
            0.0001f,
        )
        // Axis-skewed export (sofa-001-like): the width ratio wins.
        val scale =
            computeDefaultScale(
                Vector3(4239.9f, 2535.6f, 3344.6f),
                Vector3(2.35f, 0.9f, 0.95f),
            )
        assertEquals(2.35f / 4239.9f, scale, 0.000001f)
        // No intended size → identity.
        assertEquals(1f, computeDefaultScale(Vector3(1f, 1f, 1f), null), 0.0001f)
    }

    @Test
    fun boundsCacheRoundTrips() {
        val dir = createTempDir()
        try {
            val cache =
                mutableMapOf(
                    "/models/bed-001.glb" to
                        CachedBounds(
                            center = Vector3(0.1f, 0.2f, 0.3f),
                            halfExtents = Vector3(1f, 0.5f, 0.8f),
                            bottomOffset = 0.17f,
                            mtimeMs = 123456789L,
                        )
                )
            writeBoundsCache(dir, cache)

            val read = readBoundsCache(dir)
            val entry = read.getValue("/models/bed-001.glb")
            assertEquals(0.1f, entry.center.x, 0.0001f)
            assertEquals(0.5f, entry.halfExtents.y, 0.0001f)
            assertEquals(0.8f, entry.halfExtents.z, 0.0001f)
            assertEquals(0.17f, entry.bottomOffset, 0.0001f)
            assertEquals(123456789L, entry.mtimeMs)
            val bounds = entry.toModelBounds()
            assertEquals(entry.center, bounds.center)
            assertEquals(entry.halfExtents, bounds.halfExtents)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun boundsCacheToleratesMissingOrGarbage() {
        val dir = createTempDir()
        try {
            assertTrue(readBoundsCache(dir).isEmpty())
            File(dir, ".bounds-cache.json").writeText("not json")
            assertTrue(readBoundsCache(dir).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
