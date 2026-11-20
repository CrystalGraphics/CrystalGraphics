# Paint: what fills, outlines and filters anything

Colours, gradients, images, grids, vector shapes, strokes, filters and glass are **values**: you make one once, keep it
in a constant, share it between threads, and hand it to anything that paints. The same value works on the 2D canvas
and on a quad in the world.

| Package | Values |
|---|---|
| `com.crystalgraphics.api.paint` | `CgPaint`: what any fill takes (`CgPaint.color(argb)`, a gradient, a brush, a grid); `CgColor`, a colour held in linear light |
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
static final CgBrush BANNER = CgBrush.texture("mymod:textures/hud/banner.png").size(224, 56);   // by path, loaded at first draw
static final CgBrush SHADED = CgBrush.material(bannerMaterial);                             // a UI-domain material
static final CgBrush SEVEN = CgBrush.texture(digitArray).layer(7);                         // a layer of a 2D array
```

One brush serves the canvas and widgets alike: an image widget draws its brush, region, slices and tiling included.
A brush by path samples as its texture's settings file says unless `sampler(...)` sets one; `CgBrush.WHITE` is a
plain box in its tint, `CgBrush.NONE` draws nothing.

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

## Colours

Colours are written as `0xAARRGGBB` sRGB, the way they are picked. Where one must be held, blended or converted, it
is a `CgColor`: linear light, straight alpha, Unreal's `FLinearColor`.

```java
CgColor gold = CgColor.argb(0xFFFFC040);                      // decoded to linear
CgColor muted = CgColor.css("oklch(70% 0.1 250)");             // hex, rgb(), hsl(), oklab(), oklch(), names
int mid = gold.lerpOklab(muted, 0.5f).toArgb();                // mixed as CSS mixes, back to ARGB
double ratio = CgColor.argb(text).contrast(CgColor.argb(bg));  // WCAG 2: 4.5 for body text
CgColor hot = gold.intensity(3f);                              // past white, for an HDR target
```

`toHsv`, `toHsl`, `toOklab` and `toOklch` read it back; `CgColor.CODEC` reads `0xAARRGGBB`, a linear
`[r, g, b, a]` or a CSS string, and writes losslessly, HDR included.

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

// CSS's backdrop-filter: grayscale(1) contrast(1.2) blur(8px), a CgFilter chain over what is behind
static final CgBackdropFilter MUTED_PANE = CgBackdropFilter.of(CgFilter.grayscale(1f).then(CgFilter.contrast(1.2f))
        .then(CgFilter.blur(8f)));
// glass: the chain, then a lens at the pane's edge
static final CgBackdropFilter LENS = CgBackdropFilter.of(CgFilter.blur(4f).then(CgFilter.saturate(1.8f)))
        .lens(CgBackdropFilter.Lens.LIQUID.light(0.3f, -1f));
// Fluent acrylic's recipe: blur, saturate, luminosity toward the tint, the tint laid over
static final CgBackdropFilter PANE = CgBackdropFilter.acrylic(10f, 1.5f, 0.1f, 0x401F1F23);
static final CgBackdropFilter WORLD_PANE = CgBackdropFilter.FROSTED.scaled(1 / 32f);       // the lens in blocks
```

A backdrop filter is a `CgFilter` chain and an optional `CgBackdropFilter.Lens` (refraction, dispersion, rim, glow, grain). The
chain takes blurs, every colour function and `CgFilter.tint` and `CgFilter.luminosity` (Fluent's, past CSS); a drop
shadow or a material is a layer's and is refused. The pane costs one capture, a blur ladder and one pass: the colour
steps fold into that pass. Presets: `DEFAULT`, `CLEAR`, `FROSTED`, `LIQUID`, `ACRYLIC`, `MICA`, `PRISM`; the lenses
`CgBackdropFilter.Lens.DEFAULT`, `CLEAR`, `LIQUID`, `PRISM`. A pane sees anything drawn behind it, other panes included.

## Easy to get wrong

- **Build values once.** Every `stop`, `region`, `tint` and `then` answers a new value; a value made per frame is
  garbage per frame.
- **Colours are straight `0xAARRGGBB`.** An intensity (`CgPaint.color(argb, 4f)`) brightens past white on an HDR
  target.
- **A brush's `premultiplied` says what its pixels hold**, not how you want it drawn: getting it wrong darkens or
  brightens the edges.
- **A gradient needs two stops** to draw; one with fewer fails where it is drawn.
