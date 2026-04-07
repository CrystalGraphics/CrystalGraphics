# CrystalGraphics Font Rendering

CrystalGraphics now includes a modern text rendering foundation built on top of FreeType, HarfBuzz, and MSDFgen.

This system provides:
- bitmap glyph rasterization for small and medium text
- MSDF glyph generation for larger text
- BiDi-aware shaping and line breaking
- dynamic glyph atlases with LRU reuse
- a two-pass renderer for bitmap and MSDF quads
- PoseStack-driven model-view transforms with effective-size-aware rasterization
- 3D world-space text with always-MSDF rendering and projection-aware quality/LOD
- a dev integration example with on-screen rendering and mouse-wheel zoom

The integration demo is interactive by default. The older framebuffer self-check
mode is opt-in via `-Dcrystalgraphics.integration.runSelfChecks=true`.

## Main pieces

### Public API
- `io.github.somehussar.crystalgraphics.api.font.CgFont`
- `io.github.somehussar.crystalgraphics.api.font.CgTextLayoutBuilder`
- `io.github.somehussar.crystalgraphics.api.font.CgFontKey`
- `io.github.somehussar.crystalgraphics.api.font.CgGlyphKey`
- `io.github.somehussar.crystalgraphics.api.font.CgFontMetrics`
- `io.github.somehussar.crystalgraphics.api.font.CgGlyphMetrics`
- `io.github.somehussar.crystalgraphics.api.font.CgAtlasRegion`

### Internal runtime
- `io.github.somehussar.crystalgraphics.gl.text.CgFontRegistry`
- `io.github.somehussar.crystalgraphics.text.atlas.CgGlyphAtlas`
- `io.github.somehussar.crystalgraphics.gl.text.CgMsdfGenerator`
- `io.github.somehussar.crystalgraphics.gl.text.CgGlyphVbo`
- `io.github.somehussar.crystalgraphics.gl.text.CgTextRenderer`
- `io.github.somehussar.crystalgraphics.gl.text.CgTextRenderContext`
- `io.github.somehussar.crystalgraphics.gl.text.CgWorldTextRenderContext`
- `io.github.somehussar.crystalgraphics.gl.text.CgTextScaleResolver`
- `io.github.somehussar.crystalgraphics.gl.text.ProjectedSizeEstimator`

### CPU-side text pipeline
- `io.github.somehussar.crystalgraphics.text.layout.CgTextShaper`
- `io.github.somehussar.crystalgraphics.text.layout.CgLineBreaker`
- `io.github.somehussar.crystalgraphics.text.CgShapedRun`
- `io.github.somehussar.crystalgraphics.text.CgTextLayout`

## Runtime requirements

The current text renderer is a modern GL path and requires:
- core FBO support
- core shader support
- VAO support
- `glMapBufferRange`

That requirement is enforced by `CgTextRenderer.create(...)`.

## Documents
- `docs/font/architecture.md`
- `docs/font/api-guide.md`
- `docs/font/example-usage.md`
- `docs/font/integration-demo.md`
- `docs/font/atlas-internals.md`
- `docs/font/pipeline-deep-dive.md`

## Deep-dive internals

For implementation-level documentation with file and line references, start with:

- `docs/font/atlas-internals.md`
- `docs/font/pipeline-deep-dive.md`

Those two documents cover:
- how fonts are loaded and stored
- how atlas buckets are grouped
- how bitmap and MSDF glyphs are generated and uploaded
- how eviction works
- the full `String -> shaping -> layout -> atlas -> VBO -> shader -> screen` path
