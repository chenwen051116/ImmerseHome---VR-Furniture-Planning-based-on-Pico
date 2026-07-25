# Stage (Immersive Container)

A `Stage` is an unbounded immersive container for spatial content. It is the
right choice when the input or user request implies a boundless scene,
passthrough spatial content, anchors, environment mesh, or a virtual world.

## Hard rules (don't violate)

1. **Use Stage only when the experience is truly immersive or stage-only.**
2. **Do not replace a working windowed module with Stage just because the input contains rich visuals.**
3. **Stage-only APIs must stay inside a Stage-based flow.**

## Three immersion levels

| Stage mode | Background | Typical cue |
|---|---|---|
| `MIXED` | Real world / passthrough behind virtual content | free spatial content in the real room |
| `PROGRESSIVE` | Adjustable blend between real and virtual | explicit immersion control or partial environment replacement |
| `FULL` | Pure virtual environment | fully immersive world, no real-world background |

## Registration

Use `DefaultStage {}` as the root DSL entry:

```kotlin
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage {
            PicoTheme {
                ImmersiveScene(modifier = Modifier.fillMaxSize())
            }
        }
    }
```

The stage style is declared in the launcher Activity metadata. Keep it aligned
with the selected container mode.

## Opening / closing a secondary stage flow

```kotlin
val nav = LocalSpatialNavigator.current

coroutineScope.launch {
    nav.openStage(
        id = "tutorial",
        style = StageStyle.Mixed,
    )
}
```

Close when the stage flow ends:

```kotlin
coroutineScope.launch { nav.closeStage() }
```

## Manifest metadata

The launcher Activity must carry the stage metadata:

```xml
<meta-data android:name="pico.spatial.stage.id" android:value="MainStage" />
<meta-data android:name="pico.spatial.stage.style" android:value="2" />
<meta-data android:name="pico.spatial.stage.immersion" android:value="50" />
<meta-data android:name="pico.spatial.stage.immersion_min" android:value="0" />
<meta-data android:name="pico.spatial.stage.immersion_max" android:value="100" />
```

For input-driven scaffolding:

- `STAGE_MIXED` → `style=1`, conservative mixed entry
- `STAGE_PROGRESSIVE` → `style=2`, `immersion=50`, `min=0`, `max=100`
- `STAGE_FULL` → `style=3`, `immersion=100`, `min=100`, `max=100`

## What lives inside a Stage

Inside `DefaultStage { }` you can put:

- 2D Composables used as panels or overlays
- `SpatialView` / `SpatialModelView`
- Spatial ECS entities (3D models, lights, animations)
- attached informational panels when the design needs them

For ECS details see `spatial-pack-3d.md`. For anchors see `spatial-anchor.md`.

## What to say explicitly in the handoff

Because a 2D reference under-specifies immersive behavior, always state:

- whether passthrough / environment / immersion level was inferred
- whether stage overlays are mocked or simplified
- whether anchors or environment-mesh behavior still require a real device

## When NOT to use Stage

- A simple 2D panel app → `WindowContainer · ON_PLAIN`
- Multiple apps need to coexist on screen → `WindowContainer`
- You don't need anchors / env mesh / ray casting / global skybox

If the input is basically a flat settings, dashboard, chat, or file panel,
`Stage` is usually the wrong choice.
