# Font Rendering Architecture

## Overview

The font system is split into four layers:

1. font resource layer
2. CPU layout layer
3. atlas and glyph generation layer
4. rendering layer

## 1. Font resource layer

`CgFont` owns the native font handles for one font at one target pixel size.

It maintains:
- FreeType library + `FTFace` for bitmap rasterization
- HarfBuzz `HBFont` for shaping
- lazy `FreeTypeIntegration.Font` for MSDF generation

Disposal is explicit through `CgFont.dispose()`.

## 2. CPU layout layer

`CgTextLayoutBuilder` runs the full text pipeline:
- Java `Bidi` paragraph split
- HarfBuzz shaping through `CgTextShaper`
- line breaking through `CgLineBreaker`
- final immutable layout output as `CgTextLayout`

`CgShapedRun` stores glyph ids, cluster ids, advances, and offsets in pixels.

## 3. Atlas and glyph generation

`CgFontRegistry` is the render-thread cache and runtime coordinator.

For each `CgFontKey` it manages:
- one bitmap atlas
- one MSDF atlas

### Bitmap path
- FreeType loads glyph by glyph index
- optional `outlineTranslate` is applied for small-font sub-pixel buckets
- glyph is rendered to grayscale bitmap
- FreeType pitch is normalized into tightly packed rows
- pixels are uploaded into `CgGlyphAtlas`

### MSDF path
- glyph outline is loaded through msdfgen bindings
- complexity gate decides whether MSDF is worthwhile
- MSDF bitmap is generated into RGB float data
- data is uploaded into MSDF atlas
- if generation is skipped or budget is exhausted, bitmap fallback is used

## 4. Rendering layer

`CgTextRenderer` performs two-pass rendering:
- pass 1: bitmap glyphs
- pass 2: MSDF glyphs

Quads are written into `CgGlyphVbo`.

The renderer:
- resolves glyph regions through `CgFontRegistry`
- writes bitmap quads first
- writes MSDF quads second
- binds the corresponding atlas and shader for each pass
- restores GL state through `CgStateBoundary`

## Sub-pixel bucket behavior

For fonts at `<= 12px`, `CgGlyphKey` supports four x-offset buckets:
- 0 -> 0.00 px
- 1 -> 0.25 px
- 2 -> 0.50 px
- 3 -> 0.75 px

For fonts above `12px`, the bucket is normalized to `0`.

## Atlas texture formats

Bitmap atlas:
- internal format: `GL_R8`
- filtering: nearest

MSDF atlas:
- internal format: `GL_RGB16F`
- filtering: linear

## Important constraint

The current implementation does not provide a legacy GL text fallback renderer. The renderer intentionally gates itself to the modern GL feature set it actually uses.
