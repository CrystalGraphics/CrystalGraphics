# Text Architecture

Which package owns what. For how a string moves through them, read
[`pipeline-map-and-glossary.md`](pipeline-map-and-glossary.md); for how to call them,
[`api-guide.md`](api-guide.md). Paths are under `core/src/main/java/com/crystalgraphics/`.

> **Public packages hold what a caller sees; internal packages own the pipeline that makes and draws it.**
> A caller never needs the registry, an atlas or a generation job.

---

## Public

### `api/font` — fonts

| Class | Is |
|---|---|
| `CgFont` | One face at one pixel size; `atSize(px)` for another size. Loads from a path or bytes, a `.ttc` face by index |
| `CgFontData` | Where a font's bytes live: memory, or a file the natives open themselves |
| `CgFontFamily`, `CgFontSource` | A primary font and ordered fallbacks, resolved per character |
| `CgFontFallback` | The last fallback step: a font for a character none of a family's own can draw |
| `CgFontFamilyGroup` | A family per `CgFontStyle`, which a bold or italic span resolves against; a missing style is synthesised |
| `CgSystemFonts`, `CgSystemFontFace`, `CgGenericFamily` | The installed fonts: by family name, by CSS generic family, and as per-script fallback |
| `CgFontStyle`, `CgFontVariation`, `CgFontAxisInfo` | Style and variable-font axes |
| `CgFontKey`, `CgGlyphKey` | Identity of a font registration, and of one glyph variant |
| `CgFontMetrics`, `CgGlyphMetrics`, `CgGlyphPlacement` | Metrics, and where a glyph sits in the atlas |

### `api/text` — layout values

| Class | Is |
|---|---|
| `CgTextLayout` | The finished layout: lines, total size, metrics, baked glyphs. `CgTextLayout.of(...)` starts a `Request` |
| `CgShapedParagraph` | Shaped but not wrapped; `layout(width, height)` wraps it, memoising the last pair |
| `CgParagraphKnobs` | What a request fixes at shape time: direction, alignment, max lines, ellipsis, line height, tab stops |
| `CgShapedRun` | One directional run of shaped glyphs |
| `CgBakedGlyphs`, `CgTextDecorationRect` | Flat per-glyph pen positions, ids and colours, and decoration rectangles, computed once per layout |
| `CgStyledText`, `CgStyleSpan`, `CgFontFeature`, `CgTextDecoration` | Plain text plus styled ranges: bold, italic, colour, decorations, OpenType features, baseline shift |
| `CgTextAlign`, `CgTextDirection` | Per-line alignment; paragraph direction for BiDi |
| `CgTextStroke`, `CgStrokeAlign` | An outline: width in em, colour, alignment, over or under the fill |

### `text/richtext` — markup

`CgMarkupParser` turns markup into `CgStyledText`. The built-ins are `CgMarkupParser.HTML`
(`CgTagMarkupParser`: `<b>`, `<i>`, `<u>`, `<s>`, `<overline>`, `<color=#RRGGBB>`) and
`CgMarkupParser.MINECRAFT` (`CgMinecraftColorCodeParser`: `§` codes). Both are also registered by name,
`"html"` and `"minecraft"`, and a third party registers its own with `CgMarkupParser.register`.

### `text/render` — the renderer's public face

| Class | Is |
|---|---|
| `CgTextRenderer`, `CgTextRenderer.Draw` | The renderer, and the fluent draw request |
| `CgTextRenderContext` | Projection and viewport, orthographic or world, plus per-font raster history |
| `CgTextGamma` | The coverage correction the renderer applies to light text |
| `CgTextRendererRegistry` | Tracks every renderer so context teardown can free any left alive |

---

## Internal

### `text/layout` — shaping and line breaking

- `CgTextLayoutEngine` — the pipeline, split in two: `shape` (paragraphs, BiDi crossed with style spans,
  fallback runs, HarfBuzz) builds a `CgShapedParagraph`; `wrap` (line breaking, alignment, truncation,
  baking) is what `CgShapedParagraph.layout` re-runs.
- `CgTextShaper` — HarfBuzz for one directional run; `Utf8ClusterMapper` maps its clusters back to UTF-16.
- `CgLineBreaker`, `CgBreakOpportunities` — line breaking at UAX #14 break opportunities, which the
  JDK's line iterator gets wrong in places.
- `RunReshaper`, `CgReshapeContext` — re-shaping a run split at a line break, from the paragraph's source
  text, which lives here rather than on every run.
- `CgTextLayoutCache` — a bounded, content-keyed cache behind `draw().text(...)`.

Knows nothing of atlases or GL.

### `text/cache` — glyph supply

- `CgFontRegistry` — turns a `CgGlyphKey` into a `CgGlyphPlacement`: looks it up, or queues it for
  generation, and uploads what workers finish within a per-frame budget.
- `CgGlyphGenerationExecutor`, `CgGlyphGenerationJob`, `CgGlyphGenerationResult`, `CgWorkerFontContext` —
  the worker pools and each worker's own FreeType/msdfgen state.
- `CgFontWarmer` — speculative warming of a face's common characters, never ahead of a glyph on screen.
- `CgRasterFontKey`, `CgRasterGlyphKey`, `CgMsdfAtlasKey` — the internal keys.

### `text/atlas` — atlas storage

- `CgGlyphAtlas` — one atlas per format, each a `CgTexture2DArray`: an `R8` bitmap atlas and an `RGBA8`
  distance-field atlas. **Every font and size shares them.**
- `CgGlyphAtlasPage` — one layer of that array, with its packer.
- `packing/` — `CgPackingStrategy`, `MaxRectsPacker`.

### `text/msdf` — distance fields

- `CgMsdfGenerator`, `CgMsdfGlyphLayout` — generation, and the glyph box it is generated in.
- `CgMsdfAtlasConfig` — the one atlas scale every distance field is generated at (80 px), and each face's
  range.
- `CgMsdfQualityProbe` — measures how faithfully a field at a given scale reproduces the outline.
- `CgMsdfEdgeColoringMode`, `CgMsdfVerificationConfig`.

Called by the cache layer, never by the renderer.

### `text/render` — drawing

- `CgResolvedGlyphs` — resolves a layout into per-glyph placements for one draw.
- `CgGlyphPlacementCache` — those placements, cached per layout (layout, tier, font, colour, sub-pixel phase), relative
  to the layout's origin so moving text reuses them; only an entry that wanted distance-field and fell back is
  refreshed when the atlas gains glyphs.
- `CgTextCuller` — skips a draw whose whole layout is off-screen.
- `CgTextShadowList`, `CgTextShadowPlan` — a draw's shadows, and what each glyph paints for each.
- `CgTextSortKey` — packs each glyph and decoration into a sortable key; its batch bits are the tier and
  the atlas texture.
- `context/` — `CgTextScaleResolver` with `OrthographicScaleResolver` (UI) and `PerspectiveScaleResolver`
  (world), which turn a pose into a raster size; `ProjectedSizeEstimator`.

### `text/font` — font files without natives

`Sfnt` reads a file's faces, names, weight and coverage; `CodePointCoverage` is that coverage as ranges;
`ScriptFallbacks` is which installed families to try per script, Windows' ported from Chromium. Its
public face is `api/font/CgSystemFonts`.

### The material

`assets/crystalgraphics/shaders/text.shader`: one material for bitmap, MSDF and MTSDF glyphs, with a single
`MSDF_MODE` keyword. It also draws strokes, shadows and gamma, with the helpers in
`shaders/lib/text_gamma.glsl`, `shaders/lib/rect_blur.glsl` (a decoration's shadow) and
`shaders/lib/texel.glsl` (bitmap glyphs off-axis).

---

## Boundaries

- **Layout never touches atlases or GL**, and the renderer never generates a glyph: it asks the registry.
- **Font identity is not a batch key.** All fonts share the atlases, so a draw mixing fonts and sizes is
  one call; only bitmap against distance-field glyphs splits one.
- **Layout is in layout pixels and never changes with the pose.** The raster size is chosen per draw;
  only a `paragraph` draw re-wraps, and only to keep its constraints in on-screen pixels.

---

## Reading order

1. `api/text/CgTextLayout.java` — the request and the result
2. `text/layout/CgTextLayoutEngine.java` — shape and wrap
3. `api/font/CgFontFamily.java` — fallback
4. `text/render/CgTextRenderer.java` — the draw request and the draw path
5. `text/render/CgResolvedGlyphs.java` — glyphs to placements
6. `text/cache/CgFontRegistry.java` — glyph supply
7. `text/atlas/CgGlyphAtlas.java` — storage
8. `text/render/CgTextShadowPlan.java` and `api/text/CgTextStroke.java` — effects
