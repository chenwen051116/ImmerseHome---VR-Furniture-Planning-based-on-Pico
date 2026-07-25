# Window / Root Background Decision (Highest-Priority Rule, R4)

The window root background in SpatialUI is **not your responsibility by
default** — `WindowContainer` already paints a frosted-glass material
(`Material.Regular`) for you. R4 governs when (and how) you may change
or disable that default, and forbids any solid color from ever sitting
on top of the glass.

This rule applies to `DefaultWindowContainer { ... }`, every other
`WindowContainer(...)` you launch, `Subwindow { ... }`, the outermost
layer of `Stage { ... }`, and `Augment { ... }`. It does **not** apply to
inner business cards.

## How SpatialUI Renders the Window Background — Two Distinct Switches

The window-level material has **two control surfaces**, scoped to two
different kinds of containers. **Do not mix them up.**

### A. `DefaultWindowContainer` — controlled by the `<activity>` manifest

`DefaultWindowContainer` is the launcher window for the Activity. Its
default material is configured **per-Activity** via the manifest
meta-data inside the launcher `<activity>` element:

```xml
<activity android:name=".platform.LaunchActivity" ...>
    ...
    <!-- Material background of the WindowContainer
         "0": false, no material background
         "1": true, uses Material.Regular as the background (default) -->
    <meta-data
        android:name="pico.spatial.windowcontainer.materialbackground"
        android:value="1"/>
</activity>
```

- Default value `"1"` → the system paints `Material.Regular` glass on the
  `DefaultWindowContainer` root.
- `DefaultWindowContainer { ... }` itself **does not expose any DSL
  parameter** for this setting; you cannot toggle it in Kotlin code, only
  via the manifest.
- The meta-data is always declared **inside `<activity>`**, never inside
  `<application>`. Each launcher Activity owns its own switch.

### B. `WindowContainer(...)` / `Augment(...)` — controlled by DSL

Every other window container you create through the DSL takes the
material switch as a Kotlin parameter:

```kotlin
WindowContainer(
    id = "DetailPanel",
    form = Form.Planar,
    defaultSize = WindowContainerSize(width = 640.dp, height = 360.dp),
    enableMaterialBackground = true,   // ← default; turn to false to opt out
) {
    PicoTheme { Content() }
}

Augment(
    offset = IntOffset3D(...),
    size = ...,
    enableMaterialBackground = false,   // ← e.g. for a transparent decoration
) { /* 3D content */ }
```

- `enableMaterialBackground: Boolean = true` lives on
  `WindowContainerProperties` / the `WindowContainer(...)` overload and on
  `Augment(...)`.
- `true` → `Material.Regular` glass painted by the system.
- `false` → no system glass; the application is then free to either leave
  the root unbacked, set its own glass via
  `Modifier.backgroundMaterial(enable = true, style = Material.<Style>)`,
  or paint a solid color (subject to the rules below).
- The manifest meta-data on the launcher Activity does **not** retroactively
  drive non-default `WindowContainer(...)` instances — those are controlled
  exclusively by the DSL parameter.

## Priority Order (Combined)

### 1. ✅ Default (preferred): use the system glass — DO NOT add anything

If the design accepts `Material.Regular`, **write no background code on
the window root**. Just compose your content; the system glass renders
underneath automatically — whether the window is a `DefaultWindowContainer`
(manifest controlled) or a `WindowContainer(...)` (DSL `enableMaterialBackground = true` is the default).

```kotlin
// DefaultWindowContainer — controlled by manifest, no DSL knob
DefaultWindowContainer {
    Box(Modifier.fillMaxSize()) {
        AppContent()
    }
}

// Non-default WindowContainer — DSL parameter defaults to true
WindowContainer(
    id = "DetailPanel",
    form = Form.Planar,
    defaultSize = WindowContainerSize(width = 640.dp, height = 360.dp),
    // enableMaterialBackground = true  // default; can be omitted
) {
    Box(Modifier.fillMaxSize()) {
        AppContent()
    }
}
```

### 2. ✅ Allowed: switch to a different glass style

When the design requires a non-`Regular` glass style (e.g. `Thin`, `Thick`):

1. **Turn the system default off**, with the right switch for the
   container kind:

   - `DefaultWindowContainer` → manifest:
     ```xml
     <meta-data
         android:name="pico.spatial.windowcontainer.materialbackground"
         android:value="0"/>
     ```
   - `WindowContainer(...)` / `Augment(...)` → DSL:
     ```kotlin
     WindowContainer(..., enableMaterialBackground = false) { ... }
     ```
2. **Reapply explicitly** at the root with the chosen style:

   ```kotlin
   Box(
       Modifier
           .fillMaxSize()
           .backgroundMaterial(enable = true, style = Material.Thin)
   ) {
       AppContent()
   }
   ```

> Skipping step 1 stacks two materials — the system glass beneath plus
> your custom glass on top — and double-blurs / breaks vibrant linkage.
> The off-switch and the new modifier MUST be paired.

### 3. ✅ Allowed: explicit opaque (no-glass) root

Only when the business explicitly opts out of frosted-glass altogether
(fully opaque card, solid brand color, non-PICO platform fallback):

1. Disable the system glass at the right scope:
   - `DefaultWindowContainer` → set `materialbackground="0"` in the
     launcher `<activity>` block.
   - `WindowContainer(...)` / `Augment(...)` → pass
     `enableMaterialBackground = false` to the DSL call.
2. Annotate the root with `// design-style: opaque-root` and use a theme
   role color:

```kotlin
// DefaultWindowContainer — manifest must be "0"
DefaultWindowContainer {
    // design-style: opaque-root  — business requires solid background
    Box(
        Modifier
            .fillMaxSize()
            .background(PicoTheme.colorScheme.fillPrimary)
    ) {
        AppContent()
    }
}

// Non-default WindowContainer — DSL must set enableMaterialBackground = false
WindowContainer(
    id = "OpaquePanel",
    form = Form.Planar,
    defaultSize = WindowContainerSize(width = 640.dp, height = 360.dp),
    enableMaterialBackground = false,
) {
    // design-style: opaque-root  — business requires solid background
    Box(
        Modifier
            .fillMaxSize()
            .background(PicoTheme.colorScheme.fillPrimary)
    ) {
        AppContent()
    }
}
```

### 4. ❌ Forbidden: solid color over glass

Putting `Modifier.background(<color>)` on top of the system glass (or on
top of `backgroundMaterial(...)`) **defeats the entire glass layer** —
once the surface is opaque, the underlying material is invisible and
vibrant linkage (`Color.withVibrant`, `animateColorVibrantAsState`,
`vibrantEffect`) collapses to flat color.

```kotlin
// ❌ Wrong — DefaultWindowContainer with manifest "1" (default), painting fillPrimary on top
DefaultWindowContainer {
    Box(Modifier.fillMaxSize().background(PicoTheme.colorScheme.fillPrimary)) { ... }
}

// ❌ Wrong — non-default WindowContainer with enableMaterialBackground left at default true,
//   then painting solid color on top
WindowContainer(id = "Panel", form = Form.Planar, defaultSize = WindowContainerSize(...)) {
    Box(Modifier.fillMaxSize().background(PicoTheme.colorScheme.fillPrimary)) { ... }
}

// ❌ Wrong — stacking explicit glass + solid color
Box(
    Modifier
        .backgroundMaterial(enable = true, style = Material.Regular)
        .background(PicoTheme.colorScheme.fillPrimary)
)
```

If you want a solid color on the root, you MUST disable the system glass
first via the right switch (case #3) — never paint over it.

### 5. ❌ Forbidden: hardcoded `Color(0xFF...)` on the root

Even in opt-out mode, the solid color must come from
`PicoTheme.colorScheme.*`. Raw `Color(0x...)` literals are forbidden
everywhere (R5).

## Applies To

| Container | Default-glass switch | Where to flip it |
| --- | --- | --- |
| `DefaultWindowContainer { ... }` | `Material.Regular` glass on (default) | Launcher `<activity>` manifest meta-data `pico.spatial.windowcontainer.materialbackground` |
| `WindowContainer(...) { ... }` (any non-default container the app launches itself) | `Material.Regular` glass on (`enableMaterialBackground = true`, default) | DSL parameter `enableMaterialBackground` |
| `Subwindow { ... }` | inherits the parent window-level material; do not paint solid over it | n/a |
| `Stage { ... }` outermost overlay | follows the container the Stage is attached to | per-container |
| `Augment(...)` | `Material.Regular` glass on (`enableMaterialBackground = true`, default) | DSL parameter `enableMaterialBackground` |

## Does NOT Apply To

- Inner business cards / containers — `Modifier.background(fillPrimary)` on
  a card is fine and recommended. The 16dp-rounded card pattern below is
  expected:

  ```kotlin
  Column(
      Modifier
          .clip(RoundedCornerShape(16.dp))
          .backgroundMaterial(enable = true, style = Material.Regular)
  ) { /* card content */ }
  ```

  Cards may use `backgroundMaterial(...)` to introduce glass *inside* a
  page; R4 only governs the window/container root.

## Why "No Solid Over Glass"

Painting an opaque color on top of the system glass hides the material
beneath and collapses the visual effect — the glass would still be
processed but never seen. It also nullifies vibrant linkage
(`systemColorScheme(...)` + `Color.withVibrant`), because vibrant tinting
is only visible through a translucent surface. Picking exactly one
background — glass *or* solid — keeps both the rendering pipeline and
the design intent coherent.

## Verify (Updated Semantics)

`scripts/verify-design-style.sh` enforces R4 with these checks:

- **Forbidden**: any `Modifier.background(<role>)` painted on a window-root
  `Box` inside a window container, **without** a sibling
  `// design-style: opaque-root` comment. (The verifier cannot tell which
  switch is active for that container; the reviewer LLM follows up.)
- **Forbidden**: stacking `backgroundMaterial(...)` and `.background(...)`
  on the same modifier chain.
- **Forbidden**: hardcoded `Color(0x...)` as background, anywhere.
- **Allowed**: `Modifier.backgroundMaterial(enable = true, style = Material.<Style>)`
  on a root, **provided** the container has its system glass turned off
  (manifest `"0"` for `DefaultWindowContainer`, or
  `enableMaterialBackground = false` for `WindowContainer(...)` /
  `Augment(...)`). The verifier emits an `info`-level reminder so the
  reviewer can confirm the switch is set.
- **Allowed**: `// design-style: opaque-root` + `Modifier.background(<role>)`
  on a root, paired with the same off-switch.

See `references/compliance-signals.md` R4 for grep patterns and severity.
