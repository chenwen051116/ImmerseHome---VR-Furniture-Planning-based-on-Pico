---
name: spatial-app-perf-diagnose
description: Diagnose the performance status of Spatial App, identifying performance bottlenecks in the Android 2D drawing and PICO OS spatial engine 2D&3D rendering pipelines. Supports analyzing the entire chain from system clock, 2D drawing, 3D logic to spatial engine rendering threads, providing deep performance analysis, root cause diagnosis, and optimization suggestions based on Perfetto Trace.
license: 'Apache-2.0'
---

# Spatial APP Perf Diagnose

This Skill is used to perform strict, actionable, and reviewable performance diagnosis for PICO Spatial APPs on physical PICO devices. Emulator data may be used only as a non-authoritative fallback when the user explicitly accepts that limitation.

## When to Use

Use this skill when analyzing and diagnosing the performance status of a Spatial APP on physical PICO devices. For example, use this skill when analyzing the following performance issues:

- Stuttering, frame drops, frame rate degradation, unstable frame rate
- High CPU / GPU load
- High scene complexity: DrawCalls, Triangles, Mesh, Vertex
- Slow startup, slow loading, latency

## Tools

Use `pico-cli` commands to check environment like devices:

- check device: `pico-cli device list`
- check app info: `pico-cli app info com.example.app`
- check app process id: `pico-cli adb shell pidof com.example.app`

Use `pico-cli perf` commands for performance profiling:

- Check or install perf toolchains: `pico-cli perf doctor check/install ..`
- Real-time performance diagnosis: `pico-cli perf monitor run ..`
- Perfetto Trace capture: `pico-cli perf trace record ...`
- Perfetto Trace analysis: `pico-cli perf trace load ...` / `pico-cli perf trace query ...`

Use adb from `ADB_PATH` ENV PATH.

Read [Profiler CLI Usage](./references/perf-cli-usage.md) for detailed usage.

## Analyze Workflow

Analysis work **MUST** follow this Workflow step 1~7.

### 1. Perf Data Capture

This step has three sub-stages. Sub-stage 1.1 and 1.2 are **interactive confirmation gates** that exist to make the capture safe and reproducible. Always **auto-detect first, then ask the user only to confirm or override**; never turn this into a blind questionnaire.

#### 1.1 Confirm Capture Parameters (Gate 1)

Before any capture command runs, the following parameters **MUST** be settled. For each parameter, first try to auto-detect a default value, then present the resolved values to the user and ask them to confirm or override.

| Parameter                           | How to resolve / detect                                                     | Effect on diagnosis                                                                                 |
| ----------------------------------- | --------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| `package` (app package name)        | User message > `pico-cli app info <pkg>` > current foreground app           | Required for `--app` and `pidof` process location                                                   |
| `duration` (capture seconds)        | User message, otherwise default `30`; **30 seconds or more is recommended** | `--duration`                                                                                        |
| `app_type` (release / debuggable)   | `pico-cli app info <pkg>` debuggable flag                                   | release builds may lack userspace/atrace slices, so leaf-level causes may be invisible              |
| `device_type` (emulator / physical) | `pico-cli device list` model and characteristics                            | This skill targets **physical PICO devices**; emulator SPR / GPU / spatial-frame data is unreliable |

Mandatory rules for 1.1:

- **Always include `package` in the Gate 1 confirmation summary, even when a Perfetto Trace file is explicitly provided.** If the user already provided it, adopt it as the resolved value; if it is missing or ambiguous, ask the user to confirm or override the auto-detected value. The package name is still required for process / pid alignment in later steps.
- Parameters already provided in the user's message (such as `package` or `duration`) **MUST** be adopted as the resolved values; ask the user only for values that are missing or ambiguous.
- If `device_type = emulator`: **MUST** explicitly warn the user that emulator perf data is unreliable, and proceed only after the user explicitly confirms this non-authoritative analysis mode.
- If `app_type = debuggable`: **MUST** explicitly warn the user that debuggable-build perf data does not reflect real release performance, and proceed only after the user explicitly confirms this limitation.
- Use a host-agnostic confirmation mechanism (whatever the current host provides for asking the user); do not hardcode any specific tool name.

#### 1.2 Preview Commands and Final Confirmation (Gate 2)

- If a Perfetto Trace file is explicitly provided, skip capture command execution (parameters from 1.1, especially `package`, are still used for later analysis).
- Otherwise, before executing capture, **MUST** present the fully-resolved capture commands (with real arguments, duration, and output paths) to the user, state that capture will run for about N seconds and that the application **MUST** stay running in the target scene, and only start execution after the user confirms.

#### 1.3 Execute Capture

- Ensure the target application being analyzed is running.
- Start executing real-time performance diagnosis (Real-time Diagnosis) and Perfetto Trace capture **in parallel**, and use the same resolved `<duration>` for both captures unless the user explicitly requests different durations.

  ```bash
    # Parallel Task 1: Execute Real-time Diagnosis, perform <duration> seconds of real-time performance diagnosis data collection.
    pico-cli perf monitor run --app <package> --duration <duration> --output ./<perf_analyze>/<package>/<timestamp>/report.json
  ```

  ```bash
    # Parallel Task 2: Execute Perfetto Trace Capture, perform <duration> seconds of Perfetto Trace data collection.
    pico-cli perf trace record --duration <duration> -o ./<perf_analyze>/<package>/<timestamp>/trace.perfetto-trace
  ```

**Prohibited** actions:

- Directly using historical Perfetto Trace files from the repository or project without explicitly being provided a Perfetto Trace file.
- Starting any capture command before Gate 1 (1.1) and Gate 2 (1.2) confirmations are completed.

### 2. Perf Data Load

```bash
# Ensure Perf Daemon is started
pico-cli perf daemon status
# If not started, start the Perf Daemon first
pico-cli perf daemon start
# Load the Perfetto Trace file, the Perfetto Trace file path must use an absolute path
pico-cli perf trace load <Workspace>/<perf_analyze>/<package>/<timestamp>/trace.perfetto-trace
```

### 3. Perf Data Overview

#### Real-time Report Overview

If a Real-time Diagnosis Report exists, **MUST** summarize:

- Whether there is jank
- Which process is marked as abnormal
- List of all abnormal windows
- The absolute time period of each window
- `diagnosis.status`
- `diagnosis.category`
- `diagnosis.rule`

#### Perfetto Trace Overview

For overview only, do not output the final root cause at this stage.

**MUST** summarize:

- SpApp overall frame pacing
- SPR overall abnormal frame distribution
- CPU / GPU overall load
- Overall resource complexity status

#### Unified Rendering Pipeline Overview

**MUST** summarize:

- Whether SpAPP frame production is continuously late
- Whether SPR has continuous high pressure during the application frame consumption stage
- Whether Eng-Render has continuous high load
- Whether compositor has independent pressure

### 4. Perf Data Validation

#### Validation 1: Trace Quality

**MUST** check:

- Trace duration >= 3s
- Trace contains:
  - Target SpApp process
  - `com.pico.spatial.runtime`
  - `com.pico.xr.openxr_runtime`
- Key threads with valid data exist:
  - `app frame-driving thread`
  - `RenderThread`
  - `Spatial_Main`
  - `Eng-Render`
  - `XR_Wait`
  - `gpu_frame_end`
  - `compositor`

Output **MUST** include:

- trace path
- session id
- trace duration
- SpApp pid
- SPR pid
- whether diagnose tables are available

#### Validation 2: Time Alignment

- If a Real-time Diagnosis Report exists, **MUST** verify if the time of the Real Diagnosis Report can be aligned with the time of the Perfetto Trace to ensure that the sets of data were collected during the same running:
  - If they cannot be aligned, mark the Real Diagnosis Report as unavailable, and subsequent analysis **MUST NOT** reference the Real Diagnosis Report.
  - If they can be aligned, all subsequent window outputs **MUST** be associated with the corresponding Perfetto Trace time.
- If no Real Diagnosis Report exists, subsequent analysis **MUST** be expanded only based on the Perfetto Trace.

#### Validation 3: has_perf_issue Check

**Execution Prerequisite**: A Real Diagnosis Report exists, and its time can be aligned with the Perfetto Trace time.

`has_perf_issue` Check:

- If `diagnosis.status` is normal, and `jankReports` is empty or forms no clear abnormal window, then `has_perf_issue = no`
- If `diagnosis.status` is warning/abnormal, or clear abnormal windows exist in `jankReports`, then `has_perf_issue = yes`

**Execution Gate**:

- If `has_perf_issue = no`:
  - **MUST** immediately stop subsequent Analyze Workflow steps.
  - Finally, only allowed to output the conclusion "No clear performance issues observed in this sampling".

**Exception Path**:

- Even if `has_perf_issue = no`, continuing to subsequent Analyze Workflow steps is only allowed when one of the following conditions is true:
  - The user suspects the Real Diagnosis Report has missed reporting, or explicitly requests to analyze the Perfetto Trace.
  - The current goal is not to determine if there is a problem, but to verify whether a suspicious pipeline has potential long tails.
- When entering the exception path, must explicitly mark:
  - `fast_report_decision = no_issue_detected`
  - `trace_analysis_mode = exception_path`
- Under the exception path, the conclusion must not be written as "Performance issues confirmed", but only "Potential risks found" or "Did not form Real Diagnosis Report level abnormality".

### 5. Window Selection and Prioritization

**MUST Check**:
Continuing to this step and subsequent steps is only allowed when one of the following conditions is true:

- `has_perf_issue = yes`
- Entered `exception_path`
- `Validation 3: has_perf_issue Check` has not been executed

**Window Selection**:

1. Use each jank window in the Real Diagnosis Report as an analysis window.
2. If there is no Real Diagnosis Report, reverse-infer candidate windows from the trace:
   - App Top-10 long frame windows
   - `resynced` windows
   - `SpatialRuntimeAbnormalFrames` burst windows
   - CPU / GPU spike windows
   - Loading, initialization, scene switching stages

**Window Prioritization Rules**:

- Abnormal frame ratio
- Duration
- Frame fluctuation

**MUST** select at least:

- `P0`: Top 3 most important core windows
- `P1`: Next 10 important auxiliary windows

### 6. Window Drill-Down

The goal of Window Drill-Down is to simultaneously establish a chain of evidence at the unified rendering pipeline level, thread level, and frame level.

**Investigation Protocol**:

The drill-down for each P0 / P1 window **MUST** comply with the following protocol:

1. First form a working hypothesis for the current window, for example:
   - app main thread CPU hotspot
   - SPR main thread CPU hotspot
   - Synchronous Binder / Lock contention
   - Waiting victim thread, need to trace blocker
   - Handoff late or backpressure in rendering pipeline
   - EngineRender high load, late, or backpressure
2. All verified facts must first be written into the current round's evidence scratchpad; it is forbidden to write hypotheses as conclusions.
3. For any suspicious long Slice, its thread_state within the exact window must be checked: Running / Runnable / Sleeping / Blocked / D
4. If the thread is mainly not in Running, but waiting or blocked:
   - Must not directly judge this thread as the root.
   - Must continue tracking the root cause of its Sleeping/Blocked/D.
   - Must try to identify the process, thread, and hotspot where the root of Sleeping/Blocked/D is located.
5. All time range queries must use overlap semantics, filtering only by start time dropping within the window is prohibited.
6. If there are multiple bad frames in the P0 / P1 window, the internal slice pipeline of each bad frame must be analyzed separately.
7. Must not stop immediately after finding the main abnormality; must check at least once more whether an independent secondary bottleneck exists.

**Mandatory Order**:

Each P0 / P1 window **MUST** be executed in the following order:

1. Select key threads
2. Check CPU state distribution
3. Check Top Slices
4. Check waiting / blocking / locks / GC / Binder call blocking / CPU scheduling and preemption
5. Check Top-10 worst frames
6. Recursively expand the internal slice pipeline of bad frames down to the leaf cause level
7. Do cross-process expansion
8. Align SpatialFrames / Bottleneck / Resource complexity
9. Output Top-10 worst frames conclusions and window conclusions

Skipping steps is prohibited.

#### 6.1 Key Thread Selection

| process_hint | P0 Must Check                                                            | P1 Conditional Check                                        |
| ------------ | ------------------------------------------------------------------------ | ----------------------------------------------------------- |
| app          | `app frame-driving thread`, `RenderThread`                               | `Spatial_Main`, `Eng-Render`, GC/JIT, Binder, worker        |
| spr          | `Spatial_Main`, `Eng-Render`, `XR_Wait`                                  | `app frame-driving thread`, `RenderThread`, `gpu_frame_end` |
| mixed        | `app frame-driving thread`, `RenderThread`, `Spatial_Main`, `Eng-Render` | `XR_Wait`, `gpu_frame_end`, GC/JIT, Binder                  |
| unknown      | `app frame-driving thread`, `RenderThread`, `Spatial_Main`, `Eng-Render` | `XR_Wait`, `gpu_frame_end`, system service                  |

Additional rules:

- `app frame-driving thread` must be dynamically confirmed via `Choreographer#beginSpatialFrame`
- If `process_hint = app`, but SPR abnormal frame rate is high or resource complexity is continuously high, must simultaneously check `Spatial_Main + Eng-Render`
- If `process_hint = spr`, must reverse check `app frame-driving thread + RenderThread`

#### 6.2 CPU State Distribution

Must tally within the current window:

- `Running`
- `Runnable`
- `Sleeping`
- `Blocked`
- `D`

Output judgment:

- `cpu_hot`
- `runnable_pressure`
- `waiting_victim`
- `kernel_or_gpu_blocked`

Additional mandatory rules:

- For any thread considered a "hotspot thread", must distinguish:
  - Long wall time
  - Or true high CPU Running
- If a long slice's wall time is very long, but the thread is mainly not `Running`:
  - Must not treat this slice directly as a CPU root cause
  - Must continue troubleshooting its waiting source

#### 6.3 Top Slice Aggregation

For suspicious threads, Top Slices must be output:

- `cnt`
- `total_ms`
- `avg_ms`
- `max_ms`

And marked as:

- `steady_hotspot`
- `tail_hotspot`
- `waiting_hotspot`

#### 6.4 Waiting / Lock / GC Check

Must specifically search for and categorize:

- `wait`
- `waitFrame`
- `Wait_consumed`
- `waitForCommands`
- `futex`
- `Lock contention`
- `Contending for pthread mutex`
- `FullSuspendCheck`
- `Marking thread roots`

Output must be clear:

- Is it waiting for upstream, waiting for downstream, lock contention, or GC amplification?

Additional mandatory rules:

- If `wait`, `waitFrame`, `Wait_consumed`, `waitForCommands`, `futex`, `Lock contention` are found:
  - Must answer whether the thread is waiting for upstream, downstream, locks, or system services.
  - Must try to identify the associated thread or process.
  - If the associated thread has a clear Running hotspot, the associated thread is prioritized as a `root` candidate.
- If Binder / IPC is seen:
  - Must continue to check:
    - Is it a synchronous call?
    - Call count
    - Total duration
    - Maximum single duration
    - Can the target service or interface be identified?
  - If the thread making the Binder call is mainly not in Running, the Binder call thread must not be directly judged as the root cause.

#### 6.5 Jank Frame Drill-Down

For each P0 / P1 window, select the Top-10 worst frames to continue drilling down, do not stop at parent Slices like `animation`, `beginSpatialFrame`, `System_Update`.

Execution actions:

1. First list `Top-10 jank frames`
2. Select at least 3 representative worst frames
3. Record for this frame:
   - `frame_time`
   - `perfetto_absolute_time`
   - `frame_duration_ms`
   - `parent_slice`
4. Expand sub-Slices all the way from the parent hotspot until a more direct time-consuming source is seen.
5. Output a hotspot pipeline from parent to leaf cause, for example:
   - `Choreographer#beginSpatialFrame -> animation -> binder transaction -> xxx service`

When continuing to look down, prioritize identifying these leaf types:

- File IO: `open/read/pread/fsync/stat`
- DB: `SQLite/query/cursor/Room`
- Binder / IPC: `binder transaction`, `binder reply`, `ContentProvider`, `system service call`
- decode / inflate / asset load
- Lock contention / futex
- GC / ART
- Business function leaf Slices

If Binder / IPC is seen in the worst frame of the main thread, add three more things:

- Is it a synchronous call?
- Tally the count, total duration, maximum single duration.
- Try to identify the target service, interface, or call object.
- Differentiate CPU states on the binder call chain.

If it can reach the leaf layer, use a two-part description of "parent hotspot + direct cause" when outputting, for example:

- `parent_hotspot: animation`
- `direct_leaf_cause: main-thread synchronous binder transaction`

If the trace can only see the parent hotspot and cannot see finer sub-Slices, explicitly write "Currently only located to the deepest visible layer", and do not directly treat the parent hotspot as the final code problem.

Optionally do another layer of categorical aggregation to help developers quickly determine what type of operation this frame is mainly spent on:

- `binder_total_ms`
- `db_total_ms`
- `io_total_ms`
- `decode_total_ms`
- `lock_total_ms`
- `gc_total_ms`

Additional mandatory rules:

- For representative worst frames, not only expand parent-child slices but also verify the thread_state of key threads within the time range of that frame.
- If multiple candidate leaves exist under a parent hotspot:
  - Must distinguish `primary_leaf_cause`
  - And `secondary_leaf_factors`
- If it can only be located to the parent hotspot, must explicitly write:
  - `deepest_visible_layer`
  - Must not directly use the parent slice name as the final code problem.

#### 6.6 Root Thread vs Victim Thread

Must tag each key thread:

- `root`
- `contributor`
- `victim`
- `unknown`

Judgment rules:

- High `Running` and clear hotspot: prioritize `root` or `contributor`
- Waiting-class slices dominate: prioritize `victim`
- If the current thread is mainly waiting, and the associated thread simultaneously has high `Running` and clear hotspots: the associated thread is more likely the `root`

Additional judgment rules:

- If a thread is mainly in a waiting state, it is only allowed to be marked as `root` in one of the following two situations:
  - Proven to be self-blocking caused by active synchronous calls.
  - Proven that the thread holds a lock or initiates a synchronous chain causing backpressure.
- Otherwise default to prioritizing as:
  - `victim`
  - Or `contributor`
- If the blocker is not found:
  - Can only output `unknown_need_more_evidence`
  - Must not output "Root cause confirmed"

#### 6.7 Unified Rendering Pipeline Analysis

After completing thread-level and frame-level drill-down, the current window must be further mapped to the unified rendering pipeline.

The unified rendering stages are at least divided into:

- `app_produce_stage`
- `app_to_spr_handoff_stage`
- `spr_consume_stage`
- `eng_render_submit_stage`
- `openxr_or_compositor_stage`

Must answer:

1. `first_late_stage`
   - The unified rendering stage where signs of clear budget overrun or lateness first appear in the current window.
2. `first_late_edge`
   - The cross-stage boundary that first goes wrong in the current window, for example:
     - `App -> SPR`
     - `SPR -> Eng-Render`
     - `Eng-Render -> OpenXR/Compositor`
3. `upstream_owner`
   - The upstream stage / process / thread that truly is late or blocked first.
4. `downstream_victims`
   - Downstream threads or stages collaterally affected by upstream lateness, for example:
     - `Spatial_Main`
     - `Eng-Render`
     - `XR_Wait`
     - `compositor`
5. `pipeline_backpressure`
   - Is there clear backpressure:
     - `app_submit_late`
     - `spr_consume_late`
     - `eng_render_queue_buildup`
     - `openxr_or_compositor_backpressure`
     - `none_confirmed`
6. `runtime_or_compositor_pressure`
   - Is there independent pressure in `OpenXR Runtime / compositor`, rather than just upstream app slowdown?
7. `os_service_overlap`
   - Do tracking / seethrough / system services overlap with the current abnormal window, and could they constitute amplification factors?

Mandatory rules for unified rendering analysis:

- Must not assume the app is the sole main cause in unified rendering just because the `app frame-driving thread` is very hot.
- If any object among `Spatial_Main`, `Eng-Render`, `XR_Wait`, `compositor` shows obvious abnormality in the current window, it must be explained as:
  - `upstream_owner`
  - `downstream_victim`
  - `independent_contributor`
- If only thread hotspots can be confirmed, but the first late edge in unified rendering cannot be confirmed, it must explicitly output:
  - `first_late_edge = unknown_need_more_evidence`

#### 6.8 Spatial Metrics Alignment

**Execution Prerequisite(Satisfy one)**:

- jank process is SPR
- `first_late_edge=SPR -> Eng-Render`
- `first_late_edge=Eng-Render -> OpenXR/Compositor`

If diagnose tables are available, the window must be aligned with the following tables:

- `OpenXRClientSpatialFrames`
- `SpatialRuntimeAbnormalFrames`
- `SpatialBottleneckEvents`
- `TargetAppMetricCounterStates`

Must answer:

- Did `Late / Miss / Early` burst concentratedly within the window?
- Is SPR `CPUTime / GPUTime` continuously high?
- Are DrawCall / Triangle / Vertex / Mesh / Texture continuously high?
- Do these signals overlap with thread-level abnormalities?

#### 6.9 Outlier Handling

If a **single** long-tail Slice `>33.3ms` is found, it must be judged separately:

- `real stall`
- `single outlier`
- `trace anomaly`

Extrapolating a single extreme outlier directly as the main pattern for the entire window is prohibited.

#### 6.10 Window Output Schema

The output for each P0 and P1 window must follow the schema shown in [window-output-schema-template](./references/window-output-scheme-template.md).

If this schema is not met, Step 7 must not be entered.

#### 6.11 Secondary Bottleneck Scan

After completing the main evidence chain for the current window, a lightweight review must be done again to confirm whether an independent secondary bottleneck exists.

At least check:

- Binder / IPC long tail
- lock / futex
- IO / decode / DB
- SPR independent high pressure
- Whether resource complexity peaks overlap with thread abnormalities

Output fields:

- `secondary_bottleneck_checked: yes`
- `secondary_bottleneck_found: yes|no`
- `secondary_bottleneck_summary`

### 7. Root Cause Summary

Root cause summarization can only be done based on the completed `window_level_findings`.

#### 7.1 Allowed Primary Root Causes

Can only choose from the following set:

- `app_main_cpu_hotspot`
- `app_render_thread_blocked`
- `app_content_pressure_on_spr`
- `spr_cpu_pipeline_pressure`
- `spr_gpu_pipeline_pressure`
- `app_spr_interlock`
- `gpu_pressure`
- `system_pressure`
- `xr_runtime_or_compositor_pressure`
- `unknown_need_more_evidence`

#### 7.2 Secondary Factors

Can only choose from the following set:

- `gc_amplifier`
- `lock_contention`
- `binder_or_ipc_block`
- `resource_complexity_high`
- `drr_masking_problem`
- `tail_latency_only`
- `waiting_not_root`

#### 7.3 Summary Rules

1. Must first select `primary_root_cause`
2. Then supplement `secondary_factors`
3. Then output `optimization_actions`
4. Must explain:
   - Which windows support this conclusion
   - Which threads are the main evidence
   - Which worst frame is the most critical

#### 7.4 Final Deliverables

Must output two deliverables simultaneously:

1. **Structured Conclusion**
   - Output in `yaml` format file `<perf_analyze>/<package>/<timestamp>/analyse-report.yaml`, must simultaneously contain:
     - `window_level_findings`
     - `root_cause_summary`
   - This deliverable is intended for programs, Agents, and subsequent automated consumption.
   - Standard schema:

   ```yaml
   root_cause_summary:
     primary_root_cause: app_main_cpu_hotspot
     secondary_factors:
       - resource_complexity_high
     affected_windows:
       - W3
       - W4
       - W5
     key_evidence:
       - 'W4 app_frame beginSpatialFrame max 54.59ms'
       - 'W4 worst frame shows main-thread long tail'
       - 'W3/W5 resource complexity overlaps with SPR pressure'
     optimization_actions:
       - 'Optimize main-thread frame-driving work'
       - 'Reduce DrawCall / Triangle / Vertex pressure'
   window_level_findings: ...<Every P0 and P1 window findings>...
   ```

2. **Human Readable Report**
   - Output in `Markdown` format report file `<perf_analyze>/<package>/<timestamp>/analyse-report.md`, intended for developers to read.
   - This report must be generated based on the same structured conclusion and must not contradict the YAML conclusion.
   - File structure must follow the standard template shown in [diagnosis-report-template](./references/diagnosis-report-template.md).

## Review Checklist

Before writing conclusions, must quickly self-check and answer these items:
[] Do core windows have absolute time?
[] Has every P0 and P1 window drill-down analyzed all jank frames?
[] Is root / contributor / victim distinguished?
[] Have parent hotspots been drilled down to more direct leaf causes?
[] If Binder / IPC appears on the main thread, is the source of its call blocking drilled down?
[] Is evidence_log recorded, rather than just outputting conclusions?
[] For the most critical long slices, is the exact thread_state verified?
[] If the thread is mainly waiting, is the blocker traced?
[] Did time range queries use overlap semantics?
[] Was an independent secondary bottleneck checked, rather than stopping after finding the first abnormality?

If these are not yet done, write the current result as "Drilled down to a certain layer", do not write it as "Final cause has been located".

## Terminology

Only high-frequency terms of necessary parts are listed here:

- `SpApp`: Target spatial application process
- `SPR`: `com.pico.spatial.runtime`
- `app frame-driving thread`: The thread carrying `Choreographer#beginSpatialFrame`, hardcoding as `main` is not allowed
- `window`: Abnormal time window, prioritized from `jankReports` of Real-time Report
- `worst frame`: The worst 1 to 10 long frames within the current window

## References

- [Profiler CLI Usage](./references/perf-cli-usage.md)
- [Details about Spatial](./references/about-spatial.md)
- [diagnosis-report-template](./references/diagnosis-report-template.md)
- [window-output-schema-template](./references/window-output-scheme-template.md)
