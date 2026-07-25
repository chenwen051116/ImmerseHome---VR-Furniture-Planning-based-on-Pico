---
name: spatial-sdk-update
description: Upgrades and migrates older PICO Spatial SDK projects to the latest PICO Spatial SDK (PICO OS 6 environment). Must be triggered whenever the user asks to "upgrade PICO SDK", "adapt to the newest Spatial SDK version", "handle Deprecated or missing spatial APIs", "fix legacy spatial container manager errors", "update PICO Gradle/AGP dependencies", or "migrate legacy Android MR/spatial apps". Do NOT trigger for general UI creation or simple bug fixes.
license: 'Apache-2.0'
---

# spatial-sdk-update

Use this skill when you need to help the user upgrade an existing PICO Spatial SDK project (especially Android/Kotlin projects) to a newer version of the PICO Spatial SDK. This involves more than just simple API name replacements; it includes dependency resolution and project configuration upgrades.

## Macro Upgrade Flow

Before initiating any code modifications, follow this closed-loop, iterative upgrade strategy:

```mermaid
graph TD
    A[Phase 1: Build & Dependency Fixes] --> B{Gradle Sync Success?}
    B -- No --> C[Troubleshoot AGP/Compose/NDK Conflicts]
    C --> B
    B -- Yes --> D[Phase 2: Core API Migration & Adaptation]
    D --> E{Compile Success?}
    E -- No --> F[Analyze Errors/Deprecations and Replace]
    F --> E
    E -- Yes --> G[Phase 3: Runtime Compliance (Store & Runtime Safety)]
    G --> H{No Runtime Crash?}
    H -- No --> I[Investigate Crash & Apply Fixes]
    I --> H
    H -- Yes --> J[Upgrade Migration Complete]
```

## Phase 1: Build & Config

Goal: Quickly fill core gaps, resolve the most urgent build and compatibility issues, and ensure the project can compile under the new PICO ecosystem.

- **[Must] Upgrade Gradle, AGP, and Kotlin (keep versions consistent)**: PICO OS 6 and newer Spatial SDK versions generally require a modern Android toolchain. If Gradle sync fails, upgrade the project to a compatible combo (e.g., **AGP 8.x** with the matching Gradle wrapper), and set **Kotlin** to a version compatible with your AGP + Android Studio (do not guess a major jump). Ensure the **Compose Compiler** configuration matches the Kotlin version (use the official Compose–Kotlin compatibility table).
  - **SDK levels**: Keep `compileSdk = 35` and typically `targetSdk = 35` for PICO OS 6 projects. Note: upgrading `targetSdk` to 35 may introduce standard Android behavioral changes (e.g., edge-to-edge enforcement or stricter foreground service permissions); address these if the user encounters related issues.
  - **minSdk**: Do **not** blindly force `minSdk = 35`. Keep it aligned with the project baseline / template / business constraints, and only raise it if the user explicitly targets that requirement.
- **[Must] Remove Conflicting UI Dependencies (Compose)**: If the project uses **SpatialUI** components, you must exclude native AndroidX Jetpack Compose conflicting dependencies (e.g., `androidx.compose.ui:ui`, `ui-graphics`, `ui-text`, `androidx.compose.foundation:foundation`) using `exclude` or `configurations.all { resolutionStrategy { ... } }` in the module's `build.gradle.kts`.
- **[Must] Upgrade the Spatial SDK version at the right source of truth (BOM / Version Catalog)**:
  - If the project uses **Version Catalog** (has `gradle/libs.versions.toml`), update the single version key (commonly `spatialBom`) and keep dependencies on `implementation(platform(libs.spatial.bom))`.
  - If the project does not use Version Catalog, update the BOM line directly: `implementation(platform("com.pico.spatial:bom:<new_version>"))` and keep module dependencies (`com.pico.spatial.core:core`, `com.pico.spatial.ui:*`, etc.) unchanged unless build errors require changes.
- **[Recommended] Common AGP 8+ upgrade pitfalls to check** (fix only if they appear during sync/build):
  - **BuildConfig**: AGP 8+ may stop generating `BuildConfig` by default; enable it if the project relies on `BuildConfig.*` or `buildConfigField`.
  - **Non-transitive R**: After upgrading AGP, resource IDs may fail if the project relied on legacy cascading `R` behavior; update resource references per-module.
  - **Namespace / Manifest cleanup**: Ensure `namespace` is set in `build.gradle.kts`, and remove legacy Manifest-only identifiers (e.g., module-level `package=` in `AndroidManifest.xml`) when they conflict with AGP rules.
- **[Must] Handle NDK Configurations**: The target system (PICO OS 6) **only supports the `arm64-v8a`** architecture. Ensure NDK ABI filtering is explicitly set in `build.gradle.kts`: `ndk { abiFilters.add("arm64-v8a") }`.
- **[Must] Strip GMS (Google Mobile Services) Dependencies**: PICO OS 6 operates without GMS. If GMS/Firebase dependencies are found during the upgrade/migration, they must be removed or commented out. Actively advise the user to implement alternatives.
- **[Must] AndroidManifest cleanup for PICO OS 6 / AGP 8+**: Ensure `namespace` is defined in `build.gradle(.kts)` and remove legacy Manifest-only identifiers that can break modern builds.
  - Remove module-level `package="..."` from `AndroidManifest.xml` when present (namespace is the source of truth).
  - Remove any legacy `android:launchMode="..."` attribute injected into `<activity>` declarations (especially the Spatial container/launch activity) unless the user explicitly requires a specific launch mode and it is known to be supported.

## Phase 2: Runtime & Logic (Core API Migration)

Goal: Eliminate all `unresolved reference` and `Deprecated` errors encountered at compile time, enabling business logic to run on the new architecture.

- **[Must] Replace Deprecated and Relocated Packages & Classes**:
  - **UI and Spatial Container Migration**: In spatial apps, developers are used to older interfaces for container control. In the new version, prioritize migrating to declarative Compose components and unified engine entry points like `SpatialApp`.
  - **Gesture and Control Refactoring**: Methods within click logic (e.g., `TapGestureDetector`) have `@Deprecated` markers. You must search for `pointerInput` and corresponding replacement methods to handle user taps or drags.
- **[Must] Reverse Engineer Replacements from Errors**:
  - If "class not found" errors occur, analyze the PICO SDK error messages (such as `ReplaceWith` deprecation warnings) to infer the new API.
  - Proactively search for similar interfaces in the project, or use global `grep` in the SDK's `source.jar` or `dependencies` cache to locate alternative names.
- **[Must] Remove Internally Restricted APIs (@RestrictTo)**:
  - Interfaces marked with `@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)` are **strictly forbidden from being used by the application side**. If legacy code accesses interfaces that are now restricted, those calls must be decisively removed or replaced with alternative official public APIs.

## Phase 3: Compliance & Capability (Platform Features)

Goal: Ensure the application runs smoothly and meets store listing standards.

- **[Must] SDK vs ROM major.minor compatibility (no runtime version checks required)**:
  - Align the first two segments of the SDK version (`major.minor`) with the target ROM version (`major.minor`).
  - If `SDK.major.minor` is **greater than** `ROM.major.minor`, the app is **not installable/runnable** on that ROM and will be **not visible** in the app store distribution channel for that ROM.
  - Only apps with `SDK.major.minor` **less than or equal to** `ROM.major.minor` can be installed and run on that ROM.
  - Before upgrading the SDK, confirm the target ROM coverage; if lower ROMs must be supported, do not upgrade the SDK beyond that ROM's `major.minor`.

- **[Must] Experimental API Compliance Warnings**:
  - Only APIs marked with annotations like `@ExperimentalSpatialApi` belong to the experimental category.
  - Experimental APIs will **prevent the app from being published on the PICO Store**.
  - A warning comment must be added before using or substituting such an API. The warning must clearly state:
    - This usage may block publishing on the PICO Store.
    - To enable experimental APIs, you must add the following `<meta-data>` under the `<application>` tag in `AndroidManifest.xml`:
      ```xml
      <meta-data
          android:name="pico.spatial.use_experimental_api"
          android:value="1" />
      ```
      Without explicit permission from the user, calls to these APIs should be commented out by default to prevent accidental compliance violations.

## Communication and Reporting

- After extensive build configuration changes (Phase 1) or package replacements (Phase 2), it is best to independently verify success by running `./gradlew build` before proceeding.
- Once the upgrade is complete, provide a summary to the user detailing:
  - [Phase 1] Which build defects were fixed?
  - [Phase 2] Which core deprecated APIs were replaced?
  - [Phase 3] Where were runtime guards/fallbacks added (if any), and were any publishing-blocking experimental features utilized?
