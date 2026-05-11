# api/texture — CgTexture Public API

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../AGENTS.md)

## What This Package Is

Public, backend-agnostic API for owned GL texture objects and format descriptors.
After the Phase 1 refactor this package contains four types:
- `CgTexture` — the unified texture interface
- `CgTextureType` — typed format enum (replaces `CgTextureFormatSpec`)
- `CgTextureSpec` — immutable sampler specification built from `CgTextureType`
- `CgMipmapConfig` — mipmap policy (moved here from `api/` in Phase 1)

The concrete 2D / 2D-array / 3D / cubemap classes live in `gl/texture/` and `implements CgTexture`
directly.

## Type Map

| Type | Role |
|------|------|
| `CgTexture` | The single common interface: `bind()`, `bind(unit)`, `getId()`, `getTarget()`, `getWidth()/getHeight()`, `generateMipmaps()`, `delete()`, `isDeleted()`. |
| `CgTextureType` | Typed enum of ~42 GL format constants. Single source of truth for the (internalFormat, baseFormat, type) triple. Used by `CgTextureSpec` and `CgFrameBufferFormat` (Phase 2). |
| `CgTextureSpec` | Immutable Lombok `@Builder` describing format (`CgTextureType`), mipmap policy (`CgMipmapConfig`), filter, wrap, and optional shadow comparison. Pre-built constants: `RGBA8_LINEAR`, `RGBA8_NEAREST`, `RGBA16F_LINEAR`, `DEPTH24_SHADOW`, and more. |
| `CgMipmapConfig` | Mipmap policy: whether to generate mipmaps and which filters to use. Named constants: `NONE`, `TRILINEAR`, `NEAREST`. |

## CgTextureType

Single source of truth for all GL format descriptions. Used for regular textures
(`CgTextureSpec.builder().type(CgTextureType.RGBA8)`) and FBO attachments in Phase 2.

### Constant Groups

| Group | Constants |
|-------|-----------|
| 8-bit color | `R8`, `R8_SNORM`, `R8I`, `R8UI`, `RG8`, `RG8I`, `RG8UI`, `RGBA8`, `RGBA8_SNORM`, `RGBA8I`, `RGBA8UI`, `SRGB8_ALPHA8` |
| 16-bit color | `R16F`, `R16I`, `R16UI`, `RG16F`, `RG16I`, `RG16UI`, `RGBA16F`, `RGBA16I`, `RGBA16UI` |
| 32-bit color | `R32F`, `R32I`, `R32UI`, `RG32F`, `RG32I`, `RG32UI`, `RGBA32F`, `RGBA32I`, `RGBA32UI` |
| Packed / special | `RGB10_A2`, `RGB10_A2UI`, `R11F_G11F_B10F`, `RGBA4`, `RGB5_A1` |
| Depth | `DEPTH16`, `DEPTH24`, `DEPTH32F` |
| Depth + stencil | `DEPTH24_STENCIL8`, `DEPTH32F_STENCIL8` |
| Stencil-only | `STENCIL8` |

### Public Fields

```java
public final int     glInternalFormat;  // e.g. GL_RGBA8
public final int     glBaseFormat;      // e.g. GL_RGBA
public final int     glType;            // e.g. GL_UNSIGNED_BYTE
public final boolean isDepth;
public final boolean isStencil;
```

### Key Methods

- `isColor()` — `!isDepth && !isStencil`
- `glAttachmentPoint(int colorIndex)` — returns `GL_DEPTH_STENCIL_ATTACHMENT`, `GL_DEPTH_ATTACHMENT`, `GL_STENCIL_ATTACHMENT`, or `GL_COLOR_ATTACHMENT0 + colorIndex`
- `toTextureSpec()` — builds a neutral `CgTextureSpec` with clamp-to-edge, correct default filter (NEAREST for integer formats, LINEAR otherwise), **no shadow compare mode**
- `isRenderable()` — Phase 1 stub returning `true`; replaced by `CgFrameBuffer.probeRenderable(this)` in Phase 2

### Usage

```java
CgTexture2D hdr    = CgTexture2D.createEmpty(512, 512, CgTextureType.RGBA16F.toTextureSpec());
CgTexture2D shadow = CgTexture2D.createEmpty(512, 512, CgTextureSpec.DEPTH24_SHADOW);
```

## CgTextureSpec

Immutable Lombok `@Builder(toBuilder = true)` class. Zero hex literals — all GL values
come from LWJGL imports.

### Key Fields

| Field | Type | Default |
|-------|------|---------|
| `type` | `CgTextureType` | **required** — no `@Builder.Default` |
| `mipmaps` | `CgMipmapConfig` | `CgMipmapConfig.NONE` |
| `minFilter` | `int` | `GL11.GL_LINEAR` |
| `magFilter` | `int` | `GL11.GL_LINEAR` |
| `wrapS/T/R` | `int` | `GL12.GL_CLAMP_TO_EDGE` |
| `compareMode` | `int` | `GL11.GL_NONE` (disabled) |
| `compareFunc` | `int` | `GL11.GL_LEQUAL` |

### Convenience Delegates

These are explicit methods (not Lombok-generated fields):
```java
public int getGlInternalFormat() { return type.glInternalFormat; }
public int getGlBaseFormat()     { return type.glBaseFormat; }
public int getGlType()           { return type.glType; }
```
These exist so gl/texture/ call sites (`spec.getGlInternalFormat()`) remain stable.

### Pre-built Constants

```java
RGBA8_LINEAR, RGBA8_NEAREST
RGBA8_LINEAR_CLAMP_BORDER, RGBA8_NEAREST_CLAMP_BORDER
RGBA8_LINEAR_REPEAT, RGBA8_NEAREST_REPEAT
RGBA8_LINEAR_MIRRORED, RGBA8_NEAREST_MIRRORED
R8_LINEAR_REPEAT, R8_NEAREST_REPEAT
RGBA16F_LINEAR
DEPTH24_SHADOW   // ONLY constant with compareMode = GL_COMPARE_R_TO_TEXTURE
```

`DEPTH24_SHADOW` is the **only** preset with compare mode enabled. `CgTextureType.toTextureSpec()` always returns a neutral spec (no compare mode):

```java
CgTextureSpec.DEPTH24_SHADOW              // PCF shadow sampling
CgTextureType.DEPTH24.toTextureSpec()     // neutral depth spec (no compare)
```

### GL Helper

`applyTo(int target)` — writes filter, wrap, shadow compare, and mipmap params to the currently bound texture.

## CgMipmapConfig

Moved from `api/` to `api/texture/` in Phase 1. Controls mipmap generation for textures.

**`levels` field removed** — `glGenerateMipmap` builds a full chain automatically.

### Named Constants

| Constant | Enabled | minFilter | magFilter |
|----------|---------|-----------|-----------|
| `NONE` | false | LINEAR | LINEAR |
| `TRILINEAR` | true | `GL_LINEAR_MIPMAP_LINEAR` | `GL_LINEAR` |
| `NEAREST` | true | `GL_NEAREST_MIPMAP_NEAREST` | `GL_NEAREST` |

### Factory

- `enabled(int minFilter, int magFilter)` — custom filter combination
- `disabled()` — `@Deprecated` alias returning `NONE` (migration only)

### Usage

```java
CgTextureSpec spec = CgTextureSpec.builder()
    .type(CgTextureType.RGBA8)
    .mipmaps(CgMipmapConfig.TRILINEAR)
    .build();
```

## Concrete Impls (live in `gl/texture/`)

| Class | Role |
|-------|------|
| `gl.texture.CgTexture2D` | Single 2D image (`GL_TEXTURE_2D`). Static factories `create(path)`, `create(path, spec)`, `createEmpty(w, h, spec)`. Supports `upload(w, h, pixels)` re-upload. |
| `gl.texture.CgTexture2DArray` | Stack of N same-size 2D images (`GL_TEXTURE_2D_ARRAY`). `create(spec, paths...)`, `create(paths...)`. |
| `gl.texture.CgTexture3D` | Volumetric (`GL_TEXTURE_3D`). `create(spec, paths...)`, `create(paths...)`. |
| `gl.texture.CgTextureCubemap` | Cubemap (`GL_TEXTURE_CUBE_MAP`). `create(spec, posX...negZ)`, `createEmpty(size, spec)`. |

## Ownership Model

- A `CgTexture` instance owns exactly one GL texture id.
- `delete()` releases it. After deletion the texture is unusable.
- Failure during creation never leaks GL ids — impls wrap allocation in try/catch and delete on rethrow.

## Relationship to Other Packages

- **gl/texture** holds the concrete impls. They `implements CgTexture` directly.
- **api/framebuffer/CgTextureFormatSpec** — Phase 2 delete. Absorbed into `CgTextureType`.
- **api/shader/CgShaderBindings** has `sampler2D(String, int, CgTexture)` for binding.
- **api/state/CgTextureState** has `fixed(CgTexture, unit, sampler)` for layer state.

## Design Rules

- Public API surface: one interface (`CgTexture`) + one format enum (`CgTextureType`) + one spec (`CgTextureSpec`) + one mipmap policy (`CgMipmapConfig`).
- All concrete texture creation goes through static factories on impl classes in `gl/texture/`.
- Never call `glGet*`; spec params are recorded at upload time and never queried back.
- Filter/wrap writes are centralized on `CgTextureSpec.applyTo(target)`.
- `CgTextureType` constants: zero hex, all GL values from LWJGL imports.
- `CgTextureSpec` constants: zero hex, all built via `CgTextureType.X.toTextureSpec()` or `builder().type(...)`.


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
