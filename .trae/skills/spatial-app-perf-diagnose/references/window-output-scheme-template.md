# Window-level YAML Output Schema Template

```yaml
window_level_findings:
  - window_id: W4
    priority: P0/P1
    process_hint: app
    fast_report_time_range: "2026-04-09T22:12:54.000Z ~ 2026-04-09T22:12:57.000Z"
    perfetto_absolute_time_range: "2026-04-09T22:12:54.000Z ~ 2026-04-09T22:12:57.000Z"
    key_threads:
      - name: app_frame
        role: root
        cpu_state: "Running 66.34%, Runnable 4.14%, Sleeping 29.52%"
        top_slices:
          - "Choreographer#beginSpatialFrame total 2707.24ms avg 10.10ms max 54.59ms"
          - "animation total 1645.64ms max 51.46ms"
      - name: RenderThread
        role: victim
        cpu_state: "Sleeping 99.36%"
    worst_frames:
      - rank: 1
        absolute_time: "2026-04-09T22:12:55.xxxZ"
        frame_duration_ms: 54.59
        direct_cause: "main thread long frame"
        actionable_evidence:
          - "Compose:applyChanges 34.02ms"
          - "Recomposer:recompose 35.62ms"
      - rank: 2
        absolute_time: "2026-04-09T22:12:57.xxxZ"
        frame_duration_ms: 54.59
        direct_cause: "main thread long frame"
        actionable_evidence:
          - "Compose:applyChanges 34.02ms"
          - "Recomposer:recompose 35.62ms"
      - rank: 3
        absolute_time: "..."
        frame_duration_ms: 0
        direct_cause: "..."
        actionable_evidence: []
    waits_and_blocks:
      - "Wait_consumed max 11.20ms"
    render_pipeline:
      first_late_stage: app_produce_stage
      first_late_edge: App -> SPR
      upstream_owner: app frame-driving thread
      downstream_victims:
        - Spatial_Main
        - Eng-Render
        - XR_Wait
        - compositor
      pipeline_backpressure:
        - app_submit_late
        - spr_consume_late
        - eng_render_queue_buildup
        - openxr_or_compositor_backpressure
        - none_confirmed
      runtime_or_compositor_pressure: yes
      os_service_overlap: no
    spatial_signals:
      - "Late 17, Miss 10, Early 2"
      - "Triangle Count hit 29, max 588330"
    window_conclusion: "app_frame is primary root in this window"
```
