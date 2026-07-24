package com.example.testfull.content

import android.content.Context
import java.io.File
import java.util.Locale

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
