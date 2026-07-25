# Open Interface: from the Figma design to a foldable, view-following VR UI

This document records how the "Open Interface" design was pulled out of Figma via the Dev
Mode MCP server, and how the foldable UI launcher + AR-style view-following panel rig were
implemented in the PICO Spatial SDK app.

What was built, end to end:

1. A **UI launcher panel** showing the app's home icon (the LOGO node from the Figma file
   "Open Interface"). One tap folds every main panel away; another tap opens them all again.
2. A **view-following UI rig**: all panels keep their formation relative to the user's gaze
   (yaw only), like an AR panel — they rotate with the head, keep their distance, freeze when
   the head is still, and snap in front of the user on big turns.
3. On first app start, all UI appears in front of the user (`uiOpen` starts `true`).

Key files:

- `app/src/main/java/com/example/testfull/content/HomeStage.kt` — rig state, follow logic,
  fold/open wiring, debug hooks.
- `app/src/main/java/com/example/testfull/content/FurniturePanels.kt` — `UiLauncherPanel`
  composable.
- `app/src/main/res/drawable-nodpi/ui_logo.png` — the launcher icon, exported from Figma.

---

## Part 1 — Getting the design out of Figma

### 1.1 The Figma Dev Mode MCP server

Figma desktop runs a local MCP (Model Context Protocol) server when enabled
(**Figma menu → Preferences → Enable Dev Mode MCP server**). It listens on
`http://127.0.0.1:3845/mcp`, speaks JSON-RPC 2.0 over streamable HTTP, and — critically —
**serves only the node currently selected in Figma desktop**. There is no "list files" or
"open file" call: a human selects the frame, the agent reads the selection.

The agent's MCP config (user level, `C:\Users\Acer\.kimi-code\mcp.json`):

```json
{ "mcpServers": { "figma": { "url": "http://127.0.0.1:3845/mcp" } } }
```

For this task the server was driven directly with `curl` (same protocol the MCP client would
use), which has the advantage of showing the raw responses.

### 1.2 Talking JSON-RPC to the server with curl

The server requires an MCP session: `initialize` first, keep the `mcp-session-id` response
header, send `notifications/initialized`, then call tools. Responses come back as SSE
(`event: message` / `data: {...json...}`).

```bash
# 1. Handshake — capture the session id from the response headers
curl -s -D headers.txt -X POST http://127.0.0.1:3845/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
        "protocolVersion":"2025-03-26","capabilities":{},
        "clientInfo":{"name":"kimi","version":"1.0"}}}'
# -> response header: mcp-session-id: db137657-7971-4509-94bc-9a9c0bb26502

SID=db137657-7971-4509-94bc-9a9c0bb26502

# 2. Required initialized notification
curl -s -X POST http://127.0.0.1:3845/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "mcp-session-id: $SID" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

# 3. Discover tools
curl -s -X POST http://127.0.0.1:3845/mcp ... -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
# -> get_design_context, get_variable_defs, get_screenshot, get_motion_context,
#    get_metadata, get_figjam
```

### 1.3 Reading the selected node

The designer selected the **LOGO** node in the "Open Interface" file. `get_metadata`
returned:

```
Currently selected nodes:
- 2111:8151: LOGO
<rounded-rectangle id="2111:8151" name="LOGO" x="946" y="444" width="437" height="408" />
```

`get_design_context` returned the generated React+Tailwind snippet — the node is just an
image fill, and the asset URL is the real deliverable:

```jsx
const imgLogo = "http://localhost:3845/assets/059e195fdffe68c921cd185a942c8646c5cd8791.png";
export default function Logo() {
  return (
    <div className="relative size-full" data-node-id="2111:8151" data-name="LOGO">
      <img className="absolute inset-0 max-w-none object-cover pointer-events-none size-full"
           src={imgLogo} />
    </div>
  );
}
```

**Practical note**: `get_design_context` is slow to stream (the SSE body only arrives when
complete). A 60 s `curl -m` timeout truncated it silently — use `-m 180` and write with `-o`.

### 1.4 From asset PNG to Android drawable

```bash
curl -s -o figma-logo.png \
  "http://localhost:3845/assets/059e195fdffe68c921cd185a942c8646c5cd8791.png"
# PNG image data, 1057 x 986, 8-bit/color RGBA
```

The exported PNG has fully transparent margins around the dark rounded-square icon (checked
corner pixels: alpha 0; content bounding box `(118, 54, 977, 906)`). It was cropped to
content and downscaled to a 512×512 drawable with the project's Python venv
(`TestFull/.venv`, Pillow):

```python
from PIL import Image
im = Image.open("figma-logo.png").convert("RGBA")
crop = im.crop((118, 54, 977, 906)).resize((512, 512), Image.LANCZOS)
crop.save("app/src/main/res/drawable-nodpi/ui_logo.png")
```

The design itself: a dark (#2B2B2B-ish) rounded square with a white house+sofa glyph and a
small amber arched window.

---

## Part 2 — Implementation

### 2.1 Where the UI lives

The app renders its UI as PICO Spatial SDK **attachment panels** inside a single
`SpatialView` (`HomeStage.kt`). Before this change there were four panels at fixed world
positions:

| Panel | id | Initial pose |
|---|---|---|
| Room plan editor | `room-plan` | `(0, 1.4, -1.3)` |
| Furniture library | `furniture-library` | `(-0.62, 1.3, -1.05)`, yaw 30° |
| AI arrange | `ai-arrange` | `(0.62, 1.3, -1.05)`, yaw −30° |
| Placement HUD | `placement-hud` | hidden unless placing; already view-following |

The placement HUD already had a rate-limited, dead-zoned follow loop (`HudFollowState`,
`HUD_UPDATE_INTERVAL_MS = 150`) — its pattern was reused for the whole rig.

### 2.2 The launcher panel

New composable `UiLauncherPanel` in `FurniturePanels.kt`: a 140 dp material card containing
the 96 dp Figma icon plus a state hint ("Tap to fold the interface" / "Tap to open the
interface"). The **whole card is the tap target** (`Modifier.clickable`) — a generous hit
area matters in VR. Registered as a fifth attachment panel:

```kotlin
AttachmentPanel(id = "ui-launcher") {
    UiLauncherPanel(
        uiOpen = uiOpen,
        onToggle = {
            uiOpen = !uiOpen
            Log.w(TAG, "ui launcher: uiOpen -> $uiOpen")
        },
    )
}
```

Initial world pose `(0, 0.82, -1.05)` — centered below the main panels.

### 2.3 Fold / open everything

One state drives it:

```kotlin
// The launcher icon folds every main panel away (or opens them all) with one tap.
var uiOpen by remember { mutableStateOf(true) }   // UI open on first app start
```

In `SpatialView`'s `update` lambda:

```kotlin
listOf("room-plan", "furniture-library", "ai-arrange").forEach { panelId ->
    attachments.entity(id = panelId)?.let { panel ->
        if (panel.enabled != uiOpen) panel.enabled = uiOpen
    }
}
```

Deliberately **not** folded: the launcher itself (it must stay reachable to reopen) and the
placement HUD (a contextual tool that only exists while placing — folding it mid-placement
would strand the user with no Drop button).

Debug hook for adb-driven testing (`ai_test_prompt.txt` watcher, debug builds only): the
magic prompt `ui:toggle` flips `uiOpen`, logging `debug hook: uiOpen -> …`.

### 2.4 The view-following AR rig

**Goal** (user requirement): "all the UI should keep a distance from the eye and move with
the eye when rotating — like an AR panel."

**Tracking provider split.** HMD tracking previously ran only while placing furniture. It
now runs continuously; controller tracking stays placement-scoped:

```kotlin
DisposableEffect(Unit) {                 // HMD: always on — the rig needs it
    hmdProvider.start()
    onDispose { hmdProvider.stop() }
}
DisposableEffect(placementActive) {      // controller: only while placing
    if (placementActive) controllerProvider.start()
    onDispose {
        controllerProvider.stop()
        placementController.hideGhost()
    }
}
```

**Rig state** (`UiRigFollowState`): smoothed eye position, smoothed flattened forward,
last-update timestamp, last seen `uiOpen` (to force a re-snap when reopened), plus a
one-shot diagnostic flag.

**Formation math** (yaw-only — following pitch would be nauseating). Each evaluation:

1. Flatten the HMD forward to the horizontal plane and normalize → `fwd`.
2. Left vector = `up × fwd` → `left = (fwd.z, 0, -fwd.x)`. (Check: facing −z, left is −x,
   matching the original fixed layout where the library sat at x = −0.62.)
3. Panel targets, preserving the original fixed-layout formation:

   ```kotlin
   placePanel("room-plan",         ahead = 1.3f,  side =  0f,    dy = -0.2f)
   placePanel("furniture-library", ahead = 1.05f, side =  0.62f, dy = -0.3f)
   placePanel("ai-arrange",        ahead = 1.05f, side = -0.62f, dy = -0.3f)
   placePanel("ui-launcher",       ahead = 1.05f, side =  0f,    dy = -0.78f)
   // target = eye + fwd*ahead + left*side, y = eye.y + dy
   ```

4. Each panel is yawed to face the user with the existing
   `yawFacingUserDegrees(target, eye)` helper (`ObjectPlacement.kt`).

**Anti-jitter / anti-ANR guards** — every panel move redraws its whole surface, and per-frame
redraws trip the emulator's ANR watchdog, so:

- rate limit: rig re-evaluates at most every `HUD_UPDATE_INTERVAL_MS` (150 ms);
- dead zone: no move unless the head moved > 3 cm (`UI_RIG_MIN_MOVE_METERS`) or turned
  > 3° (`UI_RIG_MIN_YAW_DEGREES`) — panels freeze when the head is still;
- smooth follow: lerp factor 0.35 on both eye position and forward (re-normalized);
- instant snap when the user moved > 0.6 m (`HUD_SNAP_DISTANCE_METERS`) or turned
  > 40° (`UI_RIG_SNAP_YAW_DEGREES`), so the UI never lags far behind.

**Freshness rule and the first-anchor fix.** Tracking only applies while HMD data is fresh
(`hmdUpdatedAt` within `AIM_SOURCE_STALE_MS` = 600 ms) — the emulator streams poses in
bursts. But the *first* anchor is exempt:

```kotlin
(rigNow - aimState.hmdUpdatedAt <= AIM_SOURCE_STALE_MS || uiRig.eye == null)
```

Without the `uiRig.eye == null` escape hatch the rig never anchored at app start (no pose
had arrived yet by the time the first update ran, and later updates were gated by
staleness) — the panels just sat at their fixed spawn poses.

**Fold interaction**: when `uiOpen` flips, the rig's smoothed pose is reset
(`eye`/`forward` = null) so reopened panels snap in front of *wherever the user is looking
now*, not where they were folded away. While folded, only the launcher follows.

### 2.5 The ticker — why the rig only moved on click

**Symptom after the first rig build**: panels updated their pose only when the user clicked
something.

**Root cause**: `SpatialView`'s `update` lambda is **not** a per-frame callback — it re-runs
only when a Compose state it reads changes. The rig code read only plain mutable fields
(`aimState`, `uiRig`), so it re-evaluated solely when an unrelated state changed (e.g. a
click flipping `uiOpen`).

**Fix**: a 100 ms ticker state that the update lambda subscribes to:

```kotlin
var rigTick by remember { mutableIntStateOf(0) }

LaunchedEffect(Unit) {
    while (true) {
        rigTick += 1
        delay(100)
    }
}
```

and inside `update`:

```kotlin
// Reading rigTick subscribes this block to the 100 ms ticker — without it the rig would
// only re-evaluate when some unrelated state changes (e.g. a click).
val rigTickObserved = rigTick
```

Re-running `update` is cheap (math + entity lookups); actual panel moves — the expensive
part — are still gated by the rate limit and dead zone. A diagnostic heartbeat logs every
50 ticks (~5 s): `ui rig: alive tick=… hmdAge=…ms anchored=…`.

---

## Part 3 — Verification (PICO emulator, debug build)

Build & install each iteration:

```bash
export JAVA_HOME='E:\7.25\jdk17'
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug   # BUILD SUCCESSFUL, tests green
adb install -r 'E:\7.25\TestFull\app\build\outputs\apk\debug\app-debug.apk'
adb shell monkey -p com.example.testfull -c android.intent.category.LAUNCHER 1
```

Logcat evidence (final build, pid 5902):

```
HomeStage: attachment entity(room-plan) = true
HomeStage: attachment entity(furniture-library) = true
HomeStage: attachment entity(ai-arrange) = true
HomeStage: attachment entity(placement-hud) = true
HomeStage: attachment entity(ui-launcher) = true          <- launcher registered
HomeStage: ui rig: hmd pose flowing pos=Vector3(x=0.0, y=1.6807814, z=0.0)
HomeStage: ui rig: anchored to view                       <- rig snapped in front of user
HomeStage: ui rig: alive tick=100 hmdAge=15ms anchored=true
HomeStage: ui rig: alive tick=400 hmdAge=290ms anchored=true   <- continuous re-evaluation
HomeStage: ui launcher: uiOpen -> false                   <- real taps on the icon in VR
HomeStage: ui launcher: uiOpen -> true
HomeStage: debug hook: uiOpen -> false                    <- adb-driven toggle
HomeStage: debug hook: uiOpen -> true
```

No ANR, no FATAL; process stable.

**Known limits of emulator verification**: adb cannot inject VR taps or head rotation, so the
icon tap itself and the follow *feel* were confirmed by a human in the emulator (tap logs
above), while the state machine was confirmed via the debug hook. Panel surfaces are not
visible in `screencap` output.

---

## Appendix — tuning knobs

All in `HomeStage.kt`:

| Constant | Value | Effect |
|---|---|---|
| `HUD_UPDATE_INTERVAL_MS` | 150 | rig re-evaluation rate cap |
| `UI_RIG_MIN_MOVE_METERS` | 0.03 | dead zone: head translation |
| `UI_RIG_MIN_YAW_DEGREES` | 3 | dead zone: head rotation |
| `UI_RIG_SNAP_YAW_DEGREES` | 40 | instant snap threshold (turn) |
| `HUD_SNAP_DISTANCE_METERS` | 0.6 | instant snap threshold (move) |
| lerp factor `0.35f` | — | follow smoothness; raise for snappier tracking |
| `AIM_SOURCE_STALE_MS` | 600 | HMD freshness window |
| ticker `delay(100)` | 100 ms | how often `update` is nudged |

Feel too laggy → raise the lerp factor (e.g. 0.5). Too jittery → raise the `UI_RIG_MIN_*`
dead zones.
