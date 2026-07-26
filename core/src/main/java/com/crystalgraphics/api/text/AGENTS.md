# api/text — Agent Guide

## Package role

This package is the **public text-domain boundary**.

It exists so callers can work with text values — constraints, shaped runs, layouts — without learning the internal cache/atlas/render implementation packages.

## What belongs here

Only public text-domain values should live here.

This package is correct for:

- layout constraints
- final layout results
- shaped directional runs that callers may inspect or hand back to the renderer
- flat baked-glyph data derived from a layout

This package is not correct for:

- shaping algorithms
- line breaking implementation
- atlas or cache ownership
- GL/render submission code

## Reading order

1. `CgTextConstraints`
2. `CgShapedRun`
3. `CgBakedGlyphs`
4. `CgTextLayout`
5. `package-info.java`

## Class-by-class details

### `CgTextConstraints`

Public layout bounds.

Use it to express:

- max width only
- max height only
- both
- no bounds (`UNBOUNDED`)

It is a public API type because callers need to specify layout limits before the engine runs.

### `CgShapedRun`

Immutable result of shaping one directional run.

Carries:

- glyph ids
- cluster ids
- advances and offsets
- total advance
- run direction
- font key
- resolved font handle (`resolvedFont`, excluded from equality — fully determined by `fontKey`)
- source position (`sourceStart`/`sourceEnd`) within its paragraph

Important nuance:

`CgShapedRun` no longer carries the paragraph's source **text** — that used to be a known leak (a copy of the input string retained on every run just so line-breaking could re-shape sub-ranges). It's now supplied externally, once per paragraph, via `text.layout.CgReshapeContext`. `sourceStart`/`sourceEnd` remain on the run (cheap ints, not a leak) because line-breaking still needs to know *where* within that external text this run's segment falls.

### `CgBakedGlyphs`

Flat, per-glyph baked layout data for one `CgTextLayout` — pen positions (relative to the layout's own origin), resolved font key/font/glyph id per glyph, a `justifiable` flag per glyph (Seam A — unconsumed until text justification is built), and per-line height/glyph-start-index arrays.

Computed once by the layout engine's bake step so renderer-side consumers (`CgResolvedGlyphs`) can do a flat translate-only loop instead of re-walking the `lines` tree and re-resolving fonts by key on every draw.

### `CgTextLayout`

Final public layout result.

Carries:

- lines of shaped runs in visual order
- total width
- total height
- metrics
- `baked` (`CgBakedGlyphs`)

Important nuance:

`resolvedFontsByKey` (a `Map<CgFontKey, CgFont>`) used to live here as an acknowledged leak. It's gone — every `CgShapedRun` and every baked glyph now carries its own resolved font directly, so a separate by-key map is redundant.

## Boundary rules

- This package should stay small.
- It should contain values callers genuinely need.
- It should not absorb the internal layout algorithm or renderer/cache helpers.

## Best files to modify for common tasks

- layout bounds API → `CgTextConstraints`
- public shaped-run semantics → `CgShapedRun`
- baked per-glyph data → `CgBakedGlyphs`
- public layout result semantics → `CgTextLayout`

## Common agent mistakes to avoid

- Do not move algorithmic classes here just because callers see the output.
- Do not add cache or renderer convenience methods here unless they genuinely belong in the public text API.
- Do not reintroduce a font-by-key map on `CgTextLayout` — resolve via `CgShapedRun.resolvedFont()` or `CgBakedGlyphs.fonts()[i]` instead.
