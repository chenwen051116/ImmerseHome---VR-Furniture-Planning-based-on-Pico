# About Spatial

## Spatial Engine Unified Rendering Model

There is a significant difference between the spatial engine's unified rendering and traditional Android self-rendering, so when analyzing the performance of spatial applications, it's necessary to conduct actual associated drill-down analysis based on the spatial engine unified rendering pipeline.
Regarding the 3D rendering process and performance analysis of the spatial engine, there are official documents available here: https://developer.picoxr.com/document/spatial-sdk/3d-rendering-performance-analysis/

The spatial engine unified rendering pipeline can be divided into five stages:
- APP Produce Stage: The application produces frame data, including user input, updating 2D/3D application status, interacting with the spatial engine, etc.
- APP -> SPR Handoff Stage: The application submits frame data to the spatial engine main thread.
- SPR Consume Stage: The spatial engine main thread receives the frame data submitted by the application to process unified rendering.
- EngineRender / Submit Stage: The spatial engine rendering thread is responsible for rendering 2D + 3D content and submitting rendering results.
- Compositor Stage: The compositor is responsible for compositing the rendering results into the final frame.

## Spatial App Thread Model
- At the end of each frame, the UI Thread (APP) submits 3D ECS data directly (asynchronously) to the Spatial Runtime.
- At the end of each frame, the Render Thread (APP) submits 2D ECS data to the Spatial Runtime.
- 3D and 2D ECS frame data are in different ECS Worlds and Scenes, and the data is isolated from each other.
- The Spatial Runtime obtains the frame data submitted by each application for rendering at the beginning of each frame.

Here, Spatial Runtime is the proxy process of the spatial engine.

## Spatial Frames/Metrics
When loading Spatial App Trace for performance analysis, in addition to the above Track/Slice, Perf CLI / PICO Spatial Profiler (PSP, a performance analysis GUI tool built for spatial applications) also provides SpatialFrames and SpatialMetrics unique to spatial applications to assist in performance diagnosis. You can enable spatial diagnosis by using `pico-cli perf trace load <trace-file> --spatial-diagnose true`.

**SpatialFrames**
A unique Track for spatial applications, recording the frame time distribution (CPU Time and GPU Time) of the spatial engine, and marking different frame statuses (Normal Frame / Late Frame / Miss Frame / Discard Frame / Early Frame).
Regarding Spatial Frames data, it can be queried via SQL in OpenXRClientSpatialFrames:
```sql
-- Query Spatial Frames data
SELECT * FROM OpenXRClientSpatialFrames;
```

**SpatialMetrics**
A unique Track for spatial applications (based on Trace Counter Event), recording the feature usage (Metrics, whose performance load cannot be ignored) of spatial applications and the entire spatial engine, as well as rule-based potential bottleneck detection for feature usage. Through the usage of these features, the scene complexity of the spatial application and the load of the spatial engine can be deduced to help with performance diagnosis.
Regarding Spatial Bottlenecks data, it can be queried via SQL in SpatialBottleneckEvents, and the corresponding optimization suggestions can be found in the SpatialBottleneckRuleSuggesstions table:
```sql
-- Query all potential bottlenecks detected based on rules
SELECT * FROM SpatialBottleneckEvents;

-- Query optimization suggestions corresponding to bottleneck id=3
SELECT * FROM SpatialBottleneckRuleSuggesstions WHERE rule_id = 3
```

Regarding Spatial Metric data, it can be queried via SQL in spatial_metrics_definitions:
```sql
SELECT * FROM spatial_metrics_definitions;
```

## Spatial Key Process/Thread/Slice
The following lists the key processes, Threads (Threads here refer to thread_track in Perfetto), and Trace Slices in the Spatial App Trace. They are crucial when performing spatial application performance analysis.
More detailed Trace events can be found on the developer's official website.

**Key Process:**
| Process Name | Process | Description |
| --- | ---- | ---- |
| OpenXR Server | com.pico.xr.openxr_runtime | OpenXR Server |
| OpenXR Client | com.pico.spatial.runtime | OpenXR Client, also the server for spatial applications (SpatialApp) (i.e., spatial engine). |
| Spatial APP | xx.xxx.xxxx | Target spatial application process, provided by the user. |
| pvr tracking service | pvrtrackingservice | Tracking system service in PICO OS 6 spatial operating system, including PoseTracking, HmdTracking, BodyTracking, etc. |
| pxr seethrough service | pxrseethroughservice | Seethrough system service in PICO OS 6 spatial operating system. |

**Key Thread:**
| Thread Track Name | Track Group Name | Process | Description |
| ---- | ------- | ------ | ------ |
| Spatial_Main * | com.pico.spatial.runtime * | com.pico.spatial.runtime | Main thread of the spatial application server (spatial engine) |
| XR_Wait * | com.pico.spatial.runtime * | com.pico.spatial.runtime | As an OpenXR client, waits for the frame rendering `WaitFrame` signal from the OpenXR server |
| gpu_frame_end * | com.pico.spatial.runtime * | com.pico.spatial.runtime | GPU frame completion marker used to align SPR rendering with GPU-side completion timing. |
| Eng-Render * | com.pico.spatial.runtime * | com.pico.spatial.runtime | Spatial engine render thread responsible for rendering and submit-stage workload. |
| frameRate | com.pico.spatial.runtime * | com.pico.spatial.runtime | Frame rate of the spatial engine in the last second |
| compositor * | com.pico.xr.openxr_runtime * | com.pico.xr.openxr_runtime | OpenXR rendering compositor |
| frameRate* | Application process | xx.xxx.xxxx | Frame rate of the application in the last second |

**Key Slice:**
| Slice Name | Thread Track Name | Description |
| ---- | -------- | --- |
| doFrameBegin()-* | Spatial_Main * | Frame start callback of the Spatial Runtime main thread |
| doFrameEnd()-* | Spatial_Main * | Frame end callback of the Spatial Runtime main thread |
| SpatialMFrame | Spatial_Main * | Frame callback of the Spatial Runtime main thread, similar to Choreographer#doFrame in Android application main thread |
| *System | Spatial_Main * | System callbacks regarding 3D ECS in the Spatial Runtime main thread, e.g., NodeGroup3DSystem |
| updateInput()-* | Spatial_Main * | Input event callback in the Spatial Runtime main thread |
| Choreographer#beginSpatialFrame | Application main process | Application frame start callback |
| 3d_ec | Application main process | Application 3D content refresh callback |
| Choreographer#endSpatialFrame | Application main process | Application frame end callback |
| System_Update | Application main process | Update 3D data, execute update() for all custom systems. |
| System_Update: {name} | Application main process | The update() callback of a specific custom system in the application |
| Spatial_App_Initialize | Application main process | Spatial Engine SDK initialization callback |
