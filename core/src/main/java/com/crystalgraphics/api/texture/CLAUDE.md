# api/texture — CgTexture Public API

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../AGENTS.md)

## What This Package Is

Public, backend-agnostic API for textures: the interface, the one-call front door, and how a binding samples. A format is
a `CgFormat`; the GL triple behind one (`CgTextureType`) is `gpu/resource`'s. The concrete 2D / 2D-array / 3D / cubemap classes live in
`gpu/resource/` and implement `CgTexture` directly; a file's settings are `gpu/resource/CgTextureSettings`.

## Type Map

| Type | Role |
|------|------|
| `CgTexture` | The single common interface: `getWidth()/getHeight()`, `sampler()` (its own sampling, where a binding names none), `delete()`, `isDeleted()`, `isMissing()` (its files would not load). A texture from files loads at its first use: `gpu/resource/CLAUDE.md`. |
| `CgTextures` | The front door. From files: `of(path)` 2D, `frames(pattern, count)` / `frames(paths...)` 2D array, `volume(pattern, count)` / `volume(paths...)` 3D, `cube(path)` six faces stacked `w x 6w`, `cube(six paths)`. Made: the same four with `(name, CgTextureDesc)`; `target(name, colour, depth)`. A `.hdr`/`.exr` path loads as RGBA16F. Consumer guide: `docs/TEXTURES.md`. |
| `CgSampler` | How a texture is sampled: `Filter` (`NEAREST`, `BILINEAR`, `TRILINEAR`), `Address` per axis (`WRAP`, `CLAMP`, `MIRROR`, `BORDER`), `compare` (`CgCompare`, a shadow map's), `anisotropy(n)`. A value; `builder()`, presets (`BILINEAR_CLAMP`, `TRILINEAR_WRAP`, `NEAREST_BORDER`, `SHADOW`, ...). The device's sampler objects are `render/draw/CgDeviceSamplers`'. |

## Sampling: three places, one winner

A texture is sampled by, first to last:

1. **The binding's sampler**, where it names one: `b.sampler("_Mask", 0, MASK, CgSampler.NEAREST_CLAMP)` in
   `applyProperties`, a definition builder's `texture(property, texture, filter, address)`, a material file's
   `sampler:` block.
2. **The texture's own** (`sampler()`):
   - **a file's**: its settings file's (`noise.png`'s `noise.yaml`, `CgTextureSettings`), else plain — bilinear and
     clamped, one level. Shared by every caller of the path, so it is never set in code;
   - **a made one's**: by its format and levels — nearest for an integer format, trilinear across several levels,
     else bilinear; clamped. Its maker may change it: `.sampler(CgSampler)`, which returns the texture to chain.

```java
// A file: its settings file says how it samples
static final CgTexture2D NOISE = CgTextures.of("mymod:textures/fx/noise.png");
// mymod/textures/fx/noise.yaml:   sampler: { filter: bilinear, wrap: repeat }

// A made texture: the default, or its own
CgTexture2D lut = CgTextures.of("mymod.lut", CgTextureDesc.of(256, 256, CgFormat.RGBA8_UNORM));
CgTexture2D tiles = CgTextures.of("mymod.tiles", CgTextureDesc.of(64, 64, CgFormat.RGBA8_UNORM)).sampler(CgSampler.NEAREST_CLAMP);

// Anything, sampled another way at one binding
material.applyProperties(b -> b.sampler("_Noise", 0, NOISE, CgSampler.NEAREST_CLAMP));
```

- `.sampler(...)` on a file's texture throws: say it in the settings file, or at the binding.
- Code that loads a file its own way passes settings: `CgTexture2D.create(path, CgTextureSettings.PLAIN.withMips(true)
  .withSampler(CgSampler.TRILINEAR_CLAMP))` — cached by the path and those settings.
- A file's chain of levels comes from its settings (`mips`), never from its sampler.

## Concrete Impls (live in `gpu/resource/`)

| Class | Role |
|-------|------|
| `CgTexture2D` | One 2D image. `create(path[, settings])`, `create(name, desc)`; `write(...)` regions. |
| `CgTexture2DArray` | A stack of 2D images, smaller ones centred. `create(paths...)`, `create(name, desc)`; `uploadLayerRegion`, `growLayers`. |
| `CgTexture3D` | A volume. `create(paths...)`, `create(name, desc)`; `uploadRegion`, `generateMipmaps`. |
| `CgTextureCubemap` | A cube. `create(six paths)`, `createStacked(path[, settings])`, `create(name, desc)`; `uploadFace`. |

Each has a covariant `sampler(CgSampler)`, and a made one `release()`. `CgTextures` calls them: `of`, `frames`,
`volume` and `cube`, each with paths or with `(name, CgTextureDesc)`. A description states its kind
(`CgTextureDesc.of`/`array`/`volume`/`cube`), so a one-slice volume or a one-layer array is one; a description of
another kind than the call's throws.

## Relationship to Other Packages

- **gpu/resource** holds the concrete impls, the cache (`CgTextureManager`) and `CgTextureSettings`.
- **api/material** binds them: `CgMaterialValues.sampler(String, int, CgTexture[, CgSampler])`; a material
  definition's texture loads through `CgMaterialRegistry.texture(path)`, which takes a texture asset's defaults
  (sRGB, mips, trilinear, repeated) where a file has no settings.

## Design Rules

- No texture factory takes a sampler: a file's is its settings', a made one's its default or its `.sampler(...)`, and
  anything else is the binding's.
- All concrete texture creation goes through `CgTextures` or the static factories in `gpu/resource/`.
- `CgTexture.sampler()` returns the same object each call: `CgDeviceSamplers` caches device samplers by identity.

---

Also loaded with this folder:

@../../../../../../../../docs/ENGINE_API.md
