# text/render — Agent Guide

## Package role

This package owns the **draw-time side** of the text pipeline.

Its job begins after a layout exists and after the renderer can ask the cache for glyph placements.

The core question here is:

> given a `CgTextLayout` and atlas placements, how do we produce stable, efficient draw calls?

## Reading order

1. `CgTextRenderer`
2. `CgTextQuadSink`
3. `CgTextRenderContext`
4. `CgWorldTextRenderContext`
5. `CgTextScaleResolver`
6. `OrthographicScaleResolver`
7. `PerspectiveScaleResolver`
8. `ProjectedSizeEstimator`
9. `CgDrawBatchKey`

## Class-by-class details

### `CgTextRenderer`

Top-level render façade and the most important file in this package.

Main responsibilities:

- string/layout draw entrypoints
- resolving effective raster tier for the current draw
- asking `CgFontRegistry` for glyph placements
- building paged glyph batches
- sorting placements by `CgDrawBatchKey`
- submitting quads through `CgDynamicTextureRenderLayer` (layer path)
- submitting quads through `CgTextQuadSink` (generic target path — V3.2)
- resolving shaders from `CgDrawBatchKey`
- swapping layer render state and texture on batch-key transitions

Two internal emission paths:

1. **Layer path** (`drawInternalLayer`) — existing path, submits through
   `CgDynamicTextureRenderLayer`. Used by non-UI 2D text and world text.
2. **Target path** (`drawInternalTarget`) — V3.2 path, submits through
   `CgTextQuadSink`. Used by CrystalGUI's draw-list paint context.

Both paths share the same batch-key sorting, glyph placement resolution,
and quad generation logic. They differ only in the final emission sink.

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

The `drawInternalTarget()` methods accept a `CgTextQuadSink` instead
of a layer. These are public and intended for callers (like CrystalGUI's
`CgUiPaintContext`) that manage their own submission model.

### `CgTextQuadSink`

Quad-oriented text emission sink interface (V3.2, replaces V3.1's `CgTextEmissionTarget`).

Decouples glyph quad emission from any specific submission model:

- `beginBatch(CgRenderState, int textureId, float pxRange)` — signals a batch transition; flushes pending vertices
- `emitQuad(x0, y0, x1, y1, u0, v0, u1, v1, r, g, b, a)` — emits a single glyph quad; the sink owns vertex writing
- `endText()` — flushes remaining vertices as a draw command

This interface lives in CrystalGraphics (not CrystalGUI) because the text
renderer cannot depend on CrystalGUI types. CrystalGUI provides an adapter
(`CgUiPaintContext.DrawListTextSink`) that bridges this to draw-list recording.
The adapter tracks the vertex cursor internally (before/after text emission) and
records text draw commands on `beginBatch()` transitions and at `endText()` time.

Unlike the previous `CgTextEmissionTarget`, this interface does not expose
`vertexConsumer()` or `reserveQuads()`. The renderer calls `emitQuad()`
directly, keeping adapter implementations trivially simple (~40 lines vs. ~90).

Key ordering rule: one element's text emission must remain contiguous in the
global draw list. Internal page/material grouping via `beginBatch()` is allowed
only within a single text emission call.

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
- layer-based `draw()` and `drawWorld()` overloads require a `CgDynamicTextureRenderLayer` — there is no self-contained draw path
- target-based `drawInternalTarget()` overloads require a `CgTextQuadSink` — the caller controls the submission model
- GL state is managed by the layer's `CgRenderState` (layer path) or by the executor's state transitions (target path)
- the layer is expected to already be "begun" (managed by the owning `CgBufferSource`) on the layer path
- text layer factories live in `gl/text/CgTextLayers`
- text emission through a sink must be contiguous — no interleaving from other draw-list commands

## Common agent mistakes to avoid

- Do not reintroduce cache or atlas policy into `CgTextRenderer`.
- Do not let world-text docs drift away from actual `PerspectiveScaleResolver` behavior.
- Do not reintroduce raw shader-program plumbing when `CgShader`/bindings already own uniform handling.
- Do not reintroduce per-renderer VAO/VBO ownership under another name. The shared `CgVertexArrayRegistry` model is authoritative.
- Do not reintroduce per-renderer `begin()/end()` batch lifecycle on the layer path. The owning `CgBufferSource` manages layer lifecycle.
- Do not reintroduce a self-contained draw path that secretly constructs a batcher or layer. All draw paths require a caller-provided layer or sink.
- Do not use allocating matrix-transform helpers in the text hot path. Use `CgVertexConsumer.vertex(Matrix4f, x, y, z)` or `CgVertexTransformUtil.vertex(...)` which delegate to ThreadLocal scratch vectors (zero allocations).
- Do not create `CgTextQuadSink` implementations that break the contiguity rule — one element's text must be one unbroken sequence in the draw list.
- Do not re-introduce `CgTextEmissionTarget` or a vertex-consumer-leaking equivalent.
