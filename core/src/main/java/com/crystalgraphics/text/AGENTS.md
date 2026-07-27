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

`text/layout/CgTextLayoutEngine` is both the algorithm and the entrypoint — `api.text.CgTextLayout.Request`
calls its `shape`/`shapeStyled` directly. There is no separate `api/font` bridge class anymore
(the old `CgTextLayoutBuilder` was deleted): `CgFontFamily#resolveRuns`/`ResolvedFontRun`/
`requireShapingFont` are `public` specifically so this package can call them without one.

The layout layer is responsible for:

- paragraph splitting
- BiDi segmentation
- fallback run collection through `CgFontFamily`
- HarfBuzz shaping
- line breaking
- baking the final line/run tree into a flat `api/text/CgBakedGlyphs` (pen positions,
  resolved font/glyph id, per-line height, justifiable-position tagging — see
  `text/layout/CgTextLayoutEngine`'s `bakeGlyphs`/`computeJustifiable`)
- producing `api/text/CgTextLayout`

Paragraph source text is not retained on `CgShapedRun` — it is threaded through
`text/layout/CgReshapeContext`, one instance per paragraph, so line-breaking's
intra-run re-shaping (`RunReshaper`) has the text it needs without every run
carrying its own copy.

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

Draw calls go through `CgTextRenderer.draw()`/`retainedDraw()`, which return a fluent
`CgTextRenderer.Draw` request object (chain methods, terminated by `.submit()`) — there
is no fixed-arity `draw(...)` method. `CgTextRendererRegistry` tracks every renderer
instance for teardown and (by default, via `create()`; opt out via `createManualSized()`)
automatic resize. See
`text/render/AGENTS.md` for the full API.

## Best reading order through the implementation

If you need the whole system, read in this order:

1. `text/layout/CgTextLayoutEngine`
2. `api/font/CgFontFamily`
3. `api/text/CgTextLayout`
4. `text/render/CgTextRenderer`
5. `text/cache/CgFontRegistry`
6. `text/atlas/CgPagedGlyphAtlas`
7. `text/msdf/CgMsdfGenerator`

This gives the clearest “string to draw” story.

## Current architectural truths

### Public text types already moved

`CgTextLayout` and `CgShapedRun` are no longer implementation classes. They now live in `api/text` because callers see them directly. (`CgTextConstraints` was a separate, unrelated type that has since been deleted entirely — plain `float maxWidth`/`maxHeight` replaced it, see `CgTextRenderer.Draw#constraints`.)

### There is no `api/font` layout bridge class anymore

`CgTextLayoutEngine` (in `text/layout`) used to need a thin `api/font`-resident subclass
(`CgTextLayoutBuilder`) purely to reach `CgFontFamily`'s package-private shaping seam. That seam
(`resolveRuns`/`ResolvedFontRun`/`requireShapingFont`) is now `public` on `CgFontFamily`, so
`CgTextLayoutEngine` calls it directly and the bridge class was deleted — one less class for the
exact same behavior. Don't reintroduce a bridge subclass; if `CgTextLayoutEngine` ever needs a
new `CgFontFamily` seam member, widen that member's visibility instead.

### Cache helpers want to stay near their owner

The internal cache key / generation helper types are package-private and tightly coupled to `CgFontRegistry`. If you move them, move the owner neighborhood with them or not at all.

### `text/atlas` is storage, not policy

Atlas classes should answer “where does this glyph fit?” They should not own fallback selection or draw-state logic.

### `text/render` is orchestration, not generation

Renderer code should ask for placements and consume them. It should not start re-owning FreeType/msdfgen behavior.

## Common refactor mistakes to avoid

- Moving layout internals into public packages just to avoid bridge code
- Letting `CgTextRenderer` absorb cache or atlas ownership
- Reintroducing a source-text copy on `CgShapedRun` instead of threading it through `CgReshapeContext`
- Splitting package-private cache helpers away from `CgFontRegistry` without moving the owner too
- Adding shader-generation or atlas-storage logic into the wrong neighborhood
- Re-walking `CgTextLayout.getLines()` for per-glyph draw data in a new consumer instead of reading the already-baked `CgTextLayout.getBaked()`

## Resolved leaks (historical — no longer true)

`CgTextLayout.resolvedFontsByKey` and `CgShapedRun.sourceText` were previously documented
here as intentional leaks. Both are gone: every `CgShapedRun` carries its own resolved
font directly, and paragraph text is supplied externally via `CgReshapeContext` instead
of being copied onto each run. `CgShapedRun.sourceStart`/`sourceEnd` remain (cheap ints,
not a leak) for intra-run re-shaping.
