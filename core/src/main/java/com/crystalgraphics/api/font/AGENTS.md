# api/font — Agent Guide

## Package role

This package is the **public font-domain boundary** for the text system.

It owns the types callers use to talk about fonts, families, glyph identity, and renderer-facing glyph payloads.

## What belongs here

This package is appropriate for:

- font loading and sizing
- font/family identity
- variation metadata
- fallback composition
- glyph identity inputs
- renderer-facing glyph location payloads

This package is **not** the home of the internal layout algorithm, atlas allocation logic, async generation pipeline, or GL submission code.

## Reading order

1. `CgFont`
2. `CgFontFamily`
3. `CgFontSource`
4. `CgGlyphKey`
5. `CgGlyphPlacement`
6. supporting value types

## Class-by-class details

### `CgFont`

Primary public font handle.

This class is doing two jobs on purpose:

1. representing a logical font asset that can be reused
2. vendoring sized renderable/shapable variants through `atSize(int)`

Important responsibilities:

- loads native FreeType state
- creates HarfBuzz font state for shaping
- exposes the size-bound `CgFontKey`
- manages base-font vs sized-font distinction
- caches sized variants off a base font
- owns disposal of native resources

Important nuance:

- a base/unsized `CgFont` is convenient for callers
- a size-bound `CgFont` is what the runtime actually shapes and rasterizes

If you are debugging font ownership or native resource lifetime, start here.

### `CgFontFamily`

Ordered primary+fallback family.

This class is the ownership point for fallback resolution.

What it does:

- stores primary and fallback sources in deterministic order
- guarantees a single shared targetPx across the family
- combines metrics across sources for layout use
- resolves text cluster ranges to concrete font sources
- owns the nested `ResolvedFontRun` helper, and `resolveRuns`/`requireShapingFont`, all `public`
  specifically so `text/layout/CgTextLayoutEngine` (which lives in a different package) can call
  them directly — see "Layout bridge" below

Important invariants:

- all sources in one family must share the same target pixel size
- cluster resolution must respect continuation marks / sticky continuation behavior

If you are debugging “why was this substring shaped with that font?”, this is the class to read.

### `CgFontSource`

Thin wrapper around one concrete family member.

Provides:

- a `CgFont`
- its `CgFontKey`
- its metrics
- coverage checks for code points

This is the granularity at which fallback resolution reasons about “one candidate font”.

### Layout bridge — no bridge class, just public seam members

There used to be a `CgTextLayoutBuilder` class here: a thin subclass of
`text/layout/CgTextLayoutEngine` that existed *solely* to reach `CgFontFamily`'s
package-private `resolveRuns`/`ResolvedFontRun`/`requireShapingFont` (the algorithm's two shaping
hooks needed same-package access to those). It added an indirection layer for zero behavioral
benefit once those three members were simply made `public` on `CgFontFamily` — so it was deleted.

`CgTextLayoutEngine` (in `text/layout`) now calls `family.resolveRuns(...)` /
`family.requireShapingFont(...)` directly, and is the sole public layout entrypoint
(`CgTextLayoutEngine.shape`/`shapeStyled`, called by `api.text.CgTextLayout.Request`). This
package still doesn't own the algorithm — `resolveRuns`/`ResolvedFontRun`/`requireShapingFont`
are public *specifically* to be called from `text/layout`, not because they're meant for general
external use; treat them as the layout engine's private seam, not ordinary `CgFontFamily` API.

### `CgGlyphKey`

Public glyph identity request.

Carries:

- font key
- glyph id
- bitmap-vs-distance-field request intent
- sub-pixel bucket

This is the renderer/cache input identity, not an atlas placement.

### `CgGlyphPlacement`

Renderer-facing placement payload for the paged atlas system.

Carries:

- page texture identity
- UV coordinates
- plane bounds / bearings / metrics
- atlas type details

This is one of the most important handoff types in the system: cache/atlas produces it, renderer consumes it. It is the *only* renderer-facing glyph placement payload — `CgAtlasRegion` (the legacy single-page atlas payload) has been removed along with the single-page storage model that produced it.

### `CgFontKey`

Canonical identity for a sized font variant.

This key is heavily used across cache and renderer code as the stable “font at this size/style/variation” identity.

### `CgFontStyle`

Public style descriptor.

### `CgFontVariation`

One variation-axis assignment for variable fonts.

### `CgFontAxisInfo`

Metadata describing available variation axes.

### `CgFontMetrics`

Font-level layout metrics used by layout and renderer normalization.

### `CgGlyphMetrics`

Glyph-level metrics value type.

## Important boundaries and exceptions

- `api/font` owns public font-domain concepts.
- It does **not** own the internal layout algorithm — that's `text/layout/CgTextLayoutEngine`.
- It does **not** own atlas storage or glyph generation scheduling.
- `CgFontFamily#resolveRuns`/`ResolvedFontRun`/`requireShapingFont` are public only as the layout
  engine's bridge seam — see "Layout bridge" above. Don't treat them as ordinary public API.
- `CgGlyphPlacement` is public but should be thought of as a renderer-facing runtime payload, not a clean business-object DTO.

## Best files to modify for common tasks

- font loading / sizing issues → `CgFont`
- fallback resolution issues → `CgFontFamily`
- public layout entrypoint issues → `text/layout/CgTextLayoutEngine` (via `api.text.CgTextLayout.Request`)
- glyph identity changes → `CgGlyphKey`
- placement payload changes → `CgGlyphPlacement`

## Common agent mistakes to avoid

- Do not reintroduce a bridge subclass (`CgTextLayoutBuilder`-style) to reach `CgFontFamily`'s
  shaping seam — `resolveRuns`/`ResolvedFontRun`/`requireShapingFont` are already public for
  exactly that purpose; call them directly from `text/layout`.
- Do not add atlas/cache/generation logic here for convenience.
- Do not assume `CgGlyphPlacement` is a pure public DTO with no runtime coupling.
