package com.example.testfull.content

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.testfull.BuildConfig
import com.example.testfull.R
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.NumberField
import com.pico.spatial.ui.design.NumberFieldDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.TextField
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import java.io.File
import com.pico.spatial.ui.platform.Material

private const val MAX_LISTED_MODELS = 8

/**
 * AI models offered in the Arrange panel's model selector. Read from BuildConfig.AI_API_MODELS
 * (a comma-separated list set in local.properties via ai.api.models) so the user can edit the
 * list without code changes when the relay adds or removes a model. The label is the model id
 * with the "gpt-" prefix stripped for a shorter chip.
 */
private val AI_MODEL_OPTIONS: List<Pair<String, String>> =
    BuildConfig.AI_API_MODELS
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { id -> id to id.removePrefix("gpt-") }

/** Max chips per row in the model selector — keeps chips readable in VR. */
private const val AI_MODEL_CHIPS_PER_ROW = 4

// Figma "AI Diagnosis Page--Reference" design tokens (accent switched to grey).
private val AiAccent = Color(0xFF7C7C7C) // grey accent
private val AiAccentDisabled = Color(0x337C7C7C)
private val AiCardBg = Color(0xCCFFF6E6) // cream-white card surface
private val AiHint = Color(0x995A4632) // warm grey-brown muted text
private val AiGlassFill = Color(0x3DFFFFFF) // frosted glass pill fill
private val AiGlassBorder = Color(0x33FFFFFF) // 1px semi-transparent border

// Figma "Select Furniture" design tokens.
private val FurPrimaryText = Color(0xFF3C2015) // dark brown labels on light surfaces
private val FurCardTitle = Color(0xFFFFFFFF) // white card titles on glass (Figma spec)
private val FurHint = Color(0xCCFFFFFF) // muted white hint text
private val FurCardBg = Color(0xCCFFF6E6) // cream-white preview card (legacy solid)
private val FurPreviewGradientStart = Color(0xFFF8E3CA) // cream (Figma G85 gradient)
private val FurPreviewGradientEnd = Color(0xFFF6AD88) // peach (Figma G85 gradient)
private val FurAlertBg = Color(0xFFF6BFA9) // warm peach alert bar (Figma spec)

/** Derives a furniture category from the model file name via keyword matching. */
private fun categorizeModel(name: String): String {
    val lower = name.lowercase()
    return when {
        "sofa" in lower || "couch" in lower -> "Sofa"
        "chair" in lower || "stool" in lower || "seat" in lower -> "Chair"
        "table" in lower || "desk" in lower -> "Table"
        "bed" in lower -> "Bed"
        "cabinet" in lower || "shelf" in lower || "storage" in lower || "wardrobe" in lower -> "Storage"
        "lamp" in lower || "light" in lower -> "Lighting"
        "rug" in lower || "carpet" in lower -> "Rug"
        "plant" in lower || "flower" in lower -> "Plant"
        else -> "Other"
    }
}

/** Frosted-glass pill chip with selected state, for furniture category tabs. Figma radius 4.63dp. */
@Composable
private fun CategoryChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) Color(0x66FFFFFF) else AiGlassFill
    val textColor = if (selected) Color.White else Color(0xFFEEEEEE)
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(5.dp))
                .background(bg)
                .border(1.dp, AiGlassBorder, RoundedCornerShape(5.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = PicoTheme.typography.titleMedium,
            fontSize = 13.sp,
            color = textColor,
        )
    }
}

/**
 * Loads a preview image for a furniture model. Tries in order:
 * 1. Model-specific sidecar (`<name>.png` or `<name>.jpg` next to the .glb) — the real
 *    product image uploaded alongside the model.
 * 2. Category-level image (`Sofa.png`, `Chair.png`, etc. in the same folder).
 * 3. Drawable resource based on category (bundled in APK, always accessible) — generic
 *    fallback for Sofa/Chair/Table when no per-model image exists.
 * Returns null when none exist, falling back to a generated thumbnail.
 */
@Composable
private fun loadModelPreview(modelFile: File): ImageBitmap? {
    val context = LocalContext.current
    return remember(modelFile) {
        runCatching {
            // 1. Try model-specific filesystem image first (the real preview).
            val dir = modelFile.parentFile
            val baseName = modelFile.nameWithoutExtension
            val pngFile = File(dir, "$baseName.png")
            val jpgFile = File(dir, "$baseName.jpg")
            val imageFile = when {
                pngFile.isFile -> pngFile
                jpgFile.isFile -> jpgFile
                else -> null
            }
            if (imageFile != null) {
                return@runCatching imageFile.inputStream().use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }
            // 2. Category-level image in the same folder.
            val category = categorizeModel(modelFile.name)
            val catPng = File(dir, "$category.png")
            if (catPng.isFile) {
                return@runCatching catPng.inputStream().use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }
            // 3. Fall back to a bundled drawable for the common categories.
            val resId = when (category) {
                "Sofa" -> R.drawable.furniture_sofa
                "Chair" -> R.drawable.furniture_chair
                "Table" -> R.drawable.furniture_table
                else -> 0
            }
            if (resId != 0) {
                BitmapFactory.decodeResource(context.resources, resId)?.asImageBitmap()
            } else {
                null
            }
        }.getOrNull()
    }
}

/** Category-specific background colors for generated thumbnails. */
private val categoryColors = mapOf(
    "Sofa" to 0xFFE89060.toInt(),
    "Chair" to 0xFF5B8DEF.toInt(),
    "Table" to 0xFF5BAE7A.toInt(),
    "Bed" to 0xFF9B7EDC.toInt(),
    "Storage" to 0xFF8B6F47.toInt(),
    "Lighting" to 0xFFF5B942.toInt(),
    "Rug" to 0xFF4DB6AC.toInt(),
    "Plant" to 0xFF2E7D32.toInt(),
    "Other" to 0xFF7C7C7C.toInt(),
)

/** Generates a colored thumbnail with the category name when no sidecar .png exists. */
@Composable
private fun generateCategoryThumbnail(category: String): ImageBitmap =
    remember(category) {
        val width = 300
        val height = 180
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(categoryColors[category] ?: 0xFF7C7C7C.toInt())
        val paint =
            Paint().apply {
                color = 0xFFFFFFFF.toInt()
                textSize = 42f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
        canvas.drawText(category, width / 2f, height / 2f + 14f, paint)
        bitmap.asImageBitmap()
    }

private fun formatFileSize(bytes: Long): String =
    when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024f / 1024f)
    }

@Composable
private fun PanelFrame(
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    debugTint: Color? = null,
    transparent: Boolean = false,
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            Modifier.width(width)
                .height(height)
                .clip(RoundedCornerShape(20.dp))
                .then(
                    if (transparent) {
                        Modifier.background(Color(0x3DFFFFFF))
                    } else {
                        Modifier.backgroundMaterial(true, Material.Regular)
                    }
                )
                .then(
                    if (debugTint != null) {
                        Modifier.background(debugTint)
                    } else {
                        Modifier
                    }
                )
                .padding(22.dp)
                .verticalScroll(rememberScrollState()),
    ) {
        content()
    }
}

@Composable
private fun PanelTitle(text: String) {
    Text(
        text = text,
        style = PicoTheme.typography.displaySmall,
        fontSize = 21.sp,
    )
}

@Composable
private fun PanelHint(text: String) {
    Text(
        text = text,
        style = PicoTheme.typography.titleMedium,
        fontSize = 13.sp,
        color = Color(0x99000000),
    )
}

/**
 * UI launcher: the always-visible home icon. One tap folds every main panel away, another
 * opens them all again. Icon art comes from the Figma "Open Interface" design (LOGO node).
 */
@Composable
internal fun UiLauncherPanel(
    uiOpen: Boolean,
    onToggle: () -> Unit,
) {
    Column(
        modifier =
            Modifier.width(140.dp)
                .clip(RoundedCornerShape(20.dp))
                .backgroundMaterial(true, Material.Regular)
                .clickable(onClick = onToggle)
                .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.ui_logo),
            contentDescription = if (uiOpen) "Close interface" else "Open interface",
            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(24.dp)),
        )
        Spacer(Modifier.height(8.dp))
        PanelHint(if (uiOpen) "Tap to fold the interface" else "Tap to open the interface")
    }
}

/**
 * Furniture chooser: the model library. Scan, pick a model to place, tune its scale, or clear
 * what has been dropped. Placement itself is driven from the Placement HUD panel.
 */
@Composable
internal fun FurnitureLibraryPanel(
    availableModels: List<LibraryModel>,
    selectedModelName: String?,
    modelScale: Float,
    placedCount: Int,
    roomAvailable: Boolean,
    onScanModels: () -> Unit,
    onModelSelected: (LibraryModel) -> Unit,
    onModelScaleChange: (Float) -> Unit,
    onClearPlaced: () -> Unit,
) {
    // Group models into categories derived from file names.
    val categorized =
        remember(availableModels) {
            availableModels.groupBy { categorizeModel(it.displayName) }.toSortedMap()
        }
    val categories = categorized.keys.toList()
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    val activeCategory =
        if (selectedCategory != null && selectedCategory in categories) {
            selectedCategory
        } else {
            categories.firstOrNull()
        }
    val safeModels = if (activeCategory != null) categorized[activeCategory] ?: emptyList() else emptyList()
    var currentIndex by remember(activeCategory) { mutableStateOf(0) }
    val safeIndex =
        if (safeModels.isEmpty()) {
            0
        } else {
            currentIndex.coerceIn(0, safeModels.lastIndex)
        }
    val currentModel = safeModels.getOrNull(safeIndex)

    PanelFrame(width = 420.dp, height = 550.dp) {
        Text(
            text = "Furniture Library",
            style = PicoTheme.typography.displaySmall,
            fontSize = 21.sp,
            color = Color.White,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Push .glb files to the app's files/models folder, then scan.",
            style = PicoTheme.typography.titleMedium,
            fontSize = 13.sp,
            color = FurHint,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onScanModels,
            size = ButtonDefaults.Small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (availableModels.isEmpty()) {
                    "Scan models folder"
                } else {
                    "Rescan (${availableModels.size} found)"
                }
            )
        }

        if (availableModels.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "No models found yet. Push .glb files and scan.",
                style = PicoTheme.typography.titleMedium,
                fontSize = 13.sp,
                color = FurHint,
            )
        } else {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Categories",
                style = PicoTheme.typography.titleMedium,
                fontSize = 14.sp,
                color = FurCardTitle,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                categories.forEach { cat ->
                    CategoryChip(
                        text = cat,
                        selected = cat == activeCategory,
                        onClick = { selectedCategory = cat },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            if (currentModel != null) {
                // Preview lookup: real per-model PNG uploaded alongside the .glb first,
                // then the bundled category drawable, then a generated colored thumbnail.
                val modelCategory = categorizeModel(currentModel.file.name)
                val preview = loadModelPreview(currentModel.file)
                // Preview card — Figma G85: gradient #F8E3CA -> #F6AD88, radius 23dp
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(23.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(FurPreviewGradientStart, FurPreviewGradientEnd),
                                ),
                            )
                            .padding(12.dp),
                ) {
                    Column {
                        if (preview != null) {
                            Image(
                                bitmap = preview,
                                contentDescription = currentModel.displayName,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(100.dp)
                                        .clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            Spacer(Modifier.height(8.dp))
                        } else {
                            val thumbnail =
                                generateCategoryThumbnail(modelCategory)
                            Image(
                                bitmap = thumbnail,
                                contentDescription = modelCategory,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(100.dp)
                                        .clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(
                            text = currentModel.displayName,
                            style = PicoTheme.typography.displaySmall,
                            fontSize = 17.sp,
                            color = FurCardTitle,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${formatFileSize(currentModel.file.length())} · $activeCategory",
                            style = PicoTheme.typography.titleMedium,
                            fontSize = 13.sp,
                            color = FurPrimaryText,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))

                // Left / right navigation + position indicator
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            if (safeModels.size > 1) {
                                currentIndex =
                                    (safeIndex - 1 + safeModels.size) % safeModels.size
                            }
                        },
                        enabled = safeModels.size > 1,
                        size = ButtonDefaults.Small,
                        modifier = Modifier.width(50.dp),
                    ) {
                        Text("‹")
                    }
                    Text(
                        text = "${safeIndex + 1} / ${safeModels.size}",
                        style = PicoTheme.typography.titleMedium,
                        fontSize = 14.sp,
                        color = Color.White,
                    )
                    Button(
                        onClick = {
                            if (safeModels.size > 1) {
                                currentIndex = (safeIndex + 1) % safeModels.size
                            }
                        },
                        enabled = safeModels.size > 1,
                        size = ButtonDefaults.Small,
                        modifier = Modifier.width(50.dp),
                    ) {
                        Text("›")
                    }
                }
                Spacer(Modifier.height(8.dp))

                // Alert bar
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(FurAlertBg)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "Select furniture, then drag it to the left space.",
                        style = PicoTheme.typography.titleMedium,
                        fontSize = 13.sp,
                        color = FurPrimaryText,
                    )
                }
                Spacer(Modifier.height(8.dp))

                // Confirm selection
                Button(
                    onClick = { onModelSelected(currentModel) },
                    enabled = roomAvailable,
                    size = ButtonDefaults.Small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Confirm selection")
                }
            }

            if (selectedModelName != null) {
                Spacer(Modifier.height(8.dp))
                DimensionField(
                    label = "Model scale",
                    value = modelScale * 100f,
                    step = 25f,
                    range = 5f..500f,
                    unit = "%",
                    fractionDigits = 0,
                    onChange = { value -> onModelScaleChange(value / 100f) },
                )
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = onClearPlaced,
                    enabled = placedCount > 0,
                    size = ButtonDefaults.Small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Clear placed ($placedCount)")
                }
            }
        }
    }
}

/**
 * Draggable rotation slider (0–360°). Touch and drag anywhere on the track to rotate the
 * furniture ghost in real-time. Styled with Figma glassmorphism tokens.
 */
@Composable
private fun RotationSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var trackWidthPx by remember { mutableStateOf(0f) }
    val progress = (value / 360f).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .onSizeChanged { trackWidthPx = it.width.toFloat() }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        if (trackWidthPx > 0f) {
                            val fraction = (offset.x / trackWidthPx).coerceIn(0f, 1f)
                            onValueChange(fraction * 360f)
                        }
                    },
                ) { change, _ ->
                    change.consume()
                    if (trackWidthPx > 0f) {
                        val fraction = (change.position.x / trackWidthPx).coerceIn(0f, 1f)
                        onValueChange(fraction * 360f)
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // Track background
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(AiGlassFill),
        )
        // Progress fill
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White),
        )
        // Thumb
        if (trackWidthPx > 0f) {
            Box(
                modifier = Modifier
                    .offset {
                        val thumbX = (progress * trackWidthPx - 12f)
                            .coerceIn(0f, (trackWidthPx - 24f).coerceAtLeast(0f))
                        IntOffset(thumbX.toInt(), 0)
                    }
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}

/**
 * Placement HUD: follows the user's view (positioned by HomeStage). Shows what is being
 * placed, rotation control, and drop/undo/delete actions — always within reach while aiming.
 * Styled with Figma "Select Furniture" glassmorphism tokens.
 */
@Composable
internal fun PlacementHudPanel(
    selectedModelName: String?,
    placementActive: Boolean,
    placedCount: Int,
    aimStatus: String,
    roomAvailable: Boolean,
    rotationDegrees: Float,
    onPlacementActiveChange: (Boolean) -> Unit,
    onDropNow: () -> Unit,
    onClearPlaced: () -> Unit,
    onRotationChange: (Float) -> Unit,
    onUndo: () -> Unit,
) {
    PanelFrame(width = 340.dp, height = 460.dp, transparent = true) {
        Text(
            text = selectedModelName ?: "Place Furniture",
            style = PicoTheme.typography.displaySmall,
            fontSize = 20.sp,
            color = FurCardTitle,
        )
        Spacer(Modifier.height(6.dp))

        if (selectedModelName != null) {
            // Rotation slider — drag to rotate the ghost in real-time.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Rotation",
                    style = PicoTheme.typography.titleMedium,
                    fontSize = 13.sp,
                    color = FurHint,
                )
                Text(
                    text = "${rotationDegrees.toInt()}°",
                    style = PicoTheme.typography.titleMedium,
                    fontSize = 13.sp,
                    color = Color.White,
                )
            }
            RotationSlider(
                value = rotationDegrees,
                onValueChange = onRotationChange,
            )
            Spacer(Modifier.height(10.dp))

            // Drop — the primary action, full width.
            Button(
                onClick = onDropNow,
                enabled = placementActive && roomAvailable,
                size = ButtonDefaults.Max,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Drop here")
            }
            Spacer(Modifier.height(8.dp))

            // Undo + Delete side by side.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onUndo,
                    enabled = placedCount > 0,
                    size = ButtonDefaults.Small,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Undo")
                }
                Button(
                    onClick = onClearPlaced,
                    enabled = placedCount > 0,
                    size = ButtonDefaults.Small,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Delete all")
                }
            }
            Spacer(Modifier.height(8.dp))

            // Done — stop placing and reopen the main panel.
            Button(
                onClick = { onPlacementActiveChange(false) },
                size = ButtonDefaults.Small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Done ($placedCount placed)")
            }
            Spacer(Modifier.height(8.dp))
            PanelHint(aimStatus)
            Spacer(Modifier.height(6.dp))
            PanelHint(
                "Aim with the controller or your view. Use rotation to face the furniture " +
                    "the right way. Red ghost = no free space.",
            )
        } else {
            Spacer(Modifier.height(6.dp))
            PanelHint("Select furniture from the library to start placing.")
        }
    }
}

/** AI arrangement: prompt, presets, run button, status. (Surface textures now live in the Environment panel.) */
@Composable
internal fun AiArrangePanel(
    aiPrompt: String,
    aiBusy: Boolean,
    aiStatus: String,
    roomAvailable: Boolean,
    advancedThinking: Boolean,
    planMode: Boolean,
    iterateMode: Boolean,
    aiModel: String,
    onAdvancedThinkingChange: (Boolean) -> Unit,
    onPlanModeChange: (Boolean) -> Unit,
    onIterateModeChange: (Boolean) -> Unit,
    onAiPromptChange: (String) -> Unit,
    onAiModelChange: (String) -> Unit,
    onArrangeWithAi: () -> Unit,
    onResetRoom: () -> Unit,
) {
    PanelFrame(width = 420.dp, height = 620.dp) {
        Text(
            text = "AI Arrange",
            style = PicoTheme.typography.displaySmall,
            fontSize = 21.sp,
            color = Color.White,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text =
                "Describe the layout — the AI picks library models and places them with physics, " +
                    "replacing the furniture currently in the room.",
            style = PicoTheme.typography.titleMedium,
            fontSize = 13.sp,
            color = AiHint,
        )
        Spacer(Modifier.height(8.dp))
        // Model selector: lets the user pick a faster/smarter model at runtime. The list comes
        // from BuildConfig.AI_API_MODELS (top 8 OpenAI performance models by BenchLM score).
        // Chips wrap into rows of AI_MODEL_CHIPS_PER_ROW so 8 models fit in 2 rows in VR.
        Text(
            text = "Model (top 8 by benchmark)",
            style = PicoTheme.typography.titleMedium,
            fontSize = 12.sp,
            color = AiHint,
        )
        Spacer(Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AI_MODEL_OPTIONS.chunked(AI_MODEL_CHIPS_PER_ROW).forEach { rowModels ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    rowModels.forEach { (id, label) ->
                        AiToggleChip(
                            text = label,
                            active = aiModel == id,
                            enabled = roomAvailable && !aiBusy,
                            onClick = { onAiModelChange(id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Pad the last row so its chips don't stretch wider than the rows above.
                    repeat(AI_MODEL_CHIPS_PER_ROW - rowModels.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        TextField(
            value = aiPrompt,
            onValueChange = onAiPromptChange,
            placeholder = { Text("e.g. cozy living room for movie night", fontSize = 13.sp) },
            singleLine = false,
            minLines = 2,
            enabled = roomAvailable && !aiBusy,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        // Preset prompts — each fills the prompt box with a tested starting point.
        // Arranged in 2 rows of 3; the first row covers social/living functions,
        // the second covers work/rest/studio functions.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiChip("Cozy", roomAvailable && !aiBusy) {
                onAiPromptChange("Cozy living room for movie night")
            }
            AiChip("Dining", roomAvailable && !aiBusy) {
                onAiPromptChange("Dining setup for two")
            }
            AiChip("Lounge", roomAvailable && !aiBusy) {
                onAiPromptChange("Modern lounge with sofa, coffee table, accent chairs and rug")
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiChip("Bedroom", roomAvailable && !aiBusy) {
                onAiPromptChange("Study bedroom with a bed, a cabinet and a desk")
            }
            AiChip("Office", roomAvailable && !aiBusy) {
                onAiPromptChange("Home office with desk, chair, bookshelf and storage cabinet")
            }
            AiChip("Studio", roomAvailable && !aiBusy) {
                onAiPromptChange("Studio apartment with sleeping area, work desk, and lounge corner")
            }
        }
        Spacer(Modifier.height(8.dp))
        // Option toggles. Tapping a toggle flips its state; the active one is highlighted
        // with the accent fill so the user can see at a glance what's on.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AiToggleChip(
                text = "Advanced Thinking",
                active = advancedThinking,
                enabled = roomAvailable && !aiBusy,
                onClick = { onAdvancedThinkingChange(!advancedThinking) },
                modifier = Modifier.weight(1f),
            )
            AiToggleChip(
                text = "Plan Mode",
                active = planMode,
                enabled = roomAvailable && !aiBusy,
                onClick = { onPlanModeChange(!planMode) },
                modifier = Modifier.weight(1f),
            )
        }
        // Iterate toggle on its own row: when on, the AI re-evaluates its placed layout
        // and revises it up to 3 times until it declares satisfaction. Disabled in plan
        // mode (which produces no placements to iterate on).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AiToggleChip(
                text = "Iterate (self-improve)",
                active = iterateMode,
                enabled = roomAvailable && !aiBusy && !planMode,
                onClick = { onIterateModeChange(!iterateMode) },
                modifier = Modifier.weight(1f),
            )
        }
        if (advancedThinking) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Advanced: fengshui/国学 config prepended to the AI prompt.",
                style = PicoTheme.typography.titleMedium,
                fontSize = 11.sp,
                color = AiHint,
            )
        }
        if (planMode) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Plan: AI gives suggestions only, no furniture is placed.",
                style = PicoTheme.typography.titleMedium,
                fontSize = 11.sp,
                color = AiHint,
            )
        }
        if (iterateMode) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Iterate: after placing, AI re-evaluates the room and revises up to 3× until satisfied.",
                style = PicoTheme.typography.titleMedium,
                fontSize = 11.sp,
                color = AiHint,
            )
        }
        Spacer(Modifier.height(8.dp))
        AiActionButton(
            text =
                if (aiBusy) {
                    "AI arranging…"
                } else if (planMode) {
                    "Ask AI for suggestions"
                } else if (iterateMode) {
                    "Arrange with AI (iterate)"
                } else {
                    "Arrange with AI"
                },
            enabled = roomAvailable && !aiBusy && aiPrompt.isNotBlank(),
            onClick = onArrangeWithAi,
        )
        if (aiStatus.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = aiStatus,
                style = PicoTheme.typography.titleMedium,
                fontSize = 13.sp,
                color = AiHint,
            )
        }
        Spacer(Modifier.height(10.dp))
        AiActionButton(
            text = "Reset room",
            enabled = roomAvailable && !aiBusy,
            onClick = onResetRoom,
        )
    }
}

/** Frosted-glass pill chip for AI preset prompts. */
@Composable
private fun AiChip(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (enabled) AiGlassFill else Color(0x1AFFFFFF))
                .border(1.dp, AiGlassBorder, RoundedCornerShape(8.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = PicoTheme.typography.titleMedium,
            fontSize = 14.sp,
            color = if (enabled) Color(0xFFEEEEEE) else Color(0x66EEEEEE),
        )
    }
}

/**
 * Surface-texture reskin card: one [TextureSlotRow] per [SurfaceSlot] plus an "Apply" button
 * that rebuilds the room. Extracted so it can be hosted outside the AI Arrange panel (it now
 * lives in the Environment panel where the virtual-walk controls used to be).
 */
@Composable
internal fun TexturesCard(
    availableTextures: List<TextureSpec>,
    selectedTextures: Map<SurfaceSlot, String?>,
    roomAvailable: Boolean,
    onTextureSlotChange: (SurfaceSlot, String?) -> Unit,
    onApplyTextures: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(AiCardBg)
                .padding(12.dp),
    ) {
        Column {
            Text(
                text = "Textures",
                style = PicoTheme.typography.displaySmall,
                fontSize = 19.sp,
                color = Color(0xFF3C2015),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Reskin room surfaces. Applying rebuilds the room and clears placed furniture.",
                style = PicoTheme.typography.titleMedium,
                fontSize = 13.sp,
                color = AiHint,
            )
            if (availableTextures.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "No textures found — push .png/.jpg files to the models folder, then scan.",
                    style = PicoTheme.typography.titleMedium,
                    fontSize = 13.sp,
                    color = AiHint,
                )
            }
            SurfaceSlot.entries.forEach { slot ->
                TextureSlotRow(
                    label = slot.key.replaceFirstChar { it.uppercase() },
                    current = selectedTextures[slot],
                    options =
                        listOf(null) +
                            availableTextures
                                .filter { it.surfaces.isEmpty() || slot in it.surfaces }
                                .map { it.displayName },
                    enabled = roomAvailable,
                    onChange = { name -> onTextureSlotChange(slot, name) },
                )
            }
            Spacer(Modifier.height(8.dp))
            AiActionButton(
                text = "Apply textures (rebuild)",
                enabled = roomAvailable && availableTextures.isNotEmpty(),
                onClick = onApplyTextures,
            )
        }
    }
}

/**
 * A two-state toggle chip (on/off). When [active] is true the chip fills with the accent
 * color so the user can see at a glance which options are enabled. Tapping flips the state
 * via [onClick] (the parent decides whether to set true or false).
 */
@Composable
private fun AiToggleChip(
    text: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(8.dp))
                .background(
                    when {
                        !enabled -> Color(0x1AFFFFFF)
                        active -> AiAccent
                        else -> AiGlassFill
                    },
                )
                .border(1.dp, AiGlassBorder, RoundedCornerShape(8.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (active) "$text ✓" else text,
            style = PicoTheme.typography.titleMedium,
            fontSize = 12.sp,
            color = if (enabled) Color(0xFFEEEEEE) else Color(0x66EEEEEE),
        )
    }
}

/** Grey accent action button for primary AI panel actions. */
@Composable
private fun AiActionButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (enabled) AiAccent else AiAccentDisabled)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = PicoTheme.typography.titleMedium,
            fontSize = 16.sp,
            color = Color.White,
        )
    }
}
