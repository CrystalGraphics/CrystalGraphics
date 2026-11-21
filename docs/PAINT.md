# Paint: what fills, outlines and filters anything

**Paint** is what goes inside a shape: a colour, a gradient, an image, a grid. Beside it sit the values that change a
shape's look: its outline (a stroke), its shadow, a filter over it, and glass behind it. This page is every one of
them, and how to choose.

All of them are **values**: you make one once, keep it in a constant, share it between threads, and hand it to
anything that paints. The same value works on the 2D canvas ([`UI.md`](UI.md)), in a widget's file
([`WIDGETS.md`](WIDGETS.md)) and on a quad in the world.

```java
static final CgGradient SKY = CgGradient.linear(180f).stop(0f, 0xFF203040).stop(1f, 0xFF101820);   // made once

canvas.fill(0, 0, w, h, SKY);                             // on the canvas
canvas.box(x, y, 200, 40).fill(SKY).radius(8).draw();     // in a box
```

## Which one?

| You want | Use | Section |
|---|---|---|
| One colour | `0xAARRGGBB`, or `CgPaint.color(argb)` | [Colours](#colours) |
| A colour to hold, mix, convert or read from CSS | `CgColor` | [Colours](#colours) |
| A smooth blend between colours | `CgGradient`: linear, radial, conic | [Gradients](#gradients) |
| An image, part of one, a stretchable panel, a tiled pattern | `CgBrush` | [Images: brushes](#images-brushes) |
| An icon, sharp at any size | `CgBrush.svg(...)` | [Images: brushes](#images-brushes) |
| Ruled lines or a checkerboard | `CgGrid` | [Grids](#grids) |
| A custom shape, or how an outline is drawn | `CgVectorPath`, `CgStroke` | [Shapes and strokes](#shapes-and-strokes) |
| A box's shadow | `CgShadow` | [Shadows](#shadows) |
| Blur, recolour or shadow a group | `CgFilter` | [Filters](#filters) |
| Frosted or liquid glass | `CgBackdropFilter` | [Glass](#glass) |

---

## Colours

**Write a colour as `0xAARRGGBB`**, the way it is picked: alpha first, then red, green and blue, each `00` to `FF`, in
sRGB, with straight (not premultiplied) alpha.

```java
canvas.fill(x, y, w, h, 0xFF2060C0);                   // opaque blue
canvas.fill(x, y, w, h, 0x80000000);                   // black at half opacity
canvas.fill(x, y, w, h, CgPaint.color(0xFFFFC040, 4f)); // gold four times as bright: past white on an HDR target
```

**Where a colour is held, mixed or converted, it is a `CgColor`**: linear light, straight alpha, Unreal's
`FLinearColor`. "Linear" means its numbers are proportional to light, so mixing two of them looks right.

```java
CgColor gold = CgColor.argb(0xFFFFC040);                       // from a picked colour
CgColor muted = CgColor.css("oklch(70% 0.1 250)");             // hex, rgb(), hsl(), oklab(), oklch(), names
CgColor alsoGold = CgColor.hex("#FFC040");
int mid = gold.lerpOklab(muted, 0.5f).toArgb();                // mixed as CSS mixes, back to ARGB
double ratio = CgColor.argb(text).contrast(CgColor.argb(bg));  // WCAG 2 contrast: 4.5 for body text
CgColor hot = gold.intensity(3f);                              // three times as bright, for an HDR target
```

| To | Call |
|---|---|
| make one | `argb`, `rgba`, `hex`, `css`, `srgb(r, g, b, a)`, `hsv`, `hsl`, `oklab`, `oklch`, `new CgColor(r, g, b, a)` (linear) |
| mix two | `lerp` (in linear light), `lerpSrgb` (as most paint programs), `lerpOklab` (as CSS, most even to the eye) |
| read it back | `toArgb`, `toRgba`, `toHex`, `toCss`, `toHsv`, `toHsl`, `toOklab`, `toOklch`, `r()` `g()` `b()` `a()` |
| change one channel | `withRed`, `withGreen`, `withBlue`, `withAlpha` |
| measure | `luminance()`, `contrast(other)`, `isHdr()` |

`CgColor.CODEC` reads `0xAARRGGBB`, a linear `[r, g, b, a]` or a CSS string, and writes without loss, HDR included.

---

## Gradients

A gradient blends between colours, **stops**, along a line, out from a centre, or around one.

```java
static final CgGradient SKY = CgGradient.linear(180f)                     // CSS's angle: 0 up, clockwise
        .stop(0f, 0xFF203040).stop(1f, 0xFF101820);
static final CgGradient GLOW = CgGradient.radial(0.5f, 1f, 0.5f, 1f)      // centre x, y and radii x, y
        .stop(0f, 0xFFFFE080).stop(1f, 0x00FFE080);
static final CgGradient HUE = CgGradient.conic(0.5f, 0.5f, 0f)            // centre x, y and the start angle
        .stop(0f, 0xFFFF0000).stop(0.5f, 0xFF00FFFF).stop(1f, 0xFFFF0000);
static final CgGradient RAMP = CgGradient.between(0f, 0f, 1f, 0f)         // from one point to another
        .stops(new float[]{0f, 0.5f, 1f}, new int[]{0xFF000000, 0xFFFF8000, 0xFFFFFFFF});
```

- **Positions are fractions of the box it fills**, 0 to 1: one gradient fits a box of any size.
- **Two stops at one position make a hard edge.** Stripes:

  ```java
  static final CgGradient STRIPES = CgGradient.linear(45f).repeat()
          .stop(0f, 0xFFFFFFFF).stop(0.1f, 0xFFFFFFFF).stop(0.1f, 0x00000000).stop(0.2f, 0x00000000);
  ```

- **Past the last stop** the end colours hold; `repeat()` starts over, `reflect()` runs back and forth.
- **Any number of stops.** Colours mix premultiplied, so a fade to transparent never darkens, and are dithered against
  banding (`dither(false)` turns it off).
- **A gradient needs two stops** to draw; one with fewer fails where it is drawn.
- `colorAt(t)` answers its colour on the CPU, for a swatch or a test.

---

## Images: brushes

A **brush** is an image as paint: which texture, which part of it, how it stretches or repeats, how it is sampled and
tinted. One brush serves the canvas and widgets alike.

```java
static final CgBrush ICON = CgBrush.texture(CgTextures.of("mymod:textures/gui/save.png"));
static final CgBrush BANNER = CgBrush.texture("mymod:textures/hud/banner.png").size(224, 56);  // by path, loaded at first draw
static final CgBrush DIGIT = CgBrush.texture(digits).region(16, 0, 8, 12);                     // part of the image, in its pixels
static final CgBrush SEVEN = CgBrush.texture(digitArray).layer(7);                             // a layer of a 2D texture array
static final CgBrush FADED = ICON.tint(0x80FFFFFF);                                            // its colour multiplied
```

### A panel that stretches: the nine-slice

A panel image has corners that must keep their size and edges that may stretch. A **nine-slice** cuts the image into
nine pieces by four border widths: the four corners are drawn at their size, the four edges stretch along one axis,
and the middle fills what is left. CSS's `border-image`.

```java
static final CgBrush PANEL = CgBrush.texture(frame).nineSlice(CgNineSlice.uniform(4));          // 4 px borders
static final CgBrush FRAME = CgBrush.texture(frame).nineSlice(CgNineSlice.of(4, 8, 4, 8).hollow()); // no middle
static final CgBrush PIXEL_FRAME = CgBrush.texture(frame).nineSlice(CgNineSlice.uniform(3).scaled(2f))
        .sampler(CgSampler.NEAREST_CLAMP);                                                         // pixel art, doubled
```

### Repeating

`tiling(...)` says how the brush covers a shape larger than itself; with a nine-slice, how its middle fills.
`edges(...)` says how a nine-slice's edges cover their length (CSS's `border-image-repeat`).

| `CgTiling` | Does |
|---|---|
| `STRETCH` | stretched over the shape once (the default) |
| `REPEAT`, `REPEAT_X`, `REPEAT_Y` | repeated at its own size, cut at the far edges |
| `ROUND` | repeated a whole number of times, each copy scaled to fit exactly |
| `SPACE` | repeated a whole number of times at its own size, the leftover spread between copies |

### Sprite sheets

A sprite sheet names the pieces of one image, so a resource pack can retheme your UI by shipping a new image and its
JSON. It lives at `assets/<namespace>/ui/sprites/<path>.json`:

```json
{ "texture": "mymod:textures/gui/theme.png",
  "elements": {
    "button": { "sprite": [29, 1, 13, 13], "border": [1, 1, 1, 1] },
    "panel":  { "sprite": [0, 32, 32, 32], "border": [4, 4, 4, 4], "fillCenter": false, "repeat": "round" }
  } }
```

```java
static final CgBrush BUTTON = CgBrush.sprite("mymod:theme", "button");    // its region, nine-slice and tiling
```

`sprite` is `[x, y, width, height]` in the image's pixels; `border` the nine-slice widths, `[left, top, right,
bottom]`. A missing sheet or entry fails, naming it.

### Icons, materials and the rest

```java
static final CgBrush SAVE = CgBrush.svg("mymod:ui/icons/save.svg");      // an SVG icon (UI.md § 8)
static final CgBrush THEMED = SAVE.color(0xFFE0E0E0);                      // its currentColor
static final CgBrush OUTLINE = SAVE.color(accent).flat(1.5f);              // one colour, strokes 1.5 units wide
static final CgBrush SHADED = CgBrush.material(bannerMaterial);            // a UI material (#domain ui) as the fill
```

- `CgBrush.WHITE` is a plain box in its tint; `CgBrush.NONE` draws nothing.
- **Sampling**: a brush by path samples as its texture's settings file says unless `sampler(...)` sets one.
- **`premultiplied(true)`** says the texture's pixels are premultiplied already: an image another pass rendered. Get it
  wrong and edges darken or glow.
- **`size(w, h)`** is the size it wants to be: what an image widget measures.
- **`fit(CgFit.FILL)`** stretches an SVG to the box; `CONTAIN` (the default) fits and centres it.

---

## Grids

```java
static final CgGrid RULED = CgGrid.lines(0x40FFFFFF).cell(16f).width(1f);            // lines every 16 units, 1 wide
static final CgGrid GRAPH = CgGrid.lines(0x40FFFFFF).cell(32f, 16f).width(1f, 2f);   // per axis
static final CgGrid CHECKER = CgGrid.checker(0xFFCCCCCC, 0xFF999999).cell(8f);       // a transparency checkerboard
```

Lines never shimmer or vanish as the grid shrinks: under a pixel, a cell dissolves into the grid's average colour.

---

## Shapes and strokes

A **path** (`CgVectorPath`) is any shape: lines, curves, arcs and whole rects, ovals and circles, any number of them
together. Build it once:

```java
static final CgVectorPath TICK = CgVectorPath.builder().moveTo(2, 8).lineTo(6, 12).lineTo(14, 4).build();
static final CgVectorPath ARC = CgVectorPath.builder()
        .moveTo(0, 8).arcTo(8, 8, 0, false, true, 16, 8).build();          // SVG's arc
static final CgVectorPath BADGE = CgVectorPath.builder()
        .addRoundRect(0, 0, 24, 16, CgCornerRadii.of(4))
        .addCircle(20, 4, 3)
        .fillRule(CgFillRule.EVEN_ODD)                                     // the circle becomes a hole
        .build();
```

| Builder call | Adds |
|---|---|
| `moveTo`, `lineTo`, `close` | a start, a straight line, a line back to the start |
| `quadTo`, `cubicTo` | a quadratic or cubic Bezier curve |
| `arcTo(rx, ry, rotation, largeArc, sweep, x, y)` | an elliptical arc, as SVG's `A` |
| `addRect`, `addRoundRect`, `addOval`, `addCircle`, `add(path)` | a whole shape |
| `fillRule(...)` | which parts are inside: `NON_ZERO` (default) fills overlaps, `EVEN_ODD` makes them holes |

A **stroke** (`CgStroke`) is how a path's outline is drawn:

```java
static final CgStroke PEN = CgStroke.of(2f).cap(CgStrokeCap.ROUND).join(CgStrokeJoin.ROUND);
static final CgStroke DASHED = CgStroke.of(1f).dash(4f, 2f);              // 4 on, 2 off
CgStroke drawingOn = PEN.trim(0f, progress);                              // only part of the outline: 0 to 1
```

| Setting | Choices |
|---|---|
| `of(width)` | its full width, in the units it is drawn in |
| `cap(...)` | the line's ends: `BUTT` (flat at the end), `ROUND`, `SQUARE` (flat, past the end by half the width) |
| `join(...)` | its corners: `MITER` (sharp, up to `miterLimit`), `ROUND`, `BEVEL` (cut off) |
| `dash(on, off, ...)`, `dashOffset(d)` | dashes; a zero-length dash with round caps is a dot |
| `trim(start, end)` | draws the part between two fractions of the outline: a draw-on effect |

**Corner radii** (`CgCornerRadii`), for a box or a rounded rect: `of(r)` all corners, `of(tl, tr, br, bl)` each,
`elliptical(rx, ry)` an oval corner. Radii that do not fit are scaled down together, as CSS does.

---

## Shadows

A box's shadow, CSS's `box-shadow`:

```java
static final CgShadow CARD = CgShadow.of(0, 2, 8, 0x60000000);           // 2 down, blur 8
static final CgShadow LIFT = CgShadow.of(0, 8, 24, 4, 0x50000000);       // grown by 4 first: the spread
static final CgShadow WELL = CgShadow.of(0, 1, 3, 0x80000000).inset();   // inside the border: a sunken well
```

The arguments are an offset, a blur, an optional spread and a colour. `blur` is CSS's blur radius, twice the Gaussian
sigma `CgFilter.blur` takes; 0 is a hard shadow.

---

## Filters

A filter changes a group's pixels after it is drawn: blurs, colour changes, a shadow of its shape. It goes on a canvas
layer ([`UI.md`](UI.md) § 9) or a widget's retainer box. Chain filters with `then`; they run in order.

```java
static final CgFilter MUTED = CgFilter.saturate(0.3f).then(CgFilter.brightness(0.8f));
static final CgFilter LIFTED = CgFilter.dropShadow(0, 4, 12, 0x80000000);
static final CgFilter SMEAR = CgFilter.blur(0f, 6f);                       // down only: a motion smear
```

| Filter | Does |
|---|---|
| `blur(sigma)`, `blur(sigmaX, sigmaY)` | a Gaussian blur; one axis at 0 blurs along the other only |
| `dropShadow(dx, dy, blur, argb)` | a shadow of the group's shape under it, `blur` CSS's radius; `dropShadow(dx, dy, sigmaX, sigmaY, argb)` takes sigmas |
| `brightness`, `contrast`, `saturate`, `grayscale`, `sepia`, `invert`, `hueRotate(degrees)`, `opacity` | CSS's colour filters |
| `tint(argb)` | a colour laid over |
| `luminosity(argb, amount)` | brightness pulled toward the colour's (glass only, below) |
| `matrix(float[20])` | any colour matrix |
| `material(material, textureParameter)` | a UI material that samples the group's picture |

Colour steps next to each other fold into one matrix, so a long chain of them costs one step.

---

## Glass

A **backdrop filter** is the look of a glass pane, CSS's `backdrop-filter`: what is already drawn behind the pane,
captured, run through a filter chain, then bent and lit by an optional lens at the pane's edge. Put it on a box with
`box(...).backdropFilter(f)` ([`UI.md`](UI.md) § 10).

### The looks

| Preset | Looks like |
|---|---|
| `DEFAULT` | a pane over a light blur, a lens at its edge, lit and grained: a pane when nothing says otherwise |
| `CLEAR` | plain glass: no blur, a gentle lens, a faint highlight |
| `FROSTED` | frosted glass: a wide blur under a light tint, lit, no lens |
| `LIQUID` | Apple's liquid glass: a little blur, a wide strong lens, a bright rim and some dispersion |
| `ACRYLIC` | Windows' acrylic: its brightness takes the tint's, so a dark tint is a dark pane over anything |
| `MICA` | Windows' Mica: a very wide blur under a dark tint, its brightness mostly the tint's |
| `PRISM` | a prism: a pixel of blur under a lens that splits colours apart |
| `NONE` | no glass: the box captures nothing |

### Your own

```java
// CSS's backdrop-filter: grayscale(1) contrast(1.2) blur(8px)
static final CgBackdropFilter MUTED_PANE = CgBackdropFilter.of(
        CgFilter.grayscale(1f).then(CgFilter.contrast(1.2f)).then(CgFilter.blur(8f)));

// Fluent acrylic's recipe: blur, saturation, brightness toward the tint's, the tint laid over
static final CgBackdropFilter PANE = CgBackdropFilter.acrylic(10f, 1.5f, 0.1f, 0x401F1F23);

// a preset changed: each setter puts its step where acrylic's recipe has it
static final CgBackdropFilter SOFT = CgBackdropFilter.FROSTED.blur(12f).tint(0x30FFFFFF);

// a chain, then a lens, lit from the top left
static final CgBackdropFilter LENSED = CgBackdropFilter.of(CgFilter.blur(4f).then(CgFilter.saturate(1.8f)))
        .lens(CgBackdropFilter.Lens.LIQUID.light(-1f, -1f));
```

**The lens** (`CgBackdropFilter.Lens`) bends and lights what the chain made, at the pane's edge. Its presets are
`NONE`, `DEFAULT`, `CLEAR`, `LIQUID` and `PRISM`; change one with:

| Setter | Controls |
|---|---|
| `refraction(bezel, ior)` | how wide the bent edge is, and how strongly it bends |
| `chromatic(amount)` | how far colours split apart as they bend |
| `highlight(specular, glow, rim, rimWidth, rimAmbient)` | the lighting: strength, broad glow, the rim band, its width and its even part |
| `light(x, y)` | where the light comes from, a direction on the screen, y down |
| `grain(amount)` | noise over the pane |

- **The chain takes** blurs, every colour filter, `tint` and `luminosity`. A drop shadow or a material is a layer's
  filter and is refused here.
- **It costs** one capture, one blur and one pass: the colour steps fold into the pass that draws the pane.
- **`fallback(argb)`** is the flat colour a pane draws where it cannot capture: inside a kept layer.
- **In the world**, scale the lens to blocks: `CgBackdropFilter.FROSTED.scaled(1 / 32f)`.
- The same chain written in CSS (`blur(8px) saturate(1.25) tint(...)`) is CrystalGUI's `backdrop-filter`.

---

## Easy to get wrong

- **Build values once.** Every `stop`, `region`, `tint` and `then` answers a new value; a value made each frame is
  garbage each frame. Keep them in constants, or rebuild them only when the theme changes.
- **Colours are straight `0xAARRGGBB`.** Never premultiply them yourself; `CgPaint.color(argb, intensity)` brightens
  past white on an HDR target.
- **A brush's `premultiplied` describes its pixels**, not how you want it drawn.
- **Two blur scales.** `CgFilter.blur` takes a Gaussian sigma; a `CgShadow`'s blur and a one-blur `dropShadow` take
  CSS's radius, twice the sigma.
- **A gradient needs two stops.**

## Where the code is

| Package | Values |
|---|---|
| `com.crystalgraphics.api.paint` | `CgPaint` (`color(argb)`, and the interface every paint is), `CgColor` |
| `api.paint.gradient` | `CgGradient`, `CgSpread` |
| `api.paint.brush` | `CgBrush`, `CgNineSlice`, `CgTiling`, `CgFit`, `CgSpriteSheet` |
| `api.paint.pattern` | `CgGrid` |
| `api.paint.effect` | `CgFilter`, `CgShadow`, `CgBackdropFilter` and its `Lens` |
| `api.vector` | `CgVectorPath`, `CgStroke`, `CgStrokeCap`, `CgStrokeJoin`, `CgFillRule`, `CgCornerRadii` |
