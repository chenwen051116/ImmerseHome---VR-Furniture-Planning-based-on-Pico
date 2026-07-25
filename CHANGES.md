# Changelog

All recent changes to the TestFull PICO Spatial room planner.

## 2026-07-25 — Panel reorganization, Iterate mode, texture scanner fix

### Removed: Virtual Walk controls

The on-screen "Virtual walk" navigation buttons (← Left / Forward ↑ / Right → / Back ↓ / Reset center) have been removed from the Environment panel. The room's navigation root is no longer moved by the user at runtime.

- **`CompactEnvironmentPanel`** ([FloorPlanDesigner.kt](app/src/main/java/com/example/testfull/content/FloorPlanDesigner.kt)) — rewrote: removed the walk buttons, position readout, and emulator hint. The freed space now hosts the surface-texture card.
- **`FloorPlanExperiencePanel`** signature — dropped `roomPositionX`, `roomPositionZ`, `onMoveInRoom`, `onResetRoomPosition`; added `availableTextures`, `selectedTextures`, `onTextureSlotChange`, `onApplyTextures`.
- **`HomeStage.kt`** — removed `ROOM_NAVIGATION_LIMIT_METERS`, the `onMoveInRoom` / `onResetRoomPosition` handlers, and the `roomNavigationPosition` wiring at the call site. `roomNavigationPosition` state and `RoomNavigationPosition` data class remain in place (room build still seeds an initial position) but are no longer user-driven.
- **`NavigationButton`** composable deleted (was unused after the walk UI was removed).

### Moved: Surface Textures card → Environment panel

The "Textures" reskin card (wall/floor/ceiling/door/window slot pickers + Apply button) was moved out of the AI Arrange panel and into the Environment panel, where the virtual-walk controls used to live.

- **`TexturesCard`** ([FurniturePanels.kt](app/src/main/java/com/example/testfull/content/FurniturePanels.kt)) — extracted as a standalone `internal` composable so it can be hosted outside the AI panel. Same UI as before: header, subtitle, per-slot `TextureSlotRow`, and an "Apply textures (rebuild)" button.
- **`AiArrangePanel`** — dropped its `availableTextures`, `selectedTextures`, `onTextureSlotChange`, `onApplyTextures` parameters and the inline texture card. Panel height reduced `600.dp → 480.dp` since the texture section is gone.
- **`CompactEnvironmentPanel`** — panel height increased `430.dp → 520.dp` and added `.verticalScroll(rememberScrollState())` so the Apply button is always reachable even when the texture list is long.

### Fixed: Furniture preview images appearing as textures

`bed001.png` (and other furniture previews) were incorrectly listed in the wall/floor texture dropdowns because the scanner picked up every image in the models folder.

- **`isFurniturePreview()`** ([TextureLibrary.kt](app/src/main/java/com/example/testfull/content/TextureLibrary.kt)) — new helper: returns true when an image has a same-named `.glb`/`.gltf` next to it (e.g. `bed-001.png` next to `bed-001.glb`).
- **`scanTexturesIn`** — now filters out furniture preview images via `isFurniturePreview()`. Only standalone texture images (no matching model) appear in the reskin dropdowns.

### Added: Iterate (self-improve) mode in AI Arrange

New "Iterate (self-improve)" toggle in the AI Arrange panel. When on, the AI self-evaluates its placed layout and revises it up to 3 times until it declares satisfaction.

- **`AiLayout.satisfied: Boolean`** ([AiArranger.kt](app/src/main/java/com/example/testfull/content/AiArranger.kt)) — new field. AI returns `satisfied: true` when the current layout is optimal; `false` triggers another revision turn.
- **`requestAiLayout` / `buildArrangementMessages`** — new params: `iterate`, `iteration`, `maxIterations`, `previousNotes`. In ITERATE mode the system prompt instructs the AI to self-evaluate against overlaps, clearances, focal-point orientation, style, and (if Advanced Context is on) fengshui rules; the output schema becomes `{"satisfied": bool, "placements": [...], "notes": str}`.
- **`runAiArrange`** ([HomeStage.kt](app/src/main/java/com/example/testfull/content/HomeStage.kt)) — refactored: extracted `spawnLayout` and `applyTextures` helper lambdas (shared between seed and iteration turns), then added the iteration `while` loop. Stops on `satisfied=true`, on an empty revision (defensive — don't wipe the room), or after `ITERATE_MAX_ITERATIONS` (3) turns.
- **`ITERATE_MAX_ITERATIONS = 3`** constant caps the loop at 4 AI calls total (1 seed + 3 iterations).
- **`AiToggleChip`** row in `AiArrangePanel` — new "Iterate (self-improve)" toggle on its own row. Disabled in Plan Mode (which produces no placements to iterate on). Action button label becomes "Arrange with AI (iterate)" when active.

## 2026-07-25 — View-following rig & placement HUD (earlier session)

### Main panel view-following

- **Distance** — main panel at **0.5 m** from the eye; launcher at **0.55 m**. Closer than the previous 1.0 m for better readability.
- **Position tracking** — panel position tracks head position **1:1** (removed the 0.35 lerp factor that caused drift and inconsistent distance perception during walking).
- **Yaw tracking** — yaw dead zone removed (`|| true` guard) so the panel never lags behind small head rotations. The previous 3° dead zone caused an elliptical swing when rotating.
- **Update interval** — reduced `UI_RIG_UPDATE_INTERVAL_MS` from 150 ms (6.7 Hz) to **50 ms (20 Hz)**. At 150 ms the panel was up to 9° behind head turns at 60°/s.

### Placement HUD

- **Distance** — moved from 1.1 m to **0.5 m** to match the main panel (was invisible because it sat much farther than the panel).
- **Snap-on-first-show** — bypasses the initial positioning delay so the HUD appears at the correct pose immediately.
- **Follow state reset on hide** — prevents stale position interpolation when the HUD reappears.

## 2026-07-25 — AI Arrange: Advanced Thinking & Plan Mode (earlier session)

### Advanced Thinking toggle

When on, the AdventureX fengshui/国学 config (`adventurex_fengshui_ai_config_v1.json` in `app/src/main/assets/`) is loaded and prepended to the GPT-5 system prompt, wrapped in `=== ADVANCED CONTEXT (pre-prompt) ===` delimiters.

### Plan Mode toggle

When on, the AI returns analysis and suggestions only — no furniture is placed. The output schema becomes `{"placements": [], "notes": str, "suggestions": [str]}` and the action button label changes to "Ask AI for suggestions".

- **`AiLayout.suggestions: List<String>`** — new field for plan-mode suggestions.
- **`AiArranger.kt`** — `planMode` param threaded through `requestAiLayout` and `buildArrangementMessages`; system prompt and output schema branch on `planMode`.

## 2026-07-25 — Furniture preview images (earlier session)

- **`loadModelPreview()`** ([FurniturePanels.kt](app/src/main/java/com/example/testfull/content/FurniturePanels.kt)) — reordered preview priority: filesystem PNG next to the `.glb` → category PNG → bundled drawable → generated colored thumbnail.
- **`FurnitureLibraryPanel`** — now calls `loadModelPreview()` instead of `painterResource()` / `generateCategoryThumbnail()`, so uploaded product photos appear on furniture cards.

## 2026-07-25 — Asset organization

- Created **`models/outside material/`** — flat folder consolidating all 85 furniture and texture assets (24 `.glb`, 30 `.json`, 25 `.png`, 6 `.jpg`) from the scattered subfolders (`cabinet/`, `chair/`, `desk/`, `png/`, `归档/`, `沙发归档/`, root). Originals left in place; this is a copy for easy re-upload.
