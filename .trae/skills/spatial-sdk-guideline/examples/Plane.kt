/*
 * Copyright 2024 - 2026 PICO. Licensed under the Apache License, Version 2.0.
 */

package com.pico.spatial.sample.features.plane

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PolygonFillMode
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.sense.base.AnchorUpdate
import com.pico.spatial.sense.base.TrackingState
import com.pico.spatial.sense.plane.PlaneTrackingManager
import com.pico.spatial.tracking.controller.ControllerTrackingData
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.platform.containers.closeStage
import com.pico.spatial.ui.platform.containers.openStage
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PlaneSample() {
    val context = LocalContext.current
    var text by remember { mutableStateOf("初始文字内容") }
    DisposableEffect(key1 = Unit) {
        PlaneSampleHelper.entity = Entity()
        PlaneSampleHelper.entity?.setName("PlaneSampleEntity")
        CoroutineScope(Dispatchers.Default).launch { context.openStage("PlaneSampleStage") }
        onDispose {
            CoroutineScope(Dispatchers.Default).launch { closeStage() }
            PlaneSampleHelper.entity?.destroy()
            PlaneSampleHelper.entity = null
            PlaneSampleHelper.entityMap.clear()
            PlaneTrackingManager.stop()
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            text = PlaneTrackingManager.state.toString()
            delay(500L)
        }
    }
    PicoTheme {
        Column(
            modifier = Modifier.background(color = Color.Transparent),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier =
                    Modifier.background(
                            color = Color.Black.copy(alpha = 0.8f),
                            shape = RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(text = text, fontSize = 30.sp, color = Color.White)
            }
            Spacer(modifier = Modifier.size(50.dp))
            Button(
                onClick = { PlaneTrackingManager.start() },
                modifier = Modifier.padding(bottom = 30.dp).width(800.dp),
            ) {
                Text("PlaneAnchor开启更新", fontSize = 30.sp)
            }
            Spacer(modifier = Modifier.size(20.dp))
            Button(
                onClick = { PlaneTrackingManager.stop() },
                modifier = Modifier.padding(bottom = 30.dp).width(800.dp),
            ) {
                Text("PlaneAnchor停止更新", fontSize = 30.sp)
            }
            Spacer(modifier = Modifier.size(20.dp))
            Button(
                onClick = { subscribePlaneAnchorUpdate() },
                modifier = Modifier.padding(bottom = 30.dp).width(800.dp),
            ) {
                Text("订阅PlaneAnchor更新", fontSize = 30.sp)
            }
            Spacer(modifier = Modifier.size(20.dp))
            Button(
                onClick = { loadAllPlaneAnchor() },
                modifier = Modifier.padding(bottom = 30.dp).width(800.dp),
            ) {
                Text("loadAllPlaneAnchor", fontSize = 30.sp)
            }
        }
    }
}

@Composable
fun PlaneSampleStage() {
    PicoTheme {
        Column(
            modifier = Modifier.background(color = Color.Transparent),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SpatialView(
                modifier = Modifier.padding(bottom = 10.dp).background(color = Color.Transparent)
            ) { content, _ ->
                PlaneSampleHelper.entity?.let { content.addEntity(it) }
            }
        }
    }
    ShowControllers()
}

@Composable
@Suppress("FunctionNaming")
fun ShowControllers() {
    val controllerTrackingProvider = remember { ControllerTrackingProvider() }

    val controllerTrackingData by
        controllerTrackingProvider.dataFlow.collectAsState(
            initial = ControllerTrackingData(null, null, 0L)
        )
    DisposableEffect(controllerTrackingProvider) {
        controllerTrackingProvider.start()
        onDispose { controllerTrackingProvider.stop() }
    }
    ShowControllerModel(controllerTrackingData)
}

@Composable
@Suppress("FunctionNaming")
fun ShowControllerModel(controllerTrackingData: ControllerTrackingData) {
    val mesh = remember { MeshResource.createBox(Vector3(0.08f), 0.02f) }
    val material = remember { UnlitMaterial.create().apply { setBaseColor(Color4.WHITE) } }
    val controllerEntity: Entity = remember { Entity() }
    val leftEntity: Entity = remember {
        Entity().apply {
            val modelComponent = ModelComponent(mesh, material)
            components.set(modelComponent)
            enabled = false
        }
    }
    val rightEntity: Entity = remember {
        Entity().apply {
            val modelComponent = ModelComponent(mesh, material)
            components.set(modelComponent)
            enabled = false
        }
    }

    SpatialView(
        modifier = Modifier.size(1.dp),
        update = { _, _ ->
            controllerTrackingData.left?.let { left ->
                leftEntity.enabled = left.position != Vector3.ZERO
                val transformComponent = leftEntity.components[TransformComponent::class.java]
                transformComponent?.apply {
                    val position = controllerEntity.convertPositionFrom(left.position, null)
                    setPosition(position)
                    setQuaternion(left.rotation)
                }
            }
            controllerTrackingData.right?.let { right ->
                rightEntity.enabled = right.position != Vector3.ZERO
                val transformComponent = rightEntity.components[TransformComponent::class.java]
                transformComponent?.apply {
                    val position = controllerEntity.convertPositionFrom(right.position, null)
                    setPosition(position)
                    setQuaternion(right.rotation)
                }
            }
        },
    ) { content, _ ->
        controllerEntity.addChild(leftEntity)
        controllerEntity.addChild(rightEntity)
        content.addEntity(controllerEntity)
    }
}

fun subscribePlaneAnchorUpdate() {
    CoroutineScope(Dispatchers.Main).launch {
        PlaneTrackingManager.subscribeAnchorUpdate {
            if (PlaneTrackingManager.state == TrackingState.RUNNING) {
                if (it.event == AnchorUpdate.Event.ADDED) {
                    val root = PlaneSampleHelper.entity ?: return@subscribeAnchorUpdate
                    val anchor = it.anchor
                    val indices = anchor.indices
                    val boundingBox = anchor.boundingBoxSize
                    val vertices = anchor.vertices
                    val semantic = anchor.semantics
                    val planeOrientation = anchor.planeOrientation
                    println(
                        "Log the Plane Anchor data: index = ${indices.joinToString(",")}" +
                            ", boundingBox = $boundingBox" +
                            ", semantic = $semantic" +
                            ", planeOrientation = $planeOrientation" +
                            ", vertices = ${vertices.joinToString(",")}"
                    )
                    val mesh = MeshResource.loadFromPlaneAnchor(it.anchor.anchorUUID)
                    val material =
                        UnlitMaterial.create().apply {
                            setBaseColor(Color4.BLACK)
                            setPolygonFillMode(PolygonFillMode.LINE)
                        }
                    val entity = ModelEntity(mesh, material)
                    val position = root.convertPositionFrom(it.anchor.transform.position, null)
                    val rotation =
                        root.convertRotationFrom(it.anchor.transform.rotation.toQuat(), null)
                    entity.components[TransformComponent::class.java]?.apply {
                        setPosition(position)
                        setQuaternion(rotation)
                    }
                    root.addChild(entity)
                    PlaneSampleHelper.entityMap[it.anchor.anchorUUID] = entity
                }
                if (it.event == AnchorUpdate.Event.REMOVED) {
                    if (PlaneSampleHelper.entityMap.containsKey(it.anchor.anchorUUID)) {
                        PlaneSampleHelper.entityMap[it.anchor.anchorUUID]?.destroy()
                        PlaneSampleHelper.entityMap.remove(it.anchor.anchorUUID)
                    }
                }
                if (it.event == AnchorUpdate.Event.UPDATED) {
                    if (PlaneSampleHelper.entityMap.containsKey(it.anchor.anchorUUID)) {
                        val root = PlaneSampleHelper.entity ?: return@subscribeAnchorUpdate
                        val entity = PlaneSampleHelper.entityMap[it.anchor.anchorUUID]
                        val mesh = MeshResource.loadFromPlaneAnchor(it.anchor.anchorUUID)
                        entity?.components?.get(ModelComponent::class.java)?.mesh = mesh
                        val position = root.convertPositionFrom(it.anchor.transform.position, null)
                        val rotation =
                            root.convertRotationFrom(it.anchor.transform.rotation.toQuat(), null)
                        entity?.components?.get(TransformComponent::class.java)?.apply {
                            setPosition(position)
                            setQuaternion(rotation)
                        }
                    }
                }
            }
        }
    }
}

fun loadAllPlaneAnchor() {
    CoroutineScope(Dispatchers.IO).launch {
        val anchors = PlaneTrackingManager.loadAllAnchors()
        anchors.forEach { println("The anchor uuid is ${it.anchorUUID}") }
    }
}

object PlaneSampleHelper {
    var entity: Entity? = null
    val entityMap = HashMap<UUID, Entity>()
}
