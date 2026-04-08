# Font/Text Architecture

## TL;DR

CrystalGraphics now separates the text system into three broad categories:

1. **public font API** (`api/font`)
2. **public text API** (`api/text`)
3. **internal implementation packages** (`text/layout`, `text/cache`, `text/atlas`, `text/msdf`, `text/render`)

That split is the main architectural story.

The rule is:

> public packages define the values callers see; internal packages own the pipeline that creates, caches, and renders those values.

---

## Public boundary

### `api/font`

This package owns font-domain concepts and the public layout bridge.

Main responsibilities:

- loading and sizing fonts (`CgFont`)
- grouping fonts into fallback families (`CgFontFamily`, `CgFontSource`)
- describing font identity and variation (`CgFontKey`, `CgFontStyle`, `CgFontVariation`, `CgFontAxisInfo`)
- describing glyph identity and placement payloads (`CgGlyphKey`, `CgGlyphMetrics`, `CgAtlasRegion`, `CgGlyphPlacement`)
- exposing the public layout entrypoint (`CgTextLayoutBuilder`)

### `api/text`

This package owns public text-domain values.

Main responsibilities:

- describing layout constraints (`CgTextConstraints`)
- describing the final layout output (`CgTextLayout`)
- describing a shaped directional run (`CgShapedRun`)

This package exists so callers can work with text values without needing to learn the atlas, cache, or renderer internals.

---

## Internal implementation packages

### `text/layout`

Owns the internal layout algorithm.

Main classes:

- `CgTextLayoutEngine` — reusable algorithm skeleton
- `CgTextShaper` — HarfBuzz shaping
- `CgLineBreaker` — line breaking across shaped runs
- `RunReshaper` — callback for re-shaping subranges during line breaks

This package should stay free of atlas/cache/render ownership.

### `text/cache`

Owns glyph supply.

Main classes:

- `CgFontRegistry` — render-thread cache hub
- `CgGlyphGenerationExecutor` / `CgGlyphGenerationJob` / `CgGlyphGenerationResult` — async glyph generation pipeline
- `CgWorkerFontContext` — per-worker font state
- `CgRasterFontKey`, `CgRasterGlyphKey`, `CgMsdfAtlasKey` — internal cache keys

This package answers:

> where does a glyph live, and how do we generate it if it is missing?

### `text/atlas`

Owns atlas storage.

Main classes:

- `CgGlyphAtlas` — legacy single-page atlas
- `CgGlyphAtlasPage` — one page in the multi-page model
- `CgPagedGlyphAtlas` — paged atlas manager
- `text/atlas/packing/*` — packing strategies and packed-rect values

This package owns page allocation and packing, not fallback resolution.

### `text/msdf`

Owns distance-field generation logic.

Main classes:

- `CgMsdfGenerator`
- `CgMsdfGlyphLayout`
- `CgMsdfAtlasConfig`
- `CgMsdfEdgeColoringMode`
- `CgMsdfVerificationConfig`

This package is called by the cache layer, not by the renderer directly.

### `text/render`

Owns draw-time orchestration.

Main classes:

- `CgTextRenderer`
- `CgTextRenderContext`
- `CgWorldTextRenderContext`
- `CgTextScaleResolver`, `OrthographicScaleResolver`, `PerspectiveScaleResolver`, `ProjectedSizeEstimator`
- `CgDrawBatch`, `CgDrawBatchKey`
- `CgGlyphVbo`

This package answers:

> once layout and atlas placements already exist, how do we turn them into draw calls?

---

## Ownership chain

### 1. Font ownership

- `CgFont` owns native font state
- `CgFontFamily` resolves fallback/font-source ownership

### 2. Layout ownership

- `CgTextLayoutBuilder` is the public bridge
- `CgTextLayoutEngine` owns the reusable algorithm
- `CgTextShaper` and `CgLineBreaker` produce `CgTextLayout`

### 3. Cache ownership

- `CgTextRenderer` asks `CgFontRegistry` for glyph placements
- `CgFontRegistry` transforms public glyph requests into internal cache keys
- `CgMsdfGenerator` or FreeType rasterization fills misses

### 4. Atlas ownership

- `CgPagedGlyphAtlas` and `CgGlyphAtlasPage` allocate stable glyph locations
- `CgGlyphPlacement` becomes the renderer-facing placement record

### 5. Render ownership

- `CgTextRenderer` groups placements into batches
- `CgGlyphVbo` uploads quads
- shaders sample atlas textures and draw

---

## Intentional exceptions / remaining leaks

### `CgTextLayoutBuilder` in `api/font`

Semantically, layout logic belongs with `text/layout`.

It stays in `api/font` because it is the narrow legal bridge into package-private font-family shaping details. The real algorithm already lives in `text/layout/CgTextLayoutEngine`; the builder remains only as the public bridge.

### `CgTextLayout.resolvedFontsByKey`

Still a public/internal leak because the renderer needs resolved `CgFont` handles at draw time.

### `CgShapedRun.sourceText/sourceStart/sourceEnd`

Still public because line-breaking still needs to re-shape subranges accurately.

---

## Recommended reading order for contributors

1. `api/font/CgFont.java`
2. `api/font/CgFontFamily.java`
3. `api/font/CgTextLayoutBuilder.java`
4. `text/layout/CgTextLayoutEngine.java`
5. `api/text/CgTextLayout.java`
6. `text/render/CgTextRenderer.java`
7. `text/cache/CgFontRegistry.java`
8. `text/atlas/CgPagedGlyphAtlas.java`
9. `text/msdf/CgMsdfGenerator.java`

This order tells the cleanest top-down story.
