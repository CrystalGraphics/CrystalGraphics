# text/atlas/packing — Agent Guide

## Package role

This package owns the **rectangle packing algorithms** used by atlas pages.

It is intentionally algorithmic and low-level.

## Classes

### `CgPackingStrategy`

Strategy interface for page-local packing.

Core contract:

- accept width/height/id inputs
- return a `MaxRectsPacker.PackedRect` or `null`
- optionally accept spacing-aware inserts
- report utilization and packed count
- optionally answer cheap/exact free-space queries (`mayFit`, `countFreeRegionsFitting`,
  `describeFreeRegions`, `totalFreeArea`) — all have safe defaults, so a strategy that cannot
  answer simply opts out

### `MaxRectsPacker`

MaxRects with Best Short-Side Fit. **The only packer**, used by every page including tests.

`PackedRect` is nested inside it: the immutable result of one successful insert, carrying the
packed position and the **requested** size — not the allocator's padded footprint.

A guillotine packer used to live here as a second strategy. It was deleted: nothing in production
used it, its msdf-atlas-gen-parity rationale no longer applied, and it never implemented the
free-space query surface, so it was permanently second-class. Tests used to build pages with it
while every real atlas used MaxRects, meaning nothing exercised the packer that actually ships.

## Performance notes

Read these before "optimising" anything here — both were established by measurement, and one of
them was measured only after a plausible-sounding argument turned out to be wrong.

**`pruneContained` must only examine newly created rects.** `freeRects` is mutually
non-contained by invariant, so re-comparing existing rects against each other re-derives a known
fact. Doing so cost **1106 ms of a 1129 ms packing budget** on a real warmup, peaking at 4.4 ms
for a single insert — a quarter of a 60 fps frame, on the render thread. Restricting the prune to
new-vs-new and new-vs-existing made packing **11.3× faster** with page count unchanged and
per-page fill slightly better.

**MaxRects is the right algorithm here, but only because `n` is per-page.** The free-rect list
resets at every page boundary, so cost is bounded by per-page occupancy (~1200 packed, ~790 free
rects on a 1024² page), not by total atlas glyphs. Skyline/shelf — what stb_rect_pack and Skia use
— is the standard choice when packing thousands into one large bin, and would be the right target
*if* insert cost ever became a problem again. It would trade density for speed, so measure before
switching.

## Testing

`MaxRectsPackerTest` covers insert/overlap/utilisation plus the free-space query surface. The
`mayFit` contract test matters most: it must never return `false` for something `insert` then
places, because a false negative strands atlas space permanently with no symptom beyond a slowly
growing page count.

`MaxRectsPackerReplayTest` replays a recorded glyph sequence from a real atlas dump and asserts
the page count does not exceed the recorded baseline. It exists because **harness runs cannot
answer "did packing regress"** — distance-field generation is async, so two runs commit different
glyph counts, and a partially-converged atlas looks exactly like a badly-packed one. That
confusion cost a full investigation once; the replay is the answer to it.

## Boundary rules

- This package should stay generic and algorithmic.
- It should not know about fonts, fallback, shaping, or GL draw state.
- It should only know about fitting rectangles into a page-sized bin.
- Profiling (`CgProfiler`) is allowed and present — it is a util, not a domain dependency, and the
  scopes on `insert`/`pruneContained` are what caught the O(n²).

## Common agent mistakes to avoid

- Do not add text-domain policy here unless the packer truly requires it.
- Keep packers stateful and page-local.
- Treat spacing semantics carefully: allocation spacing is not the same thing as the visible glyph
  box size. `insert` reserves `width + spacing`; the returned rect reports `width`.
- Do not judge a packing change by **mean utilisation**. When page count is already minimal, the
  mean is pinned by `packedArea / (pages × pageArea)` and cannot move however the glyphs are
  distributed. Judge by page count at the margin and by fill of non-final pages.
- Do not reintroduce a `remove()`. Eviction is page-granular — a page's packer is discarded whole —
  so per-rect removal has no caller, and the naive version (re-add the freed rect, prune) does not
  restore the free-space structure a correct implementation would rebuild.
