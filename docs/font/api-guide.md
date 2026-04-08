# Font/Text API Guide

This file combines the old API guide and example-usage material into one current guide.

## TL;DR

Common flow:

1. load one or more `CgFont` instances
2. build a `CgFontFamily`
3. create a `CgTextRenderContext` or `CgWorldTextRenderContext`
4. call `CgTextRenderer.draw(...)` with either a raw string or a prebuilt `CgTextLayout`

Use a prebuilt `CgTextLayout` when the same text is drawn more than once.

---

## 1. Loading fonts

### Base font vs sized font

`CgFont` supports two modes:

- **base/unsized font** — reusable logical font asset
- **size-bound font** — concrete renderable/shapable variant

Typical usage:

```java
CgFont base = CgFont.load("assets/fonts/Inter-Regular.ttf", CgFontStyle.REGULAR);
CgFont ui16 = base.atSize(16);
```

If you already know the size at load time, you can load directly into a sized font.

---

## 2. Building a font family

Use `CgFontFamily` when fallback matters.

```java
CgFont latin = CgFont.load("assets/fonts/Inter-Regular.ttf", CgFontStyle.REGULAR).atSize(16);
CgFont arabic = CgFont.load("assets/fonts/NotoNaskhArabic-Regular.ttf", CgFontStyle.REGULAR).atSize(16);

CgFontFamily family = CgFontFamily.of(latin, arabic);
```

Rules:

- all family members must share the same target pixel size
- primary source is always consulted first
- fallback sources are checked in order

---

## 3. Building layouts explicitly

You can build a `CgTextLayout` directly and reuse it across draws.

```java
CgTextLayoutBuilder builder = new CgTextLayoutBuilder();
CgTextLayout layout = builder.layout(
        "Hello مرحبا こにちは",
        family,
        240.0f,
        0.0f
);
```

Use explicit layout when:

- the same text is drawn many times
- you need total width/height before drawing
- you want to separate layout from rendering

---

## 4. Rendering 2D text

### Create a context

```java
CgTextRenderContext context = CgTextRenderContext.orthographic(screenWidth, screenHeight);
```

### Draw a raw string

```java
renderer.draw(
        "Inventory",
        family,
        8.0f,
        12.0f,
        0xFFFFFFFF,
        frame,
        context,
        poseStack
);
```

### Draw a prebuilt layout

```java
renderer.draw(layout, family, 8.0f, 12.0f, 0xFFFFFFFF, frame, context, poseStack);
```

---

## 5. Rendering world text

Use `CgWorldTextRenderContext` for 3D/world-space rendering.

```java
CgWorldTextRenderContext worldContext = CgWorldTextRenderContext.create(
        projectionMatrix,
        viewportWidth,
        viewportHeight
);
```

Then draw with the world entrypoint:

```java
renderer.drawWorld(layout, family, 0.0f, 0.0f, 0xFFFFFFFF, frame, worldContext, poseStack);
```

Notes:

- world text always uses the distance-field path
- the world context owns the projection matrix and raster-tier policy
- projected-size logic influences raster-tier selection, not logical layout metrics

---

## 6. Working with constraints

`CgTextConstraints` describes optional width/height limits.

```java
CgTextConstraints unconstrained = CgTextConstraints.UNBOUNDED;
CgTextConstraints widthOnly = CgTextConstraints.maxWidth(200.0f);
CgTextConstraints bounded = CgTextConstraints.bounded(200.0f, 64.0f);
```

Use constraints when:

- line wrapping matters
- you need height truncation
- you want to pre-measure text inside a UI box

---

## 7. What the renderer expects

The renderer consumes:

- `CgTextLayout`
- `CgFontFamily` / `CgFont`
- a render context
- a `PoseStack`

The renderer then resolves glyph placements through the cache and atlas system automatically.

You do **not** need to interact with `CgFontRegistry`, `CgPagedGlyphAtlas`, `CgGlyphVbo`, or `CgMsdfGenerator` for normal API use.

---

## 8. What to cache yourself

Safe/high-value things to cache at the call-site:

- base `CgFont` objects
- sized `CgFont` variants
- `CgFontFamily`
- `CgTextRenderContext` / `CgWorldTextRenderContext`
- reusable `CgTextLayout` objects for repeated strings

Things you should generally not own directly unless you are extending the engine:

- `CgFontRegistry`
- atlas page objects
- glyph-generation jobs/results
- low-level shader bindings

---

## 9. Current caveats

These are current design realities, not usage bugs:

- `CgTextLayoutBuilder` still lives in `api/font` even though the algorithm lives in `text/layout`
- `CgTextLayout` still carries `resolvedFontsByKey`
- `CgShapedRun` still carries source text/range fields for re-shaping support

Normal callers can treat those as implementation details and keep using the public API normally.

---

## 10. Recommended usage patterns

### Best for UI labels

- keep one orthographic `CgTextRenderContext`
- cache `CgFontFamily`
- call `draw(String, ...)` for one-off labels

### Best for repeated strings

- build `CgTextLayout` once
- reuse the layout across frames

### Best for multilingual text

- always use `CgFontFamily`
- keep fallback ordering intentional

### Best for world-space text

- use `CgWorldTextRenderContext`
- update projection/viewport when needed
- let the render/cache pipeline pick distance-field raster tiers
