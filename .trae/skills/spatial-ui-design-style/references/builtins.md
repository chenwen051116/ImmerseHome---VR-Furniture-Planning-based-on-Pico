# Built-In Components Lookup

> Package convention:
> - `com.pico.spatial.ui.design.*` — main components (incl. `TitleBar`)
> - `com.pico.spatial.ui.design.windows.*` — `Subwindow` / `Toolbar` / `TabBar` / `Sheet` / `AlertDialog` / `SpatialPopup` / `Menu` / `SnackbarHost` / `CoachmarkBox` / ...
> - `com.pico.spatial.ui.design.menu.*` — `Menu`, `SubMenu`, `MenuItem`
> - `com.pico.spatial.ui.augment.Augment` — window-attached ornament (NOT a `design.windows.*` component, NOT a window root)

## Decision Rule

1. If a built-in component fits, **do not reimplement it**. The design system
   has already tuned spacing, color, animation, audio, haptics, accessibility.
2. Only customize when no built-in fits — see `references/custom-component.md`.
3. Never fork SpatialUI library code.

## Text / Icons

- `Text`, `ProvideTextStyle`
- `Icon` (overloads: `ImageVector` / `Painter` / `Bitmap`)

## Buttons / Links

- `Button` / `IconButton` / `ToggleButton` / `ToggleIconButton` / `Link`
- `ButtonChip` / `Chip` / `RemovableChip` / `ToggleableChip`

## Input / Selection

- `Checkbox` / `TriStateCheckbox`, `Switch`
- `Slider` / `SymbolSlider` / `SegmentSlider`, `Stepper`, `NumberField`
- `TextField` / `TextArea` / `SearchField`
- `DatePicker` + `rememberDatePickerState`,
  `DateRangePicker` + `rememberDateRangePickerState`,
  `TimePicker`

## Feedback / Overlays

- `LinearProgressIndicator` / `CircularProgressIndicator`
- `DotBadge` / `NumberBadge` / `Badge`
- `AlertDialog`, `DatePickerDialog`, `Sheet`, `SnackbarHost`
- `CoachmarkBox` + `SimpleCoachmark` / `RichCoachmark` / `ImageCoachmark`
- `SpatialPopup` + `rememberSpatialPopupPositionProvider`

## Containers / Navigation

- `Subwindow`, `Toolbar`, `TabBar` (`com.pico.spatial.ui.design.windows.*`)
- `TitleBar` (`com.pico.spatial.ui.design.TitleBar` — **NOT** in `windows.*`)
- `HorizontalDivider` / `VerticalDivider`
- `SideNavigation` / `SideNavigationSection` / `SideNavigationItem`
- `PageControl`, `SegmentControl` + `SegmentItem`

## Window-Attached Ornament (separate package)

- `Augment(...)` — ornament window attached to a parent window. Lives in
  `com.pico.spatial.ui.augment.Augment`, NOT in `design.windows.*`. Glass
  toggle is the DSL parameter `enableMaterialBackground` (default `true`).
  See `references/window-background.md` and `references/spatial-capabilities.md`.

## Lists / Menus

- `ListItem`, `Option`
- `Menu` / `SubMenu` + `rememberMenuPositionProvider` / `rememberSubMenuPositionProvider`,
  `MenuItem`
- `BoxScope.ScrollIndicator` + `rememberScrollIndicatorState`
  (supports `LazyList` / `LazyGrid` / `LazyStaggeredGrid`)

## Button Semantic Roles

`Button` does NOT expose a public `role` enum. Express semantic role
through `ButtonDefaults.buttonColors(containerColor = ..., contentColor = ...)`
and the appropriate component variant:

- Primary action → `Button` with default `ButtonDefaults.buttonColors()`
- Secondary action → `Button` with `containerColor = PicoTheme.colorScheme.fillSecondary` / `fillTertiary`
- Borderless / link-like action → `Link` or `IconButton`
- Destructive action → `Button` with `containerColor = PicoTheme.colorScheme.error`,
  AND gate behind a confirm step (`AlertDialog` / `Sheet`).

Do not invent role names like `Primary` / `Pass` / `Borderless` — they do
not exist in the public API.

## Common Misclassification Traps

- A top title area may look like a `Row`, but semantically it is usually
  `TitleBar`.
- A left navigation column may look like a `Column`, but semantically it is
  usually `SideNavigation` + `SideNavigationItem`.
- `TabBar`, `Toolbar`, `Subwindow`, `Sheet`, and `AlertDialog` are not ordinary
  page cards or columns. They are window-level or overlay structures.
- `spatialHoverEffect`, `backgroundMaterial`, and `zOffset` are capabilities,
  not substitutes for button / card / list components.
- A selected / unselected control is not necessarily a `Button`: tag toggles
  usually map to `ToggleableChip`; icon toggles usually map to
  `ToggleIconButton`.

## Signature Details That Commonly Break Builds

- `Option` is a card-style option component, not a traditional `RadioButton`.
  Mutually exclusive option groups are usually app-managed state.
- `LinearProgressIndicator.progress` is a lambda (`() -> Float`), not a raw
  `Float` argument.
- `IconButton` has two size concepts: `modifier = Modifier.size(...)` for the
  occupied outer box and `size = IconButtonDefaults.iconButtonSize(...)` for the
  component shell / internal render target. Prefer component color APIs over
  external `Modifier.background(...)` on the `IconButton` itself.
- `TitleBar` often uses slot-style APIs such as `title = { Text(...) }`; do not
  assume a raw `String` title or old mobile-style `navigationIcon` / `actions`
  parameters exist on every SDK version.
- `TabBar` entries may be DSL-style `item(...)` calls; verify current SDK
  parameters before assuming a dedicated `TabItem` or `icon = painterResource(...)`.
