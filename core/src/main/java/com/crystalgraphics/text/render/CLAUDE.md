# text/render — Agent Guide

## Package role

The **draw-time side** of text: given a `CgTextLayout` and the registry's atlas placements, the quads a canvas draws.
Text is a canvas item (`render/canvas/CLAUDE.md`): `canvas.text()` returns a `CgTextDraw`, its `submit()` lands the
draw's quads on that canvas in order with its other items. There is no renderer object, no lifecycle and no registry.

```java
canvas.text().text("Ready").font(font).at(8, 8).color(0xFFFFFFFF).submit();   // from frame.canvas(), or any CgCanvas
canvas.text().style(TITLE).text("Level up!").at(x, y).submit();                // in a CgTextStyle (text/style)
```

How text looks is `text/style`'s `CgTextStyle`: a face and a stack of `CgTextLayer`s. This package draws one.

## Reading order

1. `CgTextDraw` — the request
2. `CgTextBatch` — a canvas's text: resolve, plan, record, land
3. `CgTextMaterials` — `text.shader`'s fixed instances
4. `CgResolvedGlyphs`
5. `CgTextShadowList`, `CgTextShadowPlan`
6. `CgTextGradients`
7. `CgTextRaster`
8. `CgTextCapture`

## Classes

### `CgTextDraw`

The fluent request. `canvas.text()` is the canvas's one scratch, reset by the next call; `canvas.retainedText()`
is one a caller holds across frames. `CgTextDraw.queued(queue)` hands itself to `queue` at `submit()` instead of
drawing (`CgWorldText`'s labels); `detached()` belongs to no canvas.

- Priorities, not exclusions: `layout` > `paragraph` > `text`, `family` > `font`.
- `pose(PoseStack)` or `pose(Matrix4f)` is read at `submit()`, so a caller may keep pushing its stack.
- `style` is compared by identity in `sameAs`: a caller rebuilding its style each frame defeats the replay. Hold one.
- `clip`, `node`, `gamma` carry what a paint context sets per draw; a new field joins `sameAs`, `set` and `reset`.
- `submit()` returns whether the draw was whole: false while a glyph or shadow cell is still building.
- `measure()` resolves the layout `submit()` would draw, without drawing.

### `CgTextBatch`

One per canvas, made on its first `text()`. Per draw:

1. **Camera** — the canvas's (`cameraProjection/View/Width/Height`). Perspective (`projection.m23() != 0`) is world
   text: always distance-field, its bitmap placeholder at a fixed twice the font's size, never re-wrapped or snapped. Otherwise screen text, rastered at device pixels through `pixelPose` (the camera's
   NDC-to-pixel scale × view × pose), so a camera in other units than the target's pixels still rasters sharp.
2. **Cull** — `canvas.visible(pose, box)`, the layout box grown by a line and the shadow reach. Skipped for a draw
   with a spatial node (its place is the node's) and when capturing.
3. **Replay or record** — a draw the same as last time (`Replay`: the layout object, `sameAs`, pose, camera,
   tier, no eviction since, within 30 frames) appends the records it emitted then. Otherwise resolve, plan shadows,
   sort and build quads. `-Dcrystalgraphics.text.replay=false` records every draw.
4. **Land** — the records go to the canvas as runs of (rank, atlas, tier), each run one `canvas.quads(...)` under
   that run's `CgTextMaterials` instance: neighbouring runs of one atlas and tier merge in the canvas, within a draw
   and across draws.

**Paint order within a draw is the style's layers, bottom to top**, every glyph of one layer before the next
layer's, as Slate draws a run's shadow, outline and fill as separate elements (`SlateTextRun.cpp`, `ElementBatcher`).
Underlines and overlines go before the first fill or outline, a line-through after the last fill. **Across draws,
submit order**: a later label covers an earlier one, shadow and all.

- **An outline under a fill is the grown glyph** (Slate's), the fill covering its inside; **over a fill, or with no
  fill above it, it is the ring alone** (`layerRing`), as a CSS stroke over its fill.
- **Each layer picks its material** (`land`): a colour or gradient takes `CgTextMaterials.of(df, screen, gamma,
  additive)`; a material paint its own Text-domain material (`paint`, `paintState`). A gradient's quads carry its
  ramp row in `CG_QUAD_CUSTOM0`; a material's, the run's UVs (Slate's font-material UVs span the run).
- **Every layer outputs premultiplied colour**, blended `ONE, ONE_MINUS_SRC_ALPHA`; an additive layer adds instead.
- **The draw's colour tints every layer**; a span's colour replaces its fill's colour (0 in the batch: no span colour).
- **Lengths are in the style's pixels**, times `CgTextDraw.unit()`: the draw's size over the style's.
- **Skew shears each quad about its baseline** in the bake, so it costs no vertex of its own.
- `contained`: `true`, as `FSlateTextRun` measures a run: the text moves in by the outline's reach and any shadow
  offset up or left, and `measureSize` counts both. CrystalGUI's CSS (`CssTextStyle`) overflows instead.
- A captured draw (`capture`) takes colour and gradient fills, outlines and shadows; an additive or material layer
  throws, since `WORLD_LABEL` draws every record under one material.

**Captured text** (`capture`, `drawCaptured`, `drawCapturedDepth`, `CgTextCapture`): a draw's records kept rather
than landed, in the draw's own space, for text whose content stays while its pose moves. A batch with no canvas
(`new CgTextBatch(null)`) takes its camera from `camera(...)`. `drawCaptured` draws them as one OBJECT draw of
`text.shader`'s `WORLD_LABEL` variant, each record placed by the object record its `node` names. `CgWorldText`
keeps every label this way: 10,000 shadowed labels went from 34 ms a frame to 4.3.

```java
CgTextBatch labels = new CgTextBatch(null);
labels.camera(projection, view, width, height);
if (!drawn.sameAs(label) || kept.evictions() != CgGlyphAtlas.evictions()) {
    drawn.set(label);
    boolean whole = labels.capture(label, kept);    // false: capture again next frame
}
CgTextBatch.drawCaptured(chunks, kept.batch(b), glyphBuffer, count, labelMatrices);
```

- `drawCapturedDepth` is the depth pass: the records' solid core (alpha 0.5 and up, `u_TextCut`) into depth alone.
  Drawn for every label before any colour, a nearer label hides a farther one.
- Capture only where the raster tier ignores the pose (world text): the tier is resolved for an identity pose.

### `CgTextMaterials`

`text.shader`'s instances, one per (msdf, screen, label, depth pass, gamma, additive), made once and never toggled:
each carries its keywords and its `TextData` block (`overrideBlock`), and binds `CgTextGradients`' ramp at unit 1. A
material paint gets one per (definition, msdf) through `paint`, and its blend through `paintState`. Screen instances disable the depth test, so HUD
text never tests against the host's depth. `precache` names them to the world renderer's collector;
`CgGraphicsLifecycle` releases them at teardown. `textData()` is the `"text"` engine block.

### `CgResolvedGlyphs`

Resolves one draw's layout into flat per-glyph placement data (font, glyph id, subpixel bucket, pen position,
`CgGlyphPlacement`). Layout and atlas cache only: no material, no canvas. Grow-only scratch, one per batch.

### `CgTextShadowList`, `CgTextShadowPlan`

The list is internal: the batch fills it from the style's shadow layers each draw, in local pixels, a **sigma**
(never a CSS blur radius — the CSS side halves it), straight ARGB, whether each casts the outline's shape
(`outlined`), and each layer's glyph scope.

The plan decides what each glyph paints in each shadow — the glyph, a threshold on its field, or a worker-built
`CgShadowCell` — and resolves the cells. **A cell still building draws the cell that glyph's shadow last had**, so
a dragged slider never blinks the shadow off. **A plan is kept** per cached placements array and reused while the
shadows' shape, size, stroke and evictions match: `text-3d`'s 8,066 cells went from 1.8 ms a frame to 0.03.

### `CgTextGradients`

Every gradient paint's ramp, one 256-texel row each of one 256 × 256 texture, baked once per paint and kept: the
shader reads it at `CG_QUAD_CUSTOM0`'s row with the t its (a, b, c) give for the quad's space. Released with the
materials at teardown.

### `CgTextRaster`

Pure functions and the thresholds: a screen draw's raster size (its pixel pose's scale, with hysteresis against the
font's last size), a world draw's (twice the font's, fixed: distance-field atlases are keyed at one scale, so a
camera-driven size only minted bitmap families), bitmap or distance field, and the axis test that sends a rotated or
sheared draw to the distance field. The batch keeps each font's last screen size and tier (`RasterHistory`).

## Key invariants

- Text consumes placements; it never owns glyph generation or atlas policy.
- Layout stays in logical space; the raster tier is a draw-time decision.
- A batch owns no GPU object: records go into the canvas's quad stream.
- One thread at a time, as its canvas.
- The camera is read at each draw: change an immediate canvas's camera only between a `flush()` and the next draw.

## Common agent mistakes to avoid

- Reintroducing a renderer object, a begin/end batch, a resize registry or a render context: the canvas owns the
  camera, the order and the drawing.
- Holding `canvas.text()` past its `submit()`: use `retainedText()`.
- Adding a draw-time parameter as an overload instead of a `CgTextDraw` chain method.
- Toggling keywords or blocks on a shared text material: add a `CgTextMaterials` key instead.
- Adding an effect as a `CgTextDraw` setter: it is a layer kind, or a field of one, in `text/style`.
- Allocating in the draw path: the batch's scratch arrays grow and are reused.
