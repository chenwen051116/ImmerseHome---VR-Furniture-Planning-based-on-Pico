package com.example.testfull.content

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.testfull.R
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.NumberField
import com.pico.spatial.ui.design.NumberFieldDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.TextField
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material

// Figma "AI Diagnosis Page--Reference" design tokens (accent switched to grey).
private val AiAccent = Color(0xFF7C7C7C) // grey accent
private val AiAccentDisabled = Color(0x337C7C7C)
private val AiCardBg = Color(0xCCFFF6E6) // cream-white card surface
private val AiHint = Color(0x995A4632) // warm grey-brown muted text
private val AiGlassFill = Color(0x3DFFFFFF) // frosted glass pill fill
private val AiGlassBorder = Color(0x33FFFFFF) // 1px semi-transparent border

// Figma "Select Furniture" design tokens.
private val FurPrimaryText = Color(0xFF3C2015) // dark brown labels
private val FurGlassText = Color(0xFFEEEEEE) // button text on glass
private val FurGlassFill = Color(0x3DFFFFFF) // frosted glass pill fill
private val FurGlassActive = Color(0x66FFFFFF) // selected pill fill
private val FurHint = Color(0xCCFFFFFF) // muted white hint text
private val FurCardBg = Color(0xCCFFF6E6) // cream-white preview card
private val FurAlertBg = Color(0xFFF6BFA9) // warm pink alert bar

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
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            Modifier.width(width)
                .height(height)
                .clip(RoundedCornerShape(20.dp))
                .backgroundMaterial(true, Material.Regular)
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
    val activeCategory = if (selectedCategory in categories) selectedCategory else categories.firstOrNull()
    val modelsInCategory = categorized[activeCategory] ?: emptyList()
    var currentIndex by remember(activeCategory) { mutableStateOf(0) }
    val currentModel =
        if (modelsInCategory.isEmpty()) {
            null
        } else {
            modelsInCategory[currentIndex.coerceIn(0, modelsInCategory.lastIndex)]
        }

    PanelFrame(width = 320.dp, height = 560.dp) {
        Text(
            text = "Furniture Library",
            style = PicoTheme.typography.displaySmall,
            fontSize = 22.sp,
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
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(FurGlassFill)
                    .clickable(onClick = onScanModels)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text =
                    if (availableModels.isEmpty()) {
                        "Scan models folder"
                    } else {
                        "Rescan (${availableModels.size} found)"
                    },
                style = PicoTheme.typography.titleMedium,
                fontSize = 14.sp,
                color = FurGlassText,
            )
        }

        if (availableModels.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "No models found yet. Push .glb files and scan.",
                style = PicoTheme.typography.titleMedium,
                fontSize = 13.sp,
                color = FurHint,
            )
        } else {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Categories",
                style = PicoTheme.typography.titleMedium,
                fontSize = 14.sp,
                color = FurPrimaryText,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                categories.forEach { cat ->
                    GlassChip(
                        text = cat,
                        selected = cat == activeCategory,
                        onClick = { selectedCategory = cat },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            if (currentModel != null) {
                // Preview card
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(FurCardBg)
                            .padding(14.dp),
                ) {
                    Column {
                        Text(
                            text = currentModel.displayName,
                            style = PicoTheme.typography.displaySmall,
                            fontSize = 17.sp,
                            color = FurPrimaryText,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${formatFileSize(currentModel.file.length())} · $activeCategory",
                            style = PicoTheme.typography.titleMedium,
                            fontSize = 13.sp,
                            color = FurHint,
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
                    GlassChip("‹", enabled = modelsInCategory.size > 1) {
                        currentIndex =
                            (currentIndex - 1 + modelsInCategory.size) % modelsInCategory.size
                    }
                    Text(
                        text = "${currentIndex + 1} / ${modelsInCategory.size}",
                        style = PicoTheme.typography.titleMedium,
                        fontSize = 14.sp,
                        color = Color.White,
                    )
                    GlassChip("›", enabled = modelsInCategory.size > 1) {
                        currentIndex = (currentIndex + 1) % modelsInCategory.size
                    }
                }
                Spacer(Modifier.height(10.dp))

                // Alert bar
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(FurAlertBg)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = "Select furniture, then drag it into the room.",
                        style = PicoTheme.typography.titleMedium,
                        fontSize = 13.sp,
                        color = FurPrimaryText,
                    )
                }
                Spacer(Modifier.height(10.dp))

                // Confirm selection
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (roomAvailable) FurGlassActive else Color(0x1AFFFFFF))
                            .clickable(enabled = roomAvailable) { onModelSelected(currentModel) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Confirm selection",
                        style = PicoTheme.typography.titleMedium,
                        fontSize = 16.sp,
                        color = Color.White,
                    )
                }
            }

            if (selectedModelName != null) {
                Spacer(Modifier.height(12.dp))
                DimensionField(
                    label = "Model scale",
                    value = modelScale * 100f,
                    step = 25f,
                    range = 5f..500f,
                    unit = "%",
                    fractionDigits = 0,
                    onChange = { value -> onModelScaleChange(value / 100f) },
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(FurGlassFill)
                            .clickable(enabled = placedCount > 0, onClick = onClearPlaced)
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Clear placed ($placedCount)",
                        style = PicoTheme.typography.titleMedium,
                        fontSize = 14.sp,
                        color = FurGlassText,
                    )
                }
            }
        }
    }
}

/**
 * Placement HUD: follows the user's view (positioned by HomeStage). Shows what is being
 * placed, the aim status, and the drop control — always within reach while aiming.
 */
@Composable
internal fun PlacementHudPanel(
    selectedModelName: String?,
    placementActive: Boolean,
    placedCount: Int,
    aimStatus: String,
    roomAvailable: Boolean,
    onPlacementActiveChange: (Boolean) -> Unit,
    onDropNow: () -> Unit,
    onClearPlaced: () -> Unit,
) {
    PanelFrame(width = 300.dp, height = 250.dp) {
        PanelTitle(if (selectedModelName != null) "Placing: $selectedModelName" else "Placing")
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { onPlacementActiveChange(!placementActive) },
            enabled = roomAvailable && selectedModelName != null,
            size = ButtonDefaults.Small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (placementActive) "Placing: ON — stop" else "Placing: OFF — start")
        }
        if (placementActive) {
            Spacer(Modifier.height(6.dp))
            PanelHint(aimStatus)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onDropNow,
                enabled = roomAvailable,
                size = ButtonDefaults.Max,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Drop at ghost")
            }
            Spacer(Modifier.height(6.dp))
            PanelHint(
                "Aim with the controller or your view — the ghost shows the real-size resting " +
                    "pose and slides aside to avoid overlaps. Red = no free space."
            )
        } else {
            Spacer(Modifier.height(6.dp))
            PanelHint("Pick a model in the Furniture panel, then start placing.")
        }
        Spacer(Modifier.height(8.dp))
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

/** AI arrangement: prompt, presets, run button, status — plus the surface-texture slots. */
@Composable
internal fun AiArrangePanel(
    aiPrompt: String,
    aiBusy: Boolean,
    aiStatus: String,
    roomAvailable: Boolean,
    onAiPromptChange: (String) -> Unit,
    onArrangeWithAi: () -> Unit,
    availableTextures: List<TextureSpec>,
    selectedTextures: Map<SurfaceSlot, String?>,
    onTextureSlotChange: (SurfaceSlot, String?) -> Unit,
    onApplyTextures: () -> Unit,
) {
    PanelFrame(width = 320.dp, height = 470.dp) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChip("Cozy", enabled = roomAvailable && !aiBusy) {
                onAiPromptChange("Cozy living room for movie night")
            }
            GlassChip("Dining", enabled = roomAvailable && !aiBusy) {
                onAiPromptChange("Dining setup for two")
            }
            GlassChip("Bedroom", enabled = roomAvailable && !aiBusy) {
                onAiPromptChange("Study bedroom with a bed, a cabinet and a desk")
            }
        }
        Spacer(Modifier.height(8.dp))
        AiActionButton(
            text = if (aiBusy) "AI arranging…" else "Arrange with AI",
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

        Spacer(Modifier.height(14.dp))
        // Textures section on a cream-white card.
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
}

/** Frosted-glass pill chip with optional selected state (AI presets, furniture categories). */
@Composable
private fun GlassChip(
    text: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = if (!enabled) Color(0x1AFFFFFF) else if (selected) FurGlassActive else FurGlassFill
    val textColor = if (!enabled) Color(0x66EEEEEE) else if (selected) Color.White else FurGlassText
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
                .border(1.dp, AiGlassBorder, RoundedCornerShape(8.dp))
                .clickable(enabled = enabled, onClick = onClick)
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

/** Warm-yellow accent action button for primary AI panel actions. */
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
