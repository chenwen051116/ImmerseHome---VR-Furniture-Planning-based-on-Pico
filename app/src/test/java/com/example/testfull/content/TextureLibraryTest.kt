package com.example.testfull.content

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextureLibraryTest {
    @Test
    fun parseTextureSpecReadsSidecarFields() {
        val sidecar =
            """
            {
              "schema_version": 1,
              "type": "surface_texture",
              "classification": {"surfaces": ["wall", "ceiling"], "styles": ["modern", "nordic"]},
              "material": {"roughness": 0.9, "metallic": 0.1},
              "maps": {"base_color": "white-plaster-wall.jpg", "normal": "white-plaster-wall_n.jpg"}
            }
            """.trimIndent()

        val spec = parseTextureSpec(File("white-plaster-wall.jpg"), sidecar)

        assertEquals("white-plaster-wall", spec.displayName)
        assertEquals(listOf(SurfaceSlot.WALL, SurfaceSlot.CEILING), spec.surfaces)
        assertEquals(listOf("modern", "nordic"), spec.styles)
        assertEquals(0.9f, spec.roughness!!, 0.0001f)
        assertEquals(0.1f, spec.metallic!!, 0.0001f)
        assertEquals("white-plaster-wall_n.jpg", spec.normalMap?.name)
        assertTrue(spec.details!!.contains("surface_texture"))
    }

    @Test
    fun parseTextureSpecDefaultsWithoutSidecar() {
        val spec = parseTextureSpec(File("brick.jpg"), null)

        assertTrue(spec.surfaces.isEmpty())
        assertTrue(spec.styles.isEmpty())
        assertNull(spec.roughness)
        assertNull(spec.metallic)
        assertNull(spec.normalMap)
        assertNull(spec.details)
    }

    @Test
    fun surfaceSlotKeysRoundTrip() {
        assertEquals(SurfaceSlot.WALL, SurfaceSlot.fromKey("wall"))
        assertEquals(SurfaceSlot.CEILING, SurfaceSlot.fromKey(" Ceiling "))
        assertNull(SurfaceSlot.fromKey("roof"))
    }
}
