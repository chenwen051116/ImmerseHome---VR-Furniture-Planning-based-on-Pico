package com.example.testfull.content

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        PanelTitle("AI Arrange")
        Spacer(Modifier.height(6.dp))
        PanelHint(
            "Describe the layout — the AI picks library models and places them with physics, " +
                "replacing the furniture currently in the room."
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
            Button(
                onClick = { onAiPromptChange("Cozy living room for movie night") },
                size = ButtonDefaults.Small,
            ) {
                Text("Cozy")
            }
            Button(
                onClick = { onAiPromptChange("Dining setup for two") },
                size = ButtonDefaults.Small,
            ) {
                Text("Dining")
            }
            Button(
                onClick = { onAiPromptChange("Study bedroom with a bed, a cabinet and a desk") },
                size = ButtonDefaults.Small,
            ) {
                Text("Bedroom")
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onArrangeWithAi,
            enabled = roomAvailable && !aiBusy && aiPrompt.isNotBlank(),
            size = ButtonDefaults.Max,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (aiBusy) "AI arranging…" else "Arrange with AI")
        }
        if (aiStatus.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            PanelHint(aiStatus)
        }

        Spacer(Modifier.height(14.dp))
        PanelTitle("Textures")
        Spacer(Modifier.height(6.dp))
        PanelHint("Reskin room surfaces. Applying rebuilds the room and clears placed furniture.")
        if (availableTextures.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            PanelHint("No textures found — push .png/.jpg files to the models folder, then scan.")
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
        Button(
            onClick = onApplyTextures,
            enabled = roomAvailable && availableTextures.isNotEmpty(),
            size = ButtonDefaults.Max,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Apply textures (rebuild)")
        }
    }
}
