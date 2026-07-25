# Human-readable Report Template

```markdown
# Spatial App Perf Diagnose Report: <package_name>

## 1. Analysis Scope & Trace Validation
- **Target App**: `<package_name>` (PID: <app_pid>)
- **SPR Process**: `com.pico.spatial.runtime` (PID: <spr_pid>)
- **Trace Duration**: <trace_duration>
- **Trace File**: `<trace_file>`
- **Session ID**: <session_id>
- **Time Base Alignment**: <alignment_summary>

## 2. Overview
- **App FPS**: <app_fps_summary>
- **SPR FPS**: <spr_fps_summary>
- **CPU/GPU**: <cpu_gpu_summary>
- **Decision Gate**: `<has_perf_issue yes|no>`; <decision_reason>

## 3. Window Selection
- **P0 Window**: `<abs_time_range>` (Trace Relative: `<relative_range>`). <why_selected>
- **P1 Window**: `<abs_time_range>` (Trace Relative: `<relative_range>`). <why_selected>

## 4. Window Drill-Down: <window_name>
### Key Threads & CPU State
- **<thread_1_name>**: **<root|contributor|victim>**. <cpu_state_summary>
- **<thread_2_name>**: **<root|contributor|victim>**. <cpu_state_summary>
- **<thread_3_name>**: **<root|contributor|victim>**. <cpu_state_summary>

### Top Slices & Worst Frame Analysis
- The worst frame is `<frame_name>` (`<frame_duration_ms>`). During this time, `<key_thread>` is `<thread_state_summary>`.
- **Deepest Visible Hotspots**:
  - `<hotspot_1>`
  - `<hotspot_2>`
  - `<hotspot_3>`
- **Mechanism in this window**: <one_paragraph_explaining_how_the_budget_is_blown>

### Secondary Bottleneck Check
- **Checked**: <binder|ipc|lock|io|gc|db|spr_pressure>
- **Result**: <secondary_bottleneck_summary>

## 5. Root Cause Summary
- **Primary Root Cause**: `<primary_root_cause>`
- **Secondary Factors**: `<secondary_factor_1>`, `<secondary_factor_2>`
- **Mechanism**: <2_to_4_sentence_summary_linking_thread_hotspot_to_frame_jank>

## 6. Optimization Actions
1. <optimization_action_1>
2. <optimization_action_2>
3. <optimization_action_3>

## 7. Risks & Unconfirmed Items
- <risk_or_unconfirmed_item_1>
- <risk_or_unconfirmed_item_2>
```
