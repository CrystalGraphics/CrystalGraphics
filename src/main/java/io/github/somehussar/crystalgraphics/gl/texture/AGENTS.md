# gl/texture — CgTexture GL Implementations

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../AGENTS.md)
> API guide: [`api/texture/AGENTS.md`](../../api/texture/AGENTS.md)

## What This Package Is

Concrete `CgTexture` implementations. All four texture types (`CgTexture2D`,
`CgTexture2DArray`, `CgTexture3D`, `CgTextureCubemap`) are transparently cached
via the singleton `CgTextureManager`.

## File Map

| File | Role |
|------|------|
| `CgTextureManager.java` | Singleton cache + reload + fallback. See below. |
| `CgTextureAbstract.java` | Shared base: id/spec/dimensions, bind, delete, mipmap, `checkNotDeleted`. |
| `CgTexture2D.java` | Single 2D texture (`GL_TEXTURE_2D`). `create(path)`, `create(path, spec)`, `createDirect(path, spec)`, `createEmpty(w, h, spec)`, `createFromPixels(...)`. Two `upload()` overloads. |
| `CgTexture2DArray.java` | 2D-array texture (`GL_TEXTURE_2D_ARRAY`). `create(paths...)`, `create(spec, paths...)`, `createDirect(spec, paths...)`. |
| `CgTexture3D.java` | 3D texture (`GL_TEXTURE_3D`). `create(paths...)`, `create(spec, paths...)`, `createDirect(spec, paths...)`. |
| `CgTextureCubemap.java` | Cubemap (`GL_TEXTURE_CUBE_MAP`). `create(spec, posX...negZ)`, `createDirect(spec, posX...negZ)`, `createEmpty(size, spec)`. |

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

Called inside `upload()` after `glTexImage*` so params are always applied
to a fully-allocated texture object.

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
