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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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

private const val MAX_LISTED_MODELS = 8

// Figma "AI Diagnosis Page--Reference" design tokens.
private val AiAccent = Color(0xFFF5B942) // warm yellow — "the light left on at home"
private val AiAccentDisabled = Color(0x33F5B942)
private val AiCardBg = Color(0xCCFFF6E6) // cream-white card surface
private val AiHint = Color(0x995A4632) // warm grey-brown muted text
private val AiGlassFill = Color(0x3DFFFFFF) // frosted glass pill fill
private val AiGlassBorder = Color(0x33FFFFFF) // 1px semi-transparent border

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
    PanelFrame(width = 300.dp, height = 430.dp) {
        PanelTitle("Furniture")
        Spacer(Modifier.height(6.dp))
        PanelHint("Push .glb files to the app's files/models folder, then scan. Tap one to start placing it.")
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
        Spacer(Modifier.height(8.dp))
        availableModels.take(MAX_LISTED_MODELS).forEach { model ->
            EnvironmentButton(
                label = "${model.displayName} · ${formatFileSize(model.file.length())}",
                selected = model.displayName == selectedModelName,
                enabled = roomAvailable,
                onClick = { onModelSelected(model) },
            )
            Spacer(Modifier.height(6.dp))
        }
        if (availableModels.size > MAX_LISTED_MODELS) {
            PanelHint("…and ${availableModels.size - MAX_LISTED_MODELS} more (first $MAX_LISTED_MODELS shown)")
            Spacer(Modifier.height(6.dp))
        }
        if (selectedModelName != null) {
            Spacer(Modifier.height(4.dp))
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
            color = AiAccent,
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
            AiChip("Cozy", roomAvailable && !aiBusy) {
                onAiPromptChange("Cozy living room for movie night")
            }
            AiChip("Dining", roomAvailable && !aiBusy) {
                onAiPromptChange("Dining setup for two")
            }
            AiChip("Bedroom", roomAvailable && !aiBusy) {
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
            color = Color(0xFF3C2015),
        )
    }
}
