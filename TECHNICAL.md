# PICO Room Planner — Technical Documentation

> **Scope:** Everything an engineer needs to understand, build, extend, and debug this project.
> **Audience:** Kotlin/Android developers familiar with VR/Spatial SDK concepts.
> **Last updated:** 2026-07-26

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [System Architecture](#2-system-architecture)
3. [Build & Toolchain](#3-build--toolchain)
4. [Module Map](#4-module-map)
5. [Floor Plan Subsystem](#5-floor-plan-subsystem)
6. [Room Generation](#6-room-generation)
7. [Furniture Placement Engine](#7-furniture-placement-engine)
8. [Model & Texture Libraries](#8-model--texture-libraries)
9. [AI Arrangement Subsystem](#9-ai-arrangement-subsystem)
10. [UI Panel System & View-Following Rig](#10-ui-panel-system--view-following-rig)
11. [Asset Bundling & Seeding](#11-asset-bundling--seeding)
12. [Configuration Reference](#12-configuration-reference)
13. [Operational Runbook](#13-operational-runbook)
14. [Performance & Pitfalls](#14-performance--pitfalls)
15. [Extension Guide](#15-extension-guide)

---

## 1. Project Overview

### 1.1 What it is

**PICO Room Planner** (`com.example.testfull`) is a VR room-planning application built on the **PICO Spatial SDK 0.13.3**. The user wears a PICO headset (or runs the PICO Emulator), sketches a 2D floor plan, and walks through a real-scale 3D reconstruction of that plan. They can place furniture by hand, ask an OpenAI-compatible LLM (default `gpt-5`) to arrange furniture from a natural-language prompt, and reskin wall/floor/ceiling/door/window surfaces with textures.

### 1.2 What it does (end-to-end workflow)

1. **Draw a plan** — Tap wall endpoints on a 0.5 m grid, insert doors/windows on walls, edit dimensions in an inspector.
2. **Apply & preview** — A native Spatial SDK scene is rebuilt immediately: split wall solids around openings, transparent glass windows, ceiling lights + daylight, environment shell, static colliders.
3. **Place furniture** — Pick a `.glb` model from a bundled library; a translucent ghost follows the controller aim; trigger-drop spawns a dynamic rigid body that settles under physics.
4. **Arrange with AI** — Describe a layout in natural language; GPT-5 returns a JSON placement array validated against room geometry (clamping + overlap separation) and spawned one by one.
5. **Reskin surfaces** — Pick a texture per slot (wall/floor/ceiling/door/window); apply rebuilds the room with PBR materials (base color + normal + roughness + metallic).
6. **Iterate (optional)** — The AI re-evaluates its placed layout and revises it up to 3× until it declares satisfaction.

### 1.3 Key design principles

- **Single Stage, single Activity** — `LaunchActivity : SpatialLaunchActivity` → `SpatialApplication.onCreate()` calls `launch(::mainApp)`. `mainApp` builds `DefaultStage { PicoTheme { HomeStage() } }`.
- **Stage style = Mixed** — Real environment visible at full intensity alongside virtual content (AndroidManifest meta-data `pico.spatial.stage.style = 1`).
- **Compose-only UI** — All panels are Jetpack Compose `@Composable`s hosted in `AttachmentPanel`s; no XML layouts.
- **Native SDK for everything 3D** — Walls, furniture, lights, physics, raycasts all go through `com.pico.spatial.core.*`.
- **Filesystem-first asset model** — Models/textures live in `/sdcard/Android/data/com.example.testfull/files/models/`; the APK bundles a starter set and seeds it on first scan (see §11).

---

## 2. System Architecture

### 2.1 High-level data flow

```
┌─────────────────────────────────────────────────────────────────────┐
│                            HomeStage.kt                              │
│  (Single composable owning all state: plan, models, AI, textures,   │
│   UI rig, placement HUD. SpatialView initial/update/attachments.)    │
└────┬────────────────┬───────────────┬───────────────┬───────────────┘
     │                │               │               │
     ▼                ▼               ▼               ▼
┌─────────┐    ┌─────────────┐  ┌────────────┐  ┌────────────┐
│ Floor   │    │ Generated   │  │ Placement   │  │ AI         │
│ Plan    │───▶│ Room        │  │ Controller  │  │ Arranger   │
│ Model   │    │ (ECS tree)  │  │ (ghost+drop)│  │ (HTTP+JSON)│
└─────────┘    └─────────────┘  └────────────┘  └────────────┘
     │                │               │               │
     │                ▼               │               ▼
     │         ┌────────────┐         │        ┌────────────┐
     │         │ Model /    │◀────────┼────────│ Texture    │
     │         │ Texture    │         │        │ Library    │
     │         │ Library    │         │        │ + Cache    │
     │         └────────────┘         │        └────────────┘
     │                                  │
     ▼                                  ▼
┌────────────────┐               ┌──────────────────┐
│ FloorPlan      │               │ OpenAI-compat    │
│ Designer UI    │               │ relay (gpt-5)    │
│ (Canvas +      │               │ POST /v1/chat/   │
│  Inspector)    │               │  completions     │
└────────────────┘               └──────────────────┘
```

### 2.2 Threading model

| Thread                | What runs there                                                                 |
|-----------------------|---------------------------------------------------------------------------------|
| **Main (UI)**         | Compose recomposition, `SpatialView` update lambda, all `Entity`/`Component` mutations, `getVisualBounds` (it's `@MainThread`). |
| `Dispatchers.IO`      | `Entity.loadSuspend(file)`, `MeshResource.load`, `TextureResource.load`, `AssetBundle.load`, sidecar JSON reads, bounds cache I/O. |
| `Dispatchers.Default` | Not used directly.                                                              |
| Provider threads      | `HMDTrackingProvider` and `ControllerTrackingProvider` post poses into `AimState` (`@Volatile` fields) consumed by the main loop. |
| AI HTTP               | `postChatCompletionOnce` runs on `Dispatchers.IO` inside a `scope.launch`.     |

**Rule:** All SDK entity mutations happen on the main thread. Heavy native loads are wrapped in `withContext(Dispatchers.IO)` and awaited back on main before the entity is parented.

### 2.3 State ownership

`HomeStage` is the single source of truth. It holds:

- `draftPlan` / `appliedPlan` (`FloorPlan`) — what the editor shows vs. what's been built.
- `applyRevision: Int` — bumped to trigger room rebuild inside `SpatialView.update`.
- `generatedRoom: GeneratedRoomHolder` — current room + its revision.
- `attachedEnvironment: AppEnvironment?` — currently attached scene (ROOM or SHOWCASE).
- `availableModels` / `availableTextures` / `modelCatalog` — scanned libraries.
- `selectedModelName` / `placementActive` / `modelScale` / `modelRotation` — placement UI state.
- `selectedTextures: Map<SurfaceSlot, String?>` / `roomTextures: RoomTextures` — surface reskin state.
- `advancedThinking` / `planMode` / `iterateMode` — AI option toggles.
- `aiPrompt` / `aiBusy` / `aiStatus` — AI run state.
- `aimState: AimState` — `@Volatile`-backed latest HMD + controller poses from provider listeners.
- `uiRig: UiRigFollowState` / `hudFollow: HudFollowState` — smoothed pose caches for the view-following rig.
- `rigTick: Int` — bumped every 100 ms by a `LaunchedEffect` so `SpatialView.update` re-runs even when no other state changed.

---

## 3. Build & Toolchain

### 3.1 Target stack

| Component            | Version / Value                                                |
|----------------------|----------------------------------------------------------------|
| Android `compileSdk` | 35 (min/target SDK 35)                                         |
| ABI                  | `arm64-v8a` only (PICO devices are ARM64)                     |
| JDK                  | 11 (source + target compatibility)                             |
| Kotlin               | 2.0.0 (with `kotlin-android` + `kotlin-compose` plugins)       |
| AGP                  | 8.13.2                                                         |
| PICO Spatial SDK     | 0.13.3 (BOM-pinned via `gradle/libs.versions.toml`)            |
| JBR                  | Bundled with Android Studio (set `JAVA_HOME` to its `jbr/`)    |

### 3.2 Gradle modules

```
:app             — the Android application (this is what gets installed).
:editor-asset    — packs the Showcase scene (USDA + IBL + skybox) into a
                   .bundle that ships in the APK's assets.
```

`settings.gradle.kts` registers both modules and adds ByteDance's Volcengine Maven mirror (where the PICO SDK artifacts are hosted).

### 3.3 Build flow (Windows / PowerShell)

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
pico-cli app install app\build\outputs\apk\debug\app-debug.apk
pico-cli app launch com.example.testfull
```

- Output: `app/build/outputs/apk/debug/app-debug.apk`
- `local.properties` (gitignored) holds `sdk.dir`, `spatial.tools.dir`, and the three `ai.api.*` keys.
- `buildConfigField` in [app/build.gradle.kts](app/build.gradle.kts) injects `BuildConfig.AI_API_BASE` / `AI_API_KEY` / `AI_API_MODEL` at build time — **changing the API key requires a rebuild**.

### 3.4 BuildConfig injection

```kotlin
buildConfigField("String", "AI_API_BASE", localProp("ai.api.base", "https://api.openai-next.com/v1"))
buildConfigField("String", "AI_API_KEY", localProp("ai.api.key", ""))
buildConfigField("String", "AI_API_MODEL", localProp("ai.api.model", "gpt-4o-mini"))
```

`localProp` reads from `local.properties`, escaping backslashes and quotes. A missing key falls back to the default — empty key string means AI Arrange will throw `AiArrangeException("AI key missing…")` at runtime.

---

## 4. Module Map

All app source under `app/src/main/java/com/example/testfull/`:

### 4.1 Entry points

| File | Purpose |
|---|---|
| [Main.kt](app/src/main/java/com/example/testfull/Main.kt) | `fun mainApp(scope) = with(scope) { DefaultStage { PicoTheme { HomeStage() } } }` — the entire app entry. |
| [platform/SpatialApplication.kt](app/src/main/java/com/example/testfull/platform/SpatialApplication.kt) | `Application.onCreate()` calls `launch(::mainApp)`. |
| [platform/LaunchActivity.kt](app/src/main/java/com/example/testfull/platform/LaunchActivity.kt) | `class LaunchActivity : SpatialLaunchActivity()` — empty subclass that picks up Stage metadata from the manifest. |
| [AndroidManifest.xml](app/src/main/AndroidManifest.xml) | Declares `MAIN`/`LAUNCHER`, `INTERNET` permission, Stage metadata (`pico.spatial.stage.id/style/immersion*`), 3D icon arrays. |

### 4.2 Content (the actual product)

| File | Responsibility |
|---|---|
| [content/HomeStage.kt](app/src/main/java/com/example/testfull/content/HomeStage.kt) | Top-level composable. Owns all state, the `SpatialView` (`initial`/`update`/`attachments`), view-following UI rig, placement HUD pose tracking, `runAiArrange` orchestrator, debug hooks. |
| [content/FloorPlanModel.kt](app/src/main/java/com/example/testfull/content/FloorPlanModel.kt) | Pure data + geometry for plans: `FloorPlan`, `PlanWall`, `PlanOpening`, `PlanBounds`, `WallSolid`, normalization, projection, wall-solids (split around openings), connection-point moves, scaling/resizing, footprint clamp. |
| [content/FloorPlanDesigner.kt](app/src/main/java/com/example/testfull/content/FloorPlanDesigner.kt) | The floor-plan editor UI: Canvas drawing, wall/door/window tools, Inspector with dimension fields, Environment switcher, Textures card host. |
| [content/GeneratedRoom.kt](app/src/main/java/com/example/testfull/content/GeneratedRoom.kt) | `generateRoom(plan, textures)` — builds the ECS entity tree (floor, ceiling, walls split around openings, doors, windows, world shell, lights, physics world). Owns `MeshResource` + `Material` + `ShapeResource` lifetimes. |
| [content/ObjectPlacement.kt](app/src/main/java/com/example/testfull/content/ObjectPlacement.kt) | `PlacementController` — selection/ghost/drop pipeline, ray-cast aiming, rest-pose computation, `spawnPlaced` (drop + AI share this), `separateFromBoxes`, `YawBox`/`yawBoxesOverlap` SAT. |
| [content/ModelLibrary.kt](app/src/main/java/com/example/testfull/content/ModelLibrary.kt) | `scanModels`, `seedBundledAssetsIfNeeded`, `measureModelBounds`, `distillModelDetails`, `parseIntendedSize`, `computeDefaultScale`, bounds cache read/write. |
| [content/TextureLibrary.kt](app/src/main/java/com/example/testfull/content/TextureLibrary.kt) | `scanTextures`, `parseTextureSpec`, `isFurniturePreview` filter, `TextureCache` (lazy load + hold). |
| [content/AiArranger.kt](app/src/main/java/com/example/testfull/content/AiArranger.kt) | `buildCatalog`, `buildArrangementMessages` (system + user prompts), `parseAiLayout`, `resolveAiPlacements`, `resolveAiTextures`, `postChatCompletion`. |
| [content/FurniturePanels.kt](app/src/main/java/com/example/testfull/content/FurniturePanels.kt) | The other three UI panels: `FurnitureLibraryPanel`, `PlacementHudPanel`, `AiArrangePanel`, plus `TexturesCard`, `UiLauncherPanel`, `RotationSlider`, `PanelFrame`, `AiToggleChip`. |

### 4.3 Tests

`app/src/test/java/com/example/testfull/content/`:

- `FloorPlanModelTest` — geometry, normalization, wall-solids.
- `PlacementMathTest` — `yawBoxesOverlap`, `separateFromBoxes`, `clampToFootprint`.
- `AiArrangerTest` — prompt construction, JSON parsing, validation.
- `ModelLibraryTest` — sidecar distillation, default-scale computation.
- `TextureLibraryTest` — sidecar parsing, furniture-preview filtering.
- `FootprintClampTest` — point-in-polygon and clamping.

Run: `./gradlew.bat :app:testDebugUnitTest`. No emulator needed.

---

## 5. Floor Plan Subsystem

Source: [FloorPlanModel.kt](app/src/main/java/com/example/testfull/content/FloorPlanModel.kt). Pure Kotlin, no Android dependencies except `kotlin.math`.

### 5.1 Data model

```kotlin
data class FloorPlan(
    val walls: List<PlanWall>,            // segments in world XZ (meters)
    val openings: List<PlanOpening>,      // doors/windows on a wall
    val scale: Float = 1f,                // whole-plan uniform scale (10–500%)
    val zoneNotes: String? = null,        // human-readable zone description for the AI
)

data class PlanWall(
    val id: Int,
    val start: PlanPoint,                 // x, z in meters
    val end: PlanPoint,
    val height: Float = 2.8f,
    val thickness: Float = 0.16f,
)

data class PlanOpening(
    val id: Int,
    val wallId: Int,
    val type: OpeningType,                // DOOR | WINDOW
    val position: Float,                  // [0,1] along the wall
    val width: Float,                     // meters
    val height: Float,
    val sill: Float,                      // 0 for doors
    val depth: Float = 0.04f,             // wall-thickness-wise depth
)
```

### 5.2 Normalization

`FloorPlan.normalized()` clamps every dimension into sensible ranges (wall length ≥ 0.2 m, thickness ≥ 0.02 m, opening width within `[0.1, wall.length]`, opening `position` clamped so the opening can't fall off the ends, scale in `[0.1, 5]`). All downstream geometry functions call `normalized()` defensively.

### 5.3 Wall solids (split around openings)

`FloorPlan.wallSolids(wall)` returns the list of `WallSolid(start, end, bottom, top)` pieces of a wall that aren't openings. Algorithm:

1. Compute the opening ranges along the wall (`openingRanges(wall)` → `[start, end]` per opening, clamped to wall length).
2. Build a sorted list of breakpoints: `{0, length} ∪ {all opening starts and ends}`.
3. For each breakpoint interval, take its midpoint and find which openings vertically cover that midpoint (a door's vertical hole is `[0, height]`; a window's is `[sill, sill + height]`).
4. Merge overlapping vertical holes.
5. Walk from `y = 0` to `y = wall.height`, emitting a `WallSolid` for each solid sub-range.

This is what produces real gaps in walls — doors and windows are holes, not painted-on panels.

### 5.4 Default plan

`demoFloorPlan()` returns a 12 × 6 m one-bedroom plan: living room (west, 7 × 6) + bedroom (east, 5 × 6) joined by a 2.4 m open passage in the divider at x = 1. Main entrance on the south wall, 3 m picture window on the south, west cross-light window, bedroom north window. `zoneNotes` describes the two zones for the AI.

### 5.5 Geometry helpers

- `PlanWall.project(point)` — orthogonal projection of a point onto the wall segment; returns `(point, position∈[0,1], distance)`.
- `PlanOpening.worldPosition(wall)` — the world-space point at the opening's `position` along the wall.
- `FloorPlan.connectionPoints(tolerance)` — unique endpoints (for the editor's draggable handles).
- `FloorPlan.moveConnectionPoint(from, target)` — moves every endpoint within `tolerance` of `from` to `target.snapped()`; returns unchanged if any wall would become < 0.2 m.
- `FloorPlan.updateWallGeometry(wallId, length, angleDegrees)` — resizes a wall and propagates the new endpoint to connected walls.
- `FloorPlan.scaledTo(scale)` / `resizedFootprint(width, depth)` — uniform or per-axis scaling about the bounds center.

### 5.6 Footprint containment

```kotlin
isInsideFootprint(walls, point)  // even-odd ray-cast (+X direction)
distanceToWalls(walls, point)    // min over all walls' project().distance
clampToFootprint(walls, point, margin)  // step toward centroid until inside + margin
```

`clampToFootprint` walks a point toward the wall centroid in 2 % steps (up to 500 iterations) until it's both inside the polygon AND at least `margin` m from every wall. Used by the placement controller and the AI layout resolver to keep furniture inside the room.

---

## 6. Room Generation

Source: [GeneratedRoom.kt](app/src/main/java/com/example/testfull/content/GeneratedRoom.kt). Output: a `GeneratedRoom` holding the root `Entity`, `navigationRoot`, and the resources it owns (`MeshResource`, `List<Material>`, `List<ShapeResource>`, `List<PhysicsMaterialResource>`).

### 6.1 Coordinate spaces

| Space | Origin | Used by |
|---|---|---|
| **Plan space** | arbitrary, set by the user-drawn walls | FloorPlanModel |
| **Navigation-root-local** | centered on the plan's bounds; `y = 0` is the floor | GeneratedRoom's `navigationRoot`, PlacementController, AI prompt |
| **Scene space** | navigation-root-local + `navigationRoot.transform.position` | raycasts, HMD/controller poses |

`generateRoom` translates walls by `-bounds.centerX, -bounds.centerZ` so the room is centered on the navigation root. `setVirtualUserPosition(x, z)` offsets the navigation root by `(-x, 0, -z)` to walk the user through the room (used by debug hooks; the old virtual-walk UI has been removed).

### 6.2 Build pipeline

1. **`Entity()` (root)** with a `PhysicsWorldComponent`.
2. **`navigationRoot = Entity()`** with a `TransformComponent` — parented to root; everything visible goes under here so virtual-walk translation works.
3. **Resources** (created once, shared by reference):
   - `cube = MeshResource.createBox(Vector3.ONE)`
   - `boxCollisionShape = ShapeResource.createBox(Vector3.ONE)`
   - `roomPhysicsMaterial = PhysicsMaterialResource(staticFriction=0.85, dynamicFriction=0.68, restitution=0.02)`
   - Per-surface materials (wall / floor / ceiling / door / window / world-shell).
4. **Lighting** (`addRoomLighting`): `EnvironmentLightingSettingsComponent(scale=1.35)`, two warm point lights at the ceiling, one directional daylight with shadows.
5. **Floors**: an extended 60 × 60 m ground plane (so the user can walk outside) + a tighter floor plane sized to the bounds.
6. **Ceiling** at `maxWallHeight + 0.04 m`, sized to bounds.
7. **World shell** — a 80 × 40 × 80 m unlit box with `CullingMode.FRONT` at `y = 10` (non-collidable) to replace the default PICO environment even if the user walks through a wall.
8. **Walls** — for each wall, for each `WallSolid`, `addBox(...)` with the solid's along-wall position, vertical `(bottom, top)` center, the wall's yaw, and `(length, height, thickness)` scale. Collidable (no `RigidBodyComponent` ⇒ static).
9. **Openings** — for each opening, `addBox` at the opening's position with the door or window material. Door `depth = wall.thickness`, window glass `BlendingMode.TRANSPARENT` with `setDepthWrite(false)`.

### 6.3 Materials

```kotlin
material(color, roughness)                         // flat PBR
texturedMaterial(surface: TexturedSurface)         // PBR with base color + normal + roughness + metallic
texturedGlassMaterial(surface)                     // transparent PBR driven by RGBA texture alpha
glassMaterial()                                    // default transparent glass
worldShellMaterial()                               // unlit, front-culled
```

When `RoomTextures` is null for a slot, the default flat material is used (e.g. wall `Color4(0.76, 0.79, 0.82)`, roughness 0.82).

### 6.4 Lifetime

`GeneratedRoom.destroy()`:
- `root.destroy(recursively = true)` — kills the whole entity tree.
- Closes the shared `boxCollisionShape`, `roomPhysicsMaterial`, all `materials`, and `cube`.

The old room is destroyed **before** the new one is attached (see `HomeStage` update lambda): the previous room's entity tree is removed from the scene, then `previousRoom.destroy()` is called.

---

## 7. Furniture Placement Engine

Source: [ObjectPlacement.kt](app/src/main/java/com/example/testfull/content/ObjectPlacement.kt). Class `PlacementController`.

### 7.1 Lifecycle

```
bind(navigationRoot)               ←  once a room exists
  ↓
selectModel(file, displayName)     ←  user picks a model from the library
  ↓ reloadSelection()
updateAim(origin, rotation, user)  ←  per-frame while placement is active
  ↓
drop()                             ←  trigger press edge or HUD "Drop here"
  ↓ reloadSelection() (rebuilds the ghost for the next drop)
  ↓
onRoomDestroyed()                  ←  before a plan re-apply
  ↓ bind(newRoot); reloadSelection()
```

### 7.2 Aim pipeline (`updateAim`)

1. Ray-cast from the controller (or HMD gaze if no controller) forward up to 30 m, nearest hit = **anchor**.
2. Translate the anchor into navigation-root-local space (`anchorLocal = anchor.position - navRoot.position`).
3. **Clamp** the anchor's XZ to the room footprint with `margin = footprintMargin + max(halfX, halfZ) * scale` (so the whole model, not just the pivot, stays clear of walls).
4. Ray-cast straight down from `anchor.y + 0.5 m` to find the **support surface** (floor or a previously dropped object — objects stack).
5. **Rest pose** = `(anchorX, supportY + bottomOffset * scale + PREVIEW_CONTACT_SKIN, anchorZ)`.
6. **Yaw** = `manualYaw` (slider) or `yawFacingUserDegrees(restScene, userPositionScene)` (auto-face the user).
7. **Overlap test** — `separateFromBoxes(point, …)` slides the ghost along the direction away from the blocking box's center in 0.05 m steps until no intersection, up to 4 m total travel. Returns `null` ⇒ ghost is blocked (red), drop disabled.

### 7.3 Drop pipeline (`drop` → `spawnPlaced`)

`drop()`:
1. Snapshot the ghost's transform (position, rotation, scaleVector).
2. **Release the ghost** before the heavy load — the ghost + the new entity peak at ~2× the model size in native memory; keeping both alive kills the process on the emulator.
3. Call `spawnPlaced(file, displayName, positionLocal, scaleVector, center, halfExtents, rotation, yawDegrees=null, allowConvexFallback=true)`.
4. `reloadSelection()` rebuilds the ghost for the next drop.

`spawnPlaced` (shared by `drop` and `placeFromAi`):
1. Build the collider:
   - Primary: `createBoundingBoxShape(center, halfExtents)` — a `ShapeResource.createBox` translated by the bbox center (because bbox center ≠ pivot, and `CollisionComponent` has no per-shape offset).
   - Fallback for `drop`: convex hull of the loaded mesh.
   - Last resort: a plain box of the bbox size.
2. Create a fresh `PhysicsMaterialResource(staticFriction=0.6, dynamicFriction=0.5, restitution=0.05)`.
3. **Load the entity** off-thread: `Entity.loadSuspend(file)` on `Dispatchers.IO`.
4. Set transform: `position.y + DROP_SPAWN_LIFT_METERS (0.08 m)` so it drops gently (avoids initial penetration → "jumping out").
5. `CollisionComponent(collisionShape = listOf(shape), physicsMaterial)`.
6. `RigidBodyComponent` with `collisionDetectionMode = CONTINUOUS` (CCD prevents tunneling through thin walls).
7. Parent to `navigationRoot`, enable, push to `placedObjects` + `placedShapes` + `placedMaterials`.

**Critical:** every drop gets its own `ShapeResource` and `PhysicsMaterialResource`. Sharing these across dynamic bodies and then destroying some of them invalidates the shared handle (SDK logs `"not shape resource id: 0"` and bodies fall through the floor).

### 7.4 Race safety

- **`selectionGeneration: Int`** — incremented on every `selectModel`. `reloadSelection` checks the generation before parenting the loaded entity; if a newer selection superseded it, the stale load is destroyed immediately. Prevents "stuck ghosts".
- **`dropInFlight: Boolean`** — set true during `spawnPlaced`, false in `finally`. Prevents concurrent heavy loads (which is what kills the process on the emulator). `reloadSelection` short-circuits while a spawn is in flight.

### 7.5 Selection resource lifecycle

`releaseSelectionResources()` destroys the ghost entity and closes `collisionMesh`, but **keeps** `modelFile` / `selectedModelName` — so `reloadSelection()` can rebuild the ghost after a drop or an AI spawn without re-prompting the user.

The ghost's `PhysicallyBasedMaterial` is created fresh each time and **never cached**: the SDK closes a destroyed entity's materials, so re-adding a cached one crashes (`"can't add material ... with a closed PhysicallyBasedMaterial"`).

### 7.6 Bounding-box math

```kotlin
data class YawBox(centerX, centerY, centerZ, halfX, halfY, halfZ, yawDegrees)

yawBoxFor(position, yaw, scale, center, halfExtents):
  // rotate the bbox-center offset (center.x*scale, center.z*scale) by yaw
  // R_Y: x' = x·cos + z·sin,  z' = -x·sin + z·cos

yawBoxesOverlap(a, b, tolerance):
  // 1. Y-interval test (centers differ by less than halfY sum - tolerance → overlap)
  // 2. SAT on 4 ground-plane axes (both boxes' local X and Z) — if any axis separates, no overlap
```

This is the same overlap test used by both the ghost (against `placedObjects`) and the AI resolver (against `acceptedBoxes`). Tolerance 0 ⇒ mere contact counts as separated.

---

## 8. Model & Texture Libraries

### 8.1 Model library

Source: [ModelLibrary.kt](app/src/main/java/com/example/testfull/content/ModelLibrary.kt).

```kotlin
data class LibraryModel(file: File, displayName: String)
```

- **Supported extensions:** `glb, gltf, usda, usdc, usdz`.
- **Location:** `modelsDirectory(context)` = `context.getExternalFilesDir(null) / "models"` → `/sdcard/Android/data/com.example.testfull/files/models/`. No storage permission needed (app-specific external dir).
- **Scan:** `scanModels(context)` → `seedBundledAssetsIfNeeded(context)` then `scanModelsIn(dir)`.

### 8.2 Sidecar JSON (schema_version 1)

Each `<name>.glb` may have a `<name>.json` next to it:

```json
{
  "schema_version": 1,
  "identity": {"id": "bed-001", "name": "圆枕软包床", "status": "draft"},
  "classification": {"category": "bed", "room_types": ["bedroom"]},
  "geometry": {"width_m": 2.008, "depth_m": 1.414, "height_m": 0.698},
  "appearance": {"colors": [], "materials": []},
  "style_assessment": {"scores": {"style.nordic": {"score": 0.6}}},
  "placement": {"support_surface": "floor", "against_wall": null,
                "front_clearance_m": null, "side_clearance_m": null},
  "notes": null
}
```

**Distillation** (`distillModelDetails`): keeps only the sections relevant to arrangement decisions (`identity, classification, appearance, placement, style_assessment, construction, geometry, notes` + the texture-schema sections), then recursively strips null/blank/empty fields. A mostly-empty template collapses to its known facts. Output is capped at `MAX_DETAIL_CHARS = 2000`.

### 8.3 Bounds measurement

`measureModelBounds(file)`:
1. `Entity.loadSuspend(file)` on `Dispatchers.IO`.
2. `delay(200)` — let the async model load commit and the emulator main thread pump a few frames before the `@MainThread` `getVisualBounds` call. Back-to-back native calls here used to starve input dispatch and trigger an ANR.
3. `probe.getVisualBounds(relativeTo = probe, recursive = true)` — **must be on main thread**. `MeshResource.load(FROM_STORAGE)` does NOT support GLB (`FORMAT_UNSUPPORTED`); only the loaded entity tree exposes bounds.
4. `ModelBounds(center, halfExtents, bottomOffset = -bounds.min.y)` — `bottomOffset` is the distance from the pivot to the model's bottom (0 for bottom-pivot models, half a height for centered pivots).
5. `probe.destroy(recursively = true)` in `finally`.

**Cache** (`.bounds-cache.json` in the models dir): keyed by absolute file path, validated by `mtimeMs`. `buildCatalog` reads the cache, measures only what's missing/stale, writes the cache **incrementally** after each measurement (so a crash mid-run keeps finished entries).

### 8.4 Default scale

```kotlin
computeDefaultScale(measuredSize, intendedSize):
  ratios = [intendedSize.x/measuredSize.x, .y/.y, .z/.z]
  mean = avg(ratios)
  consistent = all(|r - mean| / mean <= 0.15)
  return if (consistent) mean else ratios[0]   // width wins if axes disagree
```

`intendedSize` comes from `parseIntendedSize(sidecarJson)` = `(geometry.width_m, height_m, depth_m)`. The AI's placement scale × this `defaultScale` = the **effective scale** used everywhere downstream.

### 8.5 Texture library

Source: [TextureLibrary.kt](app/src/main/java/com/example/testfull/content/TextureLibrary.kt).

```kotlin
data class TextureSpec(
    val file: File,
    val displayName: String,
    val surfaces: List<SurfaceSlot>,    // empty = offered for every slot
    val styles: List<String>,
    val roughness: Float?,
    val metallic: Float?,
    val normalMap: File?,
    val details: String?,               // distilled sidecar JSON
)

enum class SurfaceSlot { WALL, FLOOR, CEILING, DOOR, WINDOW }
```

- **Supported image extensions:** `png, jpg, jpeg, webp`.
- **Filter rules:**
  - Skip `<base>_n.<ext>` — those are normal-map companions, not standalone textures.
  - Skip furniture previews: `isFurniturePreview(image)` returns true when an image has a same-named `.glb`/`.gltf` next to it (e.g. `bed-001.png` next to `bed-001.glb`). Those PNGs are loaded by the furniture card previewer, not the texture reskin list.
- **Normal map auto-discovery:** if no sidecar declares `maps.normal`, the scanner looks for `<base>_n.<ext>` next to the image.

### 8.6 Texture sidecar JSON

```json
{
  "schema_version": 1,
  "type": "surface_texture",
  "identity": {"id": "red-brick-wall", "name": "Red brick"},
  "classification": {"surfaces": ["wall"], "styles": ["industrial", "loft"]},
  "maps": {"base_color": "red-brick-wall.jpg", "normal": "red-brick-wall_n.jpg"},
  "material": {"roughness": 0.95, "metallic": 0.0},
  "tiling": {"meters_per_tile": 1.0}
}
```

### 8.7 TextureCache

```kotlin
class TextureCache {
    private val cache = mutableMapOf<String, TextureResource>()
    suspend fun load(file: File): TextureResource?
    fun close()  // closes all + clears
}
```

Loaded textures are shared across room rebuilds, owned by the cache, and released only on `close()`. Closing them together with a room's materials would invalidate the cache. `HomeStage` holds one `TextureCache` for its lifetime and closes it in `DisposableEffect.onDispose`.

---

## 9. AI Arrangement Subsystem

Source: [AiArranger.kt](app/src/main/java/com/example/testfull/content/AiArranger.kt). Orchestration in `HomeStage.runAiArrange`.

### 9.1 Catalog building

`buildCatalog(models)`:
1. Read the on-disk bounds cache.
2. For each `LibraryModel`:
   - Cache hit (mtime matches) → use cached bounds.
   - Cache miss → `measureModelBounds(file)`, `delay(1000)` (yield between native measurements — back-to-back heavy loads killed the emulator), write the cache incrementally.
   - Read the sidecar (`readModelSidecarRaw`), compute `defaultScale`.
   - Build a `CatalogModel(file, displayName, center, halfExtents, bottomOffset, details, defaultScale)`.

### 9.2 Prompt construction (`buildArrangementMessages`)

Returns `Pair<String, String>` = `(system, user)`.

**System message** sections:
1. (Optional) **Advanced Context pre-prompt** — verbatim `adventurex_fengshui_ai_config_v1.json` wrapped in `=== ADVANCED CONTEXT (pre-prompt) ===` / `=== END ADVANCED CONTEXT ===`. Loaded from `assets/` only when `advancedThinking` is on.
2. **Role** — branches on `planMode` / `iterate` / default:
   - Plan Mode: "interior-design consultant… you must NOT produce any placement instructions".
   - Iterate: "interior-design engine running in ITERATE mode. The room already contains furniture… SELF-EVALUATE… confirm satisfactory or return a FULLY REVISED layout".
   - Default: "interior-design engine… answer with placement instructions as a single JSON object".
3. **Spatial vocabulary** — exact meanings of "next to", "against wall", "around X", "facing", "opposite", "corner", clearance rules (walkways ≥ 0.6 m, doors ≥ 1.0 m clear, gaps ≥ 0.5 m).
4. **Room semantics** — closed wall loop = one room; typical functions (bedroom, bathroom, living room, dining, office).
5. **Furniture data** — schema v1 fields, "null = unknown, never invent", same model name can repeat for multiples.
6. **Style requests** — modern vs cozy.
7. **Output schema** — strict JSON:
   - Plan Mode: `{"placements": [], "notes": str, "suggestions": [str]}`
   - Iterate: `{"satisfied": bool, "placements": [...], "notes": str, optional "textures": {...}}`
   - Default: `{"placements": [{"model", "x", "z", "yaw", "scale"}], "notes": str, optional "textures": {...}}`
8. (Iterate only) **Iterate protocol** — describes the self-improvement loop, max iterations, what "satisfied" means.

**User message** sections:
1. **ROOM JSON** — `bounds`, `walls[]` (id, from, to, height), `openings[]` (type, wall, at, width), `furniture[]` (current placements with sizes).
2. **ZONES** — `appliedPlan.zoneNotes` if present.
3. **ROOM rules** — "every item fully inside the walls; never covering a door or window; items in `furniture` are already there — include one to keep or move it, omit it to remove it".
4. **LIBRARY** — one line per catalog model: `"- <name> — footprint W x D m, H m tall. DETAILS: <distilled sidecar>"`.
5. **TEXTURES** (if any) — surface/style restrictions per texture.
6. **USER REQUEST** — `"<prompt>"`.
7. **Rules** recap.
8. (Iterate only) **Iteration context** — `iteration N of maxIterations`, the previous turn's notes.

All coordinates are **navigation-root-local** (room centered on origin, y = 0 = floor, yaw in degrees, 0 = +Z, 90 = +X). This matches `PlacementController.placeFromAi`.

### 9.3 HTTP call (`postChatCompletion`)

```kotlin
POST {AI_API_BASE}/chat/completions
Authorization: Bearer {AI_API_KEY}
Content-Type: application/json

{
  "model": "{AI_API_MODEL}",
  "response_format": {"type": "json_object"},
  "messages": [
    {"role": "system", "content": "<system>"},
    {"role": "user",   "content": "<user>"}
  ]
}
```

- **No `temperature`** — some upstreams (e.g. gpt-5 reasoning) 400 on any non-default value; JSON mode carries the determinism we need.
- **Timeouts:** connect 30 s, read 300 s (gpt-5 can think for over a minute).
- **Retry:** `MAX_HTTP_ATTEMPTS = 2` on `SocketTimeoutException` (relay-side congestion). Other exceptions become `AiArrangeException`.

### 9.4 Parsing (`parseAiLayout`)

- Top-level must be a JSON object with a `placements` array; otherwise `AiArrangeException`.
- Each placement: `model` (string, non-empty), `x`/`z` (numbers, non-NaN), `yaw` (default 0), `scale` (default 1, clamped to `[0.05, 5]`).
- `notes` (optional string).
- `textures` (optional object: `wall|floor|ceiling|door|window` → texture name).
- `suggestions` (optional array of strings — Plan Mode).
- `satisfied` (optional bool, default false — Iterate).

### 9.5 Validation (`resolveAiPlacements`)

Sequential, in the order the AI returned:

1. **Resolve model** by name (`resolveCatalogModel` — exact normalized match, then substring either way). Unknown ⇒ skip with reason.
2. **Effective scale** = `placement.scale * model.defaultScale`.
3. **Margin** = `baseMargin + halfDiagonal * effectiveScale` (so a 45°-yawed box still clears the walls).
4. **Clamp** to footprint.
5. **Overlap separation** (`separateFromBoxes`): for each accepted box that intersects, push the candidate 0.05 m away from the blocker's center, re-clamp, repeat up to 80 iterations (4 m max travel). Returns `null` ⇒ skip with reason "no free space left".
6. Append to `accepted` + `acceptedBoxes`.

Returns `ResolvedAiLayout(accepted, skipped, adjusted)` — `adjusted` lists items that were nudged (for the status line).

### 9.6 Texture resolution (`resolveAiTextures`)

For each `(slot, name)` the AI returned:
- Match by normalized name (case/space/hyphen/underscore-insensitive), exact first then substring.
- If the texture's `surfaces` is non-empty and `slot` isn't in it → skip with reason.
- Otherwise accept.

### 9.7 Orchestration (`runAiArrange` in HomeStage)

```
1. Scan models + textures on demand (works even if the user never pressed Scan).
2. Build catalog (measures on demand).
3. Build localFootprint(appliedPlan) + localOpenings(appliedPlan).
4. Load pre-prompt from assets/adventurex_fengshui_ai_config_v1.json if advancedThinking.
5. Define spawnLayout(layout) — shared closure:
   - resolveAiPlacements(...)
   - placementController.releaseSelectionGhost()  (free memory)
   - placementController.clearPlaced()
   - for each accepted: placementController.placeFromAi(...); delay(200)  (ANR guard)
   - returns "AI placed N of M. Adjusted: … Skipped: …"
6. Define applyTextures(layout) — shared closure:
   - resolveAiTextures(...)
   - update selectedTextures + roomTextures
   - applyRevision += 1  (triggers room rebuild)
   - wait for generatedRoom.room to change (up to 20 s)
   - returns " Textures skipped: …" note
7. Seed turn: requestAiLayout(iterate=iterateMode && !planMode, iteration=0)
8. Plan Mode branch: surface suggestions in aiStatus, return.
9. applyTextures(seed) → spawnLayout(seed) → set aiStatus.
10. Iterate loop (if iterateMode && !planMode):
    while (iter <= ITERATE_MAX_ITERATIONS):
      aiStatus = "Iterating N/3 — evaluating layout…"
      iterLayout = requestAiLayout(iterate=true, iteration=iter, previousNotes=lastNotes)
      if iterLayout.satisfied: aiStatus = "AI satisfied after N iteration(s).", break
      if iterLayout.placements.isEmpty(): aiStatus = "AI stopped at iteration N.", break
      applyTextures(iterLayout) → spawnLayout(iterLayout) → aiStatus
      lastNotes = iterLayout.notes; iter++
11. finally: aiBusy = false; if selectedModelName != null: reloadSelection().
```

`ITERATE_MAX_ITERATIONS = 3` ⇒ at most 4 AI calls total (1 seed + 3 iterations). Each iteration **replaces** the room's furniture with the AI's revised layout.

### 9.8 AdventureX fengshui config

`app/src/main/assets/adventurex_fengshui_ai_config_v1.json` (1335 lines) — a complete rule library for traditional Chinese宅居 culture analysis:

- **Scope:** explicitly limits "国学" to traditional residence culture, spatial order, situational relationships, yin-yang balance, and living-psychology preferences. Explicitly NOT fortune-telling.
- **Evidence hierarchy:** A (national mandatory codes), B (modern design + ergonomics), C (traditional culture), D (project heuristic thresholds).
- **Reference sources:** GB 55038-2025 (住宅项目规范), GB 55019-2021 (无障碍通用规范), 《黄帝宅经》, 形势派原则.
- **Conflict resolution priority:** safety/code > structure/fire/plumbing > function/light/vent/accessibility > user needs > cultural preferences > aesthetics.
- **15 rules** (`FS-ENT-001` through `FS-SCALE-001`) covering entrance privacy, sofa-back support, bed-head support, bed-door alignment, beam overlap, mirror placement, desk orientation, stove-sink relation, bathroom-door alignment, circulation blockage, window daylight, ventilation, sharp corners, zone separation, scale crowding.
- **Scoring model:** 0–100 with weight profiles (fengshui_high/medium/low), severity penalties (critical 25 / high 12 / medium 6 / low 2).
- **Output schema:** `is_recommended`, `scores`, `strengths`, `issues`, `recommendations`, `adjustment_order`, `required_more_data`, `disclaimer`.

The whole JSON is prepended verbatim to the system prompt when `advancedThinking` is on. The AI is expected to weigh it as context alongside the standard interior-design instructions.

---

## 10. UI Panel System & View-Following Rig

### 10.1 Panel hierarchy

```
SpatialView attachments:
├── ui-launcher       ←  UiLauncherPanel (always visible, folds/unfolds the main panel)
├── main-panel        ←  MainPanelSwitcher (cycles RoomPlan / FurnitureLibrary / AiArrange)
└── placement-hud     ←  PlacementHudPanel (only visible while placing)
```

`MainPanelSwitcher` renders a `Row` of `‹ [panel] ›` — two `PanelArrow`s with an `AnimatedContent` between them that slides left/right with a 300 ms `tween` transition. The arrows sit **beside** the panel (not overlapping) so the panel's glass material can't cover them.

### 10.2 View-following UI rig

Goal: the main panel + launcher stay centered on the user's gaze (yaw only — following pitch would be nauseating), at a fixed 0.5 m distance, so the UI is always readable without the user having to hunt for it.

**Update loop** (inside `SpatialView.update`):

1. `rigTick` is bumped every 100 ms by a `LaunchedEffect`. Reading `rigTickObserved` subscribes this block to the ticker — without it the rig would only re-evaluate when some unrelated state changes.
2. Read the latest HMD pose from `aimState.hmdPosition` / `aimState.hmdRotation` (posted by `HMDTrackingProvider.addListener`).
3. Compute the flat (yaw-only) forward vector: `gazeFwd = normalize(rawFwd.x, 0, rawFwd.z)`.
4. **Rate-limit:** only update when `now - uiRig.lastUpdateAt >= UI_RIG_UPDATE_INTERVAL_MS (50 ms = 20 Hz)`.
5. **No yaw dead zone** — the old 3° dead zone left the panel fixed in world space during small rotations, so it swung to the side (appearing farther) then jumped back to center (appearing closer) once the threshold was crossed — reading as an ellipse. The `|| true` guard ensures we always update when the timer fires.
6. **Position AND yaw track the head 1:1, no smoothing.** Smoothing either causes problems:
   - Lerping position makes the panel lag behind walking (changing apparent distance).
   - Lerping yaw while tracking position 1:1 makes the panel trace an ellipse when rotating.
7. **Snap conditions** (force `curEye = null` so the next update snaps):
   - `uiOpen` toggled — panels reopen in front of the user.
   - Move > `HUD_SNAP_DISTANCE_METERS (0.6 m)` — big teleports.
   - Turn > `UI_RIG_SNAP_YAW_DEGREES (40°)` — big rotations.
8. **Place panels:**
   - `main-panel` at `eye + fwd*0.5 + dy(-0.2)` — 0.5 m ahead, 20 cm below the eye.
   - `ui-launcher` at `eye + fwd*0.55 + dy(-0.78)` — slightly farther, much lower.
   - Both yaw to `yawFacingUserDegrees(target, eye)`.

### 10.3 Placement HUD

The placement HUD has its own follow state (`HudFollowState`) with looser tracking:

- **Distance:** 0.5 m (same as main panel; was previously 1.1 m and was invisible because it sat much farther).
- **Update interval:** 150 ms (less critical than the main panel; the HUD redraws its whole surface on every transform change, and per-frame moves trip the emulator ANR watchdog).
- **Snap-on-first-show:** when `hudFollow.position == null`, bypass the rate limiter so the HUD appears where the user is looking immediately.
- **Smoothing:** 0.35 lerp on position + `lerpAngleDegrees(0.35)` on yaw (this is fine for the HUD because it doesn't track position 1:1 — the smoothing is on the panel position relative to the head, not the head itself).
- **Reset on hide:** `hudFollow.position = null; yaw = 0; lastUpdateAt = 0` so the next show snaps fresh.

### 10.4 Constants (top of HomeStage.kt)

```kotlin
HUD_UPDATE_INTERVAL_MS      = 150L    // placement HUD rate limit
UI_RIG_UPDATE_INTERVAL_MS   = 50L     // main panel rate limit (20 Hz)
HUD_SNAP_DISTANCE_METERS    = 0.6f    // teleport threshold
HUD_MIN_MOVE_METERS         = 0.02f   // jitter dead zone
HUD_MIN_YAW_DEGREES         = 2f
UI_RIG_MIN_MOVE_METERS      = 0.03f   // sub-millimeter walking jitter filter
UI_RIG_MIN_YAW_DEGREES      = 3f      // (effectively disabled by `|| true`)
UI_RIG_SNAP_YAW_DEGREES     = 40f     // big-turn snap
AIM_UPDATE_INTERVAL_MS      = 33L     // ~30 Hz aim loop
AIM_SOURCE_STALE_MS         = 600L    // controller/hmd pose freshness window
ITERATE_MAX_ITERATIONS      = 3
FOOTPRINT_CLEARANCE_METERS  = 0.05f   // wall clearance for AI placement
```

---

## 11. Asset Bundling & Seeding

### 11.1 Why

The original workflow required `adb push` to populate `/sdcard/Android/data/com.example.testfull/files/models/` with furniture and textures. That's friction for end users (they need adb, the path is long, Git Bash rewrites `/sdcard`). The seed mechanism ships the starter library inside the APK and copies it out on first scan.

### 11.2 What's bundled

`app/src/main/assets/models/` contains 85 files copied from `models/outside material/`:

- 24 `.glb` — furniture models (bed, cabinet, chair, desk, sofa series).
- 30 `.json` — sidecar details (one per model).
- 25 `.png` — per-model preview images.
- 6 `.jpg` — surface textures (marble-floor, oak-wood-floor, red-brick-wall + normal, walnut-door, white-plaster-wall).

Plus `white-frame-window.json` + `white-frame-window.png` (window texture).

APK size grew from ~36 MB to ~220 MB.

### 11.3 `seedBundledAssetsIfNeeded(context)`

```kotlin
internal fun seedBundledAssetsIfNeeded(context: Context) {
    val destDir = modelsDirectory(context)
    if (!destDir.exists()) destDir.mkdirs()
    val assetNames = context.assets.list("models") ?: return
    for (name in assetNames) {
        val destFile = File(destDir, name)
        if (destFile.exists()) continue   // don't overwrite user-pushed files
        context.assets.open("models/$name").use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        }
    }
}
```

- **Idempotent** — only copies files that don't already exist on the filesystem. User-pushed overrides via `adb push` are preserved.
- **Called from `scanModels(context)` and `scanTextures(context)`** before scanning, so assets are available in the writable filesystem before any file-based loading.
- **Off-main-thread:** callers run it inside `scope.launch` or `Dispatchers.IO` (disk I/O).
- **First scan is slow** (~24 s) because of bounds measurement; subsequent scans use `.bounds-cache.json` for instant results.

### 11.4 Override semantics

- `adb push new.glb /sdcard/Android/data/com.example.testfull/files/models/` still works — the seed only copies missing files, so a pushed file takes precedence.
- To replace a bundled file: push it (the seed check sees it exists and skips).
- To restore the bundled version: delete the file in the device's models dir, then re-scan.

---

## 12. Configuration Reference

### 12.1 `local.properties` (gitignored)

```properties
sdk.dir=C\:\\Users\\Taven\\AppData\\Local\\Android\\Sdk
spatial.tools.dir=C\:\\Users\\Taven\\AppData\\Local\\PICO

ai.api.base=https://api.openai-next.com/v1
ai.api.key=sk-...
ai.api.model=gpt-5
```

`ai.api.*` are injected into `BuildConfig` at build time — **changing them requires a rebuild + reinstall.**

### 12.2 `AndroidManifest.xml` Stage metadata

```xml
<meta-data android:name="pico.spatial.stage.id"        android:value="RoomPlannerStage" />
<meta-data android:name="pico.spatial.stage.style"     android:value="1" />   <!-- Mixed -->
<meta-data android:name="pico.spatial.stage.immersion" android:value="100" /> <!-- Progressive-only -->
```

Stage style values:
- `0` Automatic (system default — currently Mixed).
- `1` Mixed — virtual + real at 100% each.
- `2` Progressive — adjustable 0–100% immersion.
- `3` Full — virtual only.

### 12.3 `gradle/libs.versions.toml`

PICO Spatial SDK artifacts (BOM 0.13.3):
- `com.pico.spatial:core` — ECS, math, resources, simulation.
- `com.pico.spatial.ui:platform` — `SpatialLaunchActivity`, `launch()`.
- `com.pico.spatial.ui:foundation` — `SpatialView`, `AttachmentPanel`, `Material`.
- `com.pico.spatial.ui:design` — `PicoTheme`, `Button`, `Text`, `TextField`, `NumberField`.
- `com.pico.spatial.sense:sense` — passthrough / sensing.
- `com.pico.spatial.tracking:tracking` — `HMDTrackingProvider`, `ControllerTrackingProvider`.

### 12.4 Model Manager env vars

`model-manager/server.js` reads:

| Env | Default | Purpose |
|---|---|---|
| `MM_PORT` | `8931` | HTTP port. |
| `MM_PKG` | `com.example.testfull` | App package (target dir). |
| `MM_DIR` | `/sdcard/Android/data/$PKG/files/models` | Remote dir. |
| `ADB` | (auto-detected) | Path to `adb.exe`. |

---

## 13. Operational Runbook

### 13.1 First-time setup (fresh machine)

1. Install JDK 17+ (use Android Studio's JBR), Android SDK (platform-tools + API 36), PICO SDK 0.13.x + pico-cli + Emulator bundle, Python 3.9+ with `pillow numpy`, Node.js 18+.
2. `pico-cli emulator doctor` → `install` → `create` → `start` → `status` (ADB online).
3. `cp local.properties.example local.properties` and edit `sdk.dir`, `spatial.tools.dir`, `ai.api.*`.
4. Build & install:
   ```powershell
   $env:JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr"
   .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
   pico-cli app install app\build\outputs\apk\debug\app-debug.apk
   pico-cli app launch com.example.testfull
   ```
5. In the app: Furniture panel → **Scan models folder** (first scan takes ~24 s for bounds measurement).
6. Start placing / arranging.

### 13.2 Adding a new furniture model

```powershell
# 1. Shrink embedded textures to ≤1024 (prevents LMK OOM on emulator).
python tools/glb-shrink-textures.py new.glb

# 2. Measure real bounds.
python tools/glb-rescale.py measure new.glb

# 3. Bake real-world size into the GLB (meters: width, depth, height).
python tools/glb-rescale.py bake new.glb 2.008 1.414 0.698

# 4. Write a sidecar new.json (schema §8.2) with geometry matching the baked size.

# 5. Push to device (or copy into app/src/main/assets/models/ before building).
adb push new.glb /sdcard/Android/data/com.example.testfull/files/models/
adb push new.json /sdcard/Android/data/com.example.testfull/files/models/

# 6. (Optional but recommended) Seed the bounds cache offline:
python tools/seed-bounds-cache.py --models-dir models --package com.example.testfull --push
```

In the app: Furniture → Scan.

### 13.3 Changing the AI model / endpoint / key

Edit `local.properties`:

```properties
ai.api.base=https://your-relay.example.com/v1
ai.api.key=sk-...
ai.api.model=gpt-4o-mini   # or gpt-5, gpt-4o, etc.
```

Rebuild + reinstall. `BuildConfig.AI_API_*` are read at runtime by `postChatCompletionOnce`.

### 13.4 Debug hooks (debug builds only)

A `LaunchedEffect` polls `context.getExternalFilesDir(null)/ai_test_prompt.txt` once per second. If it exists:

- Empty content → ignored.
- `"ui:toggle"` → flips `uiOpen`.
- `"place:<query>"` → selects the first model whose name contains `<query>` and turns placement on (drives the view-following HUD without a controller).
- Anything else → set as `aiPrompt` and triggers `runAiArrange`.

Trigger from adb:

```bash
adb shell "echo 'give me a modern bedroom' > /sdcard/Android/data/com.example.testfull/files/ai_test_prompt.txt && chmod 666 /sdcard/Android/data/com.example.testfull/files/ai_test_prompt.txt"
```

`chmod 666` is required — the app can't read a 660-mode file.

### 13.5 Logs

User-build logcat hides `Log.d`. All diagnostic logging uses `Log.w`:

```bash
adb logcat -d | grep -E "HomeStage|AiArranger|ModelLibrary|PlacementController|TextureLibrary"
```

The full ROOM JSON sent to the AI and the AI's raw answer are logged under the `AiArranger` tag.

### 13.6 Model Manager web tool

```powershell
node model-manager/server.js
# Open http://localhost:8931
```

Zero-dependency Node.js HTTP server that wraps `adb` to list / upload / delete files in the device's models folder. Useful for non-developer users. Port 8931 conflicts with stale `node.exe` — kill with:

```powershell
Get-NetTCPConnection -LocalPort 8931 | Select -Expand OwningProcess | Stop-Process -Force
```

### 13.7 Unit tests

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

No emulator needed. Five test classes: `FloorPlanModelTest`, `PlacementMathTest`, `AiArrangerTest`, `ModelLibraryTest`, `TextureLibraryTest`, `FootprintClampTest`. All should be green before any release.

---

## 14. Performance & Pitfalls

### 14.1 Emulator-specific issues

- **Boot ANR** — x86 emulator uses Houdini to translate ARM spatial runtime; cold boot takes >5 s and triggers an ANR dialog. Click **"Wait"** (not "Close app"). Happens once per cold boot.
- **LMK (low-memory killer)** — under memory pressure the kernel kills system processes (`Zygote: Process X exited due to signal 9` in logcat). Mitigations baked into the code:
  - Ghost released before drop / AI spawn.
  - `delay(1000)` between native bounds measurements.
  - `delay(200)` between AI placement spawns.
  - Each drop gets its own `ShapeResource`/`PhysicsMaterialResource` (sharing handles → `"not shape resource id: 0"`).
- **Frame rate** — ~1–15 FPS; `FRAME_TOO_SLOW` log spam is normal, not a bug.
- **Don't interact immediately after a reinstall** — `force-stop` first, then `launch`.

### 14.2 SDK gotchas (all learned the hard way)

1. **`MeshResource.load(path, FROM_STORAGE)` does NOT support GLB** — returns `FORMAT_UNSUPPORTED`. Use `Entity.loadSuspend(file)` + `getVisualBounds(relativeTo = entity, recursive = true)` to measure bounds.
2. **`getVisualBounds` is `@MainThread`** — and back-to-back native calls (load + bounds) starve input dispatch → ANR. Always `delay(200)` between them.
3. **`Entity.loadSuspend` on large models (>10 MB) peaks at ~2× native memory** — concurrent loads kill the process. Release the ghost before loading, gate spawns with `dropInFlight`.
4. **Every dropped object needs its own `ShapeResource` and `PhysicsMaterialResource`** — shared handles get invalidated when one object is destroyed.
5. **The SDK closes a destroyed entity's materials** — never cache and re-add a `PhysicallyBasedMaterial` to another entity (`"can't add material ... with a closed PhysicallyBasedMaterial"`).
6. **`ModelComponent.materials` getter re-pulls from native on EVERY access** — call it once per component, store in a local. Looping on `model.materials.size` re-reads the getter each iteration and hangs the main thread (ANR).
7. **Panel surfaces redraw on every `Transform` change** — rate-limit HUD/rig updates. 50 ms (20 Hz) for the main rig, 150 ms (~7 Hz) for the placement HUD.
8. **`Log.d` is invisible on user-build images** — diagnostic logging uses `Log.w`.
9. **`CollisionComponent` has no per-shape offset** — use `ShapeResource.offsetByTranslation(center)` (and `close()` the intermediate base shape).
10. **glTF root-node transforms are folded into `TransformComponent`** — `spawnPlaced`'s explicit `setScale` overwrites them.

### 14.3 UI rig pitfalls (also learned the hard way)

- **Position smoothing causes distance drift** — lerping the panel position toward the head while walking means the panel lags behind, so its apparent distance changes (drifts farther or closer).
- **Yaw smoothing while tracking position 1:1 causes an ellipse** — when the head sways during rotation, the lerped forward vector lags the actual head position, so the panel traces an ellipse around the user.
- **Yaw dead zone causes a "swing"** — a 3° dead zone left the panel fixed in world space during small rotations, then it jumped back to center once the threshold was crossed — reading as closer/farther.
- **150 ms update interval is too slow** — at 60°/s head turn, the panel was up to 9° behind, visible as lag. 50 ms (20 Hz) is the sweet spot: smooth enough for yaw following without tripping the ANR watchdog.

Solution: **track position AND yaw 1:1, no smoothing, no dead zone, 50 ms rate limit.** Keep only a tiny position dead zone (3 cm) to filter sub-millimeter walking jitter.

---

## 15. Extension Guide

### 15.1 Change the default floor plan

Edit `demoFloorPlan()` in [FloorPlanModel.kt](app/src/main/java/com/example/testfull/content/FloorPlanModel.kt). Update `FloorPlanModelTest` (it has assertions tied to the demo plan). The plan loads on first launch and when the user clicks "Demo" in the inspector.

### 15.2 Change AI prompt behavior

Edit `buildArrangementMessages` in [AiArranger.kt](app/src/main/java/com/example/testfull/content/AiArranger.kt). The system message is built with `buildString` — add sections, change vocabulary, adjust the output schema. Run `AiArrangerTest` after.

### 15.3 Add a new AI mode

1. Add a `var newMode by remember { mutableStateOf(false) }` in `HomeStage`.
2. Thread it through `requestAiLayout` and `buildArrangementMessages`.
3. Branch the system message and output schema in `buildArrangementMessages`.
4. Add an `AiToggleChip` in `AiArrangePanel`.
5. Handle the new mode in `runAiArrange`'s orchestration loop.
6. Update `parseAiLayout` if the schema changes.

### 15.4 Add a new SurfaceSlot

1. Add the enum value in `TextureLibrary.kt` (`SurfaceSlot`).
2. Add a field to `RoomTextures` in `GeneratedRoom.kt` and a material in `generateRoom`.
3. Add a default roughness in `HomeStage.defaultRoughness`.
4. Add a `TextureSlotRow` in `TexturesCard` (the `SurfaceSlot.entries.forEach` handles this automatically).

### 15.5 Change panel distances / positions

In `HomeStage`'s `SpatialView.update`, the `placePanel(id, ahead, side, dy)` calls:

```kotlin
if (uiOpen) placePanel("main-panel", 0.5f, 0f, -0.2f)        // 0.5 m ahead, 20 cm below eye
placePanel("ui-launcher", 0.55f, 0f, -0.78f)                  // 0.55 m ahead, 78 cm below eye
```

For the placement HUD:

```kotlin
val target = Vector3(hmdPos.x + forward.x * 0.5f, hmdPos.y - 0.2f, hmdPos.z + forward.z * 0.5f)
```

Tune the `ahead` (Z distance), `dy` (vertical offset), and panel dimensions in `PanelFrame(width, height, …)`.

### 15.6 Add a new furniture category

`categorizeModel(name)` in [FurniturePanels.kt](app/src/main/java/com/example/testfull/content/FurniturePanels.kt) does keyword matching:

```kotlin
private fun categorizeModel(name: String): String {
    val lower = name.lowercase()
    return when {
        "sofa" in lower || "couch" in lower -> "Sofa"
        // …
        else -> "Other"
    }
}
```

Add a new keyword branch. To get a default preview image for the new category, add a drawable `furniture_<category>.png` and a branch in `loadModelPreview`'s `when (category)`.

### 15.7 Replace the AI relay

Any OpenAI-compatible endpoint works. Set in `local.properties`:

```properties
ai.api.base=https://your-endpoint.com/v1
ai.api.key=your-key
ai.api.model=your-model
```

The relay must support:
- `POST /chat/completions`
- `Authorization: Bearer <key>`
- `response_format: {"type": "json_object"}`
- Long read timeouts (gpt-5 thinks for 60–120 s).

Tested with `gpt-5`, `gpt-4o`, `gpt-4o-mini`. Faster models produce noticeably worse layouts.

---

## Appendix A: File quick-reference

| Path | What |
|---|---|
| [README.md](README.md) | User-facing feature overview and build commands. |
| [RUNBOOK.md](RUNBOOK.md) | Chinese-language operational runbook (machine-setup focus). |
| [CHANGES.md](CHANGES.md) | Dated changelog of recent work. |
| [TECHNICAL.md](TECHNICAL.md) | This document. |
| [app/build.gradle.kts](app/build.gradle.kts) | Build config + `BuildConfig` injection. |
| [app/src/main/AndroidManifest.xml](app/src/main/AndroidManifest.xml) | Activity, Stage metadata, permissions. |
| [app/src/main/assets/models/](app/src/main/assets/models/) | Bundled furniture + textures (85 files). |
| [app/src/main/assets/adventurex_fengshui_ai_config_v1.json](app/src/main/assets/adventurex_fengshui_ai_config_v1.json) | Fengshui/国学 rule library (1335 lines). |
| [model-manager/server.js](model-manager/server.js) | Web tool for uploading models to the device. |
| [tools/glb-rescale.py](tools/glb-rescale.py) | Measure / bake real-world scale into GLB. |
| [tools/glb-shrink-textures.py](tools/glb-shrink-textures.py) | Shrink GLB-embedded textures to ≤1024. |
| [tools/seed-bounds-cache.py](tools/seed-bounds-cache.py) | Offline bounds measurement + push. |
| [floor-plan-tool/](floor-plan-tool/) | Standalone desktop floor-plan editor (no PICO runtime). |
| [editor-asset/src/main/res3d/](editor-asset/src/main/res3d/) | Showcase scene source (USDA + IBL + skybox). |
| [local.properties.example](local.properties.example) | Template for `local.properties`. |

## Appendix B: Key constants

| Constant | Value | Where | Why |
|---|---|---|---|
| `ITERATE_MAX_ITERATIONS` | 3 | HomeStage.kt | Caps AI self-improvement at 4 calls total. |
| `UI_RIG_UPDATE_INTERVAL_MS` | 50 ms | HomeStage.kt | 20 Hz main panel tracking; below this the ANR watchdog trips. |
| `HUD_UPDATE_INTERVAL_MS` | 150 ms | HomeStage.kt | Placement HUD rate limit. |
| `AIM_UPDATE_INTERVAL_MS` | 33 ms | HomeStage.kt | ~30 Hz aim loop. |
| `AIM_SOURCE_STALE_MS` | 600 ms | HomeStage.kt | Controller/hmd pose freshness window. |
| `HUD_SNAP_DISTANCE_METERS` | 0.6 m | HomeStage.kt | Big-teleport snap threshold. |
| `FOOTPRINT_CLEARANCE_METERS` | 0.05 m | HomeStage.kt | Extra wall clearance for AI placement. |
| `DROP_SPAWN_LIFT_METERS` | 0.08 m | ObjectPlacement.kt | Spawn above rest pose so physics settles gently. |
| `PREVIEW_CONTACT_SKIN_METERS` | 0.02 m | ObjectPlacement.kt | Match the physics solver's contact skin. |
| `SEPARATION_STEP_METERS` | 0.05 m | AiArranger.kt | Overlap nudge step. |
| `SEPARATION_MAX_TRAVEL_METERS` | 4 m | AiArranger.kt | Max total nudge distance. |
| `MAX_AI_SCALE` / `MIN_AI_SCALE` | 5 / 0.05 | AiArranger.kt | AI placement scale clamp. |
| `READ_TIMEOUT_MS` | 300 s | AiArranger.kt | gpt-5 can think for minutes. |
| `MAX_HTTP_ATTEMPTS` | 2 | AiArranger.kt | One retry on socket timeout. |
| `MAX_DETAIL_CHARS` | 2000 | ModelLibrary.kt | Sidecar distillation cap. |
| `SCALE_CONSISTENCY_TOLERANCE` | 0.15 | ModelLibrary.kt | Per-axis ratio band for `computeDefaultScale`. |
| `PLAN_GRID_STEP_METERS` | 0.5 | FloorPlanModel.kt | Editor grid + snap step. |
