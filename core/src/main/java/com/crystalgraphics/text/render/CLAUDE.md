# text/render — Agent Guide

## Package role

This package owns the **draw-time side** of the text pipeline.

Its job begins after a layout exists and after the renderer can ask the cache for glyph placements.

The core question here is:

> given a `CgTextLayout` and atlas placements, how do we produce stable, efficient draw calls?

## Reading order

1. `CgTextRenderer`
2. `CgTextRenderer.Draw`
3. `CgTextRendererRegistry`
4. `CgTextRenderContext`
5. `CgTextScaleResolver`
6. `OrthographicScaleResolver`
7. `PerspectiveScaleResolver`
8. `ProjectedSizeEstimator`
9. `CgResolvedGlyphs`
10. `CgTextShadowList`
11. `CgTextShadowPlan`

## Class-by-class details

### `CgTextRenderer`

Top-level render façade and the most important file in this package.

Main responsibilities:

- string/layout draw entrypoints
- resolving effective raster tier for the current draw
- delegating layout flattening/prequeueing and atlas placement resolution to `CgResolvedGlyphs`
- sorting placements by a packed `long` GL-state sort key (mode/textureId/pxRange — see
  `submitBatchedQuads`), avoiding a per-glyph key object
- submitting quads to its own owned `CgQuadRenderer`
- transitioning shader/texture/render-state on batch-state changes (`transitionToMaterial`),
  flushing whatever was pending under the previous combination first

**Owned batch lifecycle (current architecture, post batch-ownership migration —
see `CrystalGraphics/docs/plan/text-material.md` §2.6/§2.7).**
`CgTextRenderer` owns a private `CgQuadRenderer`, created in `create(caps, registry)`: each glyph is
one quad record. No caller-provided layer is in the draw path -- the renderer is self-contained.

- `beginBatch()`/`endBatch()` (no args) open/close a batching window: `draw()` calls
  made in between record into the same underlying batch and are flushed together
  wherever the GL state (shader/texture/render-state) permits.
- `draw()` **tolerates being called with no active batch** — each such call
  transparently wraps itself in its own begin/flush/end. This is a deliberate,
  permanent design choice, not a gap to close: `CgTextRenderer` must remain usable as
  a standalone, directly-instantiated object with no owning render pass (a user
  creates one and calls `draw()` whenever they want), unlike UI's `CgUiRenderer`
  which is always driven by a larger owning context (`CgUiPaintContext`/`UiWindow`).
- The renderer owns **no** GPU object: glyph records go into the quad renderer's class-wide engine
  buffer. Only the CPU-side staging is renderer-owned, which is cheap.
- Shader bind/unbind and `CgRenderState` apply/clear on batch-key transitions are
  now handled directly inside `CgTextRenderer` (`transitionTo`/`flushPending`) since
  there is no layer left to own that responsibility.

**Single `draw()`/`retainedDraw()` entry point for 2D and 3D world text — no `drawWorld()`.**
`drawInternal`/`submitBatchedQuads` dispatch 2D-vs-world behavior polymorphically off
`context.isWorldText()`/`context.getScaleResolver()`, not off which method was called.
World-space text is the exact same fluent chain as 2D text — the only difference is
that the renderer's context was set to one built via `CgTextRenderContext.world(...)`
instead of `.orthographic(...)` (see `context(CgTextRenderContext)` below). Depth is
`text.shader`'s for both: tested against the scene, never written. A draw's shadows, lines
and glyphs are coplanar quads of different extents, so writing depth makes each layer
fight the next.

**Fluent `Draw` request replaced the fixed-arity `draw(...)` overload matrix.**
The old design had ~13 overloads (`draw(CgTextLayout/String, CgFont/CgFontFamily,
[int targetPx], [CgTextConstraints], x, y, rgba, CgTextRenderContext, PoseStack)`) —
combinatorial, and every new optional draw-time parameter would have doubled the
matrix again. That entire surface is gone. The only public entry points now are:

- `renderer.draw()` — returns the renderer's single reused scratch `Draw` instance
  (zero allocation). Contract: build it and call `.submit()` in the same
  expression/statement; the reference is invalidated by the *next* `draw()` call on
  that renderer, from *any* call site.
- `renderer.retainedDraw()` — allocates a standalone `Draw` the caller may hold across
  frames (e.g. a cached HUD line, mutating only `.text(...)` each tick).
- `renderer.queuedDraw(queue)` — a retained `Draw` whose `submit()` hands it to `queue` instead of
  drawing; its owner draws it later with `drawQueued(draw)`, under a pose it only knows then.
  `CgWorldRenderer`'s labels are these. `Draw.reset()` clears one for reuse, `basePx()` is the size
  a pose scales.
- `Draw` chain methods: `layout(CgTextLayout)`/`paragraph(CgShapedParagraph)`/`text(String)`
  (each wins over the next — layout is already fully resolved, paragraph is already shaped,
  text needs both), `font(CgFont)`/`family(CgFontFamily)` (family wins if both set — strictly
  more capable superset), `targetPx(int)`, `constraints(float maxWidth, float maxHeight)`
  (`<= 0` means unbounded on that axis), `at(float, float)` (defaults `(0,0)`),
  `color(int)` (defaults opaque white), `pose(PoseStack)` (falls back to the
  renderer's own `poseStack()` if never called, and finally to a shared identity
  `PoseStack` if neither was ever set — see below).
- `Draw.measure()` resolves (without drawing) the exact `CgTextLayout` `submit()` would draw
  right now — same font/scale/paragraph-reflow resolution — for callers that need to know a
  section's on-screen size (e.g. for stacking layout) before/after drawing it.
- `Draw.submit()` resolves everything (mirroring the exact sizing/validation branches
  the old overloads had — `requireSizedFont`/`sizeFamily` — see the method body if
  touching this logic) and calls `drawInternal(...)` directly. Returns the owning
  `CgTextRenderer`, so a manually-opened batch's last `submit()` can chain into
  `.endBatch()`.

There is no `CgTextRenderContext`/`frame` parameter on any draw call anymore — both
moved to renderer-owned state (see next two subsections).

**Renderer owns its `CgTextRenderContext` — no longer a caller-passed parameter.**
`context()` returns the live mutable context (defaults to an orthographic context
sized from `CgGraphicsLifecycle`'s current known window dimensions);
`context(CgTextRenderContext)` replaces it wholesale — the way to switch orthographic
↔ world mode, since the two differ in which `CgTextScaleResolver` they hold. Callers
reach `context().updateOrtho(...)`/`.updateProjection(...)`/`.clearHistory()`/
`.updateProjectedSize(...)` directly instead of holding a separate instance.

**Renderer owns an optional fallback `PoseStack` — niche, not the default path.**
`poseStack()`/`poseStack(PoseStack)` (Lombok `@Getter @Setter @Accessors(fluent =
true)`) hold a `PoseStack` that's `null` until a caller opts in. `Draw.submit()`/
`Draw.measure()` use it only when `.pose(...)` was never called on that `Draw`; if
both are unset, they fall through further to a shared, never-mutated identity
`PoseStack` (`IDENTITY_POSE_STACK`) rather than throwing — plain screen-space text
with no real transform can skip `.pose(...)` entirely. `CgUiPaintContext` wires
its own pose stack in here in its constructor
(`CgTextRenderer.createManualSized().poseStack(this.poseStack)`) so that any draw issued through
`ctx.text()` without an explicit `.pose(...)` still works — but its own `drawText()`-style
call sites still pass `.pose(poseStack)` explicitly; this field is a backstop, not
something call sites should rely on by default.

**`CgTextRendererRegistry` — resize tracking + teardown backstop, opt-in per feature.**
Every `create()`/`createManualSized()` call registers with the singleton
`CgTextRendererRegistry.get()`, mirroring `CgFrameBufferRegistry`'s ownership model:
- `onResize(w, h)` (called from `CgGraphicsLifecycle.onResize`) auto-resizes only
  screen-sized renderers (the `create()` default) currently in orthographic mode —
  it skips renderers built via `createManualSized()` and any renderer whose context reports
  `isWorldText()` (calling `updateOrtho` on a world context would clobber the
  perspective projection). `HUDRenderer`/`CgFontDemo` use `create()` since
  their dimensions are proven to come from the same source that drives
  `CgGraphicsLifecycle.onResize()`; `CgUiPaintContext` deliberately uses
  `createManualSized()` instead — its dimensions come from `UIWindow`'s own independent
  resize path, with no proven lockstep guarantee, so it still manually calls
  `textRenderer.context().updateOrtho(...)` in `beginFrame(w, h)`.
- `deleteAll()` (called from `CgGraphicsLifecycle.destroyContext()`, before the
  VAO/VBO bulk sweep) deletes any renderer still alive as a backstop — individual
  owners (`CgUiPaintContext`, `HUDRenderer`, harness scenes) remain responsible for
  calling `delete()` promptly; this registry does not change that expectation.

**No defensive flush at the end of `submitBatchedQuads`/`drawInternal` — this is
intentional, do not add one.** `submitBatchedQuads` always calls `transitionTo` at
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

Adding a flush at the end of every `draw()` call would force an
upload+draw on every single call regardless of whether a `beginBatch()` is still
open — silently defeating the entire cross-call batching win this architecture
exists to deliver, with no signal to the caller that batching stopped working.
Treat a forgotten `endBatch()` as a caller bug that should fail fast, matching the
convention of every other begin/end pair in this codebase (`CgQuadRenderer`, `CgUiRenderer` -- neither
defensively auto-flushes).

**Inside a batch, quads wait in buckets.** Each quad goes to the bucket of its (paint rank, batch key): the rank is
its paint step less the draw's shadow count, so outer shadows are negative, then decorations under the text (0), the
glyphs (1), decorations over them (2) and inset shadows. `flush()` drains the buckets by rank, first-seen order within a
rank, submit order within a bucket: a transition per bucket, not per draw, however the draws alternate atlases and
shadow cells. 10,000 outlined, shadowed labels went from 15,723 flushes a frame to 3 (`--mode=world-labels`).
- Each draw keeps its own layering. Across draws in one batch, every shadow lies beneath every glyph: two texts that
  overlap inside one batch layer by kind, not by submit order.
- Anything a caller changes on the sink between draws (scissor, constants, view) must end the batch first, or quads
  queued before it draw under it. `sink()` itself drains first. CrystalGUI's paint context ends its text path at every
  such change.
- A quad keeps its draw's pose, clip and nodes in a pose entry (`pushPose`); `draw.drainBuckets` times the replay.

**Captured text** (`capture`, `drawCaptured`, `CgTextCapture`): a draw's bucketed records kept rather than drawn, in
the draw's own space, for text whose content stays while its pose moves. `drawCaptured` draws one batch of them from a
buffer as one OBJECT draw of `text.shader`'s `WORLD_LABEL` variant, each record placed by the model matrix of the
object record its `node` names. `CgWorldText` keeps every label this way: 10,000 shadowed labels went from 34 ms a
frame to 4.3.

```java
CgTextRenderer.Draw drawn = renderer.retainedDraw();
CgTextCapture kept = new CgTextCapture();
if (!drawn.sameAs(label) || kept.evictions() != CgGlyphAtlas.evictions()) {
    drawn.set(label);
    boolean whole = renderer.capture(label, kept);    // false: capture again next frame
}
renderer.drawCaptured(chunks, kept.batch(b), glyphBuffer, count, labelMatrices);
```

- `drawCapturedDepth` is its depth pass: the records' solid core (alpha 0.5 and up, `u_TextCut`) into depth alone,
  colour off. Drawn for every label's text before any colour, a nearer label hides a farther one. The colour draws
  declare the colour mask on, since `text.shader` leaves it undeclared and the depth pass's would stand on GL.
- Capture only where the raster tier ignores the pose (world text): the tier is resolved for an identity pose.
- A capture that returned false holds what was ready; capture it again until it returns true.
- `Draw.sameAs`/`set`/`reset` name every field: a new `Draw` field joins all three.

### `CgTextRenderer.Draw`

Non-static inner class — the fluent draw request. See "Fluent `Draw` request replaced
the fixed-arity `draw(...)` overload matrix" above for the full chain-method surface
and the field-priority rules (`layout` > `text`, `family` > `font`). Obtained via
`draw()` (shared scratch) or `retainedDraw()` (standalone); never construct it any other way
— the constructor is private.

`submit()` is the only place that calls `drawInternal(...)` — there is no other public
draw path left. If you're touching sizing/validation logic, it all lives in this one
method; read it in full before changing any of the `targetPx`/`layout`/`text`
resolution branches, since they're a faithful (branch-for-branch) port of what the old
overloads each did.

### `CgTextRendererRegistry`

Singleton registry — see "`CgTextRendererRegistry`" above for the resize-tracking and
teardown-backstop behavior. Structurally mirrors
`com.crystalgraphics.gl.framebuffer.CgFrameBufferRegistry` (screen-sized vs.
fixed-size tracking, `deleteAll()` sweep at context teardown) but does **not** own
renderer *lifecycle* the way the FBO registry owns FBO lifecycle — renderers are still
created and (in the common case) deleted by their own owner; this registry is purely a
backstop plus the resize dispatcher.

### `CgTextRenderContext`

The render context — one concrete class for both 2D UI text and 3D world text.
There is **no** `CgWorldTextRenderContext` subclass (removed — see below).

Owns:

- current projection matrix (`Matrix4f`)
- scale resolver (`CgTextScaleResolver` — `OrthographicScaleResolver` or
  `PerspectiveScaleResolver`)
- viewport dimensions (used by world text's `updateProjectedSize`; harmless dead
  fields for 2D UI text)
- per-font history used for raster-tier hysteresis — one `Map<CgFontKey, RasterHistory>`,
  not two parallel maps (see below)

Important note:

- this is not just a bag of matrices
- it also stores draw-history state that affects raster-tier stability

**One history map, not two.** `previousEffectiveTargetPx`/`previousMsdf` used to be
separate `Map<CgFontKey, Integer>`/`Map<CgFontKey, Boolean>` fields, always read and
written together per font in `CgTextRenderer.drawInternal` — a parallel-map smell for
state that's really one thing. Collapsed into a single
`Map<CgFontKey, RasterHistory>`, where `RasterHistory` is a small package-private
record (`effectiveTargetPx`, `wasMsdf`). Package-private accessors: `getHistory(fontKey)`
(returns `null` if none yet) and `setHistory(fontKey, effectiveTargetPx, wasMsdf)`.
Do not reintroduce two parallel maps here — if a third piece of per-font hysteresis
state is ever needed, add a field to `RasterHistory`, not a third map.

**Constructor is private — build via `orthographic(...)`/`world(...)` only.** Grepped
the whole tree before trimming: nothing called the old three public constructors
directly, everything went through the two static factories. Do not add a public
constructor back without checking real usage first — the same
`orthographic`/`world` factory-only pattern used here matches
`CgTextRenderer.create(...)`'s own private-constructor convention.

**Why there's no world-space subclass.** `isWorldText()`/`updateProjectedSize(...)`/
`clearProjectedSizeHint()` all delegate straight to `scaleResolver` — the "is this
world text" question is fully answered by which `CgTextScaleResolver` strategy is
active, so a parallel context subclass just duplicated that distinction. Two
factories build the same class: `orthographic(w, h)` (uses the shared stateless
`CgTextScaleResolver.ORTHOGRAPHIC` singleton) and `world(projection, w, h)` (uses a
fresh `PerspectiveScaleResolver`, since that resolver is stateful — it holds the
projected-size hint — and cannot be a shared singleton). This also fixed a real bug
that existed in the old subclass: `CgWorldTextRenderContext.updateProjection()` did
`projection.set(projection)` — a no-op self-assignment (should have been
`this.projection.set(projection)`), caused by the subclass shadowing the field name.
Collapsing to one class removed that whole bug class since there is only one
`projection` field anywhere now.

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

### `CgResolvedGlyphs`

Resolves one `drawInternal` call's `CgTextLayout` into flat per-glyph atlas-placement data —
font key/font, glyph id, subpixel bucket, pen position, and finally each glyph's
`CgGlyphPlacement`. Pure layout/atlas-cache concern, no GL/`CgQuadRenderer`/`CgMaterial`
dependency — that boundary starts once `CgTextRenderer` reads its `glyphX`/`glyphY`/`placements`
back out. Owned per-`CgTextRenderer` instance with grow-only scratch arrays, reused across draws.

There is no separate grouping-key class anymore — `submitBatchedQuads` packs each visible glyph's
(atlas mode, texture id, `pxRange`) directly into a `long` sort key (see its javadoc for the bit
layout) and sorts with `Arrays.sort(long[], int, int)`. This is also the authoritative source of
shader selection in the current renderer, via `transitionToMaterial`.

### `CgTextShadowList`

One draw's `text-shadow` list as `Draw.shadowCount`/`shadow`/`shadowScope` collect it: local pixels, a
**sigma** (never a CSS blur radius -- the CSS side halves it), straight ARGB, and the per-glyph scopes a
`::highlight` shadow uses. Grow-only, reset with the draw. Also answers the culler's `reach()`.

### `CgTextShadowPlan`

Per draw, decides what each glyph paints in each shadow -- the glyph itself, a threshold on its field, or
a worker-built `CgShadowCell` from the bitmap atlas -- and resolves the cells. **A cell still building
draws the cell that glyph's shadow last had** (kept per font, glyph and shadow index, whatever blur,
growth or size), so a dragged slider never blinks the shadow off; the cell is scaled by its own raster
size, which is why `placeGlyph` reads a shadow cell's size from its key. **A plan is kept** for a cached layout's
placements array (`CgResolvedGlyphs.cached`) and reused while the shadows' shape, size, stroke and atlas evictions
match, so a layout drawn again plans nothing: `text-3d`'s 8,066 cells went from 1.8 ms a frame to 0.03. It follows the
placement cache's refreshes, since a refreshed entry is a new array. `submitBatchedQuads` keys
every shadow instance at its own stage (`CgTextSortKey`) and `submitSorted` packs it for `text.shader`'s
negative `custom0.w` kinds.

### `package-info.java`

Package-level description of render-side responsibilities.

## Key invariants

- renderer consumes placements; it does not own glyph generation
- layout remains in logical space; raster tier is a draw-time physical decision
- the packed `long` sort key in `submitBatchedQuads` drives shader selection
- world-text and 2D text share most of the pipeline until raster-tier / projection policy differs
- the renderer owns no GPU object; only CPU-side staging is renderer-owned
- `draw()`/`retainedDraw()` are self-contained — no caller-provided layer or sink is required,
  and there is a single fluent request type (`Draw`) for both 2D UI text and 3D world
  text (see `CgTextRenderer`'s "Fluent `Draw` request" note above). `beginBatch()`/
  `endBatch()` are optional, used only to batch multiple draws together; each
  `Draw.submit()` auto-wraps itself with its own begin/flush/end if no batch is active
- GL state (shader bind/unbind, texture bind/unbind, `CgRenderState` apply/clear) is managed
  directly by `CgTextRenderer` on batch-key transitions (`transitionTo`/`flushPending`)
- text emission through the owned batch renderer must be contiguous — no interleaving from
  other draw-list commands
- there is no fixed-arity `draw(...)` method anymore — `Draw.submit()` is the only path into
  `drawInternal(...)`; `CgTextRenderContext` and `frame` are both renderer-owned state now,
  never draw-call parameters (see `context()`/`poseStack()`/`CgGraphicsLifecycle.getCurrentFrame()`)
- `CgTextRendererRegistry` tracks every renderer for teardown, and additionally auto-resizes
  screen-sized (`create()`, the default) ones — see its section above before adding a new
  `CgTextRenderer`-owning class, to decide whether it should stay screen-sized or opt into
  `createManualSized()`

## Common agent mistakes to avoid

- Do not reintroduce cache or atlas policy into `CgTextRenderer`.
- Do not let world-text docs drift away from actual `PerspectiveScaleResolver` behavior.
- Do not reintroduce raw shader-program plumbing when `CgShader`/bindings already own uniform handling.
- Do not give `CgTextRenderer` GPU objects of its own: per-instance ownership of the *batcher* (CPU
  staging only) is correct and intentional; per-instance ownership of *GPU objects* is not.
- Do not reintroduce a fixed-arity `draw(...)` overload matrix (or a caller-provided
  layer, `CgTextRenderContext` or `frame` parameter) —
  every one of those concerns is now renderer-owned state reached through `Draw`,
  `context()`, or `poseStack()`. A new optional draw-time parameter should become a new
  `Draw` chain method, never a new overload.
- Do not make `Draw.layout(...)`/`.text(...)` or `.family(...)`/`.font(...)` mutually
  exclusive (e.g. clearing one when the other is set). They're priority-based, not
  exclusive — `layout` wins over `text`, `family` wins over `font` — see `Draw.submit()`.
- Do not hold a reference returned by `renderer.draw()` past the `.submit()` call in the
  same expression — it's the renderer's single shared scratch instance and any other
  `draw()` call anywhere resets it. Use `renderer.retainedDraw()` for anything held across frames.
- Do not add a `CgTextRenderContext` or `frame` parameter back onto any draw-time method —
  the renderer owns both (`context()`/`context(CgTextRenderContext)`,
  `CgGraphicsLifecycle.getCurrentFrame()` read internally by `drawInternal`).
- Do not assume every new `CgTextRenderer` consumer wants screen-sized resize tracking.
  `create()` (screen-sized) is the default and only correct when the consumer's dimensions are
  proven to come from the same source driving `CgGraphicsLifecycle.onResize()` — otherwise use
  `createManualSized()`. See `CgTextRendererRegistry`'s section above for why `CgUiPaintContext`
  deliberately uses `createManualSized()`.
- Do not make `Draw.submit()` require an active `beginBatch()` — the standalone-tolerant
  auto-wrap behavior is deliberate, not a gap. `CgTextRenderer` must stay usable as a directly
  instantiated object with no owning render pass.
- Do not reintroduce a separate `drawWorld()` method. `drawInternal`/`submitBatchedQuads`
  already dispatch 2D-vs-world behavior off the context's `isWorldText()`/`getScaleResolver()`
  — a second method would only ever be a byte-for-byte duplicate of `draw()`, as it was
  before this merge.
- Do not reintroduce a `CgWorldTextRenderContext` (or any world-space `CgTextRenderContext`
  subclass). `isWorldText()`/`updateProjectedSize`/`clearProjectedSizeHint` on
  `CgTextRenderContext` already delegate to whichever `CgTextScaleResolver` is active — a
  subclass would only duplicate that distinction and previously caused a real bug (see
  `CgTextRenderContext`'s section above). If world text needs new state or behavior, add it to
  `PerspectiveScaleResolver` (or a new `CgTextScaleResolver` method with a no-op default), not
  to a context subclass.
- Do not split `CgTextRenderContext`'s per-font history back into two parallel maps.
  `effectiveTargetPx` and `wasMsdf` are always read/written together per font — keep them in
  one `RasterHistory` record behind one map.
- Do not add a public constructor to `CgTextRenderContext` without checking real usage first —
  it was made private specifically because nothing called the old public constructors directly;
  build via `orthographic(...)`/`world(...)`.
- Do not use allocating matrix-transform helpers in the text hot path. Use `CgVertexConsumer.vertex(Matrix4f, x, y, z)` or `CgVertexTransformUtil.vertex(...)` which delegate to ThreadLocal scratch vectors (zero allocations).
