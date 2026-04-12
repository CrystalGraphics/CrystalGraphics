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

## Class-by-class details

### `CgTextRenderer`

Top-level render façade and the most important file in this package.

Main responsibilities:

- string/layout draw entrypoints
- resolving effective raster tier for the current draw
- asking `CgFontRegistry` for glyph placements
- building paged glyph batches
- sorting placements by `CgDrawBatchKey`
- submitting quads through `CgDynamicTextureRenderLayer`
- resolving shaders from `CgDrawBatchKey`
- swapping layer render state and texture on batch-key transitions

The renderer does **not** own any GL objects (VAOs, VBOs, IBOs). All GPU
resources are managed through `CgVertexArrayRegistry` and `CgQuadIndexBuffer`,
accessed via the layer's `CgBatchRenderer`.

The canonical `CgTextRenderer.create(caps, registry)` does not create any backend
infrastructure (no batch, no VAO, no VBO). It only validates backend availability and
creates the façade. Layer/buffer-source ownership belongs to the caller.

All `draw()` and `drawWorld()` methods require a caller-provided
`CgDynamicTextureRenderLayer`. The layer is expected to already be in the
"begun" state (managed by the owning `CgBufferSource`). Text layer factories
live in `gl/text/CgTextLayers`.

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

### `package-info.java`

Package-level description of render-side responsibilities.

## Key invariants

- renderer consumes placements; it does not own glyph generation
- layout remains in logical space; raster tier is a draw-time physical decision
- `CgDrawBatchKey` drives shader selection
- world-text and 2D text share most of the pipeline until raster-tier / projection policy differs
- the renderer owns NO GL objects — all GPU resources come from the shared layer/batch infrastructure
- all `draw()` and `drawWorld()` overloads require a `CgDynamicTextureRenderLayer` — there is no self-contained draw path
- GL state is managed by the layer's `CgRenderState`, not by the renderer
- the layer is expected to already be "begun" (managed by the owning `CgBufferSource`)
- text layer factories live in `gl/text/CgTextLayers`

## Common agent mistakes to avoid

- Do not reintroduce cache or atlas policy into `CgTextRenderer`.
- Do not let world-text docs drift away from actual `PerspectiveScaleResolver` behavior.
- Do not reintroduce raw shader-program plumbing when `CgShader`/bindings already own uniform handling.
- Do not reintroduce per-renderer VAO/VBO ownership under another name. The shared `CgVertexArrayRegistry` model is authoritative.
- Do not reintroduce per-renderer `begin()/end()` batch lifecycle on the layer path. The owning `CgBufferSource` manages layer lifecycle.
- Do not reintroduce a self-contained draw path that secretly constructs a batcher or layer. All draw paths require a caller-provided layer.
