# Manifest & Entry Registration

Every PICO Spatial App needs three pieces wired together. Get any one wrong
and the app either will not launch correctly, or PICO OS will not recognize it
as a Spatial App.

Use the repo's current SpatialUI entry chain unless an existing module already
has an equivalent working variant.

## 1) Application class

`Application` initializes the SpatialUI runtime by calling `launch(::mainApp)`.

```kotlin
package {{PACKAGE}}

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch

class {{APP_NAME}}App : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
```

In existing module mode, preserve the existing `SpatialApplication` / app class
name if it already works.

## 2) Entry Activity

The launcher Activity for the Spatial app should extend the stub launch
activity:

```kotlin
package {{PACKAGE}}

import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity

class MainActivity : SpatialLaunchActivity()
```

You can keep additional non-spatial Activities, but the `MAIN/LAUNCHER` entry
for the spatial flow should extend `SpatialLaunchActivity`.

## 3) `mainApp(scope: SpatialAppScope)`

The entry DSL should use `SpatialAppScope` and one root mode only.

### Variant A — default root is `DefaultWindowContainer`

```kotlin
package {{PACKAGE}}

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.windowConstraints

fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            PicoTheme {
                MainPanel(
                    modifier = Modifier.windowConstraints(
                        minWidth = 1280.dp,
                        minHeight = 720.dp,
                    )
                )
            }
        }
    }
```

### Variant B — default root is `DefaultStage`

```kotlin
package {{PACKAGE}}

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope

fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage {
            PicoTheme {
                ImmersiveScene(modifier = Modifier.fillMaxSize())
            }
        }
    }
```

## 4) AndroidManifest.xml

The manifest must do two jobs:

1. mark the app as spatial via `com.pico.spatial.SPATIAL_APP`
2. declare the default root container metadata on the launcher Activity

### WindowContainer example

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:name=".{{APP_NAME}}App"
        android:label="@string/app_name"
        android:theme="@style/Theme.SpatialApp">

        <meta-data
            android:name="com.pico.spatial.SPATIAL_APP"
            android:value="true" />

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.SpatialApp">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>

            <meta-data
                android:name="pico.spatial.windowcontainer.id"
                android:value="MainWindowContainer" />
            <meta-data
                android:name="pico.spatial.windowcontainer.style"
                android:value="1" />
            <meta-data
                android:name="pico.spatial.windowcontainer.defaultsize"
                android:value="1280x720" />
            <meta-data
                android:name="pico.spatial.windowcontainer.defaultsize.unit"
                android:value="dp" />
            <meta-data
                android:name="pico.spatial.windowcontainer.resizetype"
                android:value="1" />
            <meta-data
                android:name="pico.spatial.windowcontainer.resizerestriction"
                android:value="0" />
            <meta-data
                android:name="pico.spatial.windowcontainer.worldscaletype"
                android:value="1" />
            <meta-data
                android:name="pico.spatial.windowcontainer.captionbar"
                android:value="0" />
            <meta-data
                android:name="pico.spatial.windowcontainer.materialbackground"
                android:value="1" />
        </activity>

    </application>
</manifest>
```

`pico.spatial.windowcontainer.style` values:

| Container | `style` value |
|---|---|
| `ON_PLAIN` | `"1"` (default in the example above) |
| `IN_VOLUME` | `"2"` |

### Stage example

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:name=".{{APP_NAME}}App"
        android:label="@string/app_name"
        android:theme="@style/Theme.SpatialApp">

        <meta-data
            android:name="com.pico.spatial.SPATIAL_APP"
            android:value="true" />

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.SpatialApp">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>

            <meta-data
                android:name="pico.spatial.stage.id"
                android:value="MainStage" />
            <meta-data
                android:name="pico.spatial.stage.style"
                android:value="2" />
            <meta-data
                android:name="pico.spatial.stage.immersion"
                android:value="50" />
            <meta-data
                android:name="pico.spatial.stage.immersion_min"
                android:value="0" />
            <meta-data
                android:name="pico.spatial.stage.immersion_max"
                android:value="100" />
        </activity>

    </application>
</manifest>
```

In existing module mode, preserve working names and only update the metadata
needed for the selected container.

## Authoritative meta-data values (verified against `pico-cli project create` v0.12.2)

`pico.spatial.windowcontainer.style` (only meaningful for `DefaultWindowContainer`):

| Value | Meaning | Skill enum |
|---|---|---|
| `"0"` | Form.Automatic — system default, currently maps to Form.Planar | (use ON_PLAIN) |
| `"1"` | Form.Planar — flat panel with default depth (default) | `ON_PLAIN` |
| `"2"` | Form.Volumetric — volume allowing custom depth | `IN_VOLUME` |

`pico.spatial.stage.style` (only meaningful for `DefaultStage`):

| Value | Meaning | Skill enum |
|---|---|---|
| `"0"` | StageStyle.Automatic — system default, currently maps to Mixed | (use STAGE_MIXED) |
| `"1"` | StageStyle.Mixed — virtual + real env at full intensity | `STAGE_MIXED` |
| `"2"` | StageStyle.Progressive — adjustable balance (0–100% via `pico.spatial.stage.immersion*`) | `STAGE_PROGRESSIVE` |
| `"3"` | StageStyle.Full — virtual content only | `STAGE_FULL` |

`IN_VOLUME` only — extra meta-data observed in volumetric template:

| Meta-data | Values | Default |
|---|---|---|
| `pico.spatial.windowcontainer.volumealignment` | `"0"` Gravity / `"1"` Tilted | `"0"` |
| `pico.spatial.windowcontainer.volumebasepanel` | `"0"` Default (visible) / `"1"` None (hidden) | `"0"` |
| `pico.spatial.windowcontainer.defaultsize` | 3D format `WxHxD` (e.g. `960x960x960`) | — |

## Common mistakes

| Mistake | Symptom |
|---|---|
| Forgot `launch(::mainApp)` in `Application` | App launches but the SpatialUI root never initializes |
| Launcher Activity extends plain `Activity` instead of `SpatialLaunchActivity` | Black screen or no SpatialUI entry |
| `DefaultWindowContainer` / `DefaultStage` root does not match manifest metadata | App compiles but launches into the wrong spatial mode |
| Used `windowConstraints` to fake first-open size | Resize bounds work, but initial window size is still wrong |
| Switched an existing module from window to stage without need | Large diff, higher breakage risk, and possible UX mismatch |
