# Vibrant Materials

Capability: SpatialUI Vibrant tinting via `Modifier.vibrantEffect`, `Color.withVibrant`, and `animateColorVibrantAsState`.

## Enable Vibrant On A Node Or Subtree

```kotlin
import com.pico.spatial.ui.foundation.vibrant.vibrantEffect
import com.pico.spatial.ui.foundation.vibrant.Vibrant

Box(
    Modifier
        .size(200.dp)
        .vibrantEffect(Vibrant.Dark)
        .background(Color.Vibrant)   // pure material-only rendering
)
```

## Stop Inheritance

```kotlin
Box(Modifier.vibrantEffect(Vibrant.Light)) {
    Text("Vibrant child")
    Box(Modifier.vibrantEffect(Vibrant.None)) {
        Text("Normal child")  // back to normal Android rendering
    }
}
```

## Encode Vibrant Into A Color

```kotlin
import com.pico.spatial.ui.foundation.vibrant.withVibrant
import com.pico.spatial.ui.foundation.vibrant.obtainVibrant
import com.pico.spatial.ui.foundation.vibrant.containsVibrant

val darkRed: Color = Color.Red.withVibrant(Vibrant.Dark)  // Encode
val v: Vibrant = darkRed.obtainVibrant()                   // Decode
val has: Boolean = darkRed.containsVibrant()               // Inspect
```

## Animation + Vibrant (Recommended Pattern)

```kotlin
val animatedColor by animateColorAsState(targetColor)
Box(
    Modifier
        .vibrantEffect(vibrant)         // keep the material effect on the modifier
        .background(animatedColor)      // keep the animated color clean
)
```

## Animate Colors Within The Same Vibrant Tier

```kotlin
import com.pico.spatial.ui.foundation.vibrant.animateColorVibrantAsState

val color by animateColorVibrantAsState(
    targetValue = if (active) Color.Red.withVibrant(Vibrant.Dark)
                 else Color.Blue.withVibrant(Vibrant.Dark)
)
Box(Modifier.background(color))
```

## Notes

- Animation, gradients, and custom shaders can break the Vibrant encoding stored in `Color`. Move the material effect onto the `Modifier` when in doubt.
- `Vibrant.Unspecified` inherits from the parent. `Vibrant.None` terminates the effect chain.
- Cross-tier animation is not supported.

## Vibrant Not Working? Check This

1. Are you running on a Spatial platform (PICO OS simulator or real device)?
2. Did a parent apply `Vibrant.None` and cut off inheritance?
3. Are you using animations or gradients that break color encoding? If so, move the effect to `Modifier.vibrantEffect`.
4. Is the content inside a `WindowContainer`?
5. Is the background color actually `Color.Vibrant` or a color encoded with `withVibrant`?

## Imports

```kotlin
import com.pico.spatial.ui.foundation.vibrant.vibrantEffect
import com.pico.spatial.ui.foundation.vibrant.Vibrant
import com.pico.spatial.ui.foundation.vibrant.withVibrant
import com.pico.spatial.ui.foundation.vibrant.obtainVibrant
import com.pico.spatial.ui.foundation.vibrant.containsVibrant
import com.pico.spatial.ui.foundation.vibrant.animateColorVibrantAsState
```

---

See also: [`troubleshooting.md`](troubleshooting.md) for cross-cutting checks (Spatial platform, `WindowContainer`, modifier order).
