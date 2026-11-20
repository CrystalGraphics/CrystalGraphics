# Text API

Three steps: load **fonts**, describe a **layout**, **draw** it on a canvas ([`../UI_CANVAS.md`](../UI_CANVAS.md)).

```java
CgFont inter = CgFont.load("assets/mymod/fonts/Inter-Regular.ttf", CgFontStyle.REGULAR, 16);   // 16 px font

CgRenderStage.HUD.register(CgRenderStage.VIEWPORT_WIDGETS, frame ->                            // every frame
        frame.canvas().text().text("Inventory").font(inter).at(8, 12).color(0xFFFFFFFF).submit());
```

Everything below is a variation on those three lines.

> **Units.** Unless a section says otherwise, lengths are **layout pixels**: the text's own coordinate
> space, before the pose transforms it. One layout pixel is one pixel of a font at the size it was
> loaded — a 16 px font's em is 16. Positions, layout widths, measured sizes and shadow offsets are all
> layout pixels. Colours are `0xAARRGGBB`.

---

## 1. Fonts

A `CgFont` is one face at one size, the size being the em in pixels.

```java
CgFont regular = CgFont.load("assets/mymod/fonts/Inter-Regular.ttf", CgFontStyle.REGULAR, 16);
CgFont large   = regular.atSize(32);                                        // same face, 32 px
CgFont gothic  = CgFont.load("C:/Windows/Fonts/msgothic.ttc", 1, CgFontStyle.REGULAR, 16);   // a .ttc face by index
```

A `CgFontFamily` is a primary font plus fallbacks, consulted in order for any character the primary
lacks. Mixed scripts need no splitting: the family picks a face per character.

```java
CgFont arabic = CgFont.load("assets/mymod/fonts/NotoNaskhArabic-Regular.ttf", CgFontStyle.REGULAR, 16);
CgFontFamily family = CgFontFamily.of(regular, arabic);                     // every member the same size
```

For whatever no bundled font covers, fall back to the fonts installed on the machine:

```java
CgSystemFonts installed = CgSystemFonts.get();
CgFontFamily family = CgFontFamily.of(regular, arabic).withFallback(installed.fallback(Locale.getDefault()));

CgFont segoe = installed.load(installed.find("Segoe UI", CgFontStyle.BOLD), CgFontStyle.BOLD, 16);
```

The fallback chooses per character as a browser does, and a Han character follows the text around it:
Japanese beside kana, Korean beside Hangul, the locale's convention otherwise.

A `CgFontFamilyGroup` gives styled text a family per style. A style with no family of its own is
synthesised from the regular one.

```java
CgFontFamilyGroup group = CgFontFamilyGroup.ofRegular(family);                        // bold/italic synthesised
CgFontFamilyGroup real  = new CgFontFamilyGroup(Map.of(CgFontStyle.REGULAR, family,
                                                       CgFontStyle.BOLD, boldFamily));
```

---

## 2. Layout

`CgTextLayout.of(...)` starts a request. Options chain; it ends in `shape()` or `build()`.

```java
CgShapedParagraph paragraph = CgTextLayout.of("Hello مرحبا こんにちは", family)
        .align(CgTextAlign.CENTER)
        .maxLines(2)
        .ellipsis("…")
        .shape();                               // shaped once, wrappable at any width

CgTextLayout laid = paragraph.layout(240, 0);   // wrapped at 240 layout px; 0 means no height limit
float width = laid.totalWidth(), height = laid.totalHeight();   // layout px
```

| End with | You get | For |
|---|---|---|
| `shape()` | a `CgShapedParagraph`: shaped, not yet wrapped | text whose width changes — a UI box, a zoomable view |
| `build()` | a `CgTextLayout`: wrapped at the request's `maxWidth` | text drawn the same way every frame |

Other options: `maxWidth` and `maxHeight` (layout px, `0` for none), `direction`, `lineHeightOverride`
(layout px per line), `tabStopWidth` (layout px).

**Styled text** comes from markup, or from spans you build:

```java
CgTextLayout.of("<b>Bold</b>, <i>italic</i> and <color=#FF5555>red</color>", group)
        .markup(CgMarkupParser.HTML)           // or CgMarkupParser.MINECRAFT for § codes
        .shape();

CgTextLayout.of(new CgStyledText(plain, spans), group).shape();   // CgStyleSpan: range, bold, italic, colour, decorations
```

---

## 3. Drawing

Text draws on a `CgCanvas` (`../UI_CANVAS.md` § *Text*): a GUI stage's `frame.canvas()`, or one you begin into a
texture. `canvas.text(CgText, x, y)` is the everyday form; `canvas.textDraw()` is the request below, every knob of it.
It is placed by the canvas's transform and rastered at the target's device pixels. In the world, text is a label
component (§ 4). Nothing to create, size or delete.

### The three forms

```java
canvas.textDraw().text("Score: " + score).font(regular).at(x, y).submit();        // laid out and cached for you
canvas.textDraw().paragraph(paragraph).family(family).constraints(240, 0).at(x, y).submit();
canvas.textDraw().layout(laid).at(x, y).submit();                                  // drawn exactly as laid out
```

### The request

| Call | Sets | Default |
|---|---|---|
| `text(s)` · `paragraph(p)` · `layout(l)` | what to draw. Given several, `layout` wins, then `paragraph` | one is required |
| `font(f)` · `family(f)` | the faces. Given both, `family` wins | required, unless drawing a non-empty `layout` |
| `targetPx(px)` | re-sizes the font or family for this draw only | the size it was loaded at |
| `at(x, y)` | the first line's top-left, in layout px | `(0, 0)` |
| `style(s)` | how it looks: its font where the draw names none, its layers, spacing and skew (below) | `CgTextStyle.PLAIN`, one white fill |
| `color(argb)` | a tint over every layer of the style; a styled span's colour replaces its fill's | opaque white |
| `contained(b)` | `true` moves the text in by its outline and shadow offsets, as Slate's runs; `false` lets them overflow, as CSS | `true` |
| `constraints(maxWidth, maxHeight)` | the wrap box for `text` and `paragraph`; ignored by `layout`. `0` or less is unbounded | unbounded |
| `measure()` | returns the `CgTextLayout` `submit()` would draw, without drawing | |
| `submit()` | draws; returns false while a glyph or shadow is still building | |

**`constraints` are on-screen pixels on a 2D draw:** the draw divides them by the pose's scale
before wrapping, so a 240 px column stays 240 px wide as the canvas zooms, and the line breaks change
instead. On world text they are layout pixels, and never re-wrap as the camera moves.

```java
CgTextDraw title = canvas.textDraw().text(label).font(regular).constraints(300, 0);
float heightPx = title.measure().totalHeight();       // layout px, wrapped exactly as submit() will
title.at(x, y).submit();
```

### Styles

How text looks, apart from what it says, is a `CgTextStyle`: a face and a stack of layers, painted bottom to top. One
style is shared by every draw that names it, and a `text_styles/` asset compiles to one (`../vfx/assets.md`).

```java
static final CgTextStyle TITLE = CgTextStyle.builder("mymod:text_styles/title")
        .size(24f)                                                                   // points at 96 DPI: 32 px
        .layer(CgTextLayer.shadow("shadow").offset(0f, 2f).blur(2f).paint(0x80000000))   // a soft drop shadow
        .layer(CgTextLayer.outline("outline").width(2f).paint(0xFF101418))              // 2 px, near-black
        .layer(CgTextLayer.fill("fill"))                                                 // white
        .build();

canvas.textDraw().style(TITLE).text("Boss").font(large).at(x, y).submit();
canvas.textDraw().style(TITLE).text("Boss").font(large).color(0x80FFFFFF).at(x, y).submit();   // the whole look, faded
```

A glow, a gradient and a material:

```java
CgTextLayer.shadow("glow").spread(2f).blur(4f).blend(CgTextLayer.Blend.ADDITIVE).paint(0x80FFD040);
CgTextLayer.fill("fill").paint(CgTextPaint.gradient(CgTextPaint.Space.GLYPH, 90f, 0f, 0xFFFFF0A0, 1f, 0xFFE09000));
CgTextLayer.fill("fill").paint(CgTextPaint.material(shimmer));              // a Text-domain material
```

- **Its font** is a file (`.font("mymod:fonts/title.ttf")`) or an installed family: `.font("system:sans-serif")`, any
  CSS generic, as a browser resolves it (Arial on Windows), or a family by name (`system:Segoe UI`), which fails at the
  style's first `font(px)` where the machine lacks it. `.bold(true)` takes its bold face. A draw's own `font` or `family`
  wins over the style's. Call `style.font(Math.round(style.px()))` in a load task: the first one reads the face.
- **Lengths are pixels at the style's size**, and scale with the text: a 2 px outline on a 32 px style is 4 px on text
  drawn at 64 px. Size a style at the size you draw it, and its lengths read as pixels.
- An outline **under** a fill is the glyph grown by its width; **over** a fill it is the ring alone, as a CSS stroke.
  `align` puts it outside the edge (the default), centred on it, or inside. Its width clamps at what the font's distance
  field holds: `CgFontRegistry.maxStrokeWidthEm(family)`, at least `CgTextLayer.MAX_FIELD_WIDTH_EM`.
- A shadow is the glyph offset, grown by `spread` and blurred by `blur` (a Gaussian sigma; a CSS blur radius is twice
  it). `outlined(true)` casts the style's first outline's shape too; `inset(true)` shades inside the glyph, clipped to it.
- A shadow takes a colour or a material, never a gradient; that fails at build or load.
- A style with an outline or a skew puts the draw on distance-field glyphs: a bitmap glyph has neither.
- To limit a layer to some glyphs (a highlighted word), give each glyph a scope with `scopes(int[])` and restrict the
  layer with `layerScope(layer, scope)`.

### Batching

Every glyph is one quad on the canvas, and the canvas joins neighbouring quads of one material and atlas
into one draw: every glyph lives in one shared atlas, its pages the layers of one array texture. So a screen
of labels from one font costs what one label does, with nothing to open or close:

```java
for (Row row : rows) {
    canvas.textDraw().text(row.label).font(regular).at(8, row.y).submit();
}
```

The split is bitmap against distance-field glyphs, which draw with different shader variants from
separate atlases. A draw mixes them while a glyph is still on its bitmap fallback, or when blurred
shadows sit under distance-field text. Within a draw every glyph of a layer lands before the next layer's, so a
style's layers paint bottom to top; a quad, curve or other draw submitted between two labels splits the run, as it
must to keep order.

---

## 4. World-space text

Text in the world is a label component of the scene: its glyphs captured once and kept on the GPU, every label
drawn from them, depth-tested and billboarded or turned. Glyphs are distance fields, their tier picked from their size
on screen; the line breaks never change with distance.

```java
CgLabelComponent tag = scene.label("Spawn").font(font).height(0.5f).at(x, y + 2, z).spawn();   // until removed
tag.text("Spawn (3)");                                                                         // captured again
scene.labelOnce(damage).font(font).height(0.3f).at(x, y, z);                                   // this frame only
```

- A label's look is a `CgTextStyle` like any draw's; its height is in blocks.
- More: [`../ENGINE_API.md`](../ENGINE_API.md) § *The scene and its views*.

---

## A text widget

How CrystalGUI's `UIText` does it: shape when the text or font changes, lay out per width, draw the
layout.

```java
CgShapedParagraph shaped = CgTextLayout.of(content, family).shape();       // on a text or font change

CgTextLayout measured = shaped.layout(availableWidth, 0);                  // on layout; layout px
return new Size(measured.totalWidth(), measured.totalHeight());

canvas.textDraw().layout(shaped.layout(boxWidth, 0)).family(family)           // on paint
        .at(boxX, boxY).color(color).submit();
```

`layout(width, height)` remembers the last pair it was asked for, so layout and paint asking at the same
width shape and wrap once.

---

## Easy to get wrong

- **`canvas.textDraw()` returns one shared request.** Build and `submit()` it in a single expression; for text you
  draw every frame, keep a `CgText` and call `canvas.text(...)`.
- **`constraints` does not re-wrap a `layout`.** A built layout draws exactly as built; use `text` or
  `paragraph` for text that should wrap to its box.
- **A family's fonts share one size.** Resize the whole family with `family.atSize(px)`.
- **Fonts from `CgSystemFonts.load` are cached.** Never `dispose()` them.
- **A draw can come out provisional** while glyphs are still being generated: `submit()` returns false. A
  caller that paints only on damage asks for another frame then.
- **Threads follow the canvas.** A recording canvas may draw text from any thread, one thread at a time; an
  immediate canvas's `flush()` is the render thread's. Layout runs on any thread at all.
