# UI: drawing on the screen

Everything 2D the engine draws (a HUD, a menu, a tooltip, a minimap, a window's contents) is drawn on a **canvas**,
`com.crystalgraphics.api.canvas.CgCanvas`. You ask it for boxes, images, text, shapes and icons, one after another, and
it draws them in that order, as a painter would. It is Skia's `SkCanvas`, or the browser's `<canvas>` 2D context, with
a browser's rounded borders, shadows, gradients and glass built in.

This guide is for code that draws. Three others sit beside it:

| You want to | Read |
|---|---|
| Draw on the screen from code: boxes, images, text, shapes, layers, glass | **this page** |
| Know what can fill or outline something: colours, gradients, images, nine-slices, grids, strokes, filters, glass looks | [`PAINT.md`](PAINT.md) |
| Make an interface element as a file (a damage number, a name plate, a HUD panel) and animate it | [`WIDGETS.md`](WIDGETS.md) |
| Load fonts, style and lay out text | [`font/README.md`](font/README.md) |

Drawing *in the world* (a quad, a label over a block, a mesh) is the scene's job, never a canvas's: those are scene
components ([`ENGINE_API.md`](ENGINE_API.md) § *The scene and its views*).

---

## 1. Your first drawing

```java
CgRenderStage.HUD.register(CgRenderStage.SCREEN_WIDGETS, frame -> {
    CgCanvas canvas = frame.canvas();                                   // the HUD's canvas, in window pixels

    canvas.fill(8, 8, 200, 40, 0xC0101418);                             // a dark, slightly see-through rect
    canvas.box(8, 56, 200, 40)                                          // a box: describe it, then draw it
          .fill(CgGradient.linear(90).stop(0, 0xFF3060FF).stop(1, 0xFF60C0FF))
          .radius(8)                                                    // rounded corners
          .border(1, 0x40FFFFFF)                                        // a faint white edge
          .shadow(CgShadow.of(0, 4, 12, 0x80000000))                    // a soft shadow under it
          .draw();
    canvas.text(TITLE, 16, 30);                                         // a CgText made once (§ 6)
});
```

That registers a function the engine calls every frame while it draws the HUD. Three things to notice:

- **Coordinates are pixels from the top-left corner**, x to the right, y down.
- **Colours are `0xAARRGGBB`**: alpha first, then red, green, blue. `0xC0101418` is a near-black at 75% opacity.
- **Later items draw over earlier ones.** The box above draws over nothing; text drawn after it would sit on top.

---

## 2. Where you draw

### On a stage

A stage is a point in the engine's frame where your code may draw. The two that take a canvas are `HUD` (over the game,
always) and `SCREEN` (over an open screen or menu). Ask the stage's frame for its canvas:

```java
CgCanvas canvas = frame.canvas();      // window pixels
CgCanvas scaled = frame.scaled();      // the host's GUI pixels: Minecraft's GUI scale applied for you
```

- **One canvas a turn.** `scaled()` after `canvas()` changes the unit for what you draw next; the order is kept.
- `frame.host().guiScale()` is that scale (1 in the harness), for converting a size by hand.
- A world stage (`OPAQUE`, `SURFACES`, `TRANSLUCENT`, `OVERLAY`) has no canvas: asking throws. Draw there with the
  scene's components.

**The order you register at is the layer you draw in:**

| Order | What goes there |
|---|---|
| `CgRenderStage.WORLD_WIDGETS` (-100) | Things that follow a point in the world: damage numbers, name plates, markers. Under every window |
| `CgRenderStage.SCREEN_WIDGETS` (0) | The game's interface. CrystalGUI's pinned windows draw here |
| above 0 | Over the windows: a cursor trail, a debug overlay |

### Over a point in the world

To put something over a block or an entity, project its position with the game's view, then draw at the answer:

```java
Vector2f at = new Vector2f();                                   // kept, reused every frame
CgRenderStage.HUD.register(CgRenderStage.WORLD_WIDGETS, frame -> {
    if (frame.project(x, y + 2, z, at)) {                       // false: behind the camera
        frame.canvas().image(MARKER, at.x - 8, at.y - 8, 16, 16);
    }
});
```

For a whole element that follows something (a name plate with a health bar), a widget layer does the projecting,
sorting and pooling for you: [`WIDGETS.md`](WIDGETS.md) § 6.2.

### Into a texture of your own

A canvas you make yourself draws into any target you give it, in a recording you own:

```java
CgCanvas canvas = new CgCanvas();                                        // keep it: it reuses its memory
canvas.begin(recording, target, CgLoad.CLEAR, CgCamera.screen(w, h), 1f);
canvas.box(0, 0, w, h).fill(CgGradient.linear(90).stop(0, top).stop(1, bottom)).draw();
canvas.end();
```

`begin` takes the recording, the target, whether to clear it first, the camera (`CgCamera.screen(w, h)` for a flat 2D
view) and the scale: target pixels per unit you draw in.

### Outside any stage

A loading screen or a host's own overlay draws over whatever is current and executes at `end()`:

```java
canvas.beginNow(width, height);                    // the current target's pixels; render thread, inside a frame
canvas.fill(0, 0, width, height, 0x80000000);
canvas.end();                                      // drawn here
```

Inside a stage, always use the stage's canvas instead: it lands in order with the rest of the frame.

---

## 3. Boxes and images

Almost everything on a screen is a box. A box is a rectangle with a **fill**, and optionally rounded corners, a border,
a shadow, glass behind it, a blend or a material of its own. Describe it, then call `draw()`:

```java
canvas.box(x, y, 160, 32)
      .fill(0xFF202630)                                    // what is inside: any paint (PAINT.md)
      .radius(6)                                           // round corners; radii(CgCornerRadii.of(8, 8, 0, 0)) per corner
      .border(1, 0x30FFFFFF)                               // or border(top, right, bottom, left, four colours)
      .shadow(CgShadow.of(0, 4, 12, 0x80000000))           // CSS's box-shadow; .inset() puts it inside the border
      .draw();
```

The short forms, for the common cases:

```java
canvas.fill(x, y, w, h, 0xFF2060C0);                                   // one colour
canvas.fill(x, y, w, h, CgGradient.radial(0.5f, 0.5f, 0.5f, 0.5f).stop(0, glow).stop(1, 0));
canvas.fill(x, y, w, h, CgGrid.checker(0xFFC0C0C0, 0xFF404040).cell(8));
canvas.image(texture, x, y, 32, 32);                                   // an image, stretched over the rect
canvas.image(CgBrush.sprite("mymod:hud", "panel"), x, y, w, h);        // a sprite: its nine-slice and tiling
canvas.svg(CgSvg.of("mymod:ui/icons/save.svg"), x, y, 16, 16);         // an icon (§ 8)
```

More a box can do:

```java
canvas.box(x, y, 16, 16).mask(icon, 0xFFFFD040).draw();                         // an image's shape, in one colour
canvas.box(x, y, 12, 16).fill(CgBrush.texture(digits).layer(7)).draw();        // layer 7 of a texture array
canvas.box(x, y, 12, 16).mask(CgBrush.texture(digits).layer(7), gold).draw();  // ...as a silhouette
canvas.box(x, y, 64, 64).fill(spark).blend(CgBlendMode.ADDITIVE).draw();       // light added onto what is behind
```

- **A box reuses one builder.** Describe and draw it in one statement; never keep a `CgCanvasBox`.
- **What a fill can be** (colours, gradients, brushes with nine-slices and tiling, grids) is [`PAINT.md`](PAINT.md).

### How a box blends

A box lays itself over what is behind it (`PREMULTIPLIED`, normal "over") unless `blend(...)` says otherwise:

| Blend | What it does | For |
|---|---|---|
| `PREMULTIPLIED` | over, by its alpha | everything, by default |
| `ADDITIVE` | adds its light | glows, sparks, highlights |
| `MODULATE` | multiplies | darkening, tinting what is behind |
| `SUBTRACT` | subtracts | rare effects |
| `MASK` | keeps what is behind only where the box is opaque | cutting out |

The blend belongs to that one box: it costs no extra pass. A box with its own `material(...)` blends as that
material's state says instead (§ 13).

---

## 4. Moving, fading and clipping

The canvas keeps a **state**: a transform (where and how big things land), an opacity, an intensity and a clip (where
anything may show). `save()` remembers the state, `restore()` puts it back. Wrap every change in a pair:

```java
canvas.save();
canvas.translate(cx, cy).rotate(30).scale(2, 2);             // later items move, turn and grow about (cx, cy)
canvas.opacity(0.5f);                                        // later items at half opacity
canvas.clip(0, 0, 64, 64, CgCornerRadii.of(8));              // and only inside this rounded square
canvas.image(icon, -16, -16, 32, 32);
canvas.restore();                                            // transform, opacity and clip as before
```

| Call | Does |
|---|---|
| `translate(x, y)`, `rotate(degrees)`, `scale(sx, sy)` | move, turn (clockwise on screen) and scale what follows |
| `concat(m00, m10, m01, m11, m02, m12)` | apply any affine before the transform now |
| `setTransform(...)` | replace the transform outright: for a caller that computes each item's whole matrix |
| `opacity(f)` | multiply later items' opacity by `f` |
| `intensity(f)` | multiply their colour by `f`: past 1, brighter than white on an HDR target |
| `clip(x, y, w, h)` / `clip(x, y, w, h, radii)` | cut later items to a rect, intersected with the clip now |
| `clip(path)` | cut to any shape (§ 9) |
| `pixelSnap(false)` | stop snapping edges to pixels (§ 14) |

Reading the state back, for a caller that decides in pixels:

```java
float[] m = canvas.transform(new float[6]);          // a b c d tx ty: your units to the target's pixels
float[] can = canvas.clipBounds(new float[4]);       // x0 y0 x1 y1: what can still show
float alpha = canvas.currentOpacity();               // the opacity every item now takes
float px = canvas.deviceScale();                     // device pixels per unit: what a level of detail keys on
float x = canvas.snapX(16.3f);                       // the nearest X on a whole device pixel; as given if rotated
```

- **Cull with `outsideClip(x0, y0, x1, y1)`**, device pixels against `clipBounds`. Before painting a long list, skip
  what lies wholly outside it: it would draw nothing.
- **Every `save` needs its `restore`.** `end()` drops whatever is left on the stack.

---

## 5. Lines and triangles

For a line, a curve or a filled triangle, use a **spline** or a **triangle**: drawn per pixel from their distance to
the shape, so sharp at any zoom and as cheap as a box. Build and draw in one expression:

```java
canvas.spline().line(x0, y0, x1, y1).width(1f).color(argb).draw();
canvas.spline().from(x0, y0).via(cx, cy).to(x1, y1).width(3f, 0.5f).colors(a, b).draw();   // tapered, two colours
canvas.spline().cubic(x0, y0, c1x, c1y, c2x, c2y, x1, y1).width(2f)
        .cap(CgStrokeCap.ROUND).arrowAtEnd().draw();                                       // a wire with an arrow
canvas.triangle(x0, y0, x1, y1, x2, y2).color(argb).draw();
canvas.triangle(x0, y0, x1, y1, x2, y2).ramp(from, x0, y0, to, x1, y1).draw();             // a gradient along a line
```

- **`width` is the full width** in your units, and the transform scales it. Two widths taper from start to end.
- **Edges antialias over about a pixel** at any scale; `feather(4f)` is a soft edge of 4 units that scales.
- **Triangles that share an edge** (a fan, a strip) turn that edge's antialiasing off with
  `antialias(CgCanvasTriangle.Edge.P1_P2)`, so the seam is not drawn twice.
- A shape with holes, joins or a fill rule is a **path** instead (§ 7).

---

## 6. Text

A `CgText` is a piece of text as a value: what it says, its font, size, style and where it wraps. Make it once, keep
it, measure it on any thread; the canvas draws it at a point.

```java
static final CgText TITLE = CgText.of("Spawn").font(font).size(24).style(HEADING);

canvas.text(TITLE, 20, 40);                               // as its style paints it
canvas.text(TITLE.text("Respawn"), 20, 40, 0xFFFFD040);   // another string, every layer tinted gold
float wide = TITLE.measure().totalWidth();                // its size, before drawing it
```

Text follows the canvas's state like anything else: it turns, scales and fades, and its glyphs are rasterised at the
size they land on the screen, so scaled text stays sharp. `maxWidth` wraps it, in your units.

```java
canvas.save().translate(x, y).rotate(-12).scale(1.4f, 1.4f);
canvas.text(CgText.of(body).family(ui).size(14).maxWidth(320), 0, 0);
canvas.restore();
```

- **A new glyph takes a frame or two to generate.** Until then it draws from a coarser tier, and
  `canvas.degradedDraws()` counts it. Kept drawing refuses to keep that frame (§ 12), so nothing keeps the blur.
- **`canvas.textGamma(...)`** sets how text coverage is corrected; it is not saved by `save()`.
- **`canvas.textDraw()`** is every knob of the text renderer with nothing made per draw (a cached layout, a pixel
  size, a layer's scope): `canvas.textDraw().layout(cached).family(ui).at(x, y).color(argb).submit()`. Like every
  item, it is drawn at the canvas's opacity.
- **Intensity does not reach text**: a glyph's colour is a byte a channel. Opacity does.
- Fonts, styles and layout: [`font/README.md`](font/README.md). Text in the world is a scene component,
  `scene.text(...)` ([`ENGINE_API.md`](ENGINE_API.md)).

---

## 7. Shapes: paths and strokes

A `CgVectorPath` is any shape (lines, curves, arcs, circles, holes), and a `CgStroke` says how to draw its outline.
Both are values: build them once ([`PAINT.md`](PAINT.md) § *Shapes and strokes*). The canvas fills a path or strokes
it:

```java
static final CgVectorPath TICK = CgVectorPath.builder().moveTo(2, 8).lineTo(6, 12).lineTo(14, 4).build();
static final CgStroke PEN = CgStroke.of(2f).cap(CgStrokeCap.ROUND).join(CgStrokeJoin.ROUND);

canvas.fill(BADGE, 0xFF3070F0);                                       // the inside, by its fill rule
canvas.fill(BADGE, CgGradient.linear(90).stop(0, 0xFF2050FF).stop(1, 0xFFFF4020));   // laid over the path's bounds
canvas.stroke(TICK, PEN, 0xFF40C060);                                 // the outline
canvas.stroke(RING, PEN.dash(0f, 6f), 0xFFFFFFFF);                    // dots: zero-length dashes with round caps
canvas.stroke(RING, PEN.trim(0f, progress), 0xFF40C060);              // drawn on as progress goes 0 to 1
```

Every path is drawn with exact coverage: how much of each pixel the shape covers, computed, not sampled. So edges are
smooth at any size or angle.

- **Small, straight and on whole pixels** (the default: snapping on, at most `CgPathCache.MAX_PX` pixels across), a
  path is rasterised once into an atlas every canvas shares and drawn from it every frame after: nearly free.
- **Rotated, skewed, large or unsnapped**, it is rasterised for its frame, as exactly, at the cost of a pass of its
  own.
- **A stroke is outlined in the path's units**, so a squashed transform squashes the pen too. A stroke thinner than a
  pixel draws a pixel wide and fades instead of breaking up.
- **A brush or a grid on a path** fills the path's bounds through a mask layer (§ 9).
- **Two canvases on two threads may draw one path**: whichever reaches it first builds it, and the other draws its
  own copy until that build has executed.

---

## 8. Icons: SVG

An SVG icon is a `CgSvg`: parsed once, shared, safe on any thread. It draws as a brush, so anywhere a brush goes, an
icon goes too; `canvas.svg(...)` is the short form.

```java
static final CgSvg SAVE = CgSvg.of("mymod:ui/icons/save.svg");             // null if it cannot be read

canvas.svg(SAVE, x, y, 16, 16);                                             // its own colours, fitted and centred
canvas.image(CgBrush.svg(SAVE).color(theme.text()), x, y, 16, 16);          // currentColor is your colour
canvas.image(CgBrush.svg(SAVE).color(text).tint(0x80FFFFFF), x, y, 16, 16); // and everything at half alpha
canvas.image(CgBrush.svg(OUTLINE).color(accent).flat(1.5f), x, y, 24, 24);  // one colour, strokes 1.5 units wide
canvas.image(CgBrush.svg(BAR).fit(CgFit.FILL), x, y, 200, 8);               // stretched, not fitted
canvas.box(x, y, 32, 32).fill(CgBrush.svg(SAVE)).border(1, 0xFF808080).draw();   // in a box, its border over it
```

A sprite sheet's icons are its `<symbol>`s, each in its own `viewBox`:

```java
CgSvg arrow = CgSvg.of("mymod:ui/icons/sheet.svg#arrow");                  // or CgSvg.of(sheet).symbol("arrow")
```

An icon is always sharp. Up to `CgPathCache.MAX_PX` pixels and not rotated, each of its shapes is rasterised once at
the size shown, into the atlas paths share, and drawn pixel for pixel. Larger or turned, it is rasterised for its
frame.

- **Snapping decides the grid.** Snapped (the default), an icon lands on whole pixels. Unsnapped (`pixelSnap(false)`,
  an icon mid-animation), it draws between pixels from its exact raster, or from one of sixteen sizes per doubling,
  filtered, so a moving icon looks as it does at rest.
- **Its curves are flattened for the size drawn**: finer as it grows.
- **Export with presentation attributes.** A `<style>` block or a `class=` fails the load, naming the file
  (Illustrator: "Presentation Attributes"; SVGO: `inlineStyles`).
- **A drop shadow** (`feDropShadow`, or the classic blur-offset-flood-merge chain) draws under the shapes it filters.
  On whole pixels it is built once and drawn from the atlas like the icon; between pixels, or past 256 pixels a side,
  it is filtered every frame. Inside a turned or scaled movable node (§ 12) it is skipped and logged once. Other
  filters are not drawn.
- **Not drawn**: text, `clipPath`, masks, dashes.
- **Reloading**: the engine's resource reload (`CgAssetReloader.reload`) calls `CgSvg.revalidate()`, which drops only
  the files that changed; `CgSvg.generation()` tells a holder its document may be stale. `CgSvg.preload(paths)`
  parses on the job pool before the first frame.

---

## 9. Groups drawn as one picture: layers

Sometimes a group must be treated as a single picture: faded as one (so overlaps do not show through), blurred as one,
cut by a shape or a fade, or added as light. A **layer** draws what is inside it into a picture of its own, then lays
that picture over what is under it. Skia's `saveLayer`; CSS's `opacity`, `filter` and `mask-image` on a group.

```java
try (CgCanvasLayer l = canvas.layer().opacity(0.5f)) {            // the two squares fade as one picture
    canvas.fill(x, y, 80, 80, 0xFF4060FF);
    canvas.fill(x + 40, y + 40, 80, 80, 0xFFFF6040);
}
```

| Layer | Gives |
|---|---|
| `canvas.layer().opacity(0.5f)` | the group at half opacity, overlaps hidden |
| `.filter(CgFilter.blur(6f))` | the group blurred (sigma 6) |
| `.filter(CgFilter.dropShadow(0, 4, 8, 0x80000000))` | a shadow of the group's shape |
| `.filter(CgFilter.grayscale(1f))` | any colour filter on the group ([`PAINT.md`](PAINT.md) § *Filters*) |
| `.mask(roundedPath)` | the group cut to a shape |
| `.mask(CgGradient.linear(90).stop(0, 0xFFFFFFFF).stop(1, 0))` | the group faded out along a gradient |
| `canvas.layer(x, y, w, h).blend(CgBlendMode.ADDITIVE)` | the group added as light, its picture bounded to a rect |

A clip to any shape is a layer too, closed by the `restore()` that pops it:

```java
canvas.save();
canvas.clip(starPath);                       // inside a save(): nothing else would close it
canvas.image(photo, x, y, 200, 200);
canvas.restore();                            // the clip's layer composites here
```

- **Set a layer up before drawing in it**: its opacity, filter, mask and blend are read as it opens.
- **Close every layer**; try-with-resources does. A turn that ends with one open drops what it drew and logs it.
- **A layer is as large as what it can reach**: its rect if you give one, else the clip, grown by how far its filter
  spreads, cut to the target. Give it a rect when you can: a layer under no clip is the whole screen.
- **Each layer costs a pass and a texture.** Use one when you need a group; to fade a single item, `opacity` on the
  canvas is free.
- **Not inside a turned or scaled movable node**: its picture's axes would not be the node's, so it is refused.
- **A `MODULATE` box cannot land in a layer**: multiply has no layer form. Draw it outside.

---

## 10. Glass

`box(...).backdropFilter(f)` is CSS's `backdrop-filter`: what is already drawn under the box is captured, blurred,
coloured and bent by a lens at its edge, then shown inside the box's corners.

```java
canvas.box(x, y, 320, 200).radius(16).backdropFilter(CgBackdropFilter.ACRYLIC).draw();
canvas.box(x, y, 320, 200).radius(16).backdropFilter(CgBackdropFilter.LIQUID)
      .fill(0x10FFFFFF).border(1, 0x30FFFFFF).draw();                       // its own paint over the glass
canvas.box(x, y, 320, 200)
      .backdropFilter(CgBackdropFilter.of(CgFilter.grayscale(1f).then(CgFilter.blur(8f)))).draw();
```

The looks (`ACRYLIC`, `MICA`, `LIQUID`, `FROSTED` and the rest) and how to make your own are
[`PAINT.md`](PAINT.md) § *Glass*.

- **Draw a pane after what it covers.** It sees what was drawn before it, other panes included.
- **One capture serves many panes**: while nothing is drawn over what a pane samples and no layer opens, the panes
  after it reuse it. A UI that holds still captures once a frame.
- **Inside a layer**, a pane sees the screen with every open layer over it, as it will look once composited.
- **Inside a kept layer (§ 12) glass draws flat**, in its `fallback` colour: a kept picture of glass would be stale.
- `CgBackdrop` is the capture and blur alone, for a drawer composing its own effect.
- **In the world** a pane is a quad: `scene.quad(w, h, null).radii(r).backdropFilter(f.scaled(1 / 32f))`, the look
  the same with its lens in blocks. It sees the scene and the panes behind it.

---

## 11. Pictures of other things

```java
CgView lab = CgView.of(labScene);                          // once, kept: it holds the view's history
canvas.view(lab, 20, 40, 320, 200);                        // its scene drawn now, at the rect's size in pixels
canvas.photograph(320, (w, h, rgb) -> thumbnail = rgb);    // what the canvas drew so far, read back later
```

A UI that records off the render thread (a CrystalGUI document) shows a view as a picture instead: drawn at the draw
point, at the size it was last shown, from a camera copied in.

```java
CgViewPicture viewport = CgViewPicture.of(CgView.of(scene)).background(0xFF3D3D3D);
viewport.camera(camera);                                    // copied; give it again to move it
canvas.picture(viewport, 20, 40, 320, 200);                 // any thread; the latest drawing, from the next frame
```

- **A view** is an editor's viewport, a preview or a minimap. Its picture is kept on the view and remade only when
  the rect's size changes. Its corners are square: for rounded ones, draw it in a layer masked by a path.
- **`canvas.view` draws now**, so it is the render thread's; **`CgViewPicture`** is any thread's, a frame behind.
- **A photograph never waits.** Its sink runs on the render thread about three frames later, rows top first, RGB. At
  most three are on their way; a fourth returns `false`.

A smaller copy of a texture, each pixel the average of what it covers, is a compute op, then an image:

```java
CgComputePass pass = recording.compute("thumbnail");
CgGpuOps.shrink(pass, window, 0, 0, window.getWidth(), window.getHeight(), thumbnail, 0, 0, 192, 108);
pass.end();
canvas.image(thumbnail, x, y, 192, 108);
```

---

## 12. Making it cheap

A canvas is fast by default: boxes, text and icons that do not overlap join into a handful of draws whatever order
you give them. When a UI grows, four tools keep its frames cheap. Take the first that fits.

| Tool | Keeps | Reach for it when |
|---|---|---|
| **Paint chunks** | what a piece of UI recorded, replayed while it would come out the same | a panel, a list row, a widget that rarely changes |
| **Movable nodes and faders** | a group's transform or opacity as a value you set, nothing redrawn | something scrolls, drags, spins or fades |
| **Kept layers** | a picture across frames, redrawing only what changed | a whole window, a big panel, a preview of either |
| **Recording off the render thread** | a whole frame recorded on another thread, shown on the render thread | a heavy document or HUD |

### Paint chunks: keeping what comes out the same

```java
CgPaintChunk kept = new CgPaintChunk();                       // held with the thing it keeps

if (unchanged && canvas.replay(kept)) return;                 // added to this frame, nothing drawn
canvas.beginChunk();
paintIt(canvas);
CgPaintChunk.Refusal why = canvas.endChunk(kept);             // NONE, or why it kept nothing
```

You decide when it would come out the same (`unchanged`: your own key). A replay adds the recorded drawing to this
frame without running your paint code; boxes, strokes, triangles and text are all kept.

- **A refusal says why.** One that built something on first use (a glyph, an icon's raster) is worth taking again a
  frame later; one that opened a pass (a layer, an icon with a shadow) will be refused every time.
- **`kept.differs(fresh)`** checks a key: paint into a second chunk and compare. The answer names the first draw that
  differs.
- The widget stack keeps every widget this way ([`WIDGETS.md`](WIDGETS.md) § 6.4).

### Movable nodes and faders: changing a value, not the drawing

Open a **movable node** around what moves, or a **fader** around what fades. Inside a chunk, a replay then sets the
node's value alone, as a browser's compositor animates a transform or an opacity without repainting:

```java
canvas.beginChunk();
canvas.save();
int spin = canvas.movable(cos, sin, -sin, cos, 0, 0);        // this transform, then what is drawn inside
int fade = canvas.fader(0.5f);                               // what is drawn inside, at half opacity
paintBadge(canvas);
canvas.restore();
canvas.endChunk(kept);

// a later frame
if (canvas.replay(kept)) {
    canvas.save();
    canvas.moved(kept.node(spin), cos2, sin2, -sin2, cos2, 0, 0);   // turned again, nothing drawn
    canvas.nodeOpacity(kept.effect(fade), 0.8f);                   // faded again, nothing drawn
    canvas.restore();
}
```

A scroll is a movable node by translation:

```java
canvas.save();
int content = canvas.translate(0, -scrollY).movable();
paintRows(canvas);
canvas.restore();
```

- **A fader fades each item, not the group**: overlapping items show through each other, as Slate's render opacity
  does. For one picture, use `layer().opacity(f)`.
- **An axis-aligned scale stays baked** into what is drawn inside a movable node, so text rasterises at it. Replay
  under the same scale (`transform(out)` reads it), or paint again.
- **Content that must not scroll** (a sticky header, a frozen column) leaves the node and comes back to it with
  `canvas.enter(content)`, rather than opening a second one.
- `nodeTransform(node, ...)` sets a node's affine outright, in its parent's space.

### Kept layers: a window's picture

A kept layer is a picture kept from frame to frame: a frame that changes nothing in it composites the picture and
records nothing, and a frame that changes a little redraws only that part. The way a desktop compositor keeps each
window's surface.

```java
static final CgKeptLayer WINDOW = new CgKeptLayer().reserve(1280, 800);    // once, kept: it owns its texture

try (CgKeptLayer.Walk walk = canvas.kept(WINDOW, x, y, w, h, revision)) {  // composited when it closes
    if (walk.draws()) {                       // false: the picture stands, nothing is recorded for it
        paintWindow(canvas);                  // draw everything
        walk.damage(cx, cy, 2, 18);           // but only this part lands; damage nothing for the whole picture
    }
}
canvas.picture(WINDOW, tx, ty, 320, 200);                  // its picture somewhere else: a preview
canvas.thumbnail(WINDOW, tx, ty, 160, 100);                // filtered down, refreshed about every 100 ms
canvas.picture(WINDOW, 0, 0, 640, 32, tx, ty, 320, 16);    // part of it: its title bar
```

Something that moves inside the picture (a caret) damages its old place as it moves:

```java
static final CgKeptLayer.Placement CARET = new CgKeptLayer.Placement();
CARET.place(walk, cx, cy, 2, 18);
canvas.fill(cx, cy, 2, 18, caretColour);
```

- **It is held while the `revision` you pass and its size in pixels stay the same.** Bump the revision whenever what
  it shows changes. A move is held too: the same picture lands at the new place.
- **Drawn whole** the first time, after a resize and after `invalidate()`; otherwise only the damage executes. Always
  draw everything on a walk that draws: damage limits where drawing lands, not what is recorded.
- **Glass inside draws flat** (§ 10). Keep live glass outside kept layers.
- **Its texture** is rounded up to 64 pixels, at least `reserve(w, h)`, at most 4096 a side. A canvas frees a kept
  layer nobody asked for in 300 frames unless `keep(true)`; call `delete()` at a context's teardown.

### Recording off the render thread

A document or a heavy HUD can record on its own thread and be shown on the render thread. `CgCanvasRecorder` paints
into a picture of its own and seals a `CgCanvasFrame`, ordered and packed off the render thread; `CgCanvasPresenter`
executes it at a stage and composites the picture.

```java
CgCanvasRecorder recorder = new CgCanvasRecorder();          // one per document
CgCanvasPresenter presenter = new CgCanvasPresenter();

// the document's thread
CgCanvas canvas = recorder.begin(w, h);
paintDocument(canvas);
CgCanvasFrame next = recorder.seal();

// the render thread, at a SCREEN or HUD stage
CgRenderStage.SCREEN.register(CgRenderStage.SCREEN_WIDGETS, stage -> {
    if (next != null) presenter.present(stage, next);        // executed, composited, kept
    else presenter.presentAgain(stage);                      // the kept picture, nothing executed
});
```

A movable node recorded there moves later through the frame's values, nothing recorded again:

```java
presenter.presented().values().translate(list, 0, -scrolled);
presenter.redraw(stage);                                     // executed again, nothing recorded
```

How much of a scroller to record so that scrolling by values still shows rows, and when to record again, is Blink's
rule:

```java
CgCullRect.forScroller(scrollX, scrollY, viewW, viewH, contentW, contentH, scale, cull);   // record what cull holds
if (CgCullRect.changedEnough(cull, next, contentW, contentH, scale)) recordAgain();
```

A redraw re-executes the frame under its new values; what the recording culled stays missing, so record what the
cull rect holds.

---

## 13. A material of your own

A box can draw through any UI material: a `.shader` that opens `#domain ui`. Its `surface()` runs over the box's paint
(`s.color`, premultiplied) for every pixel the box covers:

```java
static final CgMaterial SHINE = CgMaterial.load("mymod:shaders/ui/shine.shader");
canvas.box(x, y, 120, 32).fill(0xFF3060C0).radius(6).material(SHINE).draw();

CgMaterialInstance hot = SHINE.instance().vec4("_Parameters", 1f, 0f, 0f, 0f);   // a value per box: an instance each
canvas.box(x, y, 120, 32).material(hot).draw();
```

- The format is [`MATERIAL_FORMAT.md`](MATERIAL_FORMAT.md) § 8; start from `crystalgraphics:shaders/reference/ui.shader`.
- **Precache it** ([`PRECACHE.md`](PRECACHE.md)), or its first frame waits for the compile.
- For an API that takes a material instead of a paint, `CgUiMaterials` has ready ones: `image(texture)` (and
  `image(texture, blend)`) draws a texture over everything; `element(blend)` is the canvas's own.

```java
static final CgMaterialInstance SPLASH = CgUiMaterials.image(splash);   // made once, deleted by its owner
target.drawMaterial(SPLASH);                                            // a CgRenderTarget, covered by the texture
```

---

## 14. Easy to get wrong

- **Later draws over earlier**, wherever they overlap. Items that overlap nothing between them join one draw whatever
  their order, so interleaving boxes and text costs nothing where they do not touch.
- **Edges snap to whole pixels** when the transform is straight (translation and positive scale), so boxes stay crisp.
  `pixelSnap(false)` turns it off, for something sliding smoothly; rotated items antialias instead.
- **A square-cornered box on whole pixels draws with no edge ramp**, as Skia's pixel-aligned rects do: the ramp's
  corner would reach a pixel past it, a faint halo where an image's edge texels touch its rect.
- **Rotation and skew cost a node**, up to 4096 a recording: draw a rotated group under one `rotate`, not each item
  under its own.
- **Square clips are free; rounded or rotated ones nest**, at most `CgClipTree.MAX_DEPTH` deep past the square ones.
  Deeper than that becomes a mask layer, which needs a `save()` to close it.
- **Colours are straight `0xAARRGGBB`**: never premultiply them yourself. Gradients mix premultiplied, so a fade to
  transparent never turns grey.
- **A render target's picture** draws with `box(...).fill(picture).premultiplied().region(0, 1, 1, 0)`: it is
  premultiplied already, and its first row is its bottom.
- **Draw from one thread at a time.** Separate canvases record at the same time on separate threads.
- **Build values once.** A gradient, brush, path or text made every frame is garbage every frame
  ([`PAINT.md`](PAINT.md) § *Easy to get wrong*).

---

## Where the code is

| Type | Role |
|---|---|
| `api.canvas.CgCanvas` | the canvas: its state, and every kind of item |
| `CgCanvasBox`, `CgCanvasSpline`, `CgCanvasTriangle` | the builders `box()`, `spline()`, `triangle()` hand you |
| `CgCanvasLayer`, `CgKeptLayer`, `CgPaintChunk` | layers, kept pictures, kept drawing |
| `CgCanvasRecorder`, `CgCanvasFrame`, `CgCanvasPresenter`, `CgCullRect` | recording off the render thread |
| `CgPhotograph`, `CgUiMaterials` | read-backs; the shipped UI materials |
| `render.ui` | the engine behind it: records, recorder, paths, SVG, the capture ([`render/ui/CLAUDE.md`](../core/src/main/java/com/crystalgraphics/render/ui/CLAUDE.md)) |

**How it draws.** Each item becomes one 96-byte record (`render.ui.element.CgUiElements`): its rect, corners, border,
colours, fill, clip and opacity. Records of one material and texture run together into draws, and the frame joins
draws that overlap nothing between them, as Skia batches by bounds. An element draw is six vertices a record with no
vertex buffer: `shaders/ui/primitives/Element.primitive` reads its record by vertex index and paints it. Splines,
triangles and path coverage are `CURVE` records (`Curve.primitive`); text is a `QUAD` record a glyph
(`Text.primitive`).
