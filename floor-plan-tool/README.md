# Floor Plan Studio — boundary-free desktop route

A local, dependency-free room editor and 3D walkthrough. It runs on the PC rather than inside the
PICO immersive runtime, so PICO Spatial Safeguard cannot fade it or reveal the headset's default
room.

## Run

Double-click `run-desktop.cmd` in the project root, or open `index.html` in Edge or Chrome.

For local HTTP instead:

```powershell
python -m http.server 8080 --directory floor-plan-tool
```

Then open `http://localhost:8080`.

## Design and walk

1. Draw walls on the 0.5 m grid, or edit their exact length, direction, height, and thickness.
2. Add doors and transparent windows, then edit their width, height, sill, depth, and wall offset.
3. Drag connected wall endpoints; they snap to the nearest grid intersection.
4. Set an exact footprint width/depth, or uniformly scale the complete physical design from
   10–500%.
5. Select **Walk room in 3D**.
6. Click the view for mouse look. Use **W/A/S/D** to move, **Shift** to sprint, **R** to reset,
   and **C** to toggle wall collision. The HUD reports the camera position in meters.

The walkthrough includes an extended floor, ceiling, lights, transparent glass, passable doorways,
and wall/window collision. It rebuilds directly from the current editable plan.

## PICO export

Select **Save FloorPlan.usda**, then save or copy it to:

`editor-asset/src/main/res3d/SpatialPackContent/Sources/Scenes/FloorPlan.usda`

Rebuild the Android app to package that scene. JSON export preserves the editable plan. USDA is the
generated runtime scene and is not intended for re-editing in this browser tool.

The exporter centers the plan at the PICO Stage origin, keeps the floor at `Y=0`, and splits wall
geometry around door and window openings. Running that export on the headset still places it under
PICO's system-level safety boundary; the desktop walkthrough is the no-boundary preview.
