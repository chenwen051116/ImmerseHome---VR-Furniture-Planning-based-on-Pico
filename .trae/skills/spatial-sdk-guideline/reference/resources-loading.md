# Resources Loading (Models / AssetBundle / Mesh / Material / Texture)

## What this covers
- Model formats and recommended choices (USD vs glTF)
- AssetBundle workflow (Spatial Editor `.bundle`)
- Async loading patterns (don’t block UI)
- Resource lifecycle: validity, persistence, and safe release

## Choose a model source

### Model formats
- **Preferred**: USD (`.usd/.usda/.usdc/.usdz`)
- **Supported**: glTF/GLB

Practical guidance:
- Use USD when you control the authoring pipeline and want best compatibility.
- Use glTF/GLB when integrating existing DCC/export workflows.

## AssetBundle (Spatial Editor pipeline)

### What it is
- Spatial Editor compiles one project into a `.bundle` stored in APK assets.
- The bundle can contain multiple scenes (USDA files) and resources.

### Load a bundle
```kotlin
val bundle = AssetBundle.load("asset://path/to/YourBundleName.bundle")
```

Best practice:
- Keep a reference and **close** it explicitly when done.

### Load a scene (USDA) from a bundle
- Only scenes under the Editor project's `/Scenes` directory are loadable.
- Pass the path **relative to `/Scenes`**, without the `.usda` extension.

```kotlin
val root = bundle.loadModel("SubFolder/SceneName")
```

If the Editor scene has a top-level node named `Root`:
- Use `root.getChildren()[0]` or `root.findEntity("Root")` before operating on scene elements.

### Preload
For large scenes:
```kotlin
bundle.preloadModel("SceneName")
```

### Load materials from a bundle
- Materials live under scene paths (example structure):
  - `"SceneName/Root/MyMaterials/pbrMaterial"`

```kotlin
val m = bundle.loadMaterial("SceneName/Root/MyMaterials/pbrMaterial")
val pbr = m as PhysicallyBasedMaterial
```

### Release bundle-cached data (two steps)
There are two distinct concepts:
1) **Destroy instances** you created (`Entity.destroy()`, `Resource.close()`).
2) **Release cached underlying data** in the bundle (`bundle.releaseModel(...)`, `bundle.releaseResource(...)`).

Example pattern:
```kotlin
val e = bundle.loadModel("SceneName")
// ... use entity ...
e.destroy()               // release entity instance and its references
bundle.releaseModel("SceneName") // release underlying cached model data
bundle.close()            // release the bundle and any remaining cached data
```

## Direct loading from assets (no bundle)

Use this for files shipped directly in APK assets, including `.gltf` / `.glb` models. `glTF/GLB` is supported, although `USD` remains the preferred format when you control the content pipeline.

### Model entities

For Compose-based container code, follow the general `SpatialView` pattern: do one-time entity loading in `initial`, then attach the loaded root entity with `content.addEntity(...)`.

```kotlin
SpatialView(initial = { content, _ ->
  val root = Entity.loadSuspend("asset://models/robot.glb")
  content.addEntity(root)
})
```

Guideline:
- Prefer `loadSuspend(...)` to avoid blocking the main thread.
- If you need to show the synchronous API instead, use `withContext(Dispatchers.IO) { Entity.load("asset://...") }`.
- `Entity.loadSuspend(...)` can be called directly on the main thread; after loading completes, entity/component access and mutation should run on the main thread.
- In `SpatialView`, use `initial` for one-time loading/setup; do not keep re-adding loaded entities from `update`.
- `Entity.loadSuspend(...)` returns the **root `Entity`** of the loaded model hierarchy, so add that root to the scene/content after loading succeeds.
- Loaded child entities automatically get `TransformComponent`, and mesh-bearing child entities automatically expose `ModelComponent` for mesh/material access.
- If you need to inspect or modify rendered parts after loading, traverse the hierarchy (for example with `getChildren()` / `findEntity(...)`) and read `ModelComponent` from the mesh-bearing child entities.

## Mesh, materials, textures (resource basics)

### Mesh
- Primitives:
  - `MeshResource.createBox(...)`, `createSphere(...)`, `createPlane(...)`, etc.
- Instancing (reduce draw calls):
  - `MeshInstancesResource` (many instances of same geometry)

### Materials
- Common: `UnlitMaterial`, `PhysicallyBasedMaterial`, `ShaderGraphMaterial`.
- Choose blending mode intentionally (Opaque/Transparent/Add/Fade/Masked).

### Textures
- `TextureResource.load(...)` or `TextureResource.create(Bitmap)`.

## Resource lifecycle (critical)

### Validity
- Most resources have `valid` (or equivalent). After release, `valid == false`.

### Persistence
- `resource.toGlobal()` persists the resource beyond typical ownership.
- You must later call `resource.close()` to unpersist and release.

### Common leak patterns
- Creating a resource and never attaching it to an entity/material.
- Persisting resources (`toGlobal()`) and forgetting to `close()`.

### Common “use-after-free” pattern
- Destroying the entity that owns a resource and then reusing the old resource handle.

Checklist:
- [ ] Do I know who owns each resource (entity, material, global)?
- [ ] When a screen/container closes, do I destroy entities and cancel subscriptions?
- [ ] If I used `toGlobal()`, do I have a matching `close()`?

## Packaging checklist (AssetBundle + models)
- [ ] Ensure `.bundle` is not compressed in the APK.
- [ ] If you ship `.usdz`, `.glb`, `.wav`, also ensure they are not compressed.
- [ ] Treat bundle names and scene paths as stable runtime IDs.
