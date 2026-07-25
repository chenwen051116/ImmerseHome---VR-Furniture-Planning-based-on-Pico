package com.example.testfull.content

import android.content.Context
import android.util.Log
import com.pico.spatial.core.ecs.LoadType
import com.pico.spatial.core.ecs.resource.TextureResource
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "TextureLibrary"

/** A room surface a texture can reskin. Slot keys are used in the AI response JSON. */
internal enum class SurfaceSlot {
    WALL,
    FLOOR,
    CEILING,
    DOOR,
    WINDOW;

    val key: String
        get() = name.lowercase(Locale.US)

    companion object {
        fun fromKey(key: String): SurfaceSlot? =
            entries.firstOrNull { it.key == key.trim().lowercase(Locale.US) }
    }
}

/**
 * A texture image plus the arrangement-relevant fields of its optional `<name>.json` sidecar
 * (see the texture schema: classification.surfaces/styles, material.roughness/metallic,
 * maps.normal). The AI references a texture by its [displayName] (file base name).
 */
internal data class TextureSpec(
    val file: File,
    val displayName: String,
    /** Surfaces this texture is meant for; empty = offered for every slot. */
    val surfaces: List<SurfaceSlot>,
    val styles: List<String>,
    val roughness: Float?,
    val metallic: Float?,
    val normalMap: File?,
    /** Distilled sidecar JSON for the AI prompt, null when there is no sidecar. */
    val details: String?,
)

internal val SUPPORTED_TEXTURE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

/** Model extensions whose same-named image is a furniture PREVIEW, not a texture. */
private val MODEL_EXTENSIONS = setOf("glb", "gltf")

internal fun isSupportedTextureFile(fileName: String): Boolean {
    val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
    return extension in SUPPORTED_TEXTURE_EXTENSIONS
}

/**
 * True when [imageFile] is a furniture preview, not a standalone texture: a model file with the
 * same base name sits next to it (e.g. bed001.png next to bed001.glb). Those images are loaded
 * by the furniture card previewer and must not appear in the texture reskin list.
 */
private fun isFurniturePreview(imageFile: File): Boolean {
    val base = imageFile.nameWithoutExtension
    return MODEL_EXTENSIONS.any { ext ->
        File(imageFile.parentFile, "$base.$ext").isFile
    }
}

/** Parses one texture entry: image file + optional same-name sidecar JSON (raw text). */
internal fun parseTextureSpec(imageFile: File, sidecarJson: String?): TextureSpec {
    var surfaces = emptyList<SurfaceSlot>()
    var styles = emptyList<String>()
    var roughness: Float? = null
    var metallic: Float? = null
    var normalMap: File? = null

    if (sidecarJson != null) {
        val root = runCatching { JSONObject(sidecarJson) }.getOrNull()
        if (root != null) {
            val classification = root.optJSONObject("classification")
            classification?.optJSONArray("surfaces")?.let { array ->
                surfaces =
                    (0 until array.length())
                        .mapNotNull { SurfaceSlot.fromKey(array.optString(it, "")) }
            }
            classification?.optJSONArray("styles")?.let { array ->
                styles =
                    (0 until array.length())
                        .map { array.optString(it, "").trim() }
                        .filter { it.isNotEmpty() }
            }
            root.optJSONObject("material")?.let { material ->
                roughness = material.optDouble("roughness", Double.NaN).takeUnless { it.isNaN() }?.toFloat()
                metallic = material.optDouble("metallic", Double.NaN).takeUnless { it.isNaN() }?.toFloat()
            }
            root.optJSONObject("maps")?.optString("normal", "")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                normalMap = File(imageFile.parentFile, it)
            }
        }
    }
    // Convention fallback: <base>_n.<ext> next to the image is its normal map.
    if (normalMap == null) {
        val base = imageFile.nameWithoutExtension
        SUPPORTED_TEXTURE_EXTENSIONS.forEach { ext ->
            val candidate = File(imageFile.parentFile, "${base}_n.$ext")
            if (candidate.isFile) normalMap = candidate
        }
    }
    return TextureSpec(
        file = imageFile,
        displayName = modelDisplayName(imageFile.name),
        surfaces = surfaces,
        styles = styles,
        roughness = roughness,
        metallic = metallic,
        normalMap = normalMap,
        details = sidecarJson?.let { distillModelDetails(it) },
    )
}

/** Lists the texture images in [directory] (each with its optional sidecar), sorted by name. */
internal fun scanTexturesIn(directory: File): List<TextureSpec> {
    if (!directory.exists()) return emptyList()
    val files = directory.listFiles() ?: return emptyList()
    return files
        .filter { it.isFile && isSupportedTextureFile(it.name) }
        // A "<base>_n" file is a normal map companion, not a standalone texture.
        .filter { !it.nameWithoutExtension.endsWith("_n") }
        // Skip furniture preview images: a same-named .glb/.gltf means this PNG is the
        // card preview for that model, not a wall/floor texture.
        .filter { !isFurniturePreview(it) }
        .sortedBy { it.name.lowercase(Locale.US) }
        .map { image ->
            val sidecar = File(image.parentFile, image.nameWithoutExtension + ".json")
            val sidecarJson =
                if (sidecar.isFile) {
                    runCatching { sidecar.readText().trim() }.getOrNull()?.ifEmpty { null }
                } else {
                    null
                }
            parseTextureSpec(image, sidecarJson)
        }
}

internal fun scanTextures(context: Context): List<TextureSpec> {
    seedBundledAssetsIfNeeded(context)
    return scanTexturesIn(modelsDirectory(context))
}

/**
 * Loads and caches TextureResources for the room builder. Textures are shared across room
 * rebuilds, so they are owned here (not by GeneratedRoom) and released only on [close] —
 * closing them together with a room's materials would invalidate the cache.
 */
internal class TextureCache {
    private val cache = mutableMapOf<String, TextureResource>()

    suspend fun load(file: File): TextureResource? {
        cache[file.absolutePath]?.takeIf { it.valid }?.let { return it }
        val texture =
            withContext(Dispatchers.IO) {
                runCatching { TextureResource.load(file.absolutePath, LoadType.FROM_STORAGE) }
                    .onFailure { Log.w(TAG, "texture load failed for ${file.name}", it) }
                    .getOrNull()
            }?.takeIf { it.valid }
        if (texture != null) {
            cache[file.absolutePath] = texture
        }
        return texture
    }

    fun close() {
        cache.values.forEach { runCatching { it.close() } }
        cache.clear()
    }
}
