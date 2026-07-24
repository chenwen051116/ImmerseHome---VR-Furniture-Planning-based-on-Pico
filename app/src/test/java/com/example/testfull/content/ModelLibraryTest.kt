package com.example.testfull.content

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelLibraryTest {
    @get:Rule val tempFolder = TemporaryFolder()

    @Test
    fun supportedExtensionsAreAcceptedCaseInsensitively() {
        assertTrue(isSupportedModelFile("chair.glb"))
        assertTrue(isSupportedModelFile("Chair.GLB"))
        assertTrue(isSupportedModelFile("scene.gltf"))
        assertTrue(isSupportedModelFile("room.usda"))
        assertTrue(isSupportedModelFile("room.usdc"))
        assertTrue(isSupportedModelFile("room.usdz"))
        assertFalse(isSupportedModelFile("notes.txt"))
        assertFalse(isSupportedModelFile("noextension"))
        assertFalse(isSupportedModelFile("archive.glb.bak"))
    }

    @Test
    fun displayNameStripsExtension() {
        assertEquals("chair", modelDisplayName("chair.glb"))
        assertEquals("my chair.v2", modelDisplayName("my chair.v2.usda"))
        assertEquals("noextension", modelDisplayName("noextension"))
    }

    @Test
    fun scanFiltersSortsAndMapsModels() {
        val dir = tempFolder.newFolder("models")
        File(dir, "zebra.glb").writeBytes(byteArrayOf(1))
        File(dir, "apple.usda").writeBytes(byteArrayOf(1))
        File(dir, "ignored.txt").writeBytes(byteArrayOf(1))
        File(dir, "Mango.GLTF").writeBytes(byteArrayOf(1))
        File(dir, "subdir").mkdir()

        val models = scanModelsIn(dir)

        assertEquals(listOf("apple", "Mango", "zebra"), models.map { it.displayName })
        assertEquals(listOf("apple.usda", "Mango.GLTF", "zebra.glb"), models.map { it.file.name })
    }

    @Test
    fun scanCreatesMissingDirectoryAndReturnsEmpty() {
        val dir = File(tempFolder.root, "missing/models")

        val models = scanModelsIn(dir)

        assertTrue(models.isEmpty())
        assertTrue(dir.isDirectory)
    }
}
