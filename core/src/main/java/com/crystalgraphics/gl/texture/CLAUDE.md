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
| `CgTexture2D.java` | Single 2D texture (`GL_TEXTURE_2D`). `create(path[, spec])` (cached, loaded at first use), `createDirect(path, spec)`, `missing()` (the checkerboard), `createEmpty(w, h, spec[, levels])` (storage for that many mip levels, written by a kernel or a pass rather than generated), `createFromPixels(...)`. Two `upload()` overloads, and `uploadRegion` for a region of one level (what `CgRecording.update` writes a graph texture with). `bindLevel(unit, level)` samples one level alone until the next whole `bind`. A path ending in `.cgtex` is a cooked texture (`util/io/CgCookedTexture`): its levels land from one lease, as BC7 blocks where `CgCapabilities.bc7()` holds, else decoded by `CgBc7` to RGBA8 on the loading thread; the file decides format and levels, the spec only filter and wrap. |
| `CgTexture2DArray.java` | 2D-array texture (`GL_TEXTURE_2D_ARRAY`). `create(spec, paths...)` (cached, loaded at first use, images of different sizes centred), `createDirect(spec, paths...)`; `allocateEmpty` for incremental uploads, and a frame-graph array's storage (`CgFrameBuffer.createArray`). |
| `CgTextureAbstract.java` | The shared base. Owns the texture's `CgDeferral` (`gpu/`): all its device work goes through it, so `CgTexture2D`, `CgTexture2DArray`, `CgTexture3D` and `CgTextureCubemap` are made, filled, grown and deleted from any thread, their work waiting for the render thread where the device may not be driven. `getId`/`bind` run what is queued first; an id is 0 until then off the render thread. Pixels for waiting work are copied once into a `CgUploadLease` by the thread that passed them (`gpu/CgUploads`), and land with no copy on the render thread; `CgTexture2D.uploadRegion` and `CgTexture3D.uploadRegion` also take a lease its producer wrote. `CgTexture2DArray` takes none: its CPU mirror needs a source it can read. |
| `CgTexture3D.java` | 3D texture (`GL_TEXTURE_3D`). `create(spec, paths...)` (cached, loaded at first use), `createDirect(spec, paths...)`; `createEmpty(w, h, d, spec[, levels])` through its `CgDeferral`, any thread, `uploadRegion` for a box of one level and `generateMipmaps()` from level 0: a volume a kernel, a bake or an upload fills, and a frame-graph volume's storage. |
| `CgSamplers.java` | The GL sampler objects behind `CgSampler`s, one per sampler and mip-or-not, kept for the session (`releaseAll` at teardown), and which is bound on each unit: `bind(unit, sampler, texture)` (null unbinds), `forget()` and `unbindOwn()` at each execution's start and end, so a sampler never reaches the host's textures. A sampler first bound inside a `CgGlRecording` throws: it makes a GL object |
| `CgTextureCubemap.java` | Cubemap (`GL_TEXTURE_CUBE_MAP`). `create(spec, posX...negZ)`, `createDirect(spec, posX...negZ)`, `createEmpty(size, spec)`, any thread through its `CgDeferral` (faces decoded on the calling thread); `uploadFace(face, level, x, y, w, h, ...)` from a buffer or a lease, the face carried in the lease's `z`. |

## Where an upload runs

An upload has a CPU half (bytes into memory the GPU can copy from) and a GPU half (the copy). Off the render thread
the first is the producer's and the second lands at the render thread's next deferral apply, before the frame's
first pass (plan `render-async-uploads`):

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
| GL, persistent mapping (every desktop driver) | DMA from the lease's unpack buffer: 72 MB lands with no GPU hitch on NVIDIA, 1.5-3 ms of render thread |
| GL without it (macOS 4.1, a 3.3 context), or a converting upload | from direct memory at the call: the landing frame pays the copy on the GPU, 17-22 ms per 72 MB |
| Vulkan device | a device copy from the lease's buffer; into a texture nothing else has touched yet, on the transfer queue, which the frame's queue waits for at its next pass (`platform/CLAUDE.md`). `-Dcrystalgraphics.vulkan.transfer=false` keeps it on the frame's queue |

`CgTexture2DArray.uploadLayerRegion` lands through a lease on the render thread too: NVIDIA's GL converts the whole
array at the first client-memory upload after a grow's GPU copy into it (about 1.4 ms per 4 MB layer, paid at the next
fence), and an upload from an unpack buffer does not convert it.

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
- **Every GL call goes through `gpu`**, in `load()` too: the first use may be a worker's.

## CgTextureManager

```java
CgTexture get(String key, CgTextureSpec spec);                                   // null if absent or deleted
CgTexture getOrCreate(String key, CgTextureSpec spec, Supplier<CgTexture> maker); // what each type's create uses
CgTexture2D getOrCreate(String path, CgTextureSpec spec);                       // CgTexture2D.create; fallback for no path
CgTexture2D getFallback();                                                       // the shared CgTexture2D.missing()
void reloadAll();                                                                // a resource reload
void freeAll();                                                                  // CgGraphicsLifecycle.destroyContext()
```

- **Keyed by the paths and the spec**: the paths joined by `\0`, then `CgTextureSpec` by value. One image sampled two
  ways is two textures; the first caller's spec never wins over a second's.
- Each cached texture is tracked by its key's paths (`CgAssetReloader`), so saving one of its files reloads it in
  place, and only it.
- The fallback is never cached under a key, and is freed with the cache.

## GL Targets

- `GL_TEXTURE_2D = 0x0DE1`
- `GL_TEXTURE_2D_ARRAY = 0x8C1A` (requires GL30 for `glTexImage3D` route)
- `GL_TEXTURE_3D = 0x806F` (GL12)
- `GL_TEXTURE_CUBE_MAP = 0x8513` (bind target); face targets `GL_TEXTURE_CUBE_MAP_POSITIVE_X..NEGATIVE_Z` = `0x8515..0x851A` (upload targets)

## Failure-Atomic Allocation

Every `doCreate()` follows:

1. Load + validate data. Throw `IllegalArgumentException` on bad input.
2. `glGenTextures` → `id`.
3. Construct object (id in hand, not yet uploaded).
4. `tex.upload(data)` inside `try { ... } catch (RuntimeException) { glDeleteTextures(id); throw; }`.

This guarantees no GL ids are leaked on partial failure (e.g. OOM during `glTexImage3D`).

## Mipmap Policy

`CgTextureSpec.generateMipmaps` checks `GLContext.getCapabilities().OpenGL30`.
If unavailable, it logs a one-time warning and no-ops. The fallback is
intentional: on a hardware tier without GL30, `CgTextureSpec.mipmaps` is
best-effort and the user gets `GL_LINEAR` filtering at level 0.

## Spec Param Application

`CgTextureSpec.applyTo(target)` writes:
- `GL_TEXTURE_MIN_FILTER` / `GL_TEXTURE_MAG_FILTER`
- `GL_TEXTURE_WRAP_S` / `GL_TEXTURE_WRAP_T`
- `GL_TEXTURE_WRAP_R` (only for `GL_TEXTURE_3D` and `GL_TEXTURE_2D_ARRAY`)
- Shadow compare params (`GL_TEXTURE_COMPARE_MODE` / `GL_TEXTURE_COMPARE_FUNC`) when `compareMode != GL_NONE`

Called inside `upload()` after `glTexImage*` so params are always applied
to a fully-allocated texture object.

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

- All GL constants live in `private static final int` at the top of each class. No raw hex in method bodies.
- `load()` is the single decode path per type and `upload()` the single GL upload; factories and `reload()` call them.
- `reload()` is always in-place: same GL id, same Java object. No id stealing, no cache replacement.
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
