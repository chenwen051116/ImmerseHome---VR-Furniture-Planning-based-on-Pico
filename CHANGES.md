# UI Changes Summary

## 1. Panel Switcher (Single Panel at a Time)

**Files:** `HomeStage.kt`

Previously, all three main panels (Room Plan, Furniture Library, AI Arrange) displayed simultaneously, cluttering the view. Now only one panel shows at a time with a sliding transition.

### Changes

- **`MainPanel` enum** — defines the three switchable panels: `RoomPlan`, `FurnitureLibrary`, `AiArrange`.
- **`MainPanelSwitcher` composable** — uses `AnimatedContent` with `slideIntoContainer`/`slideOutOfContainer` for a 300ms sliding transition. The layout is a `Row`: `[‹ arrow] [panel] [› arrow]`, so arrows sit beside the panel (not overlapping it) and are never covered by the glass material surface.
- **`PanelArrow` composable** — semi-transparent circular button (`Color(0x44FFFFFF)`) with `‹` / `›` text. Arrows cycle through panels: RoomPlan → FurnitureLibrary → AiArrange → RoomPlan (and reverse).
- **Single `AttachmentPanel`** — replaced three separate attachment panels (`room-plan`, `furniture-library`, `ai-arrange`) with one `main-panel` that hosts the switcher.
- **Closer positioning** — the panel is placed at 1.0m ahead (was 1.05–1.3m for different panels), centered, since only one panel shows at a time.
- **`editorExpanded` default changed to `false`** — Room Plan starts in compact mode (560×430) instead of expanded (920×720), keeping all three panels closer in size to avoid awkward container expansion during transitions.

---

## 2. Auto-Hide Main Panel on Furniture Selection

**Files:** `HomeStage.kt`

### Changes

- When a furniture model is selected (`onModelSelected`), the main panel auto-hides (`uiOpen = false`) so the placement HUD is unobstructed.
- The **Done** button on the placement HUD stops placement and reopens the main panel (`uiOpen = true`).
- The UI launcher icon (home button) still toggles the main panel at any time.

---

## 3. Placement Controller: Rotation & Undo

**Files:** `ObjectPlacement.kt`

### Rotation Support

- **`manualYaw: Float?`** field — when non-null, overrides the auto-face-user yaw. The ghost uses this yaw in `updateAim()` instead of automatically facing the user.
- **`setManualYaw(degrees: Float?)`** — called from the placement HUD's rotation slider.
- **`selectModel()`** resets `manualYaw` to `null` so each new model starts auto-facing.

### Undo

- **`undoLast(): Boolean`** — removes the most recently placed object. Destroys its entity and closes its shape/material resources. Returns `true` if an object was removed.

---

## 4. Placement HUD Redesign

**Files:** `FurniturePanels.kt`

The placement HUD was redesigned with Figma "Select Furniture" glassmorphism design tokens and new functionality.

### Visual Changes

- **Semi-transparent background** — uses `Color(0x3DFFFFFF)` (~24% white) instead of opaque `backgroundMaterial`, so furniture behind the panel is visible while placing.
- **`PanelFrame` `transparent` parameter** — new `Boolean` parameter. When `true`, uses a semi-transparent fill instead of `backgroundMaterial`. Only the placement HUD uses `transparent = true`; other panels keep their solid glass.
- **Figma tokens** — white titles (`FurCardTitle`), muted white hints (`FurHint`), frosted glass fills (`AiGlassFill`).
- **Panel size** — 340×460dp.

### New: Rotation Slider

- **`RotationSlider` composable** — a custom draggable slider (0–360°):
  - Frosted glass track (`AiGlassFill`) with white progress fill
  - White circular thumb (24dp) positioned at the leading edge of the progress fill
  - `detectDragGestures` with `onDragStart` — touch anywhere on the track to jump the thumb to that position, then drag to rotate
  - Updates `placementController.setManualYaw()` in real-time, rotating the furniture ghost as you drag
  - `contentAlignment = CenterStart` ensures the progress bar starts from the left edge and the thumb aligns with it
- A label row shows "Rotation" and the current degree value (e.g., "45°")

### New: Undo Button

- Removes the last placed object via `placementController.undoLast()`
- Updates `placedCount`
- Disabled when no objects are placed

### New: Delete All Button

- Clears all placed furniture via `placementController.clearPlaced()`
- Disabled when no objects are placed

### New: Done Button

- Stops placement (`placementActive = false`)
- Hides the ghost
- Reopens the main panel (`uiOpen = true`)
- Shows count of placed items (e.g., "Done (3 placed)")

### Existing: Drop Here Button

- Drops the furniture at the ghost position
- Primary action, full width

---

## 5. HomeStage Wiring

**Files:** `HomeStage.kt`

### New State

- **`modelRotation: Float`** — tracks the current rotation degrees (0–360).

### Callbacks

- **`onRotationChange`** — updates `modelRotation` and calls `placementController.setManualYaw(degrees)`.
- **`onUndo`** — calls `placementController.undoLast()` and updates `placedCount`.
- **`onPlacementActiveChange`** — when set to `false` (Done button), hides the ghost and reopens the main panel.
- **`onModelSelected`** — resets `modelRotation` to `0f`, sets `placementActive = true`, and auto-hides the main panel.

### Debug Hook

- The `place:` debug hook also resets `modelRotation`, auto-hides the panel, and logs the placement.

---

## File Change Summary

| File | Changes |
|------|---------|
| `HomeStage.kt` | Panel switcher, arrows, auto-hide, rotation state, undo wiring, single `main-panel` attachment, closer positioning, `editorExpanded` default |
| `ObjectPlacement.kt` | `manualYaw` field, `setManualYaw()`, `undoLast()`, `updateAim()` yaw override, `selectModel()` reset |
| `FurniturePanels.kt` | `RotationSlider` composable, redesigned `PlacementHudPanel`, `PanelFrame` transparent parameter, new imports for gestures/layout |
