# Pipeline Map and Glossary

## TL;DR

The current text pipeline is:

1. **input text** enters through `CgTextRenderer.draw(String, ...)` or `CgTextLayoutBuilder`
2. **layout** is built by `CgTextLayoutBuilder` + `CgTextLayoutEngine`
3. **fallback runs** are resolved by `CgFontFamily`
4. **shaping** happens in `CgTextShaper`
5. **line breaking** happens in `CgLineBreaker`
6. the result becomes a public **`CgTextLayout`**
7. **render-time raster tier selection** happens in `CgTextRenderer`
8. **glyph lookup / generation** happens in `CgFontRegistry`
9. **atlas placement** comes from `CgPagedGlyphAtlas` / `CgGlyphAtlasPage`
10. **batching + quad submission** happen in `CgTextRenderer` + `CgQuadBatcher`
11. shaders sample atlas textures and perform the final **draw**

If you only remember one distinction, remember this:

> layout decides what the text means spatially; cache/atlas decides where glyph pixels live; render decides how those pixels are drawn now.

---

## Detailed end-to-end pipeline

### 0. Font and family setup

Before any text is laid out:

- `CgFont` owns loaded font bytes and native state
- `CgFont.atSize(int)` produces the sized/shapable form
- `CgFontFamily` combines a primary font and ordered fallbacks

This stage decides what font resources are available to the layout engine.

### 1. String input enters the layout boundary

There are two public entry styles:

- `CgTextRenderer.draw(String, ...)`
- `CgTextLayoutBuilder.layout(...)`

If you call the renderer with a raw string, it still routes through the same layout path first.

Main output type of this stage:

- `CgTextLayout`

### 2. Paragraph splitting and BiDi segmentation

Inside `CgTextLayoutEngine`:

- input is split into paragraphs
- each paragraph is segmented into directional runs using Java `Bidi`

Nothing is shaped yet. The engine is still deciding directionality and fallback-run boundaries.

### 3. Fallback run resolution

`CgFontFamily.resolveRuns(...)` chooses concrete font sources for slices of text.

This stage answers:

> which exact font source should shape this cluster range?

Internal representation here:

- `CgFontFamily.ResolvedFontRun`

### 4. Shaping

`CgTextShaper` turns each resolved run into a `CgShapedRun`.

This produces:

- glyph IDs
- cluster indices
- per-glyph advances
- per-glyph offsets
- run direction
- source text/range for re-shaping support

### 5. Line breaking

`CgLineBreaker` turns shaped runs into visual lines.

If a line breaks inside a run, it does not slice glyph arrays blindly. Instead it calls `RunReshaper` to re-shape the exact text fragment so the result stays correct.

This is why `CgShapedRun` still carries source text/range fields.

### 6. Final layout result

The engine assembles:

- lines of shaped runs
- total width
- total height
- metrics
- resolved font handles

into a public `CgTextLayout`.

At this point the system knows what glyphs it needs and where text lives in logical space, but it does not yet know where those glyph pixels live in atlas textures.

### 7. Render-time raster tier selection

When `CgTextRenderer` consumes a `CgTextLayout`, it first decides the effective raster tier for this draw.

That decision depends on:

- the base target pixel size from the font key
- the current render context
- the selected scale resolver
- previous frame state for hysteresis

This stage decides whether the draw uses bitmap, MSDF, or MTSDF glyphs.

Important distinction:

- layout remains in **logical space**
- raster tier selection is a **draw-time physical-space** decision

### 8. Glyph supply: cache and generation

`CgTextRenderer` calls into `CgFontRegistry` with glyph requests.

`CgFontRegistry` then:

1. converts public glyph identity into internal cache keys
2. chooses bitmap vs MSDF/MTSDF atlas family
3. checks for a cached placement
4. rasterizes or generates a missing glyph
5. commits it into atlas storage

Relevant internal key types:

- `CgRasterFontKey`
- `CgRasterGlyphKey`
- `CgMsdfAtlasKey`

Relevant worker types:

- `CgGlyphGenerationExecutor`
- `CgGlyphGenerationJob`
- `CgGlyphGenerationResult`
- `CgWorkerFontContext`

### 9. Atlas storage and placement

Atlas ownership lives in `text/atlas`.

Main classes:

- `CgGlyphAtlas` — legacy single-page atlas
- `CgPagedGlyphAtlas` — multi-page atlas manager
- `CgGlyphAtlasPage` — one page in a paged atlas
- `text/atlas/packing/*` — packing strategies

The renderer-facing result of atlas allocation is:

- `CgGlyphPlacement`

That placement carries:

- page texture identity
- UV coordinates
- plane bounds / bearings / metrics
- atlas mode information

### 10. Batch creation

`CgTextRenderer` groups placements into draw batches.

Main representations:

- `CgDrawBatchKey`

Grouping is driven by:

- atlas type
- texture ID
- `pxRange` where relevant

### 11. VBO population and draw

`CgQuadBatcher` owns the CPU staging buffer and drives GPU upload through the shared
`CgVertexArrayRegistry` / `CgStreamBuffer` / `CgSharedQuadIbo` infrastructure.

`CgTextRenderer` sorts placements by `CgDrawBatchKey` and submits quads through the batch:

- resolves the correct shader from `CgDrawBatchKey`
- binds the shader when it changes
- binds the page texture
- uploads `u_pxRange` only for distance-field passes
- submits `glDrawElements`

That is the end of the runtime pipeline.

---

## 2D vs world-text branch

The pipeline is mostly shared until render-time raster selection.

### 2D text

- context: `CgTextRenderContext`
- projection: orthographic `Matrix4f`
- scale policy: orthographic/UI scale

### World text

- context: `CgWorldTextRenderContext`
- projection: caller-supplied `Matrix4f`
- scale policy: `PerspectiveScaleResolver`
- projected-size logic influences raster-tier selection

The layout model is shared; only draw-time raster/transform policy differs.

---

## Glossary

### Base font

Unsized `CgFont` that acts like a reusable logical font asset.

### Sized font

`CgFont` bound to a concrete target pixel size. This is the form shaping and glyph generation actually use.

### Font family

Ordered primary + fallback chain represented by `CgFontFamily`.

### Glyph key

Public glyph identity (`CgGlyphKey`) used as the renderer/cache request input.

### Raster font key

Internal cache key describing a base font plus an effective raster size.

### Raster glyph key

Internal cache key describing a glyph request at a concrete raster tier.

### MSDF atlas key

Internal key describing a shared MSDF/MTSDF atlas family and its config.

### Shaped run

One directional run of shaped text (`CgShapedRun`) containing glyph IDs and spacing data.

### Text layout

Final public layout result (`CgTextLayout`) — lines of shaped runs plus dimensions.

### Atlas region

Legacy single-page glyph location (`CgAtlasRegion`).

### Glyph placement

Modern paged-atlas glyph location (`CgGlyphPlacement`) with page identity and plane bounds.

### `pxRange`

Distance-field generation range used by MSDF/MTSDF reconstruction in the fragment shaders.

### Logical layout space

Coordinate space used for line breaking, advances, spacing, and total layout metrics.

### Physical raster space

Concrete pixel size used to rasterize a glyph into a bitmap/MSDF/MTSDF atlas.

### Composite space

The final model-view-projection-transformed space used by the GPU when drawing quads.

---

## Best source files to read after this document

1. `api/font/CgTextLayoutBuilder.java`
2. `text/layout/CgTextLayoutEngine.java`
3. `api/font/CgFontFamily.java`
4. `api/text/CgTextLayout.java`
5. `text/render/CgTextRenderer.java`
6. `text/cache/CgFontRegistry.java`
7. `text/atlas/CgPagedGlyphAtlas.java`
8. `text/msdf/CgMsdfGenerator.java`
