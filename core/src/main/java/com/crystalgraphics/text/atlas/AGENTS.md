# text/atlas — Agent Guide

## Package role

This package owns **atlas storage**.

The cache layer decides *what* glyph must be stored; this package decides *where* it lives once storage is required.

## Reading order

1. `CgPagedGlyphAtlas`
2. `CgGlyphAtlasPage`
3. `CgGlyphAtlas`
4. `text/atlas/packing/*`

## Class-by-class details

### `CgPagedGlyphAtlas`

Modern authoritative atlas abstraction.

Main responsibilities:

- manage a list of atlas pages
- try the hot page first
- fall back to older pages if needed
- allocate a new page when no existing page fits
- return stable `CgGlyphPlacement` values

This class is the main place to understand the current paged-storage model.

### `CgGlyphAtlasPage`

One page within a paged atlas.

Owns:

- one GL texture
- one packing strategy instance
- that page’s glyph map

Important idea:

- placements inside a page are stable once allocated

### `CgGlyphAtlas`

Formerly the legacy single-page, LRU-evicting atlas storage model. That
implementation had zero production callers once the paged path became the
sole rasterization pipeline in `CgFontRegistry`, and was removed. All that
remains is the `Type` enum (`BITMAP`/`MSDF`/`MTSDF`), kept here — rather than
moved to its own file — only to avoid a repo-wide rename of every
`CgGlyphAtlas.Type` reference in `CgGlyphAtlasPage`, `CgPagedGlyphAtlas`,
`CgFontRegistry`, and the msdf/cache packages. Do not add instance behavior
back to this class; if you need atlas storage logic, it belongs in
`CgGlyphAtlasPage`/`CgPagedGlyphAtlas`.

### `text/atlas/packing/*`

Algorithm layer used by pages to fit glyph boxes into the page rectangle.

## Internal flow summary

1. cache layer requests allocation
2. paged atlas checks existing pages
3. page delegates fit decisions to its packing strategy
4. on success, atlas returns a stable `CgGlyphPlacement`
5. renderer later consumes that placement

## Key invariants

- this package owns storage, not fallback/font policy
- page allocation should preserve placement stability
- renderer should consume placements, not mutate atlas state directly
- `CgGlyphAtlas` is a type-tag holder only — `CgPagedGlyphAtlas` is the only
  storage model

## Common agent mistakes to avoid

- Do not move generation policy here.
- Do not mix renderer draw-batch logic into atlas classes.
- Do not treat `CgGlyphAtlas` as a live storage class — it has no instances
  to create; `CgPagedGlyphAtlas`/`CgGlyphAtlasPage` are the only storage path.
