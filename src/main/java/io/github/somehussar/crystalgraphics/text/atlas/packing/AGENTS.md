# text/atlas/packing — Agent Guide

## Package role

This package owns the **rectangle packing algorithms** used by atlas pages.

It is intentionally algorithmic and low-level.

## Classes

### `CgPackingStrategy`

Strategy interface for page-local packing.

Core contract:

- accept width/height/id inputs
- return a `PackedRect` or `null`
- optionally accept spacing-aware inserts
- report utilization and packed count

### `CgGuillotinePacker`

Guillotine-style packer aligned with the upstream atlas-generation approach.

Use this when parity with the older/upstream guillotine behavior matters more than experimental packing quality.

### `MaxRectsPacker`

Alternative MaxRects implementation.

This is the more flexible rectangle-packing strategy in the package.

### `PackedRect`

Immutable result of one successful insert.

Carries packed position and original requested size/id.

## Boundary rules

- This package should stay generic and algorithmic.
- It should not know about fonts, fallback, shaping, or GL draw state.
- It should only know about fitting rectangles into a page-sized bin.

## Common agent mistakes to avoid

- Do not add text-domain policy here unless the packer truly requires it.
- Keep packers stateful and page-local.
- Treat spacing semantics carefully: allocation spacing is not the same thing as the visible glyph box size.
