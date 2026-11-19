# The UI canvas

`com.crystalgraphics.api.canvas.CgCanvas`: 2D drawing for screens, HUDs and textures. Rectangles filled by any paint
(`docs/PAINT.md`), rounded, bordered and shadowed; images, nine-slices and tiles; under a transform, opacity and clip
stack. Items land in a recording as batched element draws, in paint order.

It replaces the old `render.canvas.CgCanvas` (`CANVAS.md`) step by step through P4.1; text, vector paths, layers and
the widget stack arrive in later steps, and until then those still draw on the old canvas.

## Getting one

```java
CgRenderStage.HUD.register(CgRenderStage.VIEWPORT_WIDGETS, frame -> {
    CgCanvas canvas = CgCanvas.on(frame);        // window pixels, from the top-left
    // CgCanvas.onGui(frame)                     // the host GUI's pixels: Minecraft's GUI scale
    canvas.fill(8, 8, 200, 40, 0xC0101418);
});
```

Off a stage, into a texture of your own:

```java
CgCanvas canvas = new CgCanvas();
canvas.begin(recording, target, CgLoad.CLEAR, CgCamera.screen(w, h), 1f);
canvas.box(0, 0, w, h).fill(CgGradient.linear(90).stop(0, a).stop(1, b)).draw();
canvas.end();
```

## Items

```java
canvas.fill(x, y, w, h, 0xFF2060C0);                         // one colour
canvas.fill(x, y, w, h, CgGradient.radial(0.5f, 0.5f, 0.5f, 0.5f).stop(0, glow).stop(1, 0));
canvas.fill(x, y, w, h, CgGrid.checker(0xFFC0C0C0, 0xFF404040).cell(8));

canvas.box(x, y, 160, 32)                                    // a box: describe, then draw
      .fill(0xFF202630)
      .radius(6)                                             // or radii(CgCornerRadii.of(8, 8, 0, 0)), elliptical too
      .border(1, 0x30FFFFFF)                                 // or per side: widths and colours
      .shadow(CgShadow.of(0, 4, 12, 0x80000000))             // .inset() draws inside the border
      .draw();

canvas.image(texture, x, y, 32, 32);                         // stretched
canvas.image(CgBrush.sprite("mymod:hud", "panel"), x, y, w, h);   // the sheet's nine-slice and tiling
canvas.box(x, y, 16, 16).mask(icon, 0xFFFFD040).draw();      // a silhouette in any colour
```

## State

```java
canvas.save();
canvas.translate(cx, cy).rotate(30).scale(2, 2);
canvas.opacity(0.5f).intensity(4f);                          // intensity: past 1 on an HDR target
canvas.clip(0, 0, 64, 64, CgCornerRadii.of(8));              // intersects the clip now
canvas.image(icon, -16, -16, 32, 32);
canvas.restore();
```

## Text

A `CgText` is the value: what it says, its font, size, style and where it wraps. Make it once, share it, measure it on
any thread; the canvas draws it at a point.

```java
static final CgText TITLE = CgText.of("Spawn").font(font).size(24).style(HEADING);

canvas.text(TITLE, 20, 40);                              // as its style paints it
canvas.text(TITLE.text("Respawn"), 20, 40, 0xFFFFD040);  // another string, every layer tinted
float wide = TITLE.measure().totalWidth();               // the document's thread
```

Under the canvas's transform it rotates, scales and fades with everything else, its glyphs rasterised at their size on
the target (size times scale), and `maxWidth` wraps in the canvas's units, where `measure()` says.

```java
canvas.save().translate(x, y).rotate(-12).scale(1.4f, 1.4f);
canvas.text(CgText.of(body).family(ui).size(14).maxWidth(320), 0, 0);
canvas.restore();
```

- A glyph still generating draws a lower tier for a frame or two and counts in `canvas.degradedDraws()`; a chunk that
  drew one refuses itself (`DEGRADED`), so nothing keeps the blur.
- `canvas.textGamma(...)` sets the coverage correction; a kept chunk with text replays only under the gamma and the
  atlases it was taken under (`STALE_TEXT`).

## Paths

A `CgVectorPath` is the shape, a `CgStroke` how its outline is drawn; both are values. The canvas fills or strokes
one in a colour or a gradient, the gradient laid over the path's own bounds.

```java
static final CgVectorPath TICK = CgVectorPath.builder().moveTo(2, 8).lineTo(6, 12).lineTo(14, 4).build();
static final CgStroke PEN = CgStroke.of(2f).cap(CgStrokeCap.ROUND).join(CgStrokeJoin.ROUND);

canvas.fill(BADGE, 0xFF3070F0);
canvas.fill(BADGE, CgGradient.linear(90).stop(0, 0xFF2050FF).stop(1, 0xFFFF4020));
canvas.stroke(TICK, PEN, 0xFF40C060);
canvas.stroke(RING, PEN.dash(0f, 6f), 0xFFFFFFFF);              // dots: zero-length dashes, round caps
canvas.stroke(RING, PEN.trim(0f, progress), 0xFF40C060);        // drawn on as progress goes 0 to 1
```

Every path draws as exact-area coverage: its cells summed into a raster, then one element painting through it.

- **Small, axis-aligned and on the pixel grid** (the default: pixel snapping on, at most `CgPathCache.MAX_PX` device
  pixels), it is rasterised once into an atlas every canvas shares, and drawn from it every frame after.
- **Rotated, skewed, large or unsnapped**, it is rasterised for its frame, as exactly; its pass costs a pass split.
- **Strokes** are outlined in the path's own units, so a non-uniform scale shapes the pen; thinner than a device
  pixel, a stroke draws a pixel wide and fades.
- **Two canvases on two threads** may draw one path: whichever claims it first builds the cell, and the other draws
  its own raster until that build has executed (the publication rule, `CgPathCache`).
- A brush or a grid fills a path through a mask layer, and a path clip is one too (`c.layer().mask(path)`, UI8).

## SVG

A `CgSvg` is a parsed document: immutable, shared, safe on any thread. It draws as a brush, so it goes anywhere a
brush does; `canvas.svg(...)` is the short form.

```java
static final CgSvg SAVE = CgSvg.of("mymod:ui/icons/save.svg");            // loaded once, shared; null if unreadable

canvas.svg(SAVE, x, y, 16, 16);                                            // its own colours, fitted and centred
canvas.image(CgBrush.svg(SAVE).color(theme.text()), x, y, 16, 16);         // currentColor bound at draw
canvas.image(CgBrush.svg(SAVE).color(text).tint(0x80FFFFFF), x, y, 16, 16); // and everything at half alpha
canvas.image(CgBrush.svg(OUTLINE).color(accent).flat(1.5f), x, y, 24, 24);  // a silhouette, its strokes 1.5 units
canvas.image(CgBrush.svg(BAR).fit(CgFit.FILL), x, y, 200, 8);              // stretched, not fitted
canvas.box(x, y, 32, 32).fill(CgBrush.svg(SAVE)).border(1, 0xFF808080).draw();  // in a box, its border over it
```

A sprite sheet's entries are its symbols, each in its own `viewBox`:

```java
CgSvg arrow = CgSvg.of("mymod:ui/icons/sheet.svg#arrow");                 // or of(sheet).symbol("arrow")
```

It is always sharp. Up to `CgPathCache.MAX_PX` device pixels, axis-aligned, each draw op's exact-area coverage is
rasterised once, at the size shown, into the atlas paths share (a fill in its own colours premultiplied, everything
else white and tinted at draw), and drawn 1:1 on the pixel grid. Larger or turned, it is rasterised for its frame.

- **Pixel snapping decides the grid.** Snapped (the default), an icon lands on whole pixels. Unsnapped
  (`pixelSnap(false)`, an icon mid-animation), it draws between pixels from its exact raster, or from a ladder of
  sixteen raster sizes per doubling, filtered, so a moving icon looks as it does at rest.
- **The file's curves are flattened for the size drawn** (`svg.tier(devicePx)`): finer as the icon grows.
- **A `<style>` block or a `class=` fails the load**, naming the file: export with presentation attributes
  (Illustrator's "Presentation Attributes", SVGO's `inlineStyles`).
- **Not drawn**: text, `clipPath`, masks, dashes, filters. A drop shadow is parsed and skipped with a log line, once
  per file, until SVG filters draw as layer filters.
- `CgSvg.revalidate()` on a resource reload drops only the documents whose file changed; `CgSvg.generation()` tells a
  holder its document may be stale. `CgSvg.preload(paths)` parses on the job pool ahead of the first frame.

## Frames recorded off the render thread

A document or a heavy HUD records on its own thread and is shown on the render thread: `CgCanvasRecorder` paints into
a picture of its own and seals a `CgCanvasFrame` (ordered, batched and packed off the render thread);
`CgCanvasPresenter` executes it in a stage's firing and composites the picture.

```java
CgCanvasRecorder recorder = new CgCanvasRecorder();          // one per document
CgCanvasPresenter presenter = new CgCanvasPresenter();

// the document's thread
CgCanvas canvas = recorder.begin(w, h);
paintDocument(canvas);
CgCanvasFrame frame = recorder.seal();

// the render thread, at a SCREEN or HUD stage
CgRenderStage.SCREEN.register(CgRenderStage.VIEWPORT_WIDGETS, stage -> {
    if (next != null) presenter.present(stage, next);       // executed, composited, kept
    else presenter.presentAgain(stage);                      // the kept picture, nothing executed
});
```

**Moving what was recorded without recording it again**: open a movable node, and move it through the frame's values.

```java
// recording
canvas.save();
int list = canvas.translate(x, y).movable();                 // a scroll, a window, a dragged card
paintRows(canvas);
canvas.restore();

// any later host frame, render thread
presenter.presented().values().translate(list, 0, -scrolled);
presenter.redraw(stage);                                     // executed again, nothing recorded or batched
```

How much of a scroller to record so a scroll by values shows rows, and when to record again, is Blink's rule:

```java
CgCullRect.forScroller(scrollX, scrollY, viewW, viewH, contentW, contentH, scale, cull);   // record what cull holds
if (CgCullRect.changedEnough(cull, next, contentW, contentH, scale)) recordAgain();
```

## Paint chunks: keeping drawing that comes out the same

```java
CgPaintChunk kept = new CgPaintChunk();                      // held with the thing it keeps

if (unchanged && canvas.replay(kept)) return;                // added to this frame, drawing nothing
canvas.beginChunk();
paintIt(canvas);
CgPaintChunk.Refusal why = canvas.endChunk(kept);            // NONE, or why not: PASS, OUTER_CLIP, FRAME_TEXTURE...
```

The caller's key says when it would come out the same; the chunk renumbers the clips, nodes and gradients it made into
the new frame. `kept.differs(fresh)` is the check mode: a fresh paint taken into a second chunk must match.

## What to know

- **Paint order holds.** A later item draws over an earlier one it overlaps; items that overlap nothing between them
  join one draw whatever their order, so interleaving looks costs nothing where they do not touch.
- **Axis-aligned edges snap to device pixels** (`pixelSnap(false)` turns it off); rotated items antialias instead.
  Translation and positive scale bake into each item; rotation and skew cost a spatial node, up to 4096 a recording.
- **Square clips intersect; rounded or rotated ones nest**, at most `CgClipTable.MAX_DEPTH` deep past the square ones.
- **A box reuses one builder**: describe and draw it in one statement.
- **Colours are `0xAARRGGBB`, straight alpha.** Gradients interpolate premultiplied.
- **A material of your own** is a `#domain ui` material of `Primitive Element`: its `surface()` runs over the item's
  paint (`s.color`, premultiplied), and `box(...).material(m)` draws with it.
- **A render target's picture** composites with `box(...).fill(picture).premultiplied().region(0, 1, 1, 0)`: it is
  premultiplied already, and its row 0 is its bottom.
- **Intensity does not reach text**: a glyph's colour is a byte per channel. Opacity does.
- **A redraw re-executes the whole frame** under its values; what the recording culled stays culled, so record what
  the `CgCullRect` holds.
- **One thread draws a canvas at a time**; separate canvases record concurrently.

## Inside

The front writes one 96-byte record per item (`render.ui.element.CgUiElements`): rect, uv, elliptical radii, border
widths and colours, colour, fill kind, nodes, clip, opacity and intensity, paint. `render.ui.CgUiRecorder` puts runs
of one material and texture into chunks of a `LOOKBACK` raster pass, and the frame's `CgBatcher` joins draws that
overlap nothing between them (Skia's bounds-checked batching). An element batch is one instance over six vertices a
record of a data-less mesh; `canvas/primitives/Element.primitive` pulls its record by vertex index and paints it.
