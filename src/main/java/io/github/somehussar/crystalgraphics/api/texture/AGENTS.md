# api/texture — CgTexture Public API

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../AGENTS.md)

## What This Package Is

Public, backend-agnostic API for owned GL texture objects. After the V2
collapse, this package contains exactly **two** types: the unified
`CgTexture` interface and the immutable `CgTextureSpec` value. The concrete
2D / 2D-array / 3D classes now live in `gl/texture/` and `implements CgTexture`
directly — there are no longer thin per-target sub-interfaces.

## Type Map

| Type | Role |
|------|------|
| `CgTexture` | The single common interface: `bind()`, `bind(unit)`, `getId()`, `getTarget()`, `getWidth()/getHeight()`, `generateMipmaps()`, `delete()`, `isDeleted()`. |
| `CgTextureSpec` | Immutable Lombok `@Builder` describing pixel format (via `CgTextureFormatSpec`), mipmap policy (via `CgMipmapConfig`), filter, and wrap. Pre-built constants: `RGBA8_LINEAR`, `RGBA8_NEAREST`, `RGBA16F_LINEAR`. Also exposes GL helpers `applyTo(target)` and static `generateMipmaps(target)` used by the impls. |

## Concrete Impls (live in `gl/texture/`)

| Class | Role |
|-------|------|
| `gl.texture.CgTexture2D` | Single 2D image (`GL_TEXTURE_2D`). Static factories `create(path)`, `create(path, spec)`, `createEmpty(w, h, spec)`. Supports `upload(w, h, pixels)` re-upload. |
| `gl.texture.CgTexture2DArray` | Stack of N same-size 2D images (`GL_TEXTURE_2D_ARRAY`). `create(spec, paths...)`, `create(paths...)`. |
| `gl.texture.CgTexture3D` | Volumetric (`GL_TEXTURE_3D`). `create(spec, paths...)`, `create(paths...)`. Linear filtering across the R axis (unlike a 2D-array). |

## Ownership Model

- A `CgTexture` instance owns exactly one GL texture id.
- `delete()` releases it. After deletion the texture is unusable; any further
  bind throws `IllegalStateException`.
- Failure during creation never leaks GL ids — the impls always wrap allocation
  in try/catch and call `glDeleteTextures` on the partially-allocated id before
  re-throwing.

## Usage

```java
import io.github.somehussar.crystalgraphics.gl.texture.CgTexture2D;

CgTexture2D t = CgTexture2D.create("textures/atlas.png");
t.bind(0);                  // GL_TEXTURE0 + 0
shader.applyBindings(b -> b.sampler2D("u_atlas", 0, t));
// ... draw ...
t.delete();
```

Variables that don't need `upload()` should be typed as the interface:

```java
CgTexture t = CgTexture2D.create("textures/atlas.png");
```

## Relationship to Other Packages

- **gl/texture** holds the concrete impls (`CgTexture2D`, `CgTexture2DArray`, `CgTexture3D`). They are public so callers can invoke their static factories and (for 2D) the `upload()` instance method.
- **api/framebuffer/CgTextureFormatSpec** describes the pixel format triple (internalFormat, pixelFormat, pixelType). `CgTextureSpec.format` reuses it.
- **api/CgMipmapConfig** describes mipmap policy. `CgTextureSpec.mipmaps` reuses it.
- **util/io/CgIO.openStream** is the canonical asset-path stream opener used by `util/io/CgTextureIO`.
- **util/io/CgTextureIO** decodes images into direct RGBA `ByteBuffer`s using `CgIO.openStream`. The `create(path, ...)` factories use it.
- **api/shader/CgShaderBindings** has `sampler2D(String, int, CgTexture)` and `sampler2DRaw(String, int, int[, target])` for binding raw GL texture ids.
- **api/state/CgTextureState** has `fixed(CgTexture, unit, sampler)` and `fixed(target, id, unit, sampler)` for layer state composition.

## Design Rules

- Public API surface here is intentionally minimal: one interface + one spec.
  All concrete texture creation goes through static factories on the impl
  classes in `gl/texture/`.
- Never call `glGet*`; spec params are recorded at upload time and never
  queried back from GL.
- Filter / wrap parameter writes are centralized on `CgTextureSpec.applyTo(target)`
  and the GL30 mipmap probe is centralized on `CgTextureSpec.generateMipmaps(target)`.
