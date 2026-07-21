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
- submitting quads to its own owned `CgBatchRenderer`
- resolving shaders from `CgDrawBatchKey`
- transitioning shader/texture/render-state on batch-key changes (`transitionTo`),
  flushing whatever was pending under the previous combination first

**Owned batch lifecycle (current architecture, post batch-ownership migration —
see `CrystalGraphics/docs/CGTEXTRENDERER_MATERIAL_OVERHAUL_PLAN.md` §2.6/§2.7).**
`CgTextRenderer` owns a private `CgBatchRenderer` (format
`CgVertexFormat.POS2_UV2_COL4UB`), created in `create(caps, registry)`. There is no
caller-provided layer or `CgBufferSource` in the draw path anymore — the renderer is
directly and fully self-contained.

- `beginBatch()`/`endBatch()` (no args) open/close a batching window: `draw()`/
  `drawWorld()` calls made in between record into the same underlying batch and are
  flushed together wherever the GL state (shader/texture/render-state) permits.
- `draw()`/`drawWorld()` **tolerate being called with no active batch** — each such
  call transparently wraps itself in its own begin/flush/end. This is a deliberate,
  permanent design choice, not a gap to close: `CgTextRenderer` must remain usable as
  a standalone, directly-instantiated object with no owning render pass (a user
  creates one and calls `draw()` whenever they want), unlike UI's `CgUiRenderer`
  which is always driven by a larger owning context (`CgUiPaintContext`/`UiWindow`).
- The renderer does **not** own any VAO/VBO/IBO GL objects itself — its owned
  `CgBatchRenderer` borrows those from the shared `CgVertexArrayRegistry`/
  `CgQuadIndexBuffer`, exactly as before. Only the CPU-side staging buffer is
  renderer-owned, and that ownership is cheap (see the batch-lifecycle plan doc for
  the reasoning on why this doesn't multiply GPU resources per instance).
- Shader bind/unbind and `CgRenderState` apply/clear on batch-key transitions are
  now handled directly inside `CgTextRenderer` (`transitionTo`/`flushPending`) since
  there is no layer left to own that responsibility.

`CgDynamicTextureRenderLayer`/`CgTextLayers` still exist as classes but are **no
longer used by `CgTextRenderer`** — confirm no other consumer exists before
considering their removal; that decision is explicitly out of scope for the
batch-ownership migration.

**No defensive flush at the end of `submitSortedQuads`/`drawInternal` — this is
intentional, do not add one.** `submitSortedQuads` always calls `transitionTo` at
least once if there are any visible glyphs (`currentKey == null` is true on the
first placement), so a `draw()` call with visible glyphs always stages something;
it does not always flush it. If a caller opens `beginBatch()`, draws, and forgets
`endBatch()`, the staged quads simply sit unflushed until something calls
`flushPending()` — they are not silently lost forever:
- the *next* `beginBatch()` call on that same instance throws immediately
  (`batchActive` only clears in `endBatch()`), which fails loudly on the very next
  attempt instead of quietly dropping text — a stronger guarantee than a silent
  auto-flush would give, since auto-flushing would mask the caller's mistake
  instead of surfacing it;
- `delete()` already closes a dangling batch (`if (batchActive) endBatch();`), so
  teardown does not leak the pending batch either.

Adding a flush at the end of every `draw()`/`drawWorld()` call would force an
upload+draw on every single call regardless of whether a `beginBatch()` is still
open — silently defeating the entire cross-call batching win this architecture
exists to deliver, with no signal to the caller that batching stopped working.
Treat a forgotten `endBatch()` as a caller bug that should fail fast, matching the
convention of every other begin/end pair in this codebase (`CgBatchRenderer`,
`CgUiRenderer`, `CgBufferSource` — none of them defensively auto-flush either).


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
- the renderer owns NO GL objects itself — its owned `CgBatchRenderer`'s VAO/VBO/IBO still come
  from the shared `CgVertexArrayRegistry`/`CgQuadIndexBuffer`; only CPU-side staging is
  renderer-owned
- `draw()`/`drawWorld()` are self-contained — no caller-provided layer or sink is required.
  `beginBatch()`/`endBatch()` are optional, used only to batch multiple draws together;
  each call auto-wraps itself with its own begin/flush/end if no batch is active
- GL state (shader bind/unbind, texture bind/unbind, `CgRenderState` apply/clear) is managed
  directly by `CgTextRenderer` on batch-key transitions (`transitionTo`/`flushPending`)
- `CgDynamicTextureRenderLayer`/`CgTextLayers` are no longer part of this renderer's draw path —
  they still exist as classes but are unused here (confirm no other consumer before deleting)
- text emission through the owned batch renderer must be contiguous — no interleaving from
  other draw-list commands

## Common agent mistakes to avoid

- Do not reintroduce cache or atlas policy into `CgTextRenderer`.
- Do not let world-text docs drift away from actual `PerspectiveScaleResolver` behavior.
- Do not reintroduce raw shader-program plumbing when `CgShader`/bindings already own uniform handling.
- Do not give `CgTextRenderer`'s owned `CgBatchRenderer` its own VAO/VBO — it must keep borrowing
  from the shared `CgVertexArrayRegistry`/`CgQuadIndexBuffer`, same as every other
  `CgBatchRenderer` consumer. Per-instance ownership of the *batcher* (CPU staging only) is
  correct and intentional; per-instance ownership of *GPU objects* is not.
- Do not reintroduce a caller-provided `CgDynamicTextureRenderLayer`/`CgBufferSource` parameter
  on `draw()`/`drawWorld()` — this was the pre-migration design and is now superseded. The
  renderer owns its batch lifecycle directly (see `CgTextRenderer`'s "Owned batch lifecycle"
  section above).
- Do not make `draw()`/`drawWorld()` require an active `beginBatch()` — the standalone-tolerant
  auto-wrap behavior is deliberate, not a gap. `CgTextRenderer` must stay usable as a directly
  instantiated object with no owning render pass.
- Do not use allocating matrix-transform helpers in the text hot path. Use `CgVertexConsumer.vertex(Matrix4f, x, y, z)` or `CgVertexTransformUtil.vertex(...)` which delegate to ThreadLocal scratch vectors (zero allocations).
