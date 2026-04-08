# text/render — Agent Guide

## Package role

This package owns the **draw-time side** of the text pipeline.

Its job begins after a layout exists and after the renderer can ask the cache for glyph placements.

The core question here is:

> given a `CgTextLayout` and atlas placements, how do we produce stable, efficient draw calls?

## Reading order

1. `CgTextRenderer`
2. `CgTextRenderContext`
3. `CgWorldTextRenderContext`
4. `CgTextScaleResolver`
5. `OrthographicScaleResolver`
6. `PerspectiveScaleResolver`
7. `ProjectedSizeEstimator`
8. `CgDrawBatchKey`
9. `CgDrawBatch`
10. `CgGlyphVbo`

## Class-by-class details

### `CgTextRenderer`

Top-level render façade and the most important file in this package.

Main responsibilities:

- string/layout draw entrypoints
- resolving effective raster tier for the current draw
- asking `CgFontRegistry` for glyph placements
- building paged glyph batches
- grouping placements into `CgDrawBatch` ranges
- emitting quads into `CgGlyphVbo`
- resolving shaders from `CgDrawBatchKey`
- binding textures/uniforms and issuing draw calls

If you want the string → draw path, this is the file to trace.

### `CgTextRenderContext`

General render context.

Owns:

- current projection matrix (`Matrix4f`)
- scale resolver
- per-font history used for raster-tier hysteresis

Important note:

- this is not just a bag of matrices
- it also stores draw-history state that affects raster-tier stability

### `CgWorldTextRenderContext`

World-space specialization of the render context.

Adds:

- viewport dimensions
- projection update path
- projected-size hint updates
- world-text semantics (`isWorldText() == true`)

This class is the main place to look for 3D text behavior differences.

### `CgTextScaleResolver`

Strategy interface for deciding effective raster size and distance-field thresholds.

### `OrthographicScaleResolver`

2D/UI resolver.

Uses UI/pixel-scale semantics rather than camera/projected-size semantics.

### `PerspectiveScaleResolver`

World-text resolver.

This is where projected-size-aware raster-tier behavior lives.

### `ProjectedSizeEstimator`

Math helper for estimating on-screen size of world text.

It matters only for world-space raster-tier decisions, not for logical layout metrics.

### `CgDrawBatchKey`

Immutable grouping key for draw batches.

Defines when two glyph ranges can share a draw call based on:

- atlas mode
- texture id
- `pxRange`

It is also the authoritative source of shader selection in the current renderer.

### `CgDrawBatch`

Contiguous VBO range sharing one `CgDrawBatchKey`.

Think of it as one future `glDrawElements` call.

### `CgGlyphVbo`

Owner of the glyph VAO/VBO/IBO setup.

This is the final CPU-side staging point before GL submission.

### `package-info.java`

Package-level description of render-side responsibilities.

## Key invariants

- renderer consumes placements; it does not own glyph generation
- layout remains in logical space; raster tier is a draw-time physical decision
- `CgDrawBatchKey` drives shader selection
- world-text and 2D text share most of the pipeline until raster-tier / projection policy differs

## Common agent mistakes to avoid

- Do not reintroduce cache or atlas policy into `CgTextRenderer`.
- Do not let world-text docs drift away from actual `PerspectiveScaleResolver` behavior.
- Do not reintroduce raw shader-program plumbing when `CgShader`/bindings already own uniform handling.
