# gl/texture — CgTexture GL Implementations

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../AGENTS.md)
> API guide: [`api/texture/CLAUDE.md`](../../api/texture/CLAUDE.md)

## What This Package Is

Concrete `CgTexture` implementations. All four texture types (`CgTexture2D`,
`CgTexture2DArray`, `CgTexture3D`, `CgTextureCubemap`) are transparently cached
via the singleton `CgTextureManager`.

## File Map

| File | Role |
|------|------|
| `CgTextureManager.java` | Singleton cache + reload + fallback. See below. |
| `CgTextureAbstract.java` | Shared base: id/spec/dimensions, bind, delete, `checkUsable`, and the load at first use (below). |
| `CgTexture2D.java` | Single 2D texture (`GL_TEXTURE_2D`). `create(path[, spec])` (cached, loaded at first use), `createDirect(path, spec)`, `missing()` (the checkerboard), `createEmpty(w, h, spec[, levels])` (storage for that many mip levels, written by a kernel or a pass rather than generated), `createFromPixels(...)`. Two `upload()` overloads, and `uploadRegion` for a region of one level (what `CgRecording.update` writes a graph texture with). `bindLevel(unit, level)` binds a view of one level. A path ending in `.hdr` is a Radiance image (`util/io/CgRadianceHdr`), uploaded as half floats under an `RGBA16F` spec; another spec throws at first use. A path ending in `.cgtex` is a cooked texture (`util/io/CgCookedTexture`): its levels land from one lease, as BC7 blocks where `CgCapabilities.bc7()` holds, else decoded by `CgBc7` to RGBA8 on the loading thread; the file decides format and levels, the spec only filter and wrap. |
| `CgTexture2DArray.java` | 2D-array texture (`GL_TEXTURE_2D_ARRAY`). `create(spec, paths...)` (cached, loaded at first use, images of different sizes centred), `createDirect(spec, paths...)`; `allocateEmpty` for incremental uploads, `growLayers` (new storage, the old layers copied on the GPU), and a frame-graph array's storage (`CgFrameBuffer.createArray`). |
| `CgTextureAbstract.java` | The shared base. Owns the texture's `CgDeferral` (`gpu/`): all its device work goes through it, so `CgTexture2D`, `CgTexture2DArray`, `CgTexture3D` and `CgTextureCubemap` are made, filled, grown and deleted from any thread, their work waiting for the render thread where the device may not be driven. `getId`/`bind` run what is queued first; an id is 0 until then off the render thread. Pixels for waiting work are copied once into a `CgUploadLease` by the thread that passed them (`gpu/CgUploads`), and land with no copy on the render thread; `CgTexture2D.uploadRegion` and `CgTexture3D.uploadRegion` also take a lease its producer wrote. |
| `CgTexture3D.java` | 3D texture (`GL_TEXTURE_3D`). `create(spec, paths...)` (cached, loaded at first use), `createDirect(spec, paths...)`; `createEmpty(w, h, d, spec[, levels])` through its `CgDeferral`, any thread, `uploadRegion` for a box of one level and `generateMipmaps()` from level 0: a volume a kernel, a bake or an upload fills, and a frame-graph volume's storage. |
| `CgTextureCubemap.java` | Cubemap (`GL_TEXTURE_CUBE_MAP`). `create(spec, posX...negZ)`, `createDirect(spec, posX...negZ)`, `createStacked(path, spec)` (one image, faces stacked top to bottom in Unreal's slice order, each face i unchanged; `w x 6w` or it throws at first use), `createEmpty(size, spec)`, any thread through its `CgDeferral` (faces decoded on the calling thread); `uploadFace(face, level, x, y, w, h, ...)` from a buffer or a lease, the face carried in the lease's `z`. |

## Where an upload runs

An upload has a CPU half (bytes into memory the GPU can copy from) and a GPU half (the copy). Off the render thread
the first is the producer's and the second lands at the render thread's next deferral apply, before the frame's
first pass (plan `render-async-uploads`), within `crystalgraphics.deferral.budget` (4 ms) a frame: a burst of textures
lands over frames, and one bound sooner lands at its bind (`CgDeferral.spreadOverFrames`):

```java
// A worker that makes the bytes writes them straight into a lease: no copy anywhere else
CgUploadLease lease = CgUploads.lease(4 * w * h);
bake(lease.bytes());
texture.uploadRegion(0, 0, 0, w, h, lease, GL_RGBA, GL_UNSIGNED_BYTE);   // any thread; lands on the render thread

// A worker holding its own buffer: the texture copies it into a lease on this thread
texture.uploadRegion(0, 0, 0, w, h, pixels, GL_RGBA, GL_UNSIGNED_BYTE);
```

| Backend | The GPU half |
|---|---|
| GL device | `copyBufferToTexture` from the lease's buffer, as an unpack-buffer upload: DMA, 72 MB with no GPU hitch on NVIDIA, 1.5-3 ms of render thread. A converting upload is converted on the CPU first |
| Vulkan device | a device copy from the lease's buffer; into a texture nothing else has touched yet, on the transfer queue, which the frame's queue waits for at its next pass (`platform/CLAUDE.md`). `-Dcrystalgraphics.vulkan.transfer=false` keeps it on the frame's queue |

A second GL context for uploads was measured and declined (`render-async-uploads` §4f); the harness keeps it as
`upload-stress -Dcrystalgraphics.harness.upload.shared=true`.

## Textures from files: loaded at first use

Every `create(...)` from paths goes through `CgTextureManager` and answers an object that has not read its files.
`CgTextureAbstract` holds the hook:

```
create(spec, paths)     manager.getOrCreate(key, spec, () -> unloaded texture)   // no decode, no GL
first use               getWidth/getHeight/getId/getLevels/isMissing/bind/any write
  -> ensureLoaded()     once, synchronized, on the thread that asks
  -> load()             decode the files, queue the upload through gpu (CgDeferral)
reload()                load() again in place: same object, same id; a file that will not load keeps what it had
createDirect(...)       the same object, load() at once, not cached
```

- **A subclass reads its own fields only after `checkUsable()` or inside `load()`**: before its first use a texture
  from files has width and height 0.
- **`load()` is the one decode path per type**, for the first load and every reload. A first load that fails leaves a
  stand-in and sets `missing`: the checkerboard for a 2D texture, a clear layer for an array, a clear 1x1 texture for
  a 3D or cubemap.
- **An array centres images of different sizes** on a layer the largest one's size (`CgTexture2DArray.centred`);
  `CgTextures.frames(pattern, count)` names numbered files.
- **`CgTextures` (`api/texture`) is the shorthand** over each type's `create`: `of`, `frames`, `array`, `volume`,
  `cubemap`. A new way to make a texture from files goes on both.
- **No decode on a server**: naming a texture builds an object and a cache entry. Nothing reads a file until something
  asks for its size or binds it.
- **Every device call goes through `gpu`**, in `load()` too: the first use may be a worker's.

## CgTextureManager

```java
CgTexture get(String key, CgTextureSpec spec);                                   // null if absent or deleted
CgTexture getOrCreate(String key, CgTextureSpec spec, Supplier<CgTexture> maker); // what each type's create uses
CgTexture2D getOrCreate(String path, CgTextureSpec spec);                       // CgTexture2D.create; fallback for no path
CgTexture2D getFallback();                                                       // the shared CgTexture2D.missing()
void reloadAll();                                                                // a resource reload
void freeAll();                                                                  // CgEngineLifecycle.shutdown()
```

- **Keyed by the paths and the spec**: the paths joined by `\0`, then `CgTextureSpec` by value. One image sampled two
  ways is two textures; the first caller's spec never wins over a second's.
- Each cached texture is tracked by its key's paths (`CgAssetReloader`), so saving one of its files reloads it in
  place, and only it.
- The fallback is never cached under a key, and is freed with the cache.

## Storage and sampling

Every texture's storage is a device texture (`CgDeviceTextures`, `CgTextureAbstract.storeOnDevice`): made with the
shape its first whole upload or `createEmpty` gives it, written by `writeTexture` or a lease's GPU copy, its chain by
`generateMipmaps`. `CgGL` reaches it by the name `CgGLImports` gives it, and that name changes when the storage is
remade (a grow, a resize): read `getId()` at each use.

A texture has no sampling state of its own. Its spec's filter, wrap and compare are the sampler a binding reads where
it names no `CgSampler` (`render/draw/CgDeviceSamplers`). `bind(unit)` notes it for the pass being recorded, and
`CgTexture2D.bindLevel(unit, level)` notes a view of one level; neither issues a GL call.

## CgTextureSpec Format API (Phase 1 change)

After Phase 1, the format triple is accessed via convenience delegates on `CgTextureSpec`
rather than through `spec.getFormat().*`:

```java
// OLD (Phase 0, removed):
spec.getFormat().getInternalFormat()
spec.getFormat().getPixelFormat()
spec.getFormat().getPixelType()

// NEW (Phase 1+):
spec.getGlInternalFormat()
spec.getGlBaseFormat()
spec.getGlType()
```

These delegate to `spec.getType().glInternalFormat` etc. `CgTextureFormatSpec` is gone;
format is now a `CgTextureType` enum constant.

## Design Rules

- `load()` is the single decode path per type and `upload()` the single whole upload; factories and `reload()` call them.
- `reload()` is always in place: the same Java object. Its id is the storage's and may change; read it at each use.
- `create(...)` is cached and tracked, so a change to its files reloads it; `createDirect(...)` is neither.
- Never call `glGet*` here; the spec is the source of truth.
- `delete()` is idempotent (second call is silent no-op).

## TODO — Animatable Textures

> **Future work: `ITickable` / animatable texture support**

Add an `AnimatedCgTexture2D` (or similar) that:
- Implements `CgTexture` (delegates bind/getId/etc. to the underlying `CgTexture2D`).
- Holds a sequence of frames (array of `CgImageData` or pre-uploaded `CgTexture2D` ids).
- Implements an `ITickable.tick(float deltaTime)` interface so the Minecraft adapter
  (`mc/` package) can advance frames each game tick or render tick.
- Frame scheduling options: fixed FPS, per-frame duration list (like MC's `.mcmeta` format).
- Could integrate with `CgAssetReloader` to reload all frames on F3+T.
- Manager interaction: register under a synthetic key; `reloadAll()` already calls `reload()`
  which can reload all frames in-place.

---

Also loaded with this folder:

@../../../../../../../../docs/ENGINE_API.md
