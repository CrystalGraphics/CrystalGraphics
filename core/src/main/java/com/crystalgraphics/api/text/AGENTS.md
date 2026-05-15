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

This package is not correct for:

- shaping algorithms
- line breaking implementation
- atlas or cache ownership
- GL/render submission code

## Reading order

1. `CgTextConstraints`
2. `CgShapedRun`
3. `CgTextLayout`
4. `package-info.java`

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
- source text and source range
- font key

Important nuance:

`CgShapedRun` is public **and** still carries a known internal leak: the source text and source range fields. Those are still needed because line-breaking may re-shape a subrange of a run. Until that reshaping contract is moved elsewhere, those fields are intentional.

### `CgTextLayout`

Final public layout result.

Carries:

- lines of shaped runs in visual order
- total width
- total height
- metrics
- `resolvedFontsByKey`

Important nuance:

`resolvedFontsByKey` is still a public/internal leak because the renderer needs resolved `CgFont` handles at draw time for glyph lookup. This means `CgTextLayout` is not yet a perfect pure DTO.

## Boundary rules

- This package should stay small.
- It should contain values callers genuinely need.
- It should not absorb the internal layout algorithm or renderer/cache helpers.

## Practical meaning of the current leaks

### `CgTextLayout.resolvedFontsByKey`

Keep in mind:

- renderer currently relies on it
- callers should avoid building independent long-lived logic around those `CgFont` handles
- long-term, this probably wants to move into a render-context or resolver object instead

### `CgShapedRun.sourceText/sourceStart/sourceEnd`

Keep in mind:

- line breaker / reshaper still depends on them
- they are not accidental leftovers
- do not remove them unless the reshaping contract has been redesigned first

## Best files to modify for common tasks

- layout bounds API → `CgTextConstraints`
- public shaped-run semantics → `CgShapedRun`
- public layout result semantics → `CgTextLayout`

## Common agent mistakes to avoid

- Do not treat these types as perfectly clean DTOs without checking the current leak fields.
- Do not move algorithmic classes here just because callers see the output.
- Do not add cache or renderer convenience methods here unless they genuinely belong in the public text API.
