package com.example.testfull.content

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.AssetBundle
import com.pico.spatial.core.math.Vector3
import com.example.testfull.R
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeStage() {
    SpatialView(
        initial = { content, attachments ->
            // You can replace the model in Spatial Editor using its built-in assets library or with
            // your own custom model.
            val bundle =
                withContext(Dispatchers.IO) { AssetBundle.load("asset://editor-asset.bundle") }
            val model = Entity.loadSuspend(modelName = "MyScene", bundle = bundle)
            // Close the bundle when finished loading.
            bundle.close()
            model.apply {
                components[TransformComponent::class.java]?.apply {
                    setPosition(Vector3(0f, 1.5f, -1f))
                }
                content.addEntity(this)
            }
            val textAttachment = attachments.entity(id = "text")
            textAttachment?.apply {
                components[TransformComponent::class.java]?.apply {
                    setPosition(Vector3(0f, 0.3f, 0.0f))
                }
                model.addChild(this)
            }
        },
        attachments = {
            AttachmentPanel(id = "text") {
                Column(
                    modifier =
                        Modifier.size(600.dp, 295.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .backgroundMaterial(true, Material.Regular),
                ) {
                    Text(
                        modifier = Modifier.height(96.dp).padding(32.dp),
                        text = stringResource(R.string.homepage_title),
                        style = PicoTheme.typography.displaySmall,
                        fontSize = 28.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        modifier = Modifier.height(156.dp).padding(horizontal = 32.dp),
                        text = stringResource(R.string.homepage_body),
                        style = PicoTheme.typography.titleLarge,
                        fontSize = 20.sp,
                        color = Color(0x80000000)
                    )
                }
            }
        }
    )
}