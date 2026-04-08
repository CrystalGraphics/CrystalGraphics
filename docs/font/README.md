# CrystalGraphics Font/Text Documentation

This directory is the **current canonical documentation set** for the CrystalGraphics font and text system.

Older investigation notes, one-off experiments, and pre-refactor writeups have been intentionally removed. The files that remain here describe the architecture that exists in the codebase **today**.

## TL;DR

- `api/font` owns **font-domain public API**: loaded fonts, families, glyph keys, atlas regions/placements, and the public layout bridge.
- `api/text` owns **public text-domain values**: `CgTextConstraints`, `CgTextLayout`, `CgShapedRun`.
- `text/layout` owns the **internal layout algorithm**.
- `text/cache` owns **glyph supply**: registry, generation jobs/results, cache keys.
- `text/atlas` owns **atlas storage**: single-page atlases, paged atlases, pages, packing.
- `text/msdf` owns **distance-field generation logic**.
- `text/render` owns the **draw side**: batching, VBOs, contexts, raster-tier policy, final GL submission.

If you only want one sentence:

> string → layout (`api/text`) → glyph supply (`text/cache`) → atlas placement (`text/atlas`) → batching/VBO (`text/render`) → draw

## Read In This Order

1. **`architecture.md`** — package ownership and structural boundaries
2. **`pipeline-map-and-glossary.md`** — end-to-end runtime flow + shared terminology
3. **`api-guide.md`** — practical usage patterns and API walkthrough

## Important Current Exceptions

- **`CgTextLayoutBuilder` still lives in `api/font`.**
  This is intentional. It is the public bridge into the internal layout engine and still needs access to package-private font-family/HarfBuzz seams.

- **`CgTextLayout.resolvedFontsByKey` is still a public/internal leak.**
  The renderer still needs resolved `CgFont` handles at draw time.

- **`CgShapedRun` still carries source text/range fields.**
  Those fields are still needed for run re-shaping during line breaking.

## Where To Look In Source

If you want the implementation story in code form, start here:

1. `api/font/CgTextLayoutBuilder.java`
2. `text/layout/CgTextLayoutEngine.java`
3. `api/font/CgFontFamily.java`
4. `api/text/CgTextLayout.java`
5. `text/render/CgTextRenderer.java`
6. `text/cache/CgFontRegistry.java`
7. `text/atlas/CgPagedGlyphAtlas.java`
8. `text/msdf/CgMsdfGenerator.java`

## Package-local agent docs

Each relevant source package also gets its own `AGENTS.md` file.

Those files are intentionally more implementation-focused than the docs in this directory and are meant for future LLM/agent onboarding directly inside the source tree.
