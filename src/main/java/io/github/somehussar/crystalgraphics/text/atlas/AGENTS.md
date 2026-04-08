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

Legacy single-page atlas.

Still used for compatibility paths and as the historical storage model. It remains important because parts of the system still support both legacy and paged flows.

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
- `CgGlyphAtlas` is still valid but is no longer the only or primary abstraction

## Common agent mistakes to avoid

- Do not move generation policy here.
- Do not mix renderer draw-batch logic into atlas classes.
- Treat `CgPagedGlyphAtlas` as the modern path and `CgGlyphAtlas` as the legacy-compatible path.
