# Font Atlas Internals

This document describes how CrystalGraphics groups fonts into atlases, how glyphs are keyed, how atlas space is allocated, and how bitmap/MSDF glyph data is uploaded.

## 1. Atlas grouping model

Atlases are grouped by `CgFontKey`.

Code references:
- `src/main/java/io/github/somehussar/crystalgraphics/api/font/CgFont.java:136-171`
- `src/main/java/io/github/somehussar/crystalgraphics/gl/text/CgFontRegistry.java:38-41`
- `src/main/java/io/github/somehussar/crystalgraphics/gl/text/CgFontRegistry.java:59-75`

### What a `CgFontKey` represents
A `CgFontKey` identifies:
- font path or logical font name
- style
- target pixel size

That means atlas grouping is effectively:
- one bitmap atlas per `(font file/logical name, style, targetPx)`
- one MSDF atlas per `(font file/logical name, style, targetPx)`

This is managed in `CgFontRegistry` by two maps:
- `bitmapAtlases`
- `msdfAtlases`

See:
- `CgFontRegistry.java:38-41`
- `CgFontRegistry.java:59-75`

## 2. Glyph key model

Within one font bucket, glyphs are keyed by `CgGlyphKey`.

Code reference:
- `src/main/java/io/github/somehussar/crystalgraphics/api/font/CgGlyphKey.java:33-76`

A `CgGlyphKey` contains:
- `fontKey`
- `glyphId`
- `msdf`
- `subPixelBucket`

### Important semantic detail
`glyphId` is not a Unicode codepoint.
It is the shaped glyph index returned by HarfBuzz, which maps to the FreeType glyph index.

See:
- `CgGlyphKey.java:12-21`
- `src/main/java/io/github/somehussar/crystalgraphics/text/CgShapedRun.java:13-27`
- `src/main/java/io/github/somehussar/crystalgraphics/text/CgTextShaper.java:93-105`

### Sub-pixel buckets
For fonts with `targetPx <= 12`, the key may vary by sub-pixel bucket:
- `0` -> 0.00 px
- `1` -> 0.25 px
- `2` -> 0.50 px
- `3` -> 0.75 px

For fonts above `12px`, the bucket is normalized to `0`.

See:
- `CgGlyphKey.java:57-75`
- `CgTextRenderer.java:163-166`
- `CgTextRenderer.java:258-276`

## 3. Atlas object layout

Each `CgGlyphAtlas` instance owns exactly one GL texture.

Code reference:
- `src/main/java/io/github/somehussar/crystalgraphics/gl/text/CgGlyphAtlas.java:19-42`

Texture types:
- bitmap atlas: `GL_R8`
- MSDF atlas: `GL_RGB16F`

Creation path:
- `CgGlyphAtlas.create(...)`
- `CgFontRegistry.getBitmapAtlas(...)`
- `CgFontRegistry.getMsdfAtlas(...)`

See:
- `CgGlyphAtlas.java:124-169`
- `CgFontRegistry.java:59-75`

### Filter and wrap setup
Bitmap atlas:
- min filter = `GL_NEAREST`
- mag filter = `GL_NEAREST`

MSDF atlas:
- min filter = `GL_LINEAR`
- mag filter = `GL_LINEAR`

Both:
- `GL_CLAMP_TO_EDGE`

See:
- `CgGlyphAtlas.java:156-164`

## 4. Packing algorithm

CrystalGraphics uses a pure-Java max-rectangles atlas packer.

Code references:
- `src/main/java/io/github/somehussar/crystalgraphics/text/atlas/MaxRectsPacker.java:7-31`
- `src/main/java/io/github/somehussar/crystalgraphics/text/atlas/MaxRectsPacker.java:78-121`
- `src/main/java/io/github/somehussar/crystalgraphics/text/atlas/MaxRectsPacker.java:202-274`

### Heuristic
The packer uses MaxRects with Best Short-Side Fit.

Selection rule:
- choose the free rectangle that minimizes `min(freeW - w, freeH - h)`
- tie-break by the long side remainder

See:
- `MaxRectsPacker.java:84-118`

### Removal
When a glyph is evicted, its `PackedRect` is removed from the packer and the free area is returned.

See:
- `MaxRectsPacker.java:133-145`
- `CgGlyphAtlas.java:423-431`

## 5. LRU eviction

Atlas eviction is per glyph slot.

Code references:
- `CgGlyphAtlas.java:27-29`
- `CgGlyphAtlas.java:218-243`
- `CgGlyphAtlas.java:274-292`
- `CgGlyphAtlas.java:401-431`
- `CgGlyphAtlas.java:504-513`

### How it works
Each atlas slot stores:
- the packed rectangle
- the built `CgAtlasRegion`
- `lastUsedFrame`

On every cache hit:
- `lastUsedFrame` is updated

On allocation failure:
- the coldest slot is selected by minimum `lastUsedFrame`
- that slot is removed from the packer and `slotMap`
- insertion is retried

## 6. What is actually stored per glyph

The atlas texture stores the pixel payload.
The Java-side region object stores placement metadata.

Region type:
- `CgAtlasRegion`

Code references:
- `src/main/java/io/github/somehussar/crystalgraphics/api/font/CgAtlasRegion.java:5-18`
- `CgAtlasRegion.java:22-59`
- `CgGlyphAtlas.java:477-490`

Each region contains:
- atlas pixel rectangle: `atlasX`, `atlasY`, `width`, `height`
- normalized UVs: `u0`, `v0`, `u1`, `v1`
- original key
- bearings: `bearingX`, `bearingY`

Those bearings are used at draw time so the renderer can place the quad relative to the text pen and baseline.

## 7. Bitmap glyph generation path

Bitmap glyph generation is coordinated by `CgFontRegistry.ensureBitmapGlyph(...)`.

Code reference:
- `CgFontRegistry.java:129-169`

Pipeline:
1. choose bitmap atlas from `fontKey`
2. fast-path cache lookup via `atlas.get(key, currentFrame)`
3. set FreeType pixel size on the face
4. choose load flags
5. load glyph by glyph index
6. optionally apply `outlineTranslate(...)` for small-font sub-pixel buckets
7. render glyph with `FT_RENDER_MODE_NORMAL`
8. read `FTBitmap`
9. normalize pitch into tightly packed rows
10. read bearings from FreeType metrics
11. upload bytes through `atlas.getOrAllocate(...)`

### Pitch normalization
FreeType bitmaps may have a `pitch` that differs from width and may even be negative.
CrystalGraphics normalizes that before upload.

See:
- `CgFontRegistry.java:153-164`
- `CgFontRegistry.java:199-215`

### GL upload path for bitmap glyphs
See:
- `CgGlyphAtlas.java:436-455`

Upload format:
- `GL_RED`
- `GL_UNSIGNED_BYTE`
- unpack alignment forced to 1 for upload

## 8. MSDF glyph generation path

MSDF generation is coordinated by `CgMsdfGenerator`, reached from `CgFontRegistry.ensureMsdfGlyph(...)`.

Code references:
- `CgFontRegistry.java:171-188`
- `CgMsdfGenerator.java:47-96`

Pipeline:
1. choose MSDF atlas from `fontKey`
2. fast-path cache lookup via `atlas.get(key, currentFrame)`
3. lazily obtain `FreeTypeIntegration.Font` from `CgFont`
4. load glyph outline by glyph index
5. reject empty shapes
6. apply complexity gate
7. normalize shape
8. edge-color the shape
9. choose atlas cell size
10. allocate MSDF bitmap
11. auto-frame transform
12. generate MSDF
13. run error correction
14. compute bearings from transformed bounds
15. upload RGB float data through `atlas.getOrAllocateMsdf(...)`

### Complexity gate
The MSDF path is not used for every large glyph blindly.

Rules:
- if edge count is above threshold, require at least 48 px
- otherwise require at least 32 px

See:
- `CgMsdfGenerator.java:28-32`
- `CgMsdfGenerator.java:112-118`

### Per-frame MSDF budget
`CgMsdfGenerator` limits generation to `MAX_PER_FRAME = 4`.
If the budget is exhausted, it returns `null` and the caller falls back to bitmap.

See:
- `CgMsdfGenerator.java:28-30`
- `CgMsdfGenerator.java:51-53`
- `CgFontRegistry.java:178-187`

### GL upload path for MSDF glyphs
See:
- `CgGlyphAtlas.java:457-473`

Upload format:
- `GL_RGB`
- `GL_FLOAT`
- target texture internal format was allocated as `GL_RGB16F`

## 9. Where fonts and atlases are stored in memory

### Font storage
`CgFont` stores:
- `byte[] fontBytes`
- `FreeTypeLibrary ftLibrary`
- `FTFace ftFace`
- `HBFont hbFont`
- optional `FreeTypeIntegration msdfFtInstance`
- optional `FreeTypeIntegration.Font msdfFtFont`

See:
- `CgFont.java:49-64`

### Atlas storage
`CgGlyphAtlas` stores:
- one GL texture id
- one `MaxRectsPacker`
- one `slotMap` from `CgGlyphKey` to slot entry
- upload scratch buffers

See:
- `CgGlyphAtlas.java:85-97`
- `CgGlyphAtlas.java:109-120`
- `CgGlyphAtlas.java:504-513`

## 10. Cleanup behavior

### Font cleanup
Calling `CgFont.dispose()`:
- runs the registered dispose listener if present
- destroys MSDF objects first
- destroys HBFont, FTFace, and FreeType library after that

See:
- `CgFont.java:258-324`

### Registry cleanup
The registry hooks font disposal to atlas cleanup using `setDisposeListener(...)`.

See:
- `CgFontRegistry.java:116-127`
- `CgFontRegistry.java:87-114`

### Atlas cleanup
Each atlas owns its GL texture and deletes it in `delete()`.

See:
- `CgGlyphAtlas.java:337-369`

## 11. Summary

Atlas grouping is per `CgFontKey`, not per Unicode range or per style family globally.
Within a font bucket, glyphs are separated by:
- glyph index
- bitmap vs MSDF
- sub-pixel bucket for small fonts

The atlas data path is:
- shape or rasterize glyph
- pack rectangle through MaxRects
- upload pixel payload into one GL texture
- retain bearings and UVs in `CgAtlasRegion`
- reuse through `CgGlyphKey` cache hits
