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
 * directory so no storage permission is required. Models are seeded from bundled assets on
 * first launch (see [seedBundledAssetsIfNeeded]); users can still add/override via `adb push`.
 */
internal fun modelsDirectory(context: Context): File {
    val base = context.getExternalFilesDir(null) ?: context.filesDir
    return File(base, "models")
}

/**
 * Copies bundled model/texture assets from `assets/models/` into the writable models directory
 * on first launch (or when individual files are missing). This ships the furniture library
 * with the APK so the user doesn't need to `adb push` files to the device.
 *
 * Idempotent: only copies files that don't already exist on the filesystem, so user-pushed
 * overrides via adb are preserved. Should be called off the main thread (disk I/O).
 */
internal fun seedBundledAssetsIfNeeded(context: Context) {
    val destDir = modelsDirectory(context)
    if (!destDir.exists()) destDir.mkdirs()

    val assetNames = runCatching { context.assets.list("models") }.getOrNull() ?: return
    if (assetNames.isEmpty()) return

    for (name in assetNames) {
        val destFile = File(destDir, name)
        if (destFile.exists()) continue // don't overwrite user-pushed files

        runCatching {
            context.assets.open("models/$name").use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
        }.onFailure { Log.w(TAG, "seed: failed to copy $name: ${it.message}") }
    }
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

internal fun scanModels(context: Context): List<LibraryModel> {
    seedBundledAssetsIfNeeded(context)
    return scanModelsIn(modelsDirectory(context))
}

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
        // Surface-texture schema sections.
        "type",
        "maps",
        "material",
        "tiling",
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
 * Reads the raw text of a model's sidecar (`<name>.json`), or null when absent/unreadable.
 */
internal fun readModelSidecarRaw(modelFile: File): String? {
    val sidecar = File(modelFile.parentFile, modelFile.nameWithoutExtension + ".json")
    if (!sidecar.isFile) return null
    return runCatching { sidecar.readText().trim() }.getOrNull()?.ifEmpty { null }
}

/**
 * Reads the optional sidecar detail file for a model (`<name>.json` next to `<name>.glb`) and
 * distills it via [distillModelDetails]. Returns null when absent or unreadable — the AI is
 * told to infer those properties from the model name in that case.
 */
internal fun readModelDetails(modelFile: File): String? =
    readModelSidecarRaw(modelFile)?.let { distillModelDetails(it) }

/**
 * The intended real-world size from a furniture sidecar: geometry.width_m/depth_m/height_m
 * (x = width, y = height, z = depth). Null when any of them is missing or non-positive —
 * meaning the model's authored units are already its real size.
 */
internal fun parseIntendedSize(sidecarJson: String?): Vector3? {
    if (sidecarJson == null) return null
    val geometry =
        runCatching { JSONObject(sidecarJson).optJSONObject("geometry") }.getOrNull()
            ?: return null
    val width = geometry.optDouble("width_m", Double.NaN)
    val depth = geometry.optDouble("depth_m", Double.NaN)
    val height = geometry.optDouble("height_m", Double.NaN)
    if (width.isNaN() || depth.isNaN() || height.isNaN()) return null
    if (width <= 0 || depth <= 0 || height <= 0) return null
    return Vector3(width.toFloat(), height.toFloat(), depth.toFloat())
}

/** Ratios within this band of their mean count as axis-consistent (uniformly scaled export). */
private const val SCALE_CONSISTENCY_TOLERANCE = 0.15f

/**
 * The uniform scale that brings a model to its intended real-world size: intended/measured
 * per axis. When the axes agree (a cleanly re-scalable export), their mean is used; when they
 * disagree (an axis-skewed export no uniform scale can fix), the width ratio wins as the most
 * important horizontal dimension. Null intended size (or degenerate measurements) means 1.
 */
internal fun computeDefaultScale(measuredSize: Vector3, intendedSize: Vector3?): Float {
    if (intendedSize == null) return 1f
    val ratios =
        listOf(
                intendedSize.x / measuredSize.x,
                intendedSize.y / measuredSize.y,
                intendedSize.z / measuredSize.z,
            )
            .filter { it.isFinite() && it > 0f }
    if (ratios.isEmpty()) return 1f
    val mean = ratios.average().toFloat()
    val consistent = ratios.all { kotlin.math.abs(it - mean) / mean <= SCALE_CONSISTENCY_TOLERANCE }
    return if (consistent) mean else ratios[0]
}

/** Measured bounds of a model file: bbox center, half-extents, and pivot-to-bottom distance. */
internal data class ModelBounds(
    val center: Vector3,
    val halfExtents: Vector3,
    val bottomOffset: Float,
)

/** File name of the on-device measured-bounds cache inside the models directory. */
private const val BOUNDS_CACHE_FILE = ".bounds-cache.json"

/** A cached measurement; valid only while the model file's mtime matches [mtimeMs]. */
internal data class CachedBounds(
    val center: Vector3,
    val halfExtents: Vector3,
    val bottomOffset: Float,
    val mtimeMs: Long,
) {
    fun toModelBounds(): ModelBounds = ModelBounds(center, halfExtents, bottomOffset)
}

/**
 * Reads the measured-bounds cache from [directory]. Measuring a model loads the whole mesh
 * through the (slow, memory-hungry) native loader, so results are persisted across sessions.
 */
internal fun readBoundsCache(directory: File): MutableMap<String, CachedBounds> {
    val file = File(directory, BOUNDS_CACHE_FILE)
    if (!file.isFile) return mutableMapOf()
    return runCatching {
            val root = JSONObject(file.readText())
            val map = mutableMapOf<String, CachedBounds>()
            root.keys().forEach { key ->
                val entry = root.optJSONObject(key) ?: return@forEach
                val center = entry.optJSONArray("c")
                val half = entry.optJSONArray("h")
                if (center != null && half != null && center.length() == 3 && half.length() == 3) {
                    map[key] =
                        CachedBounds(
                            center =
                                Vector3(
                                    center.optDouble(0).toFloat(),
                                    center.optDouble(1).toFloat(),
                                    center.optDouble(2).toFloat(),
                                ),
                            halfExtents =
                                Vector3(
                                    half.optDouble(0).toFloat(),
                                    half.optDouble(1).toFloat(),
                                    half.optDouble(2).toFloat(),
                                ),
                            bottomOffset = entry.optDouble("b", 0.0).toFloat(),
                            mtimeMs = entry.optLong("m", 0L),
                        )
                }
            }
            map
        }
        .getOrDefault(mutableMapOf())
}

/** Persists the bounds cache (best effort; failures are non-fatal to catalog building). */
internal fun writeBoundsCache(directory: File, cache: Map<String, CachedBounds>) {
    runCatching {
        val root = JSONObject()
        cache.forEach { (key, entry) ->
            root.put(
                key,
                JSONObject()
                    .put("c", JSONArray(listOf(entry.center.x, entry.center.y, entry.center.z)))
                    .put(
                        "h",
                        JSONArray(
                            listOf(entry.halfExtents.x, entry.halfExtents.y, entry.halfExtents.z)
                        ),
                    )
                    .put("b", entry.bottomOffset.toDouble())
                    .put("m", entry.mtimeMs),
            )
        }
        File(directory, BOUNDS_CACHE_FILE).writeText(root.toString())
    }
}

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
