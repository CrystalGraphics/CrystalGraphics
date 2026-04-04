# Font Rendering Architecture

## Overview

The font system is split into four layers:

1. font resource layer
2. CPU layout layer
3. atlas and glyph generation layer
4. rendering layer

## Three-Unit Size Model

The text rendering pipeline uses three distinct pixel-size concepts:

1. **Logical layout pixels** — the coordinate space used by `CgTextLayout` for
   width, height, line breaking, and glyph advances. These never change based
   on draw-time transforms.
2. **Base target pixels** (`CgFontKey.targetPx`) — the font size requested at
   load time. Determines the base rasterization size and is part of the
   font/layout cache identity.
3. **Effective target pixels** — the actual raster size used for glyph rendering
   at draw time, derived from `baseTargetPx × poseScale` via `CgTextScaleResolver`.
   Determines which atlas/cache bucket serves the glyphs.

Layout and shaping operate in logical/base pixels. The renderer resolves the
effective size at draw time from the PoseStack transform without modifying
layout metrics.

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

Layout classes (`CgTextLayoutBuilder`, `CgTextLayout`, `CgShapedRun`, `CgFontKey`)
do not contain any PoseStack-derived state or draw-time transform information.

## 3. Atlas and glyph generation

`CgFontRegistry` is the render-thread cache and runtime coordinator.

For each `CgFontKey` (base identity) it manages:
- one bitmap atlas
- one MSDF atlas

For each `CgRasterFontKey` (effective-size identity) it manages:
- additional bitmap/MSDF atlas buckets when text is rendered under pose transforms

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

### Projection and Context Model

The renderer consumes a `CgTextRenderContext` that holds:
- the projection matrix (set once, updated on viewport resize)
- the `CgTextScaleResolver` strategy for deriving effective size

This replaces the old per-draw `FloatBuffer projectionMatrix` parameter.

### PoseStack Integration

The renderer accepts a `PoseStack` at draw time and:
- derives the effective physical glyph raster size via the scale resolver
- uploads the model-view matrix from `PoseStack.last().pose()` as `u_modelview`
- uses the stabilized effective size for backend (bitmap/MSDF) selection

### Backend Selection

The renderer uses stabilized effective size with hysteresis for backend choice:
- enter MSDF at effective size >= 33
- return to bitmap at effective size <= 31
- between 31 and 33, retain the previous backend

### Scale Resolver

`CgTextScaleResolver` is a strategy interface with:
- `ORTHOGRAPHIC` — the shipped default for 2D/UI text, uses `max(|sx|, |sy|)`
  from the pose matrix basis vectors
- future `PerspectiveScaleResolver` — extension seam for world-space text that
  would estimate on-screen pixel coverage from view distance and FOV

### GL State

The renderer:
- resolves glyph regions through `CgFontRegistry`
- writes bitmap quads first, MSDF quads second
- binds the corresponding atlas and shader for each pass
- uploads both `u_projection` and `u_modelview` to each shader
- restores GL state through `CgStateBoundary`

## Sub-pixel bucket behavior

Sub-pixel buckets use the **effective** target pixel size (not the base).
For effective sizes below 32, `CgGlyphKey` supports four x-offset buckets:
- 0 -> 0.00 px
- 1 -> 0.25 px
- 2 -> 0.50 px
- 3 -> 0.75 px

For effective sizes at or above 32, the bucket is normalized to 0.

## Atlas texture formats

Bitmap atlas:
- internal format: `GL_R8`
- filtering: nearest

MSDF atlas:
- internal format: `GL_RGB16F`
- filtering: linear

## Important constraint

The current implementation does not provide a legacy GL text fallback renderer. The renderer intentionally gates itself to the modern GL feature set it actually uses.
