# Architecture & Testing Conventions

> Hard constraint for any Compose / Kotlin code emitted by `anything-to-spatial-app`.
> Enforced by `scripts/check_architecture.py` and `scripts/run_unit_tests.sh`,
> chained into `scripts/validate_workflow_and_build.sh`.

## Why this exists

Without an explicit architecture contract, generated apps tend to collapse into
a single fat `Main.kt`: data, state, UI, theme, navigation all in one file.
This document is the floor (not the ceiling) for what every generated module
must contain.

## 1. Package layout (REQUIRED)

For every generated module `<target>`, use the Gradle `namespace` as the package
root (examples below use `com.picoxr.<module>`). The corresponding
`src/main/.../<namespace>/` tree is split into always-required layers and
data/business-rule layers that become required as soon as the screen reads mock
or real data, filters, sorts, selects, or transforms it:

```
com.picoxr.<module>/
├── platform/                          # REQUIRED: Application + LaunchActivity
├── data/                              # REQUIRED when the screen reads data
│   └── repository/                    # Repository interfaces + Fake/Remote impls
├── domain/
│   ├── model/                         # REQUIRED: domain models (NOT Compose-aware)
│   └── usecase/                       # REQUIRED for non-trivial business rules,
│                                       # filtering, sorting, selection, transforms
└── ui/
    ├── <feature>/                     # REQUIRED: 1 directory per Screen
    │   ├── <Feature>UiState.kt        # data class + sealed interface Event
    │   ├── <Feature>ViewModel.kt      # androidx.lifecycle.ViewModel
    │   ├── <Feature>Screen.kt         # stateful + stateless 双层
    │   └── components/                # @Composable building blocks; non-empty for non-trivial screens
    ├── theme/                         # Brushes / shapes / palette helpers
    └── navigation/                    # Top-level enums or NavGraph
```

`Main.kt` (top-level entry) MUST stay thin — the rule is **≤ 50 lines**:
just `mainApp(scope) = with(scope) { DefaultWindowContainer/DefaultStage { PicoTheme { <FeatureScreen>() } } }`.

`<Feature>Screen.kt` should stay as the state-wiring shell. Region-level and
reusable Compose blocks (sidebars, tabs, grids, cards, rails, search rows,
chip groups) MUST live under `ui/<feature>/components/`. Creating an empty
`components/` directory is not compliant; it indicates the generated code did
not actually isolate components.

> **Exception** — single-file demo modules (e.g. pure 3D `Stage` showcases)
> may collapse `domain` + `ui` into one file IF AND ONLY IF
> `<target>/.scratch/architecture_waiver.json` exists with an explicit
> `"reason"` field. The script will still flag it as a `warning`.

## 2. Layer responsibilities

| Layer | May depend on | MUST NOT |
|---|---|---|
| `data/` | nothing app-specific (only kotlinx, libs) | reference Compose, ViewModel, Android lifecycle |
| `domain/model/` | nothing | reference Compose, Repository, ViewModel |
| `domain/usecase/` | `data/repository/`, `domain/model/` | reference Compose, ViewModel |
| `ui/<feature>/` | `domain/`, `data/repository/` (for default Factory only) | hold mock data inline; expose mutable state to Composables |
| `ui/components/`, `ui/theme/`, `ui/navigation/` | `domain/model/` | depend on `data/` or any ViewModel |
| `platform/` | top-level `mainApp` only | hold business logic |

Cycle detection is performed by the checker.

## 3. State & event protocol (REQUIRED for every Screen)

Every feature Screen MUST follow the unidirectional pattern:

```kotlin
data class FeatureUiState(/* immutable fields */)

sealed interface FeatureEvent {
    data object Refresh : FeatureEvent
    data class Select(val id: String) : FeatureEvent
    // …
}

class FeatureViewModel(/* injected use cases */) : ViewModel() {
    private val _state = MutableStateFlow(FeatureUiState())
    val state: StateFlow<FeatureUiState> = _state.asStateFlow()
    fun onEvent(event: FeatureEvent) { /* reduce → _state.update {} */ }
}

@Composable
fun FeatureScreen(/* defaults via remember { … } */) {
    val vm: FeatureViewModel = viewModel(factory = …)
    val state by vm.state.collectAsStateWithLifecycle()
    FeatureContent(state = state, onEvent = vm::onEvent)
}

@Composable
internal fun FeatureContent(state: …, onEvent: (FeatureEvent) -> Unit, …) { … }
```

Forbidden in Composables emitted by this skill:

- `var x by remember { mutableStateOf(...) }` driving any state that reaches
  a Repository / UseCase. Local UI-only state (focus, expanded) is fine.
- Direct calls into a `Repository` from a Composable.
- `runBlocking` / `GlobalScope` anywhere.

## 4. Repository abstraction (REQUIRED if there is any data)

If the Screen reads anything (mock or real), there MUST be a `XxxRepository`
**interface** in `data/repository/` plus at least one implementation
(`FakeXxxRepository` is acceptable for mock).

Forbidden:

- top-level `private val MOCK_LIST = listOf(…)` next to UI code,
- `object MockData` referenced from Composables.

## 5. Unit tests (REQUIRED)

Tests live in `<target>/src/test/java/com/picoxr/<module>/` mirroring the
production package layout. Every generated module MUST satisfy:

| Layer | Minimum test coverage | Notes |
|---|---|---|
| Each required `*UseCase` | ≥ 1 test class with ≥ 2 cases | Required when the screen has non-trivial business rules, filtering, sorting, selection, or transforms; test the rule, not the I/O |
| Each `*ViewModel` | ≥ 1 test class with ≥ 4 cases | Cover init load + 1 happy event path + 1 boundary path + 1 search/filter or empty path |
| Each non-trivial `*Repository` Fake | optional | Smoke test only if used as test util |

Test infrastructure:

- `kotlinx-coroutines-test` must be present in `testImplementation` when this
  skill emits the module; add it if the project created by `pico-cli project
  create` does not already include it. Do not pin a version here — use whatever
  version the CLI-generated project resolves (catalog / BOM / existing pin).
- `Dispatchers.setMain(StandardTestDispatcher())` is the default Main
  dispatcher harness; use `runTest { … advanceUntilIdle() }` to drive
  `viewModelScope`.
- Prefer hand-written Fakes over `mockk` / Mockito — no reflection, no
  classpath issues, predictable behaviour.

Forbidden:

- `assertTrue(true)` / `// TODO test` placeholders,
- tests that import `androidx.compose.*` (UI tests belong in
  `androidTest` instrumentation).

## 6. Acceptance gate

The canonical Phase-7 order lives in `references/workflow-contract.md`.
Architecture and unit-test checks are mandatory gates inside
`scripts/validate_workflow_and_build.sh <target>`:

- **`check_architecture.py`** — package layout + layer dependencies + ViewModel/UseCase/Repository presence
- **`run_unit_tests.sh`** — `:<module>:testDebugUnitTest`, requires `failures=0 AND errors=0`

Any failure = run incomplete. `--skip-architecture` / `--skip-unit-tests` exist
for emergencies but the SKILL still demands a clean run before sign-off.

## 7. Cheat sheet (when in doubt)

- "Where do I put a constant list of mock items?" → `data/repository/Fake*.kt`
- "Where do I filter / search?" → `domain/usecase/*UseCase.kt` (pure function preferred)
- "Where do I keep `enum class CardLayout` / colour roles?" → `domain/model/`
- "Where do I keep the `Brush.verticalGradient(...)` derived from a domain value?" → `ui/theme/`
- "Where do I hold `selectedTab` / `searchQuery`?" → `<Feature>UiState` inside the ViewModel, never `remember`
