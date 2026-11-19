# Paint: what fills, outlines and filters anything

Colours, gradients, images, grids, vector shapes, strokes, filters and glass are **values**: you make one once, keep it
in a constant, share it between threads, and hand it to anything that paints. The same value works on the 2D canvas
and on a quad in the world.

| Package | Values |
|---|---|
| `com.crystalgraphics.api.paint` | `CgPaint`: what any fill takes (`CgPaint.color(argb)`, a gradient, a brush, a grid) |
| `api.paint.gradient` | `CgGradient`, `CgSpread` |
| `api.paint.brush` | `CgBrush`, `CgNineSlice`, `CgTiling`, `CgSpriteSheet` |
| `api.paint.pattern` | `CgGrid` |
| `api.paint.effect` | `CgFilter`, `CgShadow`, `CgBackdropFilter` |
| `api.vector` | `CgVectorPath`, `CgStroke`, `CgStrokeCap`, `CgStrokeJoin`, `CgFillRule`, `CgCornerRadii` |

## Gradients

```java
static final CgGradient SKY = CgGradient.linear(180f)                     // CSS's angle: 0 up, clockwise
        .stop(0f, 0xFF203040).stop(1f, 0xFF101820);
static final CgGradient GLOW = CgGradient.radial(0.5f, 1f, 0.5f, 1f)      // centre and radii, fractions of the box
        .stop(0f, 0xFFFFE080).stop(1f, 0x00FFE080);
static final CgGradient HUE = CgGradient.conic(0.5f, 0.5f, 0f)
        .stop(0f, 0xFFFF0000).stop(0.5f, 0xFF00FFFF).stop(1f, 0xFFFF0000);
static final CgGradient STRIPES = CgGradient.linear(45f).repeat()         // two stops at one position: a hard edge
        .stop(0f, 0xFFFFFFFF).stop(0.1f, 0xFFFFFFFF).stop(0.1f, 0x00000000).stop(0.2f, 0x00000000);
static final CgGradient RAMP = CgGradient.between(0f, 0f, 1f, 0f)         // along a segment, Skia's linear
        .stops(new float[]{0f, 0.5f, 1f}, new int[]{0xFF000000, 0xFFFF8000, 0xFFFFFFFF});
```

- Geometry is in fractions of the box it fills, so one gradient fits any size.
- Any number of stops, mixed premultiplied (a fade to transparent never darkens) and dithered.
- `repeat()` and `reflect()` continue past the last stop; by default the end colours hold.
- `colorAt(t)` answers its colour on the CPU, for a swatch or a test.

## Brushes

```java
static final CgBrush ICON = CgBrush.texture(CgTextures.of("mymod:textures/gui/save.png"));
static final CgBrush DIGIT = CgBrush.texture(digits).region(16, 0, 8, 12);                 // pixels of the image
static final CgBrush PANEL = CgBrush.texture(frame).nineSlice(CgNineSlice.uniform(4));     // corners keep their size
static final CgBrush PIXEL_FRAME = CgBrush.texture(frame).nineSlice(CgNineSlice.uniform(3).scaled(2f))
        .sampler(CgSampler.NEAREST_CLAMP);
static final CgBrush BUTTON = CgBrush.sprite("mymod:theme", "button");                     // from a sprite sheet
```

A sprite sheet is one JSON file, `assets/<namespace>/ui/sprites/<path>.json`: a resource pack rethemes by shipping the
JSON and its PNG.

```json
{ "texture": "mymod:textures/gui/theme.png",
  "elements": {
    "button": { "sprite": [29, 1, 13, 13], "border": [1, 1, 1, 1] },
    "panel":  { "sprite": [0, 32, 32, 32], "border": [4, 4, 4, 4], "fillCenter": false, "repeat": "round" }
  } }
```

`sprite` is `[x, y, width, height]` in the image's pixels; `border` the slices' widths, `[left, top, right, bottom]`.
A missing sheet or entry fails by name.

## Grids

```java
static final CgGrid RULED = CgGrid.lines(0x40FFFFFF).cell(16f).width(1f);
static final CgGrid CHECKER = CgGrid.checker(0xFFCCCCCC, 0xFF999999).cell(8f);
```

Lines never shimmer or vanish as the grid shrinks: under a pixel a cell dissolves to the grid's average.

## Vector shapes and strokes

```java
static final CgVectorPath TICK = CgVectorPath.builder().moveTo(2, 8).lineTo(6, 12).lineTo(14, 4).build();
static final CgVectorPath BADGE = CgVectorPath.builder()
        .addRoundRect(0, 0, 24, 16, CgCornerRadii.of(4))
        .addCircle(20, 4, 3)
        .fillRule(CgFillRule.EVEN_ODD)                                    // the circle is a hole
        .build();
static final CgVectorPath ARC = CgVectorPath.builder()
        .moveTo(0, 8).arcTo(8, 8, 0, false, true, 16, 8).build();          // SVG's arc

static final CgStroke PEN = CgStroke.of(2f).cap(CgStrokeCap.ROUND).join(CgStrokeJoin.ROUND);
static final CgStroke DASHED = CgStroke.of(1f).dash(4f, 2f);
CgStroke drawingOn = PEN.trim(0f, progress);                               // a draw-on effect as progress goes 0 to 1
```

## Filters, shadows and glass

```java
static final CgFilter MUTED = CgFilter.saturate(0.3f).then(CgFilter.brightness(0.8f));   // folds into one matrix
static final CgFilter LIFTED = CgFilter.dropShadow(0, 4, 12, 0x80000000);
static final CgFilter SMEAR = CgFilter.blur(0f, 6f);                                       // down only
static final CgShadow CARD = CgShadow.of(0, 2, 8, 0x60000000);                              // CSS's box-shadow
static final CgShadow WELL = CgShadow.of(0, 1, 3, 0x80000000).inset();

static final CgBackdropFilter PANE = CgBackdropFilter.ACRYLIC.tint(0x401F1F23);
static final CgBackdropFilter LENS = CgBackdropFilter.LIQUID.lens(20f, 1.6f).light(0.3f, -1f);
static final CgBackdropFilter WORLD_PANE = CgBackdropFilter.FROSTED.scaled(1 / 32f);       // lengths in blocks
```

`CgBackdropFilter`'s presets are `DEFAULT`, `CLEAR`, `FROSTED`, `LIQUID`, `ACRYLIC`, `MICA` and `PRISM`. A pane sees
anything drawn behind it, other panes included.

## Easy to get wrong

- **Build values once.** Every `stop`, `region`, `tint` and `then` answers a new value; a value made per frame is
  garbage per frame.
- **Colours are straight `0xAARRGGBB`.** An intensity (`CgPaint.color(argb, 4f)`) brightens past white on an HDR
  target.
- **A brush's `premultiplied` says what its pixels hold**, not how you want it drawn: getting it wrong darkens or
  brightens the edges.
- **A gradient needs two stops** to draw; one with fewer fails where it is drawn.
