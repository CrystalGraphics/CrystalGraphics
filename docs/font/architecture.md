# Font Rendering Architecture

## Overview

The font system is split into four layers:

1. font resource layer
2. CPU layout layer
3. atlas and glyph generation layer
4. rendering layer

## Three-Space Model

The text rendering pipeline enforces a strict separation of three coordinate
spaces, following the same principle as CSS Transforms (W3C CSS Transforms
Module Level 1, §3): transforms are applied _after_ layout and do not mutate
layout flow or spacing.

### 1. Logical Layout Space

The canonical space for all text shaping, layout, and metric queries.

- **Units**: logical pixels, defined by `CgFontKey.targetPx` (the base font
  size requested at load time).
- **What lives here**: HarfBuzz advances, kerning, offsets, line metrics,
  `CgTextLayout` width/height, caret positions, line break decisions.
- **Invariant**: UI scale, PoseStack transforms, and camera distance/FOV must
  _never_ change any value in this space. Logical metrics are set once during
  shaping and are immutable at draw time.
- **Owning types**: `CgShapedRun` (advancesX, offsetsX, offsetsY,
  totalAdvance), `CgTextLayout` (lines, totalWidth, totalHeight),
  `CgFontMetrics` (ascender, descender, lineHeight), `CgFontKey` (targetPx).

### 2. Physical Raster Space

The space of actual glyph bitmaps/MSDF textures as rasterized by FreeType
or MSDFgen.

- **Units**: physical raster pixels at the _effective_ target size.
- **What lives here**: `CgAtlasRegion` bearingX, bearingY, width, height;
  atlas UV coordinates; bitmap/MSDF pixel data.
- **Determining factor**: `effectiveTargetPx = baseTargetPx × poseScale`,
  resolved by `CgTextScaleResolver` at draw time.
- **Invariant**: Physical raster data must _never_ be used as canonical
  placement metrics. Before combining with logical pen positions, the renderer
  must normalize physical bearings/extents back into logical space using
  `scaleFactor = baseTargetPx / effectiveTargetPx`.
- **Owning types**: `CgAtlasRegion` (all metric fields), `CgRasterFontKey`
  (effectiveTargetPx), `CgRasterGlyphKey`, `CgGlyphAtlas`.

### 3. Composite Space

The final GPU coordinate space after PoseStack and projection transforms.

- **Units**: clip-space coordinates (after projection) or screen pixels (after
  viewport transform).
- **What lives here**: the model-view matrix from `PoseStack.last().pose()`,
  the projection matrix from `CgTextRenderContext`, and the resulting
  transformed vertex positions.
- **Determining factor**: PoseStack (model-view) × projection matrix,
  uploaded as `u_modelview` and `u_projection` shader uniforms.
- **Contract**: The PoseStack in 2D UI mode represents logical→physical UI
  scale. In 3D world mode, it represents model-view positioning (entity
  rotation, billboard transforms), not UI zoom.

### Metric Normalization at the Renderer Boundary

The renderer (`CgTextRenderer.appendQuads(...)`) is the single site where
logical pen positions meet physical atlas metrics. Normalization uses:

```
scaleFactor = baseTargetPx / (float) effectiveTargetPx
logicalBearingX = physicalBearingX × scaleFactor
logicalBearingY = physicalBearingY × scaleFactor
logicalWidth    = physicalWidth    × scaleFactor
logicalHeight   = physicalHeight   × scaleFactor
```

This keeps spacing and kerning invariant under UI scale while still allowing
larger physical glyph rasters to be selected for sharper output.

### What the Old Model Got Wrong

The previous mixed-metric design (superseded by this contract) allowed the
effective target pixel size to directly drive placement metrics, causing
physical-size bearings and extents to be combined with logical pen positions
without normalization. This produced visible spacing/kerning corruption
whenever UI scale differed from 1.0x.

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
- `WORLD` — always-MSDF resolver for 3D world-space text, ignores PoseStack
  scale (treats it as model-view positioning, not UI zoom), uses an optional
  projected-size hint from `ProjectedSizeEstimator` for quality/LOD tier selection

### GL State

The renderer:
- resolves glyph regions through `CgFontRegistry`
- writes bitmap quads first, MSDF quads second
- binds the corresponding atlas and shader for each pass
- uploads both `u_projection` and `u_modelview` to each shader
- restores GL state through `CgStateBoundary`

### Placement Metric Ownership (Three-Space Contract)

`CgTextRenderer` treats shaped pen positions as canonical **logical** metrics.
`CgAtlasRegion` exposes **physical** raster metrics captured at the atlas entry's
effective target pixel size. Before the renderer combines atlas-region bearings
or extents with logical pen positions, it normalizes them into logical space with:

`scaleFactor = baseTargetPx / effectiveTargetPx`

See the Three-Space Model section above for the full contract. The normalization
site is `CgTextRenderer.logicalMetricScale()`, called from `appendQuads()`.

This keeps spacing and kerning invariant under UI scale while still allowing
larger physical glyph rasters to be selected for sharper output. `CgFontRegistry`
retains physical-only metrics — normalization happens exclusively at the
renderer/composite boundary.

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

## 5. World-Space Text (3D)

### Contract

World-space text uses a separate entry point (`CgTextRenderer.drawWorld()`) with a
`CgWorldTextRenderContext` that enforces:

- **Always MSDF**: `WorldTextScaleResolver.shouldUseMsdf()` always returns `true`.
  No bitmap fallback, no subpixel bucket logic.
- **Depth testing enabled**: World text renders with `GL_DEPTH_TEST` on and
  `glDepthMask(true)`, so it occludes and is occluded by world geometry.
- **Back-face culling enabled**: Text is treated as a single-sided surface.
- **PoseStack = model-view positioning**: In world mode, PoseStack scale represents
  entity rotation, billboard transforms, etc. — not UI zoom. It does not drive
  raster tier selection.
- **Projection-aware quality/LOD**: `ProjectedSizeEstimator` estimates on-screen
  pixel coverage from MVP + viewport to select higher-quality MSDF atlas tiers
  for close-up text.

### Layout Invariance

Layout metrics (advances, kerning, line breaks, caret positions) remain in logical
space. Camera distance, FOV, and viewport changes may alter the MSDF atlas tier
(quality) but never alter spacing or line breaks.

### API Usage

```java
// Create a world-text context with perspective projection
Matrix4f persp = new Matrix4f().perspective(fov, aspect, near, far);
CgWorldTextRenderContext worldCtx = CgWorldTextRenderContext.create(persp, vpWidth, vpHeight);

// Optionally update quality hint before each draw
worldCtx.updateProjectedSize(modelView, persp, font.getKey().getTargetPx());

// Draw using the world-text entry point
renderer.drawWorld(layout, font, x, y, rgba, frame, worldCtx, poseStack);
```

### Key Differences from 2D UI Text

| Concern | 2D UI Text | 3D World Text |
|---------|-----------|---------------|
| Entry point | `draw()` | `drawWorld()` |
| Context | `CgTextRenderContext` | `CgWorldTextRenderContext` |
| Resolver | `ORTHOGRAPHIC` | `WORLD` (always MSDF) |
| Depth test | OFF | ON |
| Cull face | OFF | ON |
| PoseStack scale | drives raster tier | model-view only |
| Backend | bitmap or MSDF | MSDF only |
| Quality policy | hysteresis-based | projection-aware |
