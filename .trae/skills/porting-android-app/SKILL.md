---
name: porting-android-app
description: Helps migrate a traditional Android 2D application to a Spatial platform such as PICO OS, covering architecture preparation, Spatial SDK integration, container migration, UI adaptation, Spatial capabilities, and cross-platform compatibility.
license: 'Apache-2.0'
---

# Porting Android Apps to a Spatial Platform

This skill helps refine an existing Android application for a Spatial runtime such as PICO OS. It follows the official porting direction of preparing architecture first, then integrating the Spatial SDK, then migrating screens and interactions incrementally instead of attempting a one-shot rewrite.

## When to use

Use this skill when:

- an existing Android phone or tablet app needs to run on a Spatial platform such as PICO OS
- the app must be adapted from a traditional 2D Activity-owned UI into Spatial containers
- the project needs Spatial SDK integration guidance
- the app should support both Spatial runtime and standard Android runtime safely
- the migration needs to be staged instead of rewritten from scratch

DO NOT use this skill when:

- the project is a brand-new Spatial app with no existing Android structure to port
- the task is only about adding a single isolated SDK API without broader app migration concerns
- the target platform and SDK version are unknown and cannot yet be confirmed

## Inputs to collect

Before making changes, collect the following information from the project or the user:

- current app architecture
  - whether it is single-Activity, multi-Activity, Fragment-based, or Compose Navigation-based
- target screens and flows
  - which screens should become independent Spatial containers
  - which screen is the launcher entry
- target runtime support
  - Spatial-only APK or shared APK for both standard Android and Spatial runtime
- Gradle setup
  - Groovy or Kotlin DSL
  - whether `libs.versions.toml` is used
- target Spatial SDK baseline
  - default `0.11.7` unless the project has another approved version
- current UI technology
  - Views, Fragments, Compose, or mixed UI
- Spatial feature scope
  - basic windowed migration only, or also MR, tracking, Spatial UI, audio, or ML capabilities
- known compatibility issues
  - Compose dependency conflicts, unsupported mobile assumptions, or platform restrictions

## Step-by-step procedure

### 1. Upgrade Android-related configurations first

Before integrating the Spatial SDK, verify that the Android build toolchain is compatible with the target PICO OS baseline.

#### Upgrade Gradle and related plugins

When developing apps for PICO OS 6, use Android Studio 2025.1.x.

If the project is still using an older Gradle version, upgrade Gradle first, and then align the Android Gradle Plugin accordingly.

Pay attention to the following:

- upgrading Gradle usually also requires upgrading AGP
- different Android Studio versions require different minimum AGP versions
- Gradle and AGP versions must remain mutually compatible
- version mapping should be verified against the official Android documentation

#### Upgrade Kotlin with AGP compatibility in mind

After upgrading Gradle and AGP, verify the Kotlin version as well.

Keep Kotlin `2.0.0` unless the project has a confirmed compatibility problem, and verify that the selected Kotlin version is supported by the chosen AGP version through the official Android release notes.

#### Upgrade project configurations if AGP changes significantly

If the previous AGP version was relatively old, especially below `8.0`, expect follow-up project configuration changes.

Common upgrade areas include:

- `BuildConfig` generation changes
- `namespace` configuration requirements
- cascading `R` file behavior changes

Use Android Studio's built-in AGP Upgrade Assistant when possible to help apply required project adjustments.

### 2. Evaluate architecture before porting

Inspect how the app currently organizes navigation and screen ownership.

If the app is a traditional single-Activity app with in-app navigation such as Fragment navigation or Jetpack Compose Navigation, DO NOT directly map that structure into multiple Spatial containers.

Required preparation:

1. identify which screens need independent Spatial ownership
2. refactor tightly coupled screens so they can be migrated independently
3. add explicit Back, Close, or Exit controls in the UI
4. remove assumptions that depend on phone-only system back behavior

### 3. Configure repositories

Use the repository configuration from the official PICO Spatial SDK setup guide or from a fresh `pico-cli project create` template. Do not add private or organization-internal Maven repositories to a public project unless the user explicitly provides one for their own environment.

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Add the public PICO Spatial SDK repository documented for your SDK version.
    }
}
```

### 4. Register SDK versions

If the project uses a version catalog, update `gradle/libs.versions.toml` with the Spatial SDK version entry.

Keep the version variable-based, with `0.11.7` as the default value.

Example:

```toml
[versions]
spatialBom = "0.11.7"
```

If the project does not use a version catalog, define an equivalent version variable in the module build file and reference it from the BOM dependency.

### 5. Add dependencies in the app module

If the project uses Version Catalogs, declare the SDK artifacts in `gradle/libs.versions.toml`.

```toml
spatial-core = { group = "com.pico.spatial.core", name = "core" }
spatial-ui-platform = { group = "com.pico.spatial.ui", name = "platform" }
spatial-ui-foundation = { group = "com.pico.spatial.ui", name = "foundation" }
spatial-ui-design = { group = "com.pico.spatial.ui", name = "design" }
spatial-ui-sense = { group = "com.pico.spatial.sense", name = "sense" }
spatial-ui-tracking = { group = "com.pico.spatial.tracking", name = "tracking" }
```

Update `app/build.gradle` or `app/build.gradle.kts`.

If used with `libs.versions.toml`:

```kotlin
dependencies {
    implementation(platform(libs.spatial.bom))
    implementation(libs.spatial.core)
    implementation(libs.spatial.ui.platform)
    implementation(libs.spatial.ui.foundation)
    implementation(libs.spatial.ui.design)
    implementation(libs.spatial.ui.sense)
    implementation(libs.spatial.ui.tracking)
}
```

```kotlin
dependencies {
    implementation(platform("com.pico.spatial:bom:$spatialBom"))

    implementation("com.pico.spatial.core:core")

    implementation("com.pico.spatial.ui:platform")
    implementation("com.pico.spatial.ui:foundation")
    implementation("com.pico.spatial.ui:design")

    implementation("com.pico.spatial.sense:sense")
    implementation("com.pico.spatial.tracking:tracking")
}
```

Expected dependency set:

- PICO Spatial SDK BOM
- `com.pico.spatial.core:core`
- `com.pico.spatial.ui:platform`
- `com.pico.spatial.ui:foundation`
- `com.pico.spatial.ui:design`
- `com.pico.spatial.sense:sense`
- `com.pico.spatial.tracking:tracking`

### 6. Add dependency exclusions when required

After the project adds the Spatial SDK dependencies, add the exclusions in the Gradle configuration section.

```groovy
configurations.all {
    resolutionStrategy {
        exclude group: 'androidx.compose.ui', module: 'ui'
        exclude group: 'androidx.compose.ui', module: 'ui-graphics'
        exclude group: 'androidx.compose.ui', module: 'ui-text'
        exclude group: 'androidx.compose.foundation', module: 'foundation'
    }
}
```

### 7. Migrate app entry into a Spatial container

Map the launcher experience to the default Spatial entry container and ensure the entry is declared correctly in `AndroidManifest.xml`.

Additional windows or stage-like experiences should be declared using the Spatial SDK entry definitions used by the project.

If the launcher Activity is migrated to a Spatial container such as `DefaultWindowContainer` or `DefaultStage`, updating `AndroidManifest.xml` is mandatory as part of the migration.

Required manifest work:

- make sure the Spatial launcher Activity remains the `MAIN` and `LAUNCHER` entry
- make sure the application entry is configured correctly
- add the Spatial container metadata required by the launcher container configuration
- use different metadata keys for `DefaultWindowContainer` and `DefaultStage`

Example manifest structure for a default window container:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <application
        android:name=".MyApplication">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:label="@string/app_name"
            android:theme="@style/AppTheme">

            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>

            <meta-data
                android:name="pico.spatial.windowcontainer.id"
                android:value="MyAppHome" />
            <meta-data
                android:name="pico.spatial.windowcontainer.style"
                android:value="1" />
            <meta-data
                android:name="pico.spatial.windowcontainer.defaultsize"
                android:value="1080x720" />
            <meta-data
                android:name="pico.spatial.windowcontainer.defaultsize.unit"
                android:value="dp" />
            <meta-data
                android:name="pico.spatial.windowcontainer.materialbackground"
                android:value="0" />
        </activity>

    </application>
</manifest>
```

Example manifest structure for a default stage:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <application
        android:name=".platform.SpatialApplication"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.SpatialApp"
        tools:targetApi="31">

        <activity
            android:name=".platform.LaunchActivity"
            android:exported="true"
            android:theme="@style/Theme.SpatialApp">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>

            <meta-data
                android:name="pico.spatial.stage.id"
                android:value="your_stage_name" />
            <meta-data
                android:name="pico.spatial.stage.style"
                android:value="1" />
        </activity>
    </application>
</manifest>
```

For `DefaultWindowContainer`, use `pico.spatial.windowcontainer.*` properties.

For `DefaultStage`, use `pico.spatial.stage.*` properties instead. Do not reuse the window container metadata keys for a stage launcher.

Use the matching metadata keys and values required by the selected launcher container type.

This manifest patch is required during launcher migration, not an optional cleanup after the migration is complete.

### 8. Register containers from the Application entry

After declaring Spatial containers, register them during app startup from the main `Application` class.

This step is required so the containers declared in `mainApp()` are registered in the system before they are used by later code.

If the project does not already have a main `Application` class, create one first, then call `launch(::mainApp)` in `onCreate()`.

Example:

```kotlin
class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        launch(::mainApp)
    }
}
```

Make sure the `Application` class referenced here is also the one configured in `AndroidManifest.xml`.

### 9. Move UI ownership into Spatial content

Traditional Android apps often call `setContent {}` directly inside an Activity. In a Spatial app, the Activity usually delegates to the Spatial runtime, while the actual content is defined in the Spatial SDK container layer.

Example:

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Home()
        }
    }
}
```

Spatial-style structure:

```kotlin
class MainActivity : ComponentActivity() {
    private val spatialDelegate: SpatialActivityDelegate by SpatialActivityDelegate()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        spatialDelegate.setSpatialContent()
    }
}

fun mainApp(scope: SpatialAppScope) = with(scope) {
    DefaultWindowContainer {
        Home()
    }
}
```

Use the exact APIs and entry patterns required by the target SDK version.

### 10. Port screens incrementally

Start with the launcher screen, validate entry and rendering behavior, then migrate additional screens one by one.

For each migrated screen:

- confirm it has clear ownership
- confirm explicit exit or back behavior exists
- confirm it works without relying on mobile-only navigation assumptions

### 11. Adapt UI and interaction for Spatial use

After the app renders in a Spatial container, validate that it also behaves correctly.

Review:

- hover behavior
- focus behavior
- click target size and spacing
- visual comfort and layout fit in Spatial presentation
- whether standard Material components need Spatial-specific treatment

For Material components that need explicit Spatial hover behavior, use the SDK support available in the target release, for example:

```kotlin
Modifier.SpatialHover()
```

If hover visuals DO NOT match the component shape, refine clipping or move to a Spatial-native implementation.

### 12. Add advanced Spatial capabilities only after the core flow is stable

Potential capability areas include:

- Spatial UI components
- Spatial interaction patterns
- mixed reality features
- Spatial audio or video
- tracking such as hand, head, or body input
- machine learning capabilities exposed by the platform

Some features require Full Space mode, especially MR and tracking-related capabilities. Confirm platform and SDK requirements before enabling them.

### 13. Guard Spatial-only APIs for shared APK deployment

If the APK must run on both standard Android devices and a Spatial platform, guard Spatial-only logic at runtime.

```kotlin
if (SpatialBuild.isSpatialPlatform()) {
    // Call Spatial-only APIs
} else {
    // Keep standard Android behavior
}
```

Only call Spatial APIs after confirming the runtime is a Spatial platform.

## Output format

When using this skill, produce output in this structure:

### 1. Migration assessment

- current architecture summary
- blockers to direct Spatial migration
- screens that should become independent containers

### 2. Required project changes

- repository configuration updates
- version variable definition
- dependency block changes
- Compose exclusions if needed

### 3. Entry and container migration plan

- launcher entry mapping
- additional container definitions
- Activity-to-container migration notes

### 4. UI and interaction changes

- navigation and exit changes
- hover and focus adjustments
- components that should move to Spatial-native patterns

### 5. Compatibility and rollout notes

- runtime guards for shared APK support
- advanced Spatial capabilities to defer until later
- testing or validation priorities for the next step

## Constraints / pitfalls

- DO NOT port a single-Activity navigation model directly into multiple Spatial containers
- DO NOT assume mobile-style system Back behavior exists on the Spatial runtime
- DO NOT keep major UI ownership tightly coupled to Activity lifecycle code if the UI is being split across containers
- DO NOT hardcode the SDK version in multiple dependency locations; keep it variable-based with `0.11.7` as the default
- DO NOT add Compose exclusions unless dependency conflicts actually require them
- DO NOT call Spatial-only APIs on standard Android devices
- DO NOT treat the migration as only a rendering task; interaction and navigation changes are part of the port
- DO NOT add MR, tracking, or ML features before the basic launcher and navigation flow are stable

## Summary

A good Spatial port is a staged transformation of architecture, entry, interaction, and runtime behavior. Start by making screens independent, integrate the Spatial SDK, migrate entry and containers incrementally, then add Spatial capabilities only where they improve the experience.
