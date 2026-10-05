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
| `CgTextureAbstract.java` | Shared base: id/spec/dimensions, bind, delete, mipmap, `checkNotDeleted`. |
| `CgTexture2D.java` | Single 2D texture (`GL_TEXTURE_2D`). `create(path)`, `create(path, spec)`, `createDirect(path, spec)`, `createEmpty(w, h, spec)`, `createEmpty(w, h, spec, levels)` (storage for that many mip levels, written by a kernel or a pass rather than generated), `createFromPixels(...)`. Two `upload()` overloads, and `uploadRegion` for a region of one level (what `CgRecording.update` writes a graph texture with). `bindLevel(unit, level)` samples one level alone until the next whole `bind`. |
| `CgTexture2DArray.java` | 2D-array texture (`GL_TEXTURE_2D_ARRAY`). `create(paths...)`, `create(spec, paths...)`, `createDirect(spec, paths...)`; `allocateEmpty` for incremental uploads, and a frame-graph array's storage (`CgFrameBuffer.createArray`). |
| `CgTextureAbstract.java` | The shared base. Owns the texture's `CgDeferral` (`gpu/`): all its device work goes through it, so `CgTexture2D`, `CgTexture2DArray`, `CgTexture3D` and `CgTextureCubemap` are made, filled, grown and deleted from any thread, their work waiting for the render thread where the device may not be driven. `getId`/`bind` run what is queued first; an id is 0 until then off the render thread. Pixels for waiting work are copied once into a `CgUploadLease` by the thread that passed them (`gpu/CgUploads`), and land with no copy on the render thread; `CgTexture2D.uploadRegion` and `CgTexture3D.uploadRegion` also take a lease its producer wrote. `CgTexture2DArray` takes none: its CPU mirror needs a source it can read. |
| `CgTexture3D.java` | 3D texture (`GL_TEXTURE_3D`). `create(paths...)`, `create(spec, paths...)`, `createDirect(spec, paths...)`; `createEmpty(w, h, d, spec[, levels])` through its `CgDeferral`, any thread, `uploadRegion` for a box of one level and `generateMipmaps()` from level 0: a volume a kernel, a bake or an upload fills, and a frame-graph volume's storage. |
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

## Uniform Factory Pattern

All four types follow the same internal structure:

```
create(...)       — cached path: manager.getOrCreate(key, () -> doCreate(..., paths))
createDirect(...) — uncached path: doCreate(..., null)            // sourcePaths = null = no reload

doCreate(spec, paths, sourcePaths) [private static]
  1. Load + validate image data
  2. glGenTextures → id
  3. Construct object (id, dimensions, spec, sourcePaths)
  4. tex.upload(images)   ← THE single GL upload method
  5. return tex           (or glDeleteTextures(id) + rethrow on exception)

upload(images) [instance, public]
  — Binds own id, glTexImage2D / glTexSubImage3D (depending on type),
    spec.applyTo, generateMipmaps if enabled, unbind. Updates width/height.

reload() [override]
  — if sourcePaths == null: no-op
  — else: load images → upload(images)  (in-place, same id, same object)
```

### CgTexture2D — two upload overloads

`CgTexture2D` has an extra raw-buffer path for `createEmpty` and `createFromPixels`:

```java
upload(CgImageData image)
    // path-loaded textures; infers pixelFormat from channel count

upload(int width, int height, ByteBuffer pixels, int pixelFormat, int pixelType)
    // raw buffer path; used by createEmpty (null pixels) and createFromPixels
```

`doCreate` in `CgTexture2D` delegates to `upload(w, h, pixels, pf, pt)` for all three
factory paths (`createDirect`, `createEmpty`, `createFromPixels`).
`reload()` calls `upload(CgImageData)`.

## CgTextureManager

Singleton that caches all texture types, handles context cleanup, and resource reload.

### Cache Key Scheme

- **Single-path textures** (2D): key is the asset path
- **Multi-path textures** (Array, 3D, Cubemap): key is paths joined by `\0`

### API

```java
// Generic — works for any texture type
CgTexture get(String key);
CgTexture getOrCreate(String key, Supplier<CgTexture> loader);  // stores result, NOT the loader

// 2D convenience — returns fallback checkerboard on failure, never null
CgTexture2D getOrCreate(String path);
CgTexture2D getOrCreate(String path, CgTextureSpec spec);
CgTexture2D bind(String path);            // getOrCreate + bind

// Fallback
CgTexture2D getFallback();                // 8×8 purple/black checkerboard, lazy

// Manual registration (texture must manage its own reload via sourcePaths)
void register(String key, CgTexture texture);

void freeAll();           // called by CgGraphicsLifecycle.destroyContext()
void reloadAll();         // called by CgAssetReloader on F3+T
```

### Reload Design

`reloadAll()` does **not** replace cache entries. It calls `texture.reload()` on every
cached texture. Each texture re-uploads into its own GL id in-place. Callers holding a
`CgTexture` reference automatically see the refreshed image after reload — no re-fetch needed.

Reload is a no-op for:
- Procedural textures (`createEmpty`, `createFromPixels`, or any texture with `sourcePaths == null`).
- Textures registered via `register(key, texture)` unless they were originally
  created by a factory that stored `sourcePaths` (all four `create(...)` factories do).

### Fallback Texture

`getOrCreate(String path, CgTextureSpec spec)` returns `getFallback()` (never `null`) when
the path fails to load. The fallback is an 8×8 purple/black checkerboard created via
`CgTexture2D.createFromPixels` — no asset file needed. It uses `RGBA8_NEAREST` so the
pattern stays crisp at any display scale.

The fallback is freed in `freeAll()` alongside the rest of the cache and re-created lazily
on the next context init.

### Lifecycle Integration

- `CgGraphicsLifecycle.destroyContext()` → `CgTextureManager.get().freeAll()`
- `CgAssetReloader.reload()` → `CgTextureManager.get().reloadAll()`

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
- `upload()` methods are the **single source of GL upload logic** per type. Neither factories nor `reload()` duplicate them.
- `reload()` is always in-place: same GL id, same Java object. No id stealing, no cache replacement.
- `createDirect(...)` always passes `sourcePaths = null` — no reload support.
- `create(...)` always passes `sourcePaths = paths` — full reload support.
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
