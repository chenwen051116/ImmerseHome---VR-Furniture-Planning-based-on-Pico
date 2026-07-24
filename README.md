# PICO Room Planner

An Android Spatial SDK Stage app for sketching a room in plan view and rebuilding it as a
room-scale PICO environment.

## In-headset workflow

1. Open **PICO Room Planner**.
2. The starter plan is a 15 m × 8 m rectangle. Choose **Wall**, then tap endpoints on the
   0.5 m plan grid to draw connected wall segments. Beginning a new wall while the starter plan is
   untouched automatically clears that starter, so it is not included in the custom room.
3. Choose **Door** or **Window**, then tap a wall to insert the opening.
4. Choose **Select** and tap an item. Every dimension field accepts direct keyboard input as well
   as the `−` / `+` controls:
   - Walls: exact length, direction, height, and thickness.
   - Doors/windows: type, offset from the wall start, width, height, sill, and panel depth.
5. In **Select** mode, drag a white connection point to reshape every wall joined at that point.
   Connection points magnetically snap to the nearest 0.5 m grid intersection. Tap empty grid
   space for plan controls, where you can set an exact footprint width/depth or uniformly scale
   the entire design from 10–500%. **View zoom** changes only the 2D editor view.
6. Select **Apply & preview room**. The native Spatial SDK scene is rebuilt immediately and the
   editor collapses.
7. Use the compact panel's **Virtual walk** arrows to move through the generated room in 0.5 m
   steps. The X/Z readout shows your virtual position; **Reset center** returns to the plan origin.
   Use **Showcase** / **Generated room** to switch environments, or **Edit plan** to continue.

The generated wall geometry is split around openings, so doors and windows are real gaps rather
than colored panels placed over solid walls. Windows use a transparent PBR glass material, and the
room includes warm ceiling lights plus a daylight source. Applying a room first removes whichever
scene is currently attached, so neither a previous generated room nor the Showcase remains
underneath the replacement.
Applying a plan also creates a ceiling, an extended ground plane, and an inside-facing environment
shell. PICO's Spatial Safeguard remains a system-level safety feature: moving the physical or
emulated headset outside its safe zone fades the app and reveals the VST background. Use the
in-app Virtual walk controls for room-scale travel. In PICO Emulator, W/A/S/D moves the simulated
headset and can trigger that fade; press Ctrl+R to restore its initial pose.

The plan canvas displays wall lengths and door/window sizes in meters. The generated root owns a
PICO `PhysicsWorldComponent`; floors, ceiling, every wall solid, doors, and windows use static box
colliders with friction. Future furniture can be given `RigidBodyComponent` in dynamic mode and
will collide with the generated room. Headset movement itself is not a physics body, so room
colliders affect virtual furniture rather than preventing a user from physically walking.

The APK declares an exported `MAIN` / `LAUNCHER` activity with the **PICO Room Planner** label, a
high-contrast 1024×1024 room-plan fallback icon, and PICO spatial icon metadata. An installed build
appears in the PICO application panel and resumes as a single Stage when launched.

## Placing models with physics

The room can be furnished with local 3D models (`.glb`, `.gltf`, `.usda`, `.usdc`, `.usdz`):

1. Push model files into the app's models folder (no storage permission needed — it is the
   app-specific external files directory):

   ```powershell
   adb push chair.glb /sdcard/Android/data/com.example.testfull/files/models/
   ```

2. In the expanded editor panel, open the **Objects** section and choose **Scan models folder**.
3. Select a model. Placing mode switches on and a translucent **ghost preview** follows your
   controller aim (in PICO Emulator the mouse drives the emulated controller; if no controller
   reports, head gaze is used). The ghost shows the final resting pose: a physics ray-cast finds
   your aim anchor on walls/floor, a second cast straight down finds the supporting surface —
   floor or a previously dropped object, so models stack — and the model's pivot offset lifts it
   onto that surface.
4. **Click the trigger** to drop. The dropped copy is a dynamic `RigidBodyComponent` with a convex
   collision shape, so it falls and settles under full physics against the room's static colliders.
   Drop as many copies as you like; adjust **Model scale** (5–500%) before dropping.
5. **Clear placed** removes every dropped object. Applying a rebuilt room also clears them (the
   old room's entity tree owns them); the model selection survives and the ghost reloads against
   the new room.

The preview is a physics *query* (ray casts), not a live tumble simulation: it is exact for
upright models landing on flat surfaces; objects dropped onto edges may tumble slightly after the
drop. The drop itself is fully simulated.

## Build and deploy

The project targets Android API 35, `arm64-v8a`, JDK/JBR 21, and PICO Spatial SDK 0.13.3.

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
pico-cli app install app\build\outputs\apk\debug\app-debug.apk
pico-cli app launch com.example.testfull
```

The installable APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Desktop interchange

For a genuinely boundary-free route, use the dependency-free desktop editor and full-screen 3D
walkthrough in `floor-plan-tool/`. It runs outside the PICO runtime, so walking cannot trigger the
headset's circular Spatial Safeguard fade. Double-click:

```text
run-desktop.cmd
```

The 3D view supports mouse look, W/A/S/D movement, sprinting, collision toggling, transparent
windows, room lighting, and a position readout in meters. The same plan remains editable and
exports PICO-compatible `FloorPlan.usda`. To serve it over local HTTP instead:

```powershell
python -m http.server 8080 --directory floor-plan-tool
```

Save the export to
`editor-asset/src/main/res3d/SpatialPackContent/Sources/Scenes/FloorPlan.usda`, then rebuild the
Android app to pack that USDA scene into the asset bundle.
