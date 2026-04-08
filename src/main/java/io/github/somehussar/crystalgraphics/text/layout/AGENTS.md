# text/layout — Agent Guide

## Package role

This package owns the **internal CPU-side text layout algorithm**.

It is responsible for turning text + font-family information into shaped runs and final line layout, while staying free of atlas, cache, and GL concerns.

## What belongs here

- paragraph splitting
- BiDi run segmentation
- shaping orchestration
- line breaking
- reshaping support for split runs

## What does not belong here

- font file loading
- atlas allocation
- glyph cache ownership
- shader/GL submission

## Reading order

1. `CgTextLayoutEngine`
2. `CgTextShaper`
3. `CgLineBreaker`
4. `RunReshaper`

## Class-by-class details

### `CgTextLayoutEngine`

Abstract base implementation of the layout pipeline.

Main responsibilities:

- split input into paragraphs
- run Java `Bidi` over each paragraph
- iterate directional runs
- delegate concrete shaping/fallback collection through protected hooks
- call the line breaker
- assemble the final `CgTextLayout`

Why it is abstract:

- the actual algorithm belongs here
- but the public bridge still has to supply package-private font-family/HarfBuzz access from `api/font`

This file is the best place to understand the algorithm skeleton without the font-package bridge noise.

### `CgTextShaper`

HarfBuzz shaping wrapper.

It is responsible for turning one concrete directional text slice into a `CgShapedRun`.

Important idea:

- this class shapes a run
- it does not decide fallback fonts
- it does not break lines

### `CgLineBreaker`

Line-breaking implementation over shaped runs.

It owns the decision of where a visual line should break based on run widths and constraints.

Important idea:

- it works on already-shaped runs
- but it still needs `RunReshaper` because a break may cut through a previously shaped run

### `RunReshaper`

Callback contract for re-shaping a subrange of a run.

This exists so the line breaker can remain layout-focused while still asking the outer bridge layer to re-shape a text fragment correctly.

## End-to-end role in the bigger pipeline

This package sits between:

- `api/font/CgTextLayoutBuilder` (public bridge)
- and `api/text/CgTextLayout` (public result)

It is the purest place in the pipeline to understand:

- how text is segmented
- how runs are shaped
- how line wrapping is computed

## Common agent mistakes to avoid

- Do not move atlas/cache/render concerns into this package.
- Do not remove source-text fields from `CgShapedRun` without preserving reshaping support.
- Do not move `CgTextLayoutBuilder` here unless the package-private bridge seam changes too.
