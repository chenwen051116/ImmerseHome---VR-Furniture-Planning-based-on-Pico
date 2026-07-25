---
name: testfull-project-guide
description: Project-specific guide for the TestFull PICO Room Planner (Kotlin + Spatial SDK). Invoke when modifying this repo's code, building/running the app, managing furniture models, or working with the AI arrangement system. Captures architecture, commands, model pipeline, and SDK pitfalls unique to this codebase.
license: 'Apache-2.0'
---

# TestFull — PICO Room Planner Project Guide

Project-specific conventions for `d:\Pico Dev\TestFull`. Invoke when working on **this** repo.
For generic PICO Spatial SDK questions, defer to `spatial-sdk-guideline`; this skill captures what's
specific to *this* codebase on top of that.

## What this app is

A PICO Spatial SDK app (package `com.example.testfull`, Kotlin + Jetpack Compose) that turns a 2D floor
plan into a room-scale VR environment, then furnishes it with local 3D models and AI-arranged layouts.
Stage style = Mixed. Targets Android API 35, `arm64-v8a` only, PICO Spatial SDK 0.13.x.

## Source map (`app/src/main/java/com/example/testfull/`)

- `content/HomeStage.kt` — total assembly. Holds state (plan/models/textures/AI), panel rig, view-following
  HUD (150ms rate-limit + jitter dead-zone + far-snap to prevent ANR), room rebuild flow, AI orchestration
  `runAiArrange`, debug hooks.
- `content/FloorPlanDesigner.kt` — plan editor panel (walls/doors/windows, inspector, env switch, virtual walk).
- `content/FurniturePanels.kt` — FurnitureLibrary + PlacementHud + AiArrange panels; `PanelFrame` shell.
- `content/ObjectPlacement.kt` — `PlacementController`: aim ray-cast, ghost preview, physics drop.
  Per-object independent `ShapeResource!`. Selection generation counter (prevents ghost entities on rapid taps).
- `content/AiArranger.kt` — prompt building (`buildArrangementMessages`), JSON parse, overlap resolution
  (`resolveAiPlacements` / `separateFromBoxes`), OpenAI-compatible HTTP, model catalog (defaultScale + bounds cache).
- `content/ModelLibrary.kt` — model scan, sidecar read + **distillation** (recursive null/empty strip), bounds cache.
- `content/TextureLibrary.kt` — texture scan / sidecar / `TextureCache`.
- `content/GeneratedRoom.kt` — plan → room geometry (wall solids, openings, floor/ceiling, lighting).
- `content/FloorPlanModel.kt` — plan data + geometry; **`demoFloorPlan()` default unit is here**.
- `platform/LaunchActivity.kt`, `platform/SpatialApplication.kt`, `Main.kt` — launch shell.
- `AndroidManifest.xml` — `pico.spatial.stage.*` metadata (style: 0 auto / 1 Mixed / 2 Progressive / 3 Full).

Tests: `app/src/test/java/com/example/testfull/content/*Test.kt` — PlacementMath / AiArranger / ModelLibrary /
TextureLibrary / FloorPlanModel.

## Build / run / test (Windows)

```bash
# local.properties: copy from local.properties.example, set sdk.dir + spatial.tools.dir + ai.api.*
cp local.properties.example local.properties

# Build (Git Bash)
export JAVA_HOME="/c/Program Files/Android/Android Studio1/jbr"   # adjust to your JBR
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain

# Install + launch
pico-cli app install app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.example.testfull     # pico-cli launch won't restart a running app
pico-cli app launch com.example.testfull
```

`ai.api.*` in `local.properties` → `BuildConfig.AI_API_BASE / AI_API_KEY / AI_API_MODEL` at build time.
**Changing them requires a rebuild.** Default model `gpt-5`; `gpt-4o-mini` is faster but worse layouts.

## Model pipeline (adding furniture — do all 4 or it breaks)

Device model dir: `/sdcard/Android/data/com.example.testfull/files/models/`. Push via
`node model-manager/server.js` (→ http://localhost:8931, recommended), `push-model.sh <file.glb>`, or
`adb push` (Git Bash: `export MSYS_NO_PATHCONV=1` first or `/sdcard` gets mangled).

```bash
python tools/glb-shrink-textures.py new.glb          # 1) textures ≤1024 (prevents emulator LMK)
python tools/glb-rescale.py measure new.glb          # 2) measure real bbox (includes node transforms)
python tools/glb-rescale.py bake new.glb 宽 深 高     # 3) bake real size into GLB (meters)
# 4) write new.json sidecar (schema_version 1); geometry.* = post-bake actual values
python tools/seed-bounds-cache.py --models-dir models --package com.example.testfull --push  # 5) reseed cache
```

Re-run step 5 whenever models change (cache invalidates by mtime). Without it, first Scan/AI does native
per-model measurement — slow and may crash.

Sidecar `geometry.*` is **real size**; app computes `defaultScale` from it (scale=1 if matches GLB measured).

## AI arrangement system

Entry: AI Arrange panel or debug hook. Flow:
1. `buildCatalog` (cache-first) → per-model center/halfExtents/bottomOffset/defaultScale/details.
2. `buildArrangementMessages` assembles system prefill (designer role + spatial vocabulary + room semantics
   + schema + examples) + user ROOM JSON (bounds/walls/openings/existing furniture) + LIBRARY + TEXTURES + request.
3. POST `{base}/chat/completions`, `response_format: json_object`, temp 0.4, read timeout 180s.
4. `parseAiLayout` (tolerant) → `resolveAiPlacements` physics: clamp into room polygon, push apart overlaps
   (0.05m steps, tolerance=0, max 4m), texture match via `resolveAiTextures` (name fuzzy + surfaces enforced).
5. If textures: rebuild room + wait, then clear old furniture, `placeFromAi` each (full bbox collider + CCD + 0.08m drop).

Edit prompt → `AiArranger.kt` `buildArrangementMessages`. Edit validation → `resolveAiPlacements` / `separateFromBoxes`.
After changes run `AiArrangerTest`.

## Debug hooks (debug build only — write file to trigger, no UI needed)

```bash
adb shell "echo 'give me a modern bedroom' > /sdcard/Android/data/com.example.testfull/files/ai_test_prompt.txt && chmod 666 /sdcard/Android/data/com.example.testfull/files/ai_test_prompt.txt"
# chmod 666 mandatory — app can't read 660 (reads empty string then deletes)
adb shell "echo 'place:bed-001' > ...same path... && chmod 666 ..."   # simulate "select model"
```

Logs (user images hide `Log.d`, so key logs use `Log.w`):
`adb logcat -d | grep -E "HomeStage|AiArranger|ModelLibrary|PlacementController|TextureLibrary"`.
Full request (ROOM JSON) + raw AI response are under the `AiArranger` tag.

## Critical SDK pitfalls (all hit before — don't repeat)

1. `MeshResource.load(path, FROM_STORAGE)` does **not** support GLB (FORMAT_UNSUPPORTED). Measure bbox via
   `Entity.getVisualBounds(relativeTo = entity, recursive = true)` — **must be main thread**, excludes scene
   root's own transform (bake size into vertices/children, not just root).
2. `Entity.loadSuspend` on >10MB models peaks ~2× memory; **consecutive/concurrent loads trigger LMK**.
   Release old ghost before loading new.
3. Each dropped object needs its **own `ShapeResource`/material** — shared handles get closed early by SDK
   ("not shape resource id: 0").
4. SDK closes a material when its entity is destroyed — **never cache materials to reuse across entities**
   (ghost material must be newly built each time).
5. `ModelComponent.materials` getter round-trips to native each call — read once, don't call in a loop (ANR).
6. Panel surface redraws on every Transform change — **rate-limit HUD updates**.
7. User images hide `Log.d` — diagnostics use `Log.w`.
8. `CollisionComponent` has no per-shape offset — use `ShapeResource.offsetByTranslation(center)` (close intermediates).
9. glTF root transform is folded into `TransformComponent` by SDK; `spawnPlaced`'s explicit `setScale` overrides it.

## Emulator survival

- Cold start shows "Not responding" — click **Wait** (not Close), once only (x86 + Houdini ARM translation).
- LMK may kill system processes under memory pressure — textures ≤1024, release ghost before AI, one big model at a time.
- ~1–15 FPS normal; `FRAME_TOO_SLOW` spam is expected, not a bug.
- After overlay-install, force-stop before relaunching, don't interact immediately.
- Don't delete `sdcard.img`/`userdata` (has your models + app); save-state/snapshots are safe to delete.

## Common task recipes

- **Add furniture**: model pipeline 4 steps + sidecar + push + seed cache.
- **Change default unit**: `FloorPlanModel.kt` `demoFloorPlan()`; mind `FloorPlanModelTest` assertions.
- **Swap AI model/endpoint/key**: `local.properties` `ai.api.*` + rebuild + reinstall.
- **Adjust panel position/size**: `HomeStage.kt` initial coords/yaw + `FurniturePanels.kt` `PanelFrame(w,h)`;
  HUD follow params in same file's top constants.
- **Edit AI prompt**: `AiArranger.kt` `buildArrangementMessages`; run `AiArrangerTest` after.
- **New device first run**: env setup → `local.properties` → build/install → `pico-cli emulator start`
  → seed bounds cache → app Furniture → Scan → play.

## Reference docs in this repo

- `README.md` — overview + quickstart.
- `RUNBOOK.md` — full operations + dev manual (Chinese; the authoritative source for this skill).
- `docs/open-interface-ui-launcher.md` — the view-following UI rig design.
- `local.properties.example` — config template with AI relay defaults.
