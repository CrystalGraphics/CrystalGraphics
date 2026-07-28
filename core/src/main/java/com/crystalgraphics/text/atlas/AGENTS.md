# text/atlas — Agent Guide

## Package role

This package owns **atlas storage**.

The cache layer decides *what* glyph must be stored; this package decides *where* it lives once storage is required.

## Reading order

1. `CgGlyphAtlas`
2. `CgGlyphAtlasPage`
3. `text/atlas/packing/*`

## Class-by-class details

### `CgGlyphAtlas`

The authoritative atlas: a paged, array-texture-backed store, and the owner of the
`Type` enum (`BITMAP`/`MSDF`/`MTSDF`).

Main responsibilities:

- manage a list of atlas pages, one per layer of a `CgTexture2DArray`
- try the hot page first
- fall back to older pages if needed
- allocate a new page when no existing page fits
- return stable `CgGlyphPlacement` values

This class is the main place to understand the current paged-storage model.

> **Naming history.** There were once two classes: a paged one whose name carried the word
> "Paged", and a legacy single-page LRU atlas holding the plain `CgGlyphAtlas` name. The
> legacy one was retired and the paged one took the plain name, absorbing the `Type` enum.
> So an older document referring to a "paged glyph atlas" class means *this* class, and the
> retired implementation survives only as `CgOldGlyphAtlas`, with no callers — do not build
> on it.
>
> (Deliberately phrased without the old identifier: a repo-wide find/replace for it would
> otherwise rewrite this very paragraph into saying the class was renamed to itself.)

### `CgGlyphAtlasPage`

One page within a paged atlas — one layer of the array texture.

Owns:

- one packing strategy instance
- that page’s glyph map

Important idea:

- placements inside a page are stable once allocated

### `text/atlas/packing/*`

Algorithm layer used by pages to fit glyph boxes into the page rectangle.

## Storage format

Two atlases exist process-wide, one per texture format, and **every font shares them**:

| atlas | format | shared across |
|---|---|---|
| bitmap | `R8` | all fonts, all raster sizes |
| distance field | `RGBA8` | all fonts (one atlas scale by construction) |

There are two rather than one only because a `CgTexture2DArray` carries a single internal
format across all its layers and the tiers need different ones. Atlases used to be keyed
per font, which gave a forty-glyph UI font a whole page to itself and cost a texture
rebind per font in mixed-font text; see `CgFontRegistry`'s atlas fields for why that was
merged and what it means for eviction and font disposal.

The distance-field atlas is `RGBA8`, not `RGBA16F` — 8 bits per channel is enough for a
distance field, verified rather than assumed by `CgMsdfFieldStorageTest`. Uploads hand the
driver `GL_FLOAT` and let it quantise; converting CPU-side first was measured and is
substantially slower.

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
- eviction is **page**-granular, never glyph-granular — a whole page's packer is discarded
  along with it, which is why packing strategies never need to free individual rects

## Common agent mistakes to avoid

- Do not move generation policy here.
- Do not mix renderer draw-batch logic into atlas classes.
- Do not build on `CgOldGlyphAtlas` — it is the retired single-page model, kept for
  reference only and with no callers.
- Do not reintroduce per-font atlas keying. Glyph identity already carries the font via
  `CgGlyphKey`, so shared pages cannot collide, and per-font atlases were the source of
  the memory waste in the first place.
