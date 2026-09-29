# Text API

Three steps: load **fonts**, describe a **layout**, **draw** it through a `CgTextRenderer`.

```java
CgFont inter = CgFont.load("assets/mymod/fonts/Inter-Regular.ttf", CgFontStyle.REGULAR, 16);   // 16 px font
CgTextRenderer text = CgTextRenderer.create();                                                 // once, on the GL thread

text.draw().text("Inventory").font(inter).at(8, 12).color(0xFFFFFFFF).submit();                 // every frame
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

`CgTextRenderer.create()` makes a renderer whose screen context follows the window. Use
`createManualSized()` when it should not, and size it yourself with
`text.context().update(viewportWidthPx, viewportHeightPx)`. Call `delete()` when you are done with it.

### The three forms

```java
text.draw().text("Score: " + score).font(regular).at(x, y).submit();          // laid out and cached for you
text.draw().paragraph(paragraph).family(family).constraints(240, 0).at(x, y).submit();
text.draw().layout(laid).at(x, y).submit();                                    // drawn exactly as laid out
```

### The request

| Call | Sets | Default |
|---|---|---|
| `text(s)` · `paragraph(p)` · `layout(l)` | what to draw. Given several, `layout` wins, then `paragraph` | one is required |
| `font(f)` · `family(f)` | the faces. Given both, `family` wins | required, unless drawing a non-empty `layout` |
| `targetPx(px)` | re-sizes the font or family for this draw only | the size it was loaded at |
| `at(x, y)` | the first line's top-left, in layout px | `(0, 0)` |
| `color(argb)` | the text's colour; a styled span keeps its own | opaque white |
| `pose(poseStack)` | the transform into the screen or world | the renderer's pose, else identity |
| `constraints(maxWidth, maxHeight)` | the wrap box for `text` and `paragraph`; ignored by `layout`. `0` or less is unbounded | unbounded |
| `measure()` | returns the `CgTextLayout` `submit()` would draw, without drawing | |
| `submit()` | draws, and returns the renderer | |

**`constraints` are on-screen pixels on a 2D draw:** the renderer divides them by the pose's scale
before wrapping, so a 240 px column stays 240 px wide as the pose zooms, and the line breaks change
instead. On world text they are layout pixels, and never re-wrap as the camera moves.

```java
CgTextRenderer.Draw title = text.draw().text(label).font(regular).constraints(300, 0).pose(pose);
float heightPx = title.measure().totalHeight();       // layout px, wrapped exactly as submit() will
title.at(x, y).submit();
```

### Outline

```java
text.draw().text("Boss").font(large).at(x, y)
        .stroke(0.06f, 0xFF101418)                     // 0.06 em wide, near-black
        .strokeAlign(CgStrokeAlign.OUTSET)             // OUTSET (default): outside the contour; CENTER; INSET
        .strokeOverFill(false)                         // false (default): behind the fill; true: over it
        .submit();
```

- The width is in **em**, a fraction of the font size, so the outline keeps its proportion at any zoom.
  It clamps at `CgTextStroke.MAX_FIELD_WIDTH_EM`.
- `strokeWidth(em)` and `strokeColor(argb)` set one half. A width of `0` or a transparent colour draws none.
- A stroke puts the draw on distance-field glyphs: a bitmap glyph has no outline to offset.

### Shadows

```java
text.draw().text("Level up").font(large).at(x, y)
        .shadowCount(2)
        .shadow(0, 0, 0, 4, 0, 0xFF44CCFF, false)      // a cyan glow, painted on top
        .shadow(1, 2, 2, 1, 0, 0x80000000, false)      // a soft drop shadow beneath it
        .submit();
```

`shadow(index, offsetX, offsetY, sigma, spread, argb, inset)`:

- `offsetX`, `offsetY` — layout px, positive right and down.
- `sigma` — the blur's standard deviation in layout px. A CSS blur radius is twice this.
- `spread` — grows the glyph outline by that many layout px before blurring.
- `argb` — the shadow's colour; its alpha is the shadow's whole opacity.
- `inset` — shades inside the glyph, over the text, instead of beneath it.

Shadow `0` is painted on top; every shadow sits beneath the text, and a stroke is part of the shadow's
shape. To shadow only some glyphs — a highlighted word — give each glyph a scope with
`shadowScopes(int[])` and restrict a shadow to one with `shadowScope(index, scope)`.

### Batching

Every glyph is one instance of a shared quad. A single `draw()` renders all of its glyphs in **one
instanced draw call**, whatever fonts and sizes it mixes: every glyph lives in one shared atlas, its
pages the layers of one array texture.

The one split is bitmap against distance-field glyphs, which draw with different shader variants from
separate atlases. A draw mixes them while a glyph is still on its bitmap fallback, or when blurred
shadows sit under distance-field text. Glyphs are sorted by variant before drawing, so a draw that
mixes both costs at most two calls. Shadows paint as their own step, before the text, so a shadow on
the other variant from its text can add one more.

`beginBatch()` and `endBatch()` extend that across draws. Every `draw()` in between goes into the same
instanced calls, so a screen of labels costs what one label does:

```java
text.beginBatch();
for (Row row : rows) {
    text.draw().text(row.label).font(regular).at(8, row.y).submit();
}
text.endBatch();
```

Outside a batch, each `draw()` opens and closes one of its own.

---

## 4. World-space text

The same draws, through a perspective context. Glyphs switch to distance fields and stay sharp at any
distance; the line breaks never change with distance.

```java
CgTextRenderContext screen = text.context();          // the default, kept to switch back
CgTextRenderContext world  = CgTextRenderContext.world(projection, viewportWidthPx, viewportHeightPx);

// each frame
world.updateProjectedSize(modelView, projection, 48); // 48: the font's size in px
text.context(world);
text.draw().layout(signText).at(0, 0).pose(pose).submit();
text.context(screen);
```

- `projection` is the scene camera's own projection matrix; a different one misplaces the text silently.
- `viewportWidthPx` and `viewportHeightPx` are the size in pixels of the viewport the scene is drawn
  into: the framebuffer area `glViewport` was given, not a GUI-scaled size. On a resize, pass the new
  ones with `world.updateProjection(projection, viewportWidthPx, viewportHeightPx)`.
- `modelView` places the text in the world; `updateProjectedSize` uses it to judge how large the glyphs
  appear, which picks their raster size.

---

## A text widget

How CrystalGUI's `UIText` does it: shape when the text or font changes, lay out per width, draw the
layout.

```java
CgShapedParagraph shaped = CgTextLayout.of(content, family).shape();       // on a text or font change

CgTextLayout measured = shaped.layout(availableWidth, 0);                  // on layout; layout px
return new Size(measured.totalWidth(), measured.totalHeight());

text.draw().layout(shaped.layout(boxWidth, 0)).family(family)             // on paint
        .at(boxX, boxY).color(color).submit();
```

`layout(width, height)` remembers the last pair it was asked for, so layout and paint asking at the same
width shape and wrap once.

---

## Easy to get wrong

- **`draw()` returns one shared request.** Build and `submit()` it in a single expression.
  `retainedDraw()` gives one of your own to keep across frames.
- **`constraints` does not re-wrap a `layout`.** A built layout draws exactly as built; use `text` or
  `paragraph` for text that should wrap to its box.
- **A family's fonts share one size.** Resize the whole family with `family.atSize(px)`.
- **Fonts from `CgSystemFonts.load` are cached.** Never `dispose()` them.
- **A draw can come out provisional** while glyphs are still being generated. A caller that paints only on
  damage compares `text.getDegradedDrawCount()` before and after, and asks for another frame if it rose.
- **GL thread only**, for the renderer and every draw.
