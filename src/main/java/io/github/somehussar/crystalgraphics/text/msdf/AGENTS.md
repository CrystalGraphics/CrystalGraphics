# text/msdf — Agent Guide

## Package role

This package owns **distance-field generation logic and policy**.

It exists so the cache layer can ask for distance-field glyph production without dragging generation math into the renderer or atlas packages.

## Reading order

1. `CgMsdfAtlasConfig`
2. `CgMsdfGenerator`
3. `CgMsdfGlyphLayout`
4. `CgMsdfEdgeColoringMode`
5. `CgMsdfVerificationConfig`

## Class-by-class details

### `CgMsdfAtlasConfig`

Configuration object for one shared MSDF/MTSDF atlas family.

This is the main policy/config surface for:

- atlas scale
- MTSDF vs MSDF mode
- overlap support
- spacing
- generation tolerances and related knobs

### `CgMsdfGenerator`

Render-thread MSDF/MTSDF generator.

Main responsibilities:

- legacy single-page generation path
- authoritative paged generation path
- frame-budget limiting
- shape preparation and edge coloring
- fallback gating when distance fields are not worth using

This class is the operational center of the package.

### `CgMsdfGlyphLayout`

Parity-sensitive glyph box / range math.

This is one of the most important correctness classes in the package because subtle layout/range mismatches show up as runtime sampling artifacts.

### `CgMsdfEdgeColoringMode`

Enum/config surface for edge-coloring strategy.

### `CgMsdfVerificationConfig`

Configuration for MSDF verification/support workflows.

## Internal flow summary

1. cache layer requests MSDF/MTSDF generation
2. `CgMsdfGenerator` loads outline data through msdfgen bindings
3. `CgMsdfGlyphLayout` computes atlas-box/range math
4. generator emits pixel data
5. cache layer commits the result into atlas storage

## Key invariants

- this package owns generation math and config, not final draw batching
- `CgMsdfGenerator` is called by the cache layer, not by normal public API callers
- parity-sensitive constants and formulas matter; visual correctness is tightly coupled to this package
- distinguish clearly between the authoritative paged path and legacy single-page compatibility code

## Common agent mistakes to avoid

- Do not move this math into the renderer package.
- Be conservative when changing constants/ranges/layout formulas.
- Do not assume a passing compile means distance-field behavior stayed correct.
