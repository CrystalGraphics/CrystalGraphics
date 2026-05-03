# gl/texture — CgTexture GL Implementations

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../AGENTS.md)
> API guide: [`api/texture/AGENTS.md`](../../api/texture/AGENTS.md)

## What This Package Is

Concrete `CgTexture` implementations. After the V2 collapse, these classes
**are** the public textures — the previous thin `api/texture/CgTexture2D|2DArray|3D`
sub-interfaces have been removed and the impls now `implements CgTexture`
directly while carrying their own static factory methods.

## File Map

| File | Role |
|------|------|
| `CgTexture2D.java` | Single 2D texture (`GL_TEXTURE_2D`). Factories `create(path)`, `create(path, spec)`, `createEmpty(w, h, spec)`. Supports `upload(w, h, pixels)` re-allocation. Upload via `glTexImage2D`. |
| `CgTexture2DArray.java` | 2D-array texture (`GL_TEXTURE_2D_ARRAY`). Factories `create(paths...)` and `create(spec, paths...)`. Storage via `GL12.glTexImage3D` (target requires GL30); per-layer upload via `GL12.glTexSubImage3D`. |
| `CgTexture3D.java` | 3D texture (`GL_TEXTURE_3D`, GL12). Factories `create(paths...)` and `create(spec, paths...)`. Storage via `GL12.glTexImage3D`; per-slice upload via `GL12.glTexSubImage3D`. |
| `CgTextureCubemap.java` | Cubemap (`GL_TEXTURE_CUBE_MAP = 0x8513`). Factories `create(spec, posX, negX, posY, negY, posZ, negZ)` and `createEmpty(size, spec)`. Storage + upload via six `glTexImage2D` calls, one per face target (`POSITIVE_X` through `NEGATIVE_Z`, 0x8515..0x851A). All faces must be the same square size. `getWidth()` and `getHeight()` both return the face size. |

Filter/wrap setup and the GL30 mipmap probe formerly housed in
`CgTextureSupport` (deleted) now live on `CgTextureSpec` itself —
`spec.applyTo(target)` and `CgTextureSpec.generateMipmaps(target)`.

## GL Targets

- `GL_TEXTURE_2D = 0x0DE1`
- `GL_TEXTURE_2D_ARRAY = 0x8C1A` (requires GL30 for `glTexImage3D` route)
- `GL_TEXTURE_3D = 0x806F` (GL12)
- `GL_TEXTURE_CUBE_MAP = 0x8513` (bind target); face targets `GL_TEXTURE_CUBE_MAP_POSITIVE_X..NEGATIVE_Z` = `0x8515..0x851A` (upload targets)

## Failure-Atomic Allocation

Every `create()` / `createEmpty()` factory follows the
[`CgCoreFramebuffer.createFromSpec`](../framebuffer/CgCoreFramebuffer.java)
pattern:

1. `glGenTextures` upfront.
2. `try { upload + spec.applyTo(target) + (optional) CgTextureSpec.generateMipmaps(target); unbind; return new impl; }`
3. `catch (RuntimeException) { unbind; glDeleteTextures(id); throw; }`

This guarantees no GL ids leak on partial failure (e.g. an out-of-memory
during `glTexImage3D` for a large 2D-array).

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

If the spec has mipmaps enabled, the min/mag filters are overridden with the
mipmap-config filters after the base filters are written.

## Design Rules

- All GL constants live in `private static final int` at the top of each
  class. No raw hex in method bodies.
- Each class is `public final` so callers can invoke static factories and (for
  `CgTexture2D`) the `upload()` instance method directly.
- Never call `glGet*` here; the spec is the source of truth.
- Never re-use a deleted texture; `delete()` is fail-fast on second call.
