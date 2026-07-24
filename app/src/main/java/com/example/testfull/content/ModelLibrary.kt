package com.example.testfull.content

import android.content.Context
import android.util.Log
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.math.Vector3
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "ModelLibrary"

/** A model file discovered in the local models folder, ready to be placed in the room. */
internal data class LibraryModel(
    val file: File,
    val displayName: String,
)

/** Formats accepted by `Entity.loadSuspend(File)` in the PICO Spatial SDK. */
internal val SUPPORTED_MODEL_EXTENSIONS = setOf("glb", "gltf", "usda", "usdc", "usdz")

internal fun isSupportedModelFile(fileName: String): Boolean {
    val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
    return extension in SUPPORTED_MODEL_EXTENSIONS
}

internal fun modelDisplayName(fileName: String): String {
    val base = fileName.substringBeforeLast('.')
    return base.ifBlank { fileName }
}

/**
 * The folder the app scans for placeable models. It lives in the app-specific external files
 * directory so no storage permission is required; users populate it with `adb push`.
 */
internal fun modelsDirectory(context: Context): File {
    val base = context.getExternalFilesDir(null) ?: context.filesDir
    return File(base, "models")
}

/** Lists the supported model files in [directory], sorted case-insensitively by file name. */
internal fun scanModelsIn(directory: File): List<LibraryModel> {
    if (!directory.exists()) {
        directory.mkdirs()
    }
    val files = directory.listFiles() ?: return emptyList()
    return files
        .filter { it.isFile && isSupportedModelFile(it.name) }
        .sortedBy { it.name.lowercase(Locale.US) }
        .map { LibraryModel(file = it, displayName = modelDisplayName(it.name)) }
}

internal fun scanModels(context: Context): List<LibraryModel> = scanModelsIn(modelsDirectory(context))

/** Cap for a sidecar detail file, so a huge JSON can't bloat the AI prompt. */
private const val MAX_DETAIL_CHARS = 2000

/**
 * Sidecar sections worth showing to the AI; everything else (asset urls, licensing, …) is
 * template noise for arrangement decisions.
 */
private val DETAIL_SECTIONS =
    setOf(
        "identity",
        "classification",
        "appearance",
        "placement",
        "style_assessment",
        "feature_scores",
        "construction",
        "geometry",
        "notes",
    )

/**
 * Distills a furniture sidecar JSON (asset schema_version 1) for the AI prompt: keeps only the
 * sections relevant to arrangement decisions and recursively strips null/blank/empty fields,
 * so a mostly-empty template collapses to its known facts. JSON without a `schema_version`
 * (hand-written details) passes through unchanged. Never throws — falls back to the raw text.
 */
internal fun distillModelDetails(raw: String): String {
    val root = runCatching { JSONObject(raw) }.getOrNull() ?: return raw.take(MAX_DETAIL_CHARS)
    if (!root.has("schema_version")) return raw.take(MAX_DETAIL_CHARS)
    val out = JSONObject()
    DETAIL_SECTIONS.forEach { key ->
        val cleaned = stripEmpty(root.opt(key)) ?: return@forEach
        out.put(key, cleaned)
    }
    return (if (out.length() > 0) out.toString() else "{}").take(MAX_DETAIL_CHARS)
}

/** Copy of [value] with nulls, blanks, and empty objects/arrays removed; null when empty. */
private fun stripEmpty(value: Any?): Any? =
    when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> {
            val out = JSONObject()
            value.keys().forEach { key ->
                stripEmpty(value.opt(key))?.let { out.put(key, it) }
            }
            if (out.length() > 0) out else null
        }
        is JSONArray -> {
            val out = JSONArray()
            for (index in 0 until value.length()) {
                stripEmpty(value.opt(index))?.let { out.put(it) }
            }
            if (out.length() > 0) out else null
        }
        is String -> value.ifBlank { null }
        else -> value
    }

/**
 * Reads the optional sidecar detail file for a model (`<name>.json` next to `<name>.glb`) and
 * distills it via [distillModelDetails]. Returns null when absent or unreadable — the AI is
 * told to infer those properties from the model name in that case.
 */
internal fun readModelDetails(modelFile: File): String? {
    val sidecar = File(modelFile.parentFile, modelFile.nameWithoutExtension + ".json")
    if (!sidecar.isFile) return null
    return runCatching { sidecar.readText().trim() }
        .getOrNull()
        ?.ifEmpty { null }
        ?.let { distillModelDetails(it) }
}

/** Measured bounds of a model file: bbox center, half-extents, and pivot-to-bottom distance. */
internal data class ModelBounds(
    val center: Vector3,
    val halfExtents: Vector3,
    val bottomOffset: Float,
)

/**
 * Loads [file] just long enough to read its bounding box, then destroys the probe entity.
 * Bounds come from the loaded entity tree's visual bounds — the standalone MeshResource
 * loader does not support GLB (FORMAT_UNSUPPORTED on this SDK), which silently produced
 * fallback-size colliders. `getVisualBounds` is @MainThread, so the caller must be on the
 * main thread. Returns null when the bounds cannot be read.
 */
internal suspend fun measureModelBounds(file: File): ModelBounds? {
    val probe =
        withContext(Dispatchers.IO) {
            runCatching { Entity.loadSuspend(file) }
                .onFailure { Log.w(TAG, "measure: entity load failed for ${file.name}", it) }
                .getOrNull()
        } ?: return null
    return try {
        // Let the async model load commit and the (slow emulator) main thread pump a few
        // frames before the @MainThread native bounds call — back-to-back native calls here
        // were enough to starve input dispatch and trigger an ANR dialog.
        delay(200)
        val bounds =
            runCatching { probe.getVisualBounds(relativeTo = probe, recursive = true) }
                .onFailure { Log.w(TAG, "measure: visual bounds failed for ${file.name}", it) }
                .getOrNull()
                ?.takeUnless { it.isEmpty() }
                ?: return null
        Log.w(TAG, "measure: ${file.name} size=${bounds.size} minY=${bounds.min.y}")
        ModelBounds(
            center = bounds.center,
            halfExtents = bounds.halfExtent,
            bottomOffset = (-bounds.min.y).coerceAtLeast(0f),
        )
    } finally {
        probe.destroy(recursively = true)
    }
}
