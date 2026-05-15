# text — Agent Guide

## Why this file exists

This directory is the **internal implementation root** for the CrystalGraphics text system.

If `api/font` and `api/text` describe the public surface, `text/` describes how that surface is actually implemented.

Use this file as the first stop when you need to orient yourself across the internal packages.

## Internal package map

- `text/layout` — CPU-side layout algorithm
- `text/cache` — glyph supply, cache keys, async generation, render-thread registry
- `text/atlas` — atlas storage and page allocation
- `text/atlas/packing` — rectangle packing algorithms used by atlas pages
- `text/msdf` — distance-field generation logic and config
- `text/render` — draw-time orchestration, batching, VBO submission, render contexts

## End-to-end responsibility chain

### 1. Layout

`api/font/CgTextLayoutBuilder` is the public bridge, but the real work starts in `text/layout/CgTextLayoutEngine`.

The layout layer is responsible for:

- paragraph splitting
- BiDi segmentation
- fallback run collection through `CgFontFamily`
- HarfBuzz shaping
- line breaking
- producing `api/text/CgTextLayout`

### 2. Cache / generation

`text/cache/CgFontRegistry` receives public glyph requests from the renderer and answers with atlas-backed placements.

This layer decides:

- which cache key to use
- which atlas family to look in
- whether to use bitmap or MSDF/MTSDF generation
- whether to queue async work or resolve synchronously

### 3. Atlas storage

`text/atlas` stores the generated glyphs.

This layer decides:

- which page a glyph lives on
- where inside that page it fits
- how stable glyph placement is over time

### 4. Render

`text/render/CgTextRenderer` consumes a public layout, asks the cache for placements, groups placements into batches, fills a VBO, and submits draw calls.

This layer decides:

- effective raster tier for this draw
- batch grouping by texture / atlas mode / pxRange
- shader selection and binding
- final GL submission

## Best reading order through the implementation

If you need the whole system, read in this order:

1. `api/font/CgTextLayoutBuilder`
2. `text/layout/CgTextLayoutEngine`
3. `api/font/CgFontFamily`
4. `api/text/CgTextLayout`
5. `text/render/CgTextRenderer`
6. `text/cache/CgFontRegistry`
7. `text/atlas/CgPagedGlyphAtlas`
8. `text/msdf/CgMsdfGenerator`

This gives the clearest “string to draw” story.

## Current architectural truths

### Public text types already moved

`CgTextLayout`, `CgTextConstraints`, and `CgShapedRun` are no longer implementation classes. They now live in `api/text` because callers see them directly.

### `CgTextLayoutBuilder` is still an intentional exception

The public layout bridge still lives in `api/font`, even though the reusable algorithm lives in `text/layout`. That is intentional because it still bridges package-private font-family/HarfBuzz seams.

### Cache helpers want to stay near their owner

The internal cache key / generation helper types are package-private and tightly coupled to `CgFontRegistry`. If you move them, move the owner neighborhood with them or not at all.

### `text/atlas` is storage, not policy

Atlas classes should answer “where does this glyph fit?” They should not own fallback selection or draw-state logic.

### `text/render` is orchestration, not generation

Renderer code should ask for placements and consume them. It should not start re-owning FreeType/msdfgen behavior.

## Common refactor mistakes to avoid

- Moving layout internals into public packages just to avoid bridge code
- Letting `CgTextRenderer` absorb cache or atlas ownership
- Treating `CgShapedRun` as a pure DTO and deleting source-text fields without preserving reshaping support
- Splitting package-private cache helpers away from `CgFontRegistry` without moving the owner too
- Adding shader-generation or atlas-storage logic into the wrong neighborhood

## What this tree still intentionally leaks

- `CgTextLayout.resolvedFontsByKey`
- `CgShapedRun.sourceText/sourceStart/sourceEnd`

Those are known current tradeoffs, not accidental leftovers.
