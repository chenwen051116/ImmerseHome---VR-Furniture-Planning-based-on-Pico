---
title: Figma Annotation → SpatialUI Code Mapping
audience: anything-to-spatial-app / figma-adapter
trigger: Phase 1.5 figma-adapter, when input_mode == "visual_design" and platform == "android"
migrated_from: legacy-d2c-reference/figma-to-code-mapping.md (Phase B1.1)
---

# Figma Annotation to SpatialUI Code Mapping

Use this guide to convert Figma annotations into SpatialUI code with high fidelity.

> **Core rule**: trust the **token name**, not just the color swatch shown below it.

> **Fidelity rule**: when XML / screenshot evidence gives exact visible size,
> offset, radius, opacity, color, or repeated count, reproduce it 1:1 unless it
> violates SpatialSDK legality. Generic responsiveness, theme normalization, or
> “cleaner” layout must not silently change the visual result.

> **Asset rule**: `<Icon download-url>` and `<Image src>` are visual source of
> truth. Export/download them and render the local asset. A gradient, initials,
> generic vector, or arbitrary placeholder is not a valid replacement for a
> Figma icon, avatar, thumbnail, screenshot, or placeholder art.

---

## 1. Vibrant Token Mapping (`(Vibrant)` suffix)

| Figma Annotation | Code | Typical Use |
|------------------|------|-------------|
| `Darkest (Vibrant)` | `Color.Vibrant.withVibrant(Vibrant.Darkest)` | body text, main titles |
| `UltraDark (Vibrant)` | `Color.Vibrant.withVibrant(Vibrant.UltraDark)` | emphasized foreground |
| `Darker (Vibrant)` | `Color.Vibrant.withVibrant(Vibrant.Darker)` | primary button background |
| `Semidark (Vibrant)` | `Color.Vibrant.withVibrant(Vibrant.Semidark)` | supporting foreground, section labels |
| `Dark (Vibrant)` | `Color.Vibrant.withVibrant(Vibrant.Dark)` | secondary foreground |
| `Neutral (Vibrant)` | `Color.Vibrant.withVibrant(Vibrant.Neutral)` | cards, list rows, secondary buttons |
| `Light (Vibrant)` | `Color.Vibrant.withVibrant(Vibrant.Light)` | section background |
| `SemiLight (Vibrant)` | `Color.Vibrant.withVibrant(Vibrant.SemiLight)` | selected state |
| `UltraLight (Vibrant)` | `Color.Vibrant.withVibrant(Vibrant.UltraLight)` | very light foreground |

> **Common aliases**: `Label Primary` usually maps to `Darkest`; `Label Tertiary` often maps to `Dark`.

---

## 2. Semantic Role Mapping

> For the full role model, defer to `spatial-ui-design-style/references/tokens.md`. This section keeps the high-frequency mappings used during D2C.

| Figma Annotation | Code |
|------------------|------|
| `Fill Primary / Secondary / Tertiary / Light` | `PicoTheme.colorScheme.fillPrimary / fillSecondary / fillTertiary / fillLight` |
| `Label Primary / Secondary / Tertiary / Quaternary` | `PicoTheme.colorScheme.labelPrimary / labelSecondary / labelTertiary / labelQuaternary` |
| `Label Primary Light` | `PicoTheme.colorScheme.labelPrimaryLight` |
| `Lighten Hover / Pressed` | `PicoTheme.colorScheme.lightenHover / lightenPressed` |

---

## 3. Semantic Fixed Colors

| Figma Annotation | Code |
|------------------|------|
| `Error` | `PicoTheme.colorScheme.error` |
| `Alert` | `PicoTheme.colorScheme.alert` |
| `Passable` | `PicoTheme.colorScheme.passable` |
| `Interaction` | `PicoTheme.colorScheme.interaction` |
| `Divider Line` | `PicoTheme.colorScheme.dividerLine` |
| literal fixed color | `Color(0xFFxxxxxx) // design-style: fixed-figma-color <source>` |

---

## 4. Mixed Color Annotation (`Color + Vibrant`)

When Figma explicitly annotates both a base color and a Vibrant level:

```kotlin
// convenient form
Color(0xff28ad00).withVibrant(Vibrant.UltraDark)

// lower-level form
Modifier.vibrantEffect(Vibrant.UltraDark).background(Color(0xff28ad00))
```

---

## 5. Typography Mapping

> For typography role details, see `spatial-ui-design-style/references/tokens.md`.

| Figma Style | Code |
|-------------|------|
| `Display Large / Medium / Small` | `PicoTheme.typography.displayLarge / displayMedium / displaySmall` |
| `Headline Large / Medium / Small` | `PicoTheme.typography.headlineLarge / headlineMedium / headlineSmall` |
| `Title Large / Medium / Small` | `PicoTheme.typography.titleLarge / titleMedium / titleSmall` |
| `Label Large / Medium / Small` | `PicoTheme.typography.labelLarge / labelMedium / labelSmall` |
| `Body Large / Medium / Small` | `PicoTheme.typography.bodyLarge / bodyMedium / bodySmall` |

---

## 6. System Material

Regions annotated as `Material` inside dialogs, menus, sheets, or subwindows should normally use the material semantics already owned by those window components. For window-level boundaries, see `spatial-windows-guide.md`; for content-level material APIs, see `spatial-ui-design-style/references/spatial-capabilities.md`.

For `WindowContainer` shell material, use `enableMaterialBackground = true`.

---

## 7. Color Conversion Decision Flow

```text
1. Read the token name.
2. Has a `(Vibrant)` suffix? -> `Color.Vibrant.withVibrant(Vibrant.Xxx)`
3. Is it a semantic role? -> `PicoTheme.colorScheme.xxx`
4. Is it a semantic fixed color? -> `PicoTheme.colorScheme.xxx`
5. Is it explicitly annotated as Color + Vibrant? -> `Color(value).withVibrant(Vibrant.Xxx)`
6. Otherwise -> `Color(0xFFxxxxxx) // design-style: fixed-figma-color <source>`
```

---

## 7.1 Hardcoded Color Anti-Patterns and Deeper Rules

### 1. The truth behind `PicoTheme.colorScheme`

- Text and background roles such as `labelPrimary` and `fillPrimary` already carry built-in Vibrant levels such as `Darkest` or `Darker`.
- Semantic colored roles such as `error`, `alert`, and `passable` are pre-wrapped with **`Vibrant.None`** at the SDK level.

### 2. Revised D2C color decisions

- **Grayscale hierarchy colors** (`Label`, `Fill`, etc.)
  - Never hardcode them. For example, do not translate `Label Secondary` into `Color(0xB3595959)`.
  - Use a Vibrant expression such as `Color.Vibrant.withVibrant(Vibrant.UltraDark)`.
- **Standard semantic opaque colors**
  - Prefer `PicoTheme.colorScheme.error / alert / passable`.
  - They already preserve the intended pure-color behavior through `Vibrant.None`.
- **Semi-transparent semantic colors**
  - If Figma explicitly gives a translucent value such as `Color(0xCCDDFF99)`, do **not** mechanically replace it with `PicoTheme.colorScheme.passable`.
  - That would lose the original alpha and turn the color into an opaque semantic block.
  - Use `Color(0xCCDDFF99) // design-style: fixed-figma-color <source>` for
    1:1 restoration unless the SDK component already provides the exact visual.
- **Foreground on dark surfaces**
  - When white text sits on a dark button, prefer `PicoTheme.colorScheme.labelPrimaryLight` over `Color.White`.

---

## 8. Visual Feature -> Code Mapping (Screenshot Flow)

When there are no Figma tokens and only screenshots are available, infer code from visual structure.

> For subwindow and floating-layer rules, see `spatial-windows-guide.md`. For tooltip and glass-material boundaries, see `spatial-ui-design-style/references/spatial-capabilities.md`.

### 8.1 Shape Cues

| Visual Feature | Code |
|----------------|------|
| rounded rectangle | `Modifier.clip(RoundedCornerShape(X.dp))` |
| circle | `Modifier.clip(CircleShape)` |
| pill | `Modifier.clip(RoundedCornerShape(50))` |
| no visible rounding | omit `clip` |

### 8.2 Shadow / Elevation Cues

| Visual Feature | Code |
|----------------|------|
| element appears lifted / floating | `Modifier.zOffset { value }` |
| real 3D depth | `Box3D` + `depth()` |
| popup-like floating layer | `AlertDialog` / `Sheet` / `SpatialPopup` |
| edge-attached tool rail | `TabBar` / `Toolbar` |

### 8.3 Layout Cues

| Visual Feature | Code |
|----------------|------|
| equally divided horizontal layout | `Row` + `Modifier.weight(1f)` |
| vertical stack | `Column` |
| grid of cards | `LazyVerticalGrid(columns = GridCells.Fixed(N))` |
| vertical scroll list | `LazyColumn` |
| wrapping tags | `FlowRow` |
| carousel / paged horizontal content | `HorizontalPager` + `PageControl` |
| in-page left navigation + right content | `Row { SideNavigation(); Content() }` |
| floating edge navigation around a window | `TabBar` / `Toolbar` |
| segmented choice + content swap | `SegmentControl` + `Crossfade` / `AnimatedContent` |

### 8.4 Interaction Cues

| Visual Feature | Code |
|----------------|------|
| clickable card with hover highlight | `.clip().spatialHoverEffect().clickable {}` |
| hover emphasis | `.spatialHoverEffect()` |
| toggle switch | `Switch` |
| checkbox | `Checkbox` |
| draggable value adjustment | `Slider` |

### 8.5 Transparency / Glass Cues

| Visual Feature | Code |
|----------------|------|
| translucent blurred shell | `enableMaterialBackground = true` or `Modifier.backgroundMaterial()` |
| color adapts to background | Vibrant color system |
| translucent mixed overlay | `Color.Xxx.withVibrant(Vibrant.Yyy)` |

### 8.6 Icons and Placeholder Images

| XML / screenshot evidence | Required implementation |
|---------------------------|-------------------------|
| `<Icon download-url>` | Download through `d2c_download_icons`; use the generated drawable / vector resource at the same size as XML |
| `<Image src>` app thumbnail / avatar / placeholder art | Download the bitmap to `drawable-nodpi`; render with `Image(painterResource(...), contentScale = ContentScale.Crop)` and the XML size / clip radius |
| Missing / failed image asset | Record URL and failure in `assumption_ledger.json`; use a visibly marked fallback only for that failed asset |
| Generic generated gradients / initials | Forbidden when Figma provides an image source |

---

## 9. Figma Component -> SpatialUI Component Mapping

When a Figma component already semantically matches a SpatialUI component, prefer the official SpatialUI component rather than rebuilding it by hand.

> Component whitelist & decision tree: `spatial-ui-components.md`. Window-level fittings (TabBar / Toolbar / Subwindow / Sheet / Augment): `spatial-windows-guide.md`.

### 9.1 Component decision tree

Use this as the first semantic pass after reading the design tree:

```text
Design element
├─ A. Outside normal page flow? floating layer, side window, edge ornament, popup?
│  ├─ global switch / edge navigation -> TabBar
│  ├─ tool action strip -> Toolbar
│  ├─ independent auxiliary window -> Subwindow
│  ├─ panel prompt / warning -> Sheet
│  ├─ short confirmation / blocking warning -> AlertDialog
│  ├─ menu layer -> Menu
│  ├─ anchored spatial popup -> SpatialPopup
│  ├─ teaching anchor hint -> CoachmarkBox
│  ├─ transient feedback host -> SnackbarHost
│  └─ attached ornament content -> Augment
├─ B. Page header, navigation, or switching?
│  ├─ title / back / header actions -> TitleBar
│  ├─ in-page left navigation -> SideNavigation + SideNavigationItem
│  ├─ same-page segmented switch -> SegmentControl + SegmentItem
│  └─ page dots -> PageControl
├─ C. Input, selection, or value adjustment?
│  ├─ single-line text -> TextField
│  ├─ multi-line text -> TextArea
│  ├─ search input -> SearchField
│  ├─ numeric input -> NumberField
│  ├─ on/off -> Switch
│  ├─ checkbox / multi-select item -> Checkbox
│  ├─ card-style single option -> Option
│  ├─ continuous value -> Slider
│  ├─ date -> DatePicker
│  └─ time -> TimePicker
├─ D. Action rather than input?
│  ├─ standard primary / secondary action -> Button
│  ├─ icon action -> IconButton
│  ├─ button-shaped state toggle -> ToggleButton
│  └─ icon-shaped state toggle -> ToggleIconButton
├─ E. Lightweight tag / filter / condition?
│  ├─ clickable tag -> ButtonChip
│  ├─ removable condition -> RemovableChip
│  └─ selectable tag -> ToggleableChip
├─ F. List row or scroll indicator?
│  ├─ standard row / settings row -> ListItem
│  └─ explicit scroll position -> ScrollIndicator
├─ G. Loading / processing feedback?
│  ├─ circular loading -> CircularProgressIndicator
│  └─ linear progress -> LinearProgressIndicator
├─ H. Display-only content?
│  ├─ text -> Text
│  ├─ icon -> Icon
│  ├─ divider -> Divider
│  ├─ badge -> Badge
│  └─ lightweight link text -> Link
└─ I. Spatial ability rather than component?
   ├─ hover feedback -> spatialHoverEffect
   ├─ material background -> backgroundMaterial
   ├─ 3D container -> Box3D
   ├─ tooltip -> Tooltip
   └─ Z layering -> zOffset
```

Decision rules:

- First classify structure level, then interaction semantics, then visual style.
- If it is window-level, do not force it into a `Row` / `Column` page layout.
- Hover, material, 3D, and depth are abilities; they enhance components but do
  not replace the component's semantic choice.

### 9.2 Visual quick lookup

| If the design looks like … | Real question | First-choice component |
|---|---|---|
| top title + back / actions | page header semantics | `TitleBar` |
| left category navigation | in-page navigation | `SideNavigation` + `SideNavigationItem` |
| row of switching tabs | same-page segmented switch | `SegmentControl` + `SegmentItem` |
| capsule filter tag | tag filter / state toggle | `ButtonChip` / `ToggleableChip` |
| prominent clickable block | one-shot action | `Button` |
| icon-only click target | icon action | `IconButton` |
| on/off control | boolean state | `Switch` |
| multi-select item | checkbox semantics | `Checkbox` |
| card-style radio choice | single option card | `Option` |
| plain input | single-line text | `TextField` |
| search box | search semantics | `SearchField` |
| long description field | multi-line text | `TextArea` |
| value bar | continuous numeric input | `Slider` |
| date / time panel | picker | `DatePicker` / `TimePicker` |
| settings / message row | standard row semantics | `ListItem` |
| carousel dots | page indicator | `PageControl` |
| loading spinner / bar | status feedback | `CircularProgressIndicator` / `LinearProgressIndicator` |
| hover hint | auxiliary hint | `Tooltip` |
| glass / material surface | spatial material ability | `backgroundMaterial` |
| custom card hover highlight | hover ability | `spatialHoverEffect` |
| detached popup / side surface | window-level structure | `Subwindow` / `Sheet` / `SpatialPopup` / `AlertDialog` |

---

## 10. Known Fidelity Traps from Visual Designs

Use this list after parsing the XML / screenshot and before emitting code:

- **Bottom-most actions can be truncated** in long Figma pages. Inspect the full node tree for final actions such as destructive / exit buttons before concluding the screen is complete.
- **Horizontal fading edges** on carousels or chip rows are easy to miss. Preserve them with a dedicated `horizontalFadingEdge`-style modifier using alpha masks (`BlendMode.DstOut` / `DstIn`) when the fade is visually important.
- **Gradient-based truncation** may be semantic, not decoration. If list rows or text blocks fade out through alpha masks, keep the truncation effect instead of flattening it to a hard clip.
- **Mirrored chat / timeline states** must not be over-generalized from one sample. Inspect both sender / receiver or normal / highlighted examples and branch statefully (for example `if (isMe)`) when geometry differs.
- **Composite group avatars** need real reconstruction. Do not flatten stacked avatars into a single colored `Box`; rebuild them with nested image nodes, alignment, offsets, and a shared background plate.
