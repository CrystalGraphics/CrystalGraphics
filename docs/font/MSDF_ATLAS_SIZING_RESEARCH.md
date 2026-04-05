# MSDF Atlas Sizing & Growth Research — Source-Level Analysis

**Date**: 2026-04-05  
**Scope**: Deep research on how `msdf-atlas-gen` determines atlas dimensions, handles growth/overflow, and relates to CrystalGraphics' current implementation.  
**Primary source**: https://github.com/Chlumsky/msdf-atlas-gen (commit-level source reads)  
**Purpose**: Correct misconceptions, establish ground truth, and recommend optimal strategy for CrystalGraphics.

---

## Table of Contents

1. [How msdf-atlas-gen Determines Atlas Size (Static/Tight Path)](#1-how-msdf-atlas-gen-determines-atlas-size-statictight-path)
2. [Fixed vs Solver-Driven vs Constraint-Driven: The Real Model](#2-fixed-vs-solver-driven-vs-constraint-driven-the-real-model)
3. [APIs and Options for Developer-Controlled vs Automatic Sizing](#3-apis-and-options-for-developer-controlled-vs-automatic-sizing)
4. [How Font Size / Scale / pxRange / emRange Interact with Atlas Dimensions](#4-how-font-size--scale--pxrange--emrange-interact-with-atlas-dimensions)
5. [Single MSDF Atlas per Charset vs Multiple Atlases per Size](#5-single-msdf-atlas-per-charset-vs-multiple-atlases-per-size)
6. [How msdf-atlas-gen Handles Overflow / Dynamic Growth / Re-Layout](#6-how-msdf-atlas-gen-handles-overflow--dynamic-growth--re-layout)
7. [Comparison to CrystalGraphics Current Behavior](#7-comparison-to-crystalgraphics-current-behavior)
8. [Recommended Optimal Strategy for CrystalGraphics](#8-recommended-optimal-strategy-for-crystalgraphics)

---

## 1. How msdf-atlas-gen Determines Atlas Size (Static/Tight Path)

### The Core Algorithm: `TightAtlasPacker::pack()`

**Source**: `msdf-atlas-gen/TightAtlasPacker.cpp`, lines 89-102

The static atlas generation pipeline works in three stages:

#### Stage 1: Glyph Box Computation

For each non-whitespace glyph, `tryPack()` calls `glyph->wrapBox(attribs)` with a `GlyphAttributes` struct containing:

```cpp
GlyphAttributes attribs = { };
attribs.scale = scale;                                    // pixels per EM
attribs.range = unitRange + pxRange / scale;              // total SDF range in EM units
attribs.innerPadding = innerUnitPadding + innerPxPadding / scale;
attribs.outerPadding = outerUnitPadding + outerPxPadding / scale;
attribs.miterLimit = miterLimit;
attribs.pxAlignOriginX = pxAlignOriginX;
attribs.pxAlignOriginY = pxAlignOriginY;
```

Each glyph's `wrapBox()` then:
1. Takes the raw shape bounds from msdfgen.
2. Expands by the SDF range (to include the distance field border).
3. Expands by miter limit (for sharp corners).
4. Adds inner and outer padding.
5. Scales the expanded bounds to pixel space.
6. Optionally pixel-aligns the origin.
7. Computes `boxWidth` and `boxHeight` — **these are per-glyph, not uniform**.

The resulting rectangles are collected into a `std::vector<Rectangle>`.

#### Stage 2: Dimension Solving (when dimensions are NOT fixed)

When `width == -1 || height == -1` (unset), `tryPack()` uses a `SizeSelector` template to find the smallest atlas dimensions that can fit all rectangles.

**Source**: `msdf-atlas-gen/rectangle-packing.hpp`

```cpp
template <class SizeSelector, typename RectangleType>
std::pair<int, int> packRectangles(RectangleType *rectangles, int count, int spacing) {
    int totalArea = 0;
    for (int i = 0; i < count; ++i)
        totalArea += rectangles[i].w * rectangles[i].h;

    SizeSelector sizeSelector(totalArea);
    int width, height;
    while (sizeSelector(width, height)) {
        if (!RectanglePacker(width+spacing, height+spacing).pack(rectanglesCopy.data(), count)) {
            dimensions = {width, height};
            --sizeSelector;   // Try smaller
        } else {
            ++sizeSelector;   // Try larger
        }
    }
    return dimensions;
}
```

The `SizeSelector` starts from an **area-based estimate** (total packed area of all glyphs) and iterates through candidate sizes (e.g., powers of two for `SquarePowerOfTwoSizeSelector`). It tries packing at each candidate size, incrementing on failure and decrementing on success, converging on the **smallest dimensions** that fit.

**This is why the reference atlas is ~800×250**: dimensions are solved from content, not preallocated. The solver found that 800×250 (or similar) is the tightest rectangle satisfying the dimension constraint that fits all glyph boxes.

#### Stage 3: Rectangle Packing

**Source**: `msdf-atlas-gen/RectanglePacker.cpp`

The actual packing is a **single-bin guillotine packer** with `rateFit = min(spaceW - rectW, spaceH - rectH)`. It:

1. Maintains a list of free spaces (initially one space = full atlas).
2. For each rectangle, finds the space with the lowest `rateFit` score (tightest fit).
3. Places the rectangle and splits the space into two remainder rectangles.
4. The split heuristic extends the larger remainder to minimize fragmentation.

### Critical Insight: Dimensions Are Content-Derived

**The reference atlas image dimensions (e.g., 800×250) are NOT a target size the user specified.** They are the **output** of the dimension solver — the smallest dimensions satisfying the constraint that successfully pack all glyph boxes.

This directly corrects the misconception in our prior review that compared CrystalGraphics' 2048×2048 page against the reference's ~800×250 as if they should match. The reference dimensions were solved from the specific font, charset, scale, and range used during generation. Our 2048×2048 is a fixed page allocation, not a solver output.

---

## 2. Fixed vs Solver-Driven vs Constraint-Driven: The Real Model

The atlas sizing model in msdf-atlas-gen is **mixed**, with three interacting axes:

### Axis 1: Dimensions — Fixed or Solver-Derived

| API | Behavior |
|-----|----------|
| `setDimensions(w, h)` | Atlas dimensions are **fixed**. Packer must fit all glyphs within these bounds or fail. |
| `unsetDimensions()` (default) | Dimensions are **solver-derived**: the packer finds the smallest atlas satisfying the constraint. |
| `-dimensions W H` (CLI) | Sets fixed dimensions. |

When dimensions are fixed but scale is not, the packer uses `packAndScale()` to find the **maximum scale** that fits all glyphs within the fixed dimensions via binary search.

### Axis 2: Scale — Fixed or Maximized

| API | Behavior |
|-----|----------|
| `setScale(s)` | Glyph scale is **fixed** at `s` pixels/EM. |
| `setMinimumScale(ms)` | Scale will be **at least** `ms`, but may be larger if the atlas can fit larger glyphs. |
| Neither set | Scale starts at `minScale` (default `1.0`); maximized if dimensions are fixed. |
| `-size S` (CLI) | Sets scale = `S` (default 32.0, meaning 32 pixels per EM). |
| `-minsize S` (CLI) | Sets minimum scale; actual scale is maximized to fill the atlas. |

### Axis 3: Dimension Constraint

**Source**: `msdf-atlas-gen/types.h`

```cpp
enum class DimensionsConstraint {
    NONE,                         // No constraint (only used internally in packAndScale)
    SQUARE,                       // Any square
    EVEN_SQUARE,                  // Even-dimension square
    MULTIPLE_OF_FOUR_SQUARE,      // Multiple-of-4 square (default)
    POWER_OF_TWO_RECTANGLE,       // Power-of-two rectangle (w and h independently)
    POWER_OF_TWO_SQUARE           // Power-of-two square (TightAtlasPacker default constructor)
};
```

CLI flags: `-pots` (POT square), `-potr` (POT rectangle), `-square`, `-square2`, `-square4` (default).

**Important**: The constructor default for `TightAtlasPacker` is `POWER_OF_TWO_SQUARE`, but the CLI default (set in `main.cpp`) is `MULTIPLE_OF_FOUR_SQUARE` (`-square4`).

### Interaction Matrix

| Dimensions | Scale | Behavior |
|-----------|-------|----------|
| Unset | Fixed (`-size`) | Solver finds smallest atlas for given scale + constraint |
| Unset | Min (`-minsize`) | Solver finds smallest atlas for min scale, then maximizes scale |
| Fixed (`-dimensions`) | Fixed (`-size`) | Packs at given scale into given dims; fails if too small |
| Fixed (`-dimensions`) | Unset or Min | Binary-search maximizes scale to fill fixed dims |

---

## 3. APIs and Options for Developer-Controlled vs Automatic Sizing

### CLI Options (Complete Atlas Sizing Set)

**Source**: `msdf-atlas-gen/README.md`, `msdf-atlas-gen/main.cpp`

| Option | Default | Description |
|--------|---------|-------------|
| `-size <em size>` | `32.0` | Fixed glyph scale (pixels per EM). This is the **primary** size control. |
| `-minsize <em size>` | _(none)_ | Minimum scale; actual scale maximized to fill atlas. Overrides `-size`. |
| `-dimensions <W> <H>` | _(auto)_ | Fix atlas dimensions. Without this, dimensions are solver-derived. |
| `-pxrange <px>` | `2.0` | SDF distance range in pixels. Affects glyph box sizes. |
| `-emrange <em>` | _(none)_ | SDF distance range in EM units. Added to pxRange after conversion. |
| `-apxrange <outer> <inner>` | _(none)_ | Asymmetric pixel range. |
| `-aemrange <outer> <inner>` | _(none)_ | Asymmetric EM range. |
| `-pxpadding <px>` | _(none)_ | Inner pixel padding (part of the glyph quad). |
| `-empadding <em>` | _(none)_ | Inner EM padding. |
| `-outerpxpadding <px>` | _(none)_ | Outer pixel padding (not part of quad, just spacing). |
| `-outerempadding <em>` | _(none)_ | Outer EM padding. |
| `-pxalign` | `vertical` | Origin pixel alignment mode. |
| `-pots` | _(off)_ | Power-of-two square constraint. |
| `-potr` | _(off)_ | Power-of-two rectangle constraint. |
| `-square` | _(off)_ | Any square. |
| `-square2` | _(off)_ | Even square. |
| `-square4` | **default** | Multiple-of-4 square. |

### C++ API (Library Use)

For library consumers (not CLI), `TightAtlasPacker` exposes the full set:

```cpp
TightAtlasPacker packer;
packer.setDimensions(1024, 1024);       // or unsetDimensions() for auto
packer.setDimensionsConstraint(DimensionsConstraint::POWER_OF_TWO_SQUARE);
packer.setScale(48.0);                  // or setMinimumScale(32.0) for adaptive
packer.setPixelRange(msdfgen::Range(4.0));
packer.setMiterLimit(1.0);
packer.setSpacing(1);
packer.setOriginPixelAlignment(true);
packer.setInnerPixelPadding(Padding(0));
packer.setOuterPixelPadding(Padding(0));
packer.pack(glyphs, count);

int w, h;
packer.getDimensions(w, h);             // Retrieve solved dimensions
double finalScale = packer.getScale();  // Retrieve solved scale
```

---

## 4. How Font Size / Scale / pxRange / emRange Interact with Atlas Dimensions

### Scale → Box Size → Atlas Size (The Causal Chain)

The fundamental chain is:

```
scale (px/EM) × glyph EM bounds → pixel-space glyph extent
  + pxRange (pixels)             → SDF field border added
  + miter expansion              → sharp corner margin added
  + padding                      → explicit spacing added
  = per-glyph box (width × height in pixels)

Σ(all glyph boxes)              → total area estimate
  → SizeSelector                → candidate atlas dimensions
  → guillotine packer trial     → smallest fitting atlas
```

**Each parameter affects box size, which affects total area, which affects atlas dimensions.**

### Scale (`-size`)

- **Default**: 32.0 px/EM
- **Effect**: Directly proportional to glyph box dimensions. Doubling scale roughly quadruples total area, roughly doubling atlas side length.
- **Example**: At scale 32, a glyph spanning 0.5 EM horizontally produces a ~16px-wide box (plus range/padding). At scale 128, the same glyph produces a ~64px-wide box.

### pxRange (`-pxrange`)

- **Default**: 2.0 px
- **Effect**: Added as a border around each glyph box in pixel space. Each glyph gains `+pxRange` pixels in both width and height. For 95 printable ASCII glyphs at pxRange=2, that's 95 × (2×2) = ~380 extra pixels of area. At pxRange=8, it's 95 × (8×2) × 2 = ~3040 extra pixels.
- **Critical for rendering quality**: pxRange determines the anti-aliasing smoothness. Higher pxRange = smoother edges but larger boxes. The shader must know pxRange to compute the correct screen-space coverage.

### emRange (`-emrange`)

- **Effect**: Converted to pixel range via `totalRange = unitRange + pxRange / scale`. At scale 32 with emRange=0.1, that adds `0.1 × 32 = 3.2 px` to the range in each direction. emRange is scale-dependent; pxRange is scale-independent.
- **Use case**: When you want the SDF border to be proportional to glyph size rather than a fixed pixel count.

### Shader-Side pxRange Scaling

When rendering MSDF text at a size different from the atlas generation scale, the shader must compute:

```glsl
float screenPxRange = pxRange * (renderSizePx / atlasSizePx_per_glyph);
```

Where `atlasSizePx_per_glyph` is the size of the glyph in the atlas (derived from `atlasBounds`). This scaling is necessary because the distance values encoded in the MSDF assume a specific pixel density. Rendering at 2× the atlas scale means each atlas pixel covers 2 screen pixels, so the effective range doubles.

If `screenPxRange` drops below ~1.0, anti-aliasing breaks down and text becomes aliased. If it's too high, edges become overly soft.

### Practical Impact Table

| Parameter Change | Atlas Size Impact | Rendering Impact |
|-----------------|-------------------|------------------|
| Scale ×2 | ~4× area, ~2× side | Sharper at large sizes; wasteful at small sizes |
| pxRange ×2 | +N pixels per glyph border | Smoother anti-aliasing; wider range before clipping |
| emRange +0.1 | Scale-proportional area increase | Better quality at the generation scale |
| Miter limit ↑ | Minor increase for serif fonts | Better corner handling |
| Padding ↑ | Linear area increase | Better glyph isolation |

---

## 5. Single MSDF Atlas per Charset vs Multiple Atlases per Size

### Can One MSDF Atlas Serve All Render Sizes?

**Yes, with caveats.** This is the fundamental value proposition of MSDF over bitmap fonts.

MSDF encodes the distance field, not pixel colors. The shader reconstructs edges at **any** resolution via:

```glsl
float sd = median(msd.r, msd.g, msd.b);
float screenPxDist = screenPxRange * (sd - 0.5);
float opacity = clamp(screenPxDist + 0.5, 0.0, 1.0);
```

Because distance fields are continuous and sampled via bilinear interpolation, a single MSDF atlas can render text from very small to very large sizes. **You do not need a separate atlas per render size.**

### Quality Limits of a Single Atlas

The quality envelope depends on three factors:

#### 1. Minimum Effective Render Size

When rendering text smaller than the atlas generation scale, `screenPxRange` shrinks. Below `screenPxRange ≈ 1.0`, the SDF border is sub-pixel and anti-aliasing breaks down.

**Rule of thumb**: Minimum effective render size ≈ `atlasScale × (1.0 / pxRange)`.

| Atlas Scale | pxRange | Min Render Size |
|-------------|---------|-----------------|
| 32 px/EM | 2.0 | ~16 px |
| 32 px/EM | 4.0 | ~8 px |
| 48 px/EM | 4.0 | ~12 px |
| 64 px/EM | 4.0 | ~16 px |

Below these sizes, bitmap rendering is typically better.

#### 2. Maximum Effective Render Size

MSDF preserves edges well at large scales, but very large rendering (e.g., 200px+ from a 32px atlas) can reveal:
- Sampling artifacts from low atlas resolution
- Slight corner rounding due to limited SDF precision

In practice, MSDF from a 32px atlas looks acceptable up to ~200-300px render size for most fonts. Higher atlas scales push this further.

#### 3. Detail Fidelity

Complex glyphs (CJK, ornamental, high edge count) need higher atlas resolution to capture fine detail. A 32px atlas may not capture fine serifs or thin strokes that a 64px atlas would.

### When Multiple Atlases Are Justified

1. **Charset overflow**: When the glyph count exceeds what fits in one GPU texture (typically 4096×4096 max). CJK fonts with 10,000+ glyphs may need multiple atlas textures.

2. **Extreme size range**: If you need crisp rendering from 8px to 400px, a single atlas with scale=32 and pxRange=4 has `screenPxRange < 1.0` below 8px. You could use two atlases: scale=16/pxRange=6 for small text and scale=64/pxRange=4 for large text. **However, this is rarely necessary in practice.**

3. **Different pxRange requirements**: If some text needs very smooth anti-aliasing (UI labels) while other text needs sharp edges (code), different pxRange atlases make sense.

4. **Font mixing**: Different fonts/styles can share or split atlases. `msdf-atlas-gen` supports combining fonts via `-and` but doesn't paginate natively.

### CrystalGraphics-Specific Answer

**For Minecraft 1.7.10 modding, a single MSDF atlas per font is almost certainly sufficient.** The typical text size range in Minecraft UI is 8-48px. An atlas generated at scale=48 with pxRange=4 covers this entire range with acceptable quality.

CrystalGraphics' current model of **one MSDF atlas per `(font, style, targetPx)`** creates unnecessary duplication. The same font at 24px and 48px gets two separate MSDF atlases, each with its own set of glyph MSDF generations. Since MSDF is scale-independent, a single atlas at a reasonable base scale (e.g., 48) could serve both sizes with the shader adjusting `screenPxRange`.

**However**: Switching to a single atlas per font would require:
1. Decoupling `CgFontKey.targetPx` from atlas identity for the MSDF path
2. Shader changes to compute `screenPxRange` dynamically per glyph
3. Renderer changes to pass atlas-generation-scale metadata per batch

---

## 6. How msdf-atlas-gen Handles Overflow / Dynamic Growth / Re-Layout

### Static Path: No Overflow Handling

**Source**: `msdf-atlas-gen/TightAtlasPacker.cpp`

The static `TightAtlasPacker` does **not** handle overflow at all. If packing fails:
- `tryPack()` returns the count of remaining (unpacked) rectangles
- `pack()` returns a non-zero error code
- The caller must handle the failure (resize, reduce scale, split charset, etc.)

There is no automatic fallback, no pagination, and no partial packing. It's all-or-nothing.

### Dynamic Path: Single-Atlas Growth via Resize

**Source**: `msdf-atlas-gen/DynamicAtlas.hpp`

`DynamicAtlas` is a template class that wraps `RectanglePacker` and an `AtlasGenerator`. It handles runtime glyph addition with the following algorithm:

```cpp
ChangeFlags DynamicAtlas::add(GlyphGeometry *glyphs, int count, bool allowRearrange) {
    // 1. Compute box sizes for new glyphs, add to rectangle list
    // 2. Accumulate totalArea

    // 3. Try packing new rectangles
    while (remaining = packer.pack(newRects) > 0) {
        // 4. Grow atlas: next power-of-two that fits totalArea
        side = nextPOT(side);
        while (side * side < totalArea)
            side <<= 1;

        if (allowRearrange) {
            // 5a. Recreate packer at new size, repack ALL rectangles from scratch
            packer = RectanglePacker(side, side);
            // Repacks everything, invalidates all existing placements
            changeFlags |= RESIZED | REARRANGED;
        } else {
            // 5b. Expand packer, only pack remaining rectangles
            packer.expand(side, side);
            changeFlags |= RESIZED;
        }
    }

    // 6. Generate MSDF bitmaps for new glyphs
    generator.generate(glyphs, count);
}
```

#### Key Behaviors

| Aspect | Behavior |
|--------|----------|
| **Growth direction** | Always power-of-two squares: `side <<= 1` until `side² ≥ totalArea` |
| **Rearrangement** | Optional (`allowRearrange` param). If true, ALL existing placements are invalidated and repacked. |
| **Without rearrange** | Atlas expands but existing placements are preserved. Only new glyphs are placed in the expanded area. |
| **Pagination** | **None**. DynamicAtlas is always a single texture. No multi-page support. |
| **Eviction** | **None**. Glyphs are never removed. The atlas only grows. |
| **UV stability** | Only if `allowRearrange = false`. With rearrange, all UVs change. |
| **Dirty tracking** | `ChangeFlags` bitmask: `RESIZED (0x01)`, `REARRANGED (0x02)`. Caller checks these to know if texture reallocation or UV updates are needed. |
| **Remap mechanism** | `Remap` struct stores `source` and `target` positions for rearranged glyphs. Generator's `rearrange()` is called with the remap buffer. |

#### What DynamicAtlas Does NOT Do

1. **Does not paginate.** There is no concept of multiple atlas pages.
2. **Does not evict.** No LRU, no removal, no replacement.
3. **Does not shrink.** Atlas only grows, never compacts.
4. **Does not limit size.** No maximum texture size check. Will grow to 8192×8192 or beyond if needed.
5. **Does not handle GPU texture limits.** That's the caller's responsibility.

### Grid Path: Uniform Cell Sizing

**Source**: `msdf-atlas-gen/GridAtlasPacker.cpp`

The `GridAtlasPacker` uses a uniform cell grid instead of tight packing:
- All glyphs are placed in identically-sized cells
- Cell dimensions are derived from the maximum glyph bounds (largest glyph determines cell size)
- Layout is `columns × rows`
- Dimensions are computed from `columns × cellWidth` and `rows × cellHeight`
- Supports the same dimension constraints as tight packing

This is simpler but wastes space for glyphs with size variance (e.g., 'i' vs 'W'). CrystalGraphics' old `cellSizeForFontPx()` model was conceptually closer to grid packing than tight packing.

---

## 7. Comparison to CrystalGraphics Current Behavior

### Atlas Sizing Model

| Aspect | msdf-atlas-gen (Static) | msdf-atlas-gen (Dynamic) | CrystalGraphics (Current) |
|--------|------------------------|-------------------------|--------------------------|
| **Dimension source** | Solver-derived from content OR caller-fixed | Power-of-two growth from 0 | Fixed at page creation (`pageWidth × pageHeight`) |
| **Dimension constraint** | Configurable (POT, square, square4, etc.) | Always POT square | Caller chooses at atlas creation |
| **Scale model** | Fixed or maximized via binary search | Fixed (caller wraps boxes beforehand) | Fixed per font key (`targetPx`) |
| **Overflow handling** | Fails (returns error) | Grows (resize + optional rearrange) | New page allocation |
| **Eviction** | None | None | LRU per-slot (old path); none (paged path) |
| **Pagination** | None | None | **Yes — CrystalGraphics' unique advantage** |

### Glyph Box Model

| Aspect | msdf-atlas-gen | CrystalGraphics (Old) | CrystalGraphics (New) |
|--------|---------------|----------------------|----------------------|
| **Box sizing** | Per-glyph via `wrapBox()` | Fixed cell via `cellSizeForFontPx()` | Per-glyph via `CgMsdfGlyphLayout.compute()` ✅ |
| **Range inclusion** | Range expands bounds before scaling | Range only used in shader | Range expands bounds ✅ |
| **Miter limit** | Applied via `boundMiters()` | Not applied | TODO (noted in code) |
| **Inner/outer padding** | Configurable, asymmetric | Not configurable | Not configurable |
| **Pixel alignment** | Configurable X/Y | Not used | Configurable ✅ |
| **Plane bounds** | Computed from box transform | Not computed (bearing-based) | Computed ✅ |

### Packing Algorithm

| Aspect | msdf-atlas-gen | CrystalGraphics |
|--------|---------------|-----------------|
| **Algorithm** | Guillotine best-fit | Guillotine best-fit ✅ (CgGuillotinePacker) |
| **Split heuristic** | Maximize larger remainder | Same ✅ |
| **Fit scoring** | `min(sw-w, sh-h)` | Same ✅ |
| **Spacing** | Configurable, applied around rectangles | Not currently applied |
| **Rotation** | Supported via `OrientedRectangle` | Not supported |

### The Real Gap: Page Size Selection

The primary remaining gap is **not** in the packing algorithm (which is now at parity) or the glyph box math (which is close to parity). The gap is in **atlas dimension selection**:

- **msdf-atlas-gen**: Solves dimensions from content. A 95-glyph ASCII atlas at scale=32, pxRange=2 might solve to ~320×256.
- **CrystalGraphics**: Pre-allocates a fixed 2048×2048 page. This is 16× the area needed, hence 12.5% utilization.

The reference image's small dimensions (~800×250) are not a target to match blindly — they're the output of the solver for that specific font/charset/scale/range combination. But the principle is correct: **atlas dimensions should be derived from content, not preallocated at a hardcoded size.**

---

## 8. Recommended Optimal Strategy for CrystalGraphics

### 8.1. Atlas Sizing Strategy

#### For Static/Prewarm Mode (Harness, Screenshots, Pre-built Atlases)

**Use content-derived sizing à la `TightAtlasPacker`.**

Implementation approach:
1. Compute all glyph box dimensions upfront (already possible via `CgMsdfGlyphLayout.compute()`).
2. Sum total area.
3. Try packing into increasingly larger dimensions (e.g., POT-square progression: 128 → 256 → 512 → 1024...) until all glyphs fit.
4. Use the smallest successful dimension as the atlas page size.

This would produce output visually comparable to the reference image.

**Recommended default constraint**: `POWER_OF_TWO_SQUARE`. GPU texture allocation is most efficient with POT dimensions, and Minecraft's OpenGL context universally supports them.

#### For Runtime/Dynamic Mode (In-Game Rendering)

**Use fixed-size pages with pagination.**

Pre-allocating a fixed page size is correct for runtime because:
1. You don't know the full glyph set upfront (glyphs arrive incrementally).
2. Texture reallocation on the render thread causes frame spikes.
3. UV stability is critical — you can't rearrange without invalidating all cached quads.

**Recommended page size**: `512×512` for MSDF, `256×256` for bitmap.

Rationale:
- At scale=48, pxRange=4, a typical ASCII glyph box is ~30-60px wide and ~40-70px tall.
- 95 printable ASCII glyphs need roughly `95 × 50 × 55 ≈ 261,250 px²` of area.
- A 512×512 page has 262,144 px² — just enough for a full ASCII set with tight packing.
- Non-ASCII (Latin Extended, etc.) would overflow to page 2, which is fine with pagination.
- 1024×1024 is comfortable for most use cases without excessive waste.
- 2048×2048 is almost always too large for a single font/charset.

#### Adaptive Page Sizing (Future Enhancement)

A more sophisticated approach: start with a small page (256×256) and let the paged atlas manager choose the next page size based on observed glyph box statistics. This is not necessary for v1 but would optimize memory further.

### 8.2. Single Atlas per Font (Not per Font×Size)

**Recommendation**: For the MSDF path, generate glyphs at a single "atlas scale" (e.g., 48 px/EM) regardless of the render size requested.

**Why**: MSDF is scale-independent. A glyph generated at 48px/EM can render at 12px, 24px, 48px, or 96px with the shader adjusting `screenPxRange`. Generating separate MSDF atlases for each `targetPx` wastes memory and generation time.

**Implementation change needed**:
1. Introduce an "atlas scale" configuration parameter (e.g., `MSDF_ATLAS_SCALE = 48`).
2. Decouple `CgFontKey.targetPx` from MSDF atlas identity. All MSDF glyphs for the same font/style go into one atlas regardless of requested render size.
3. The shader receives `pxRange` and the atlas-to-screen scale ratio to compute `screenPxRange` dynamically.
4. **Keep bitmap path per-`targetPx`** — bitmap is not scale-independent.

**Suggested atlas scale**: `48` px/EM is a good default for Minecraft UI (covers 12-200px render range with pxRange=4).

### 8.3. pxRange and emRange

**Recommendation**: Use `pxRange = 4.0` (CrystalGraphics' current value).

Rationale:
- Upstream default is `2.0`, which is minimal. It produces tight boxes but thin anti-aliasing.
- `4.0` provides a comfortable safety margin for small-text rendering and dynamic `screenPxRange` computation.
- Going higher (6, 8) adds unnecessary box size for marginal quality gain.
- `4.0` is a well-tested value in the CrystalGraphics codebase.

**Do NOT use emRange** unless there's a specific need for scale-proportional distance fields. For CrystalGraphics' use case (one atlas scale serving multiple render sizes), pixel-based range is simpler and more predictable.

### 8.4. Growth / Overflow Policy

**Recommendation**: Fixed-size pages + pagination. Do NOT copy DynamicAtlas behavior.

| msdf-atlas-gen DynamicAtlas | CrystalGraphics Recommendation |
|----------------------------|-------------------------------|
| Single texture, grows via resize | Fixed-size pages, grows via new page |
| POT-square doubling (256→512→1024) | Same page size for each new page |
| Optional rearrange (invalidates UVs) | Never rearrange (placement-stable) |
| No eviction | No eviction (paged path) |
| No pagination | Pagination is the core strategy |

**Why pagination beats resize for CrystalGraphics**:
1. **UV stability**: Once a glyph is placed, its UV coordinates never change. No need to rebuild VBOs.
2. **No texture copy**: Adding a page is a new `glGenTextures` + `glTexImage2D`, not a copy of the old texture.
3. **Bounded per-page memory**: Each page is a known size. No risk of allocating a 4096×4096 texture when you only needed 600×600.
4. **Batch-friendly**: Renderer batches by page, which maps naturally to GL texture bind calls.
5. **Incremental**: Adding a new page is O(1) with respect to existing pages.

### 8.5. Recommended Default Parameters

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| MSDF atlas scale | 48 px/EM | Good balance: covers 12-200px render range |
| pxRange | 4.0 px | CrystalGraphics standard; 2× upstream default for better small-text AA |
| Page size (MSDF) | 512×512 | Fits ~95 ASCII glyphs tightly at scale=48/range=4; overflows to page 2 gracefully |
| Page size (bitmap) | 256×256 | Bitmap glyphs are small; 256² is ample for a single font size |
| Dimension constraint | POT square | GPU-friendly; simplifies the math |
| Spacing | 1 px | Prevents sampling artifacts at glyph boundaries |
| Miter limit | 1.0 | Match upstream default for serif fonts |
| Origin pixel alignment | X: false, Y: true | Matches upstream `-pxalign vertical` default |
| Inner/outer padding | 0 | Not needed with adequate pxRange |

### 8.6. Static Prewarm Sizing (Harness-Specific)

For the harness prewarm path (deterministic atlas dump for comparison):

1. Compute all glyph boxes for the charset.
2. Sum total pixel area.
3. Find the smallest POT square that fits: `side = nextPOT(ceil(sqrt(totalArea × 1.15)))` (15% headroom for packing inefficiency).
4. Try packing. If it fails, double the side.
5. Use the successful dimensions as the page size.
6. Pack and dump.

This would produce harness output that is dimensionally comparable to msdf-atlas-gen's static output and directly answers the reviewer's concern about 2048×2048 vs 800×250.

### 8.7. Summary: What Changes

| Current Behavior | Recommended Change | Priority |
|-----------------|-------------------|----------|
| One MSDF atlas per `(font, style, targetPx)` | One MSDF atlas per `(font, style)` at fixed atlas scale | Medium — requires shader + renderer changes |
| Fixed 2048×2048 page size | 512×512 default; content-derived for prewarm | High — biggest visual/memory win |
| No spacing between packed glyphs | 1px spacing | Low — minor quality improvement |
| No miter limit | Miter limit = 1.0 | Low — affects serif fonts only |
| pxRange = 4.0 | Keep 4.0 | None — already correct |
| Guillotine packer | Keep guillotine packer | None — already at parity |
| Per-glyph box sizing via CgMsdfGlyphLayout | Keep | None — already at parity |

---

## Appendix A: Source File Reference

All upstream source readings performed directly from GitHub:

| File | SHA | Key Content |
|------|-----|-------------|
| `types.h` | `4b0e8c02` | `DimensionsConstraint` enum, `PackingStyle` enum |
| `TightAtlasPacker.h` | `0f2aa5f2` | Full API surface for tight packing |
| `TightAtlasPacker.cpp` | `77f921c6` | `pack()`, `tryPack()`, `packAndScale()` — complete algorithms |
| `RectanglePacker.cpp` | _(read via agent)_ | Guillotine single-bin packer, `rateFit`, split heuristic |
| `rectangle-packing.hpp` | `5e17002a` | `packRectangles()` templates, `SizeSelector` dimension search |
| `GlyphGeometry.cpp` | _(read via agent)_ | `wrapBox()`, `frameBox()`, `getQuadPlaneBounds()`, `getQuadAtlasBounds()` |
| `DynamicAtlas.hpp` | `c555caa9` | `add()` with growth, rearrange, and `ChangeFlags` |
| `main.cpp` | _(read via agent)_ | CLI option mappings, defaults |
| `json-export.cpp` | _(read via agent)_ | Export metadata schema |

## Appendix B: Key msdf-atlas-gen GitHub Issues Referenced

| Issue | Topic |
|-------|-------|
| [#75](https://github.com/Chlumsky/msdf-atlas-gen/issues/75) | pxRange/emRange quality at different sizes |
| [#21](https://github.com/Chlumsky/msdf-atlas-gen/issues/21) | Atlas resolution and pxRange for small text |
| [#116](https://github.com/Chlumsky/msdf-atlas-gen/issues/116) | screenPxRange calculation for varying render sizes |
| [#20](https://github.com/Chlumsky/msdf-atlas-gen/issues/20) | Overflow handling for large charsets |
| [#13](https://github.com/Chlumsky/msdf-atlas-gen/issues/13) | Multiple fonts in single atlas |
| [#229](https://github.com/Chlumsky/msdf-atlas-gen/issues/229) | Artifacts from downscaling large atlases |
