package com.example.testfull.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
