# Gradle / Build Setup (Spatial SDK external distribution)

The current external distribution of Spatial SDK ships as a local Maven
repository zip. Setup is fragile — one missing line and `com.pico.spatial:*`
fails to resolve. The base project comes from `pico-cli project create`; this
file documents WHY each piece is there so codegen can debug failures.

## Required versions

| Tool | Minimum |
|---|---|
| Android Gradle Plugin (AGP) | **8.6.0** |
| Gradle | matching AGP requirement (8.7+ recommended) |
| Kotlin | 1.9.20+ |
| compileSdk | 34 |
| minSdk | 29 (PICO OS baseline) |

## `settings.gradle.kts`

```kotlin
pluginManagement {
    repositories {
        google()
        mavenLocal()                              // ← Spatial SDK lives here
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenLocal()                              // ← Spatial SDK lives here
        mavenCentral()
    }
}

rootProject.name = "{{APP_NAME}}"
include(":app")
```

**Do not** add `maven { url = uri("https://...") }` for Spatial SDK in the
external setup — only `mavenLocal()` works currently. URL-based maven
repos for `com.pico.spatial:*` will return 404 / unresolved.

## `gradle/libs.versions.toml`

```toml
[versions]
agp = "8.6.0"
kotlin = "1.9.20"
spatialBom = "0.12.2"   # or the user's target Spatial SDK version

[libraries]
# BOM — pins all com.pico.spatial.* artifact versions
spatial-bom = { group = "com.pico.spatial", name = "bom", version.ref = "spatialBom" }

# Spatial ECS (3D, scene)
spatial-core = { group = "com.pico.spatial.core", name = "core" }

# SpatialUI (3 artifacts under the same UI module)
spatial-ui-platform   = { group = "com.pico.spatial.ui", name = "platform" }
spatial-ui-foundation = { group = "com.pico.spatial.ui", name = "foundation" }
spatial-ui-design     = { group = "com.pico.spatial.ui", name = "design" }

# Optional feature modules (include only if spatial_features needs them)
# `sense` covers world / plane / mesh anchors (Stage-only)
spatial-sense    = { group = "com.pico.spatial.sense",    name = "sense" }
# `tracking` covers hand / eye / body / controller / HMD / motion tracking
spatial-tracking = { group = "com.pico.spatial.tracking", name = "tracking" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android      = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
```

## `app/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "{{PACKAGE}}"
    compileSdk = 34

    defaultConfig {
        applicationId = "{{PACKAGE}}"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform(libs.spatial.bom))    // ← BOM platform line, mandatory

    implementation(libs.spatial.core)
    implementation(libs.spatial.ui.platform)
    implementation(libs.spatial.ui.foundation)
    implementation(libs.spatial.ui.design)

    // Conditional — add when spatial_features needs them:
    // implementation(libs.spatial.sense)
    // implementation(libs.spatial.tracking)
}
```

The BOM line **must** use `platform(...)`; otherwise versions of the
individual artifacts are unresolved.

## `gradle/wrapper/gradle-wrapper.properties`

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.7-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

## Diagnosing failures

| Symptom | Fix |
|---|---|
| `Unresolved reference: <Component>` | First check `references/spatial-ui-components.md`; the component may be invented or unsupported by the current SDK. |
| `Could not find com.pico.spatial:bom` | Missing `mavenLocal()` in `settings.gradle.kts`, OR SDK zip not extracted to `~/.m2/repository/com/...` |
| `Could not resolve com.pico.spatial.ui:platform` | BOM line missing or not using `platform(...)` |
| `AGP X.Y requires Gradle Z` | Bump `gradle-wrapper.properties` distributionUrl |
| `Unresolved reference: SpatialLaunchActivity` | Missing `spatial-ui-platform` dependency |
| `Unresolved reference: WorldTrackingManager` | Missing `spatial-sense` dependency (`com.pico.spatial.sense:sense`); world / plane / mesh anchors all live under `sensepack`, not `trackingpack` |
| `IllegalStateException: not in Full Space` | Stage-only API was called from WindowContainer; revisit `references/spatial-anchor.md` and the Phase-4 container decision. |
| `mainApp` not invoked / blank launch after install | Check `Application.launch(::mainApp)`, `SpatialLaunchActivity`, and manifest entry wiring in `references/manifest-and-entry.md`. |

## CLI-template baseline check before heavy D2C work

Use this only to decide whether the current project is close enough to a valid
Spatial SDK environment to continue. The baseline is the output of
`pico-cli project create`; compare structure, do not blindly copy files from the
temporary template.

Fast static anchors:

1. `gradle/libs.versions.toml` contains a BOM whose group is `com.pico.spatial`.
2. Required Maven repositories are declared in `settings.gradle.kts`.
3. The app module includes Spatial dependencies: `core`, `platform`,
   `foundation`, `design`.
4. ViewModel / StateFlow UI uses lifecycle Compose dependencies when needed.
5. Compose conflict exclusions are present when the project requires them.
6. Manifest / namespace / ABI / Gradle basics are structurally valid for a
   Spatial app.

Non-blocking differences: module names, package names, business features,
assets, screen structure, UI state modeling, and extra app-side libraries.

Red-light differences — stop generation and hand off to `spatial-sdk-update`
when the project is missing or structurally breaks:

- repository / Gradle foundation required by the CLI baseline
- Spatial BOM or core SpatialUI dependency foundations
- Compose conflict exclusions that are required by the current baseline
- Spatial Manifest / namespace / ABI assumptions
- environment-level buildability compared with the CLI baseline

Artifact crosswalk:

| Alias | Group / Artifact |
|-------|------------------|
| `bom` | `com.pico.spatial:bom` |
| `core` | `com.pico.spatial.core:core` |
| `platform` | `com.pico.spatial.ui:platform` |
| `foundation` | `com.pico.spatial.ui:foundation` |
| `design` | `com.pico.spatial.ui:design` |
| `sense` | `com.pico.spatial.sense:sense` |
| `tracking` | `com.pico.spatial.tracking:tracking` |

## How a new project is created

1. Run `pico-cli project create --dir <out> --name <name> --package <pkg>
   --template <planar|volumetric|stage>` — the CLI owns the complete base
   project: Gradle setup, package layout, `Main.kt` entry chain, and a
   fully-populated `AndroidManifest.xml` with the container meta-data in place.
2. For Stage only — all three `STAGE_*` variants share `--template stage`, so
   run `python3 -m scripts.inject_container --output <out> --container STAGE_*`
   to set the `pico.spatial.stage.*` immersion values on the meta-data the CLI
   already emitted. This is a no-op for the `ON_PLAIN` / `IN_VOLUME` cases.
3. Add `spatial-sense` / `spatial-tracking` dependency lines manually when
   `spatial_features` requires them (see the conditional block above).

If you hand-modify the project later, run `scripts/smoke_build.sh` to validate
before handing off.
