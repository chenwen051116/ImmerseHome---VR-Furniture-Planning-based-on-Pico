# Common Troubleshooting Card

Open this file **first** for any "not working / nothing renders" symptom. Most issues reduce to one of the cross-cutting checks below; only after these pass should you open the domain-specific reference.

## Generic Spatial Capability Checklist

1. **Spatial platform**: are you running on PICO OS simulator or a real device? Many capabilities (`vibrantEffect`, `backgroundMaterial`, `spatialHoverEffect`, `scale3D`, `Augment`) are no-ops on non-Spatial platforms.
2. **Container**: is the content hosted inside a `WindowContainer` (or `DefaultWindowContainer` / `SpatialScene`)? Out-of-container content cannot participate in spatial features.
3. **Recommended modifier order** (use this when composing multiple spatial capabilities on the same node):
   `size → border/background → backgroundMaterial → vibrantEffect → rotate3D / scale3D → spatialHoverEffect → pointerInput`.
4. **Direct root child rule**: `windowConstraints` only takes effect when attached to the **direct child** of a `WindowContainer` / `DefaultWindowContainer`.
5. **`resizeType` / manifest**: window-size APIs require `ContainerResizeType.ContentSize` and a matching `pico.spatial.windowcontainer.resizetype` value (`0` Disabled, `1` User, `2` ContentSize).
6. **DSL stability**: hover DSL blocks must declare the **same number of effects** in both `isActive = true` and `false` branches; only vary the values, not the structure.
7. **Inheritance break**: `Vibrant.None` and `disableSpatialHoverEffect` terminate inheritance for an entire subtree.
8. **Imports**: import the real symbols (`com.pico.spatial.ui.foundation.*`); IDE auto-import sometimes picks the wrong package.

## Symptom → Reference

| Symptom                                          | Open                                                              |
| ------------------------------------------------ | ----------------------------------------------------------------- |
| Gestures not responding                          | [`ability-gestures.md`](ability-gestures.md)                      |
| Vibrant effect missing or animation breaks color | [`ability-vibrant.md`](ability-vibrant.md)                        |
| Hover not working / DSL crash                    | [`ability-hover.md`](ability-hover.md)                            |
| `windowConstraints` ignored                      | [`ability-window-constraints.md`](ability-window-constraints.md)  |
| 3D depth / `Box3D` not stacking                  | [`ability-depth-layout.md`](ability-depth-layout.md)              |
| Glass background not rendering                   | [`ability-background-material.md`](ability-background-material.md) |
| Z floating / sinking issues                      | [`ability-z-offset.md`](ability-z-offset.md)                      |
| `rotate3D` / `scale3D` doing nothing             | [`ability-3d-transforms.md`](ability-3d-transforms.md)            |
| Augment / TabBar / Toolbar / Subwindow missing   | [`ability-augment.md`](ability-augment.md)                        |

## Composing Multiple Capabilities

When stitching snippets from multiple `ability-*.md` files onto the same node, follow the **modifier order** above and the per-domain rules in each reference. Do not pre-load combo examples — open only the references that match the user's explicit request.
