# MTSDF Generation Correctness — Upstream-Grounded Audit

**Date**: 2026-04-07  
**Scope**: Generation-stage correctness for MTSDF glyph fields.  
**Primary Sources**: `Chlumsky/msdfgen` + `Chlumsky/msdf-atlas-gen` (source-level reads), CrystalGraphics local implementation.  
**Purpose**: Identify and document every generation rule that matters for correct MTSDF output, compare CrystalGraphics against upstream, and provide actionable guidance.

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Upstream Generation Pipeline (Ground Truth)](#2-upstream-generation-pipeline-ground-truth)
3. [CrystalGraphics Divergence Audit](#3-crystalgraphics-divergence-audit)
4. [Critical Rules for MTSDF Correctness](#4-critical-rules-for-mtsdf-correctness)
5. [Actionable Recommendations](#5-actionable-recommendations)

---

## 1. Executive Summary

CrystalGraphics' **paged-atlas path** (`preparePagedGlyph` + `CgMsdfGlyphLayout`) is architecturally close to upstream `msdf-atlas-gen`. The JNI bridge correctly calls msdfgen's native `generateMTSDF()` with `Projection`, `Range`, and `MSDFGeneratorConfig`. The core math in `CgMsdfGlyphLayout.compute()` closely matches `GlyphGeometry::wrapBox()`.

However, there are **eight concrete divergences** from upstream that range from "cosmetic" to "likely root cause of the test-font.ttf atlas mismatch". These are detailed below.

### Severity Summary

| # | Issue | Severity | Section |
|---|-------|----------|---------|
| D1 | Contour winding validation differs from upstream | **HIGH** | §3.1 |
| D2 | `getBounds()` before `getBoundsMiters()` ordering mismatch | **MEDIUM** | §3.2 |
| D3 | `geometryScale` not used (FONT_SCALING_EM_NORMALIZED vs FONT_SCALING_NONE) | **HIGH** | §3.3 |
| D4 | `pxRange` default 16.0 vs upstream 2.0 (intentional but impacts box math) | **LOW** | §3.4 |
| D5 | `miterLimit` default 2.0 vs upstream 1.0 | **LOW** | §3.5 |
| D6 | planeBounds computation differs from upstream `getQuadPlaneBounds()` | **HIGH** | §3.6 |
| D7 | Missing scanline sign-correction pass | **MEDIUM** | §3.7 |
| D8 | Edge coloring seed not passed (deterministic but may be suboptimal) | **LOW** | §3.8 |

---

## 2. Upstream Generation Pipeline (Ground Truth)

### 2.1. Glyph Loading

**Source**: `GlyphGeometry.cpp:load()`

```cpp
bool GlyphGeometry::load(FontHandle *font, double geometryScale,
                          GlyphIndex index, bool preprocessGeometry) {
    if (font && loadGlyph(shape, font, index, FONT_SCALING_NONE, &advance)
        && shape.validate()) {
        this->geometryScale = geometryScale;
        advance *= geometryScale;
```

**Critical detail**: Upstream uses `FONT_SCALING_NONE`. The raw FreeType font-unit coordinates are preserved in the shape. A separate `geometryScale` factor (typically `1.0/emSize` where `emSize` = units-per-EM) converts font units to a normalized coordinate system. This `geometryScale` participates in `wrapBox()` math: `scale = glyphAttributes.scale * geometryScale`.

The `fontScale` parameter passed to `FontGeometry::loadGlyphRange()` becomes the `geometryScale`. When the CLI uses `-fontscale 1.0` (default), geometryScale = 1.0, and the raw font-unit coordinates are used directly. The `-size` parameter (default 32.0) then sets the scale in `wrapBox()`.

### 2.2. Shape Preprocessing (Two Paths)

**Source**: `GlyphGeometry.cpp:load()`, `main.cpp` defaults

**Path A — With Skia (`preprocessGeometry = true`)**:
1. `resolveShapeGeometry(shape)` — Skia-based path boolean operations that resolve self-intersections and overlapping contours into clean, non-overlapping geometry.
2. `shape.normalize()` — Removes degenerate edges, ensures contour closure.
3. Bounds computed from cleaned geometry.
4. `overlapSupport = false`, `scanlinePass = false` (geometry is already clean).

**Path B — Without Skia (`preprocessGeometry = false`, *the common path*)**:
1. `shape.normalize()` — Removes degenerate edges.
2. `bounds = shape.getBounds()` — Raw bounds.
3. **Winding validation via distance test**:
   ```cpp
   Point2 outerPoint(bounds.l-(bounds.r-bounds.l)-1,
                     bounds.b-(bounds.t-bounds.b)-1);
   if (SimpleTrueShapeDistanceFinder::oneShotDistance(shape, outerPoint) > 0) {
       for (Contour &contour : shape.contours)
           contour.reverse();
   }
   ```
   This picks a point guaranteed to be outside the shape bounds, computes the true signed distance. If it's positive (meaning the point is *inside* the shape), all contours are reversed. This is **more robust** than `shape.orientContours()`.
4. `overlapSupport = true`, `scanlinePass = true` (let the generator handle overlaps).

**Key upstream defaults (from `main.cpp`)**:
```cpp
config.preprocessGeometry = false;  // unless built with Skia
config.generatorAttributes.config.overlapSupport = !config.preprocessGeometry; // true
config.generatorAttributes.scanlinePass = !config.preprocessGeometry;          // true
```

### 2.3. Edge Coloring

**Source**: `main.cpp` defaults

```cpp
config.edgeColoring = msdfgen::edgeColoringInkTrap;  // Default
// Alternatives: edgeColoringSimple, edgeColoringByDistance
```

- **Default**: `edgeColoringInkTrap` — Designed to avoid color discontinuities at concave corners ("ink traps"). This is the recommended coloring for MSDF/MTSDF.
- **Angle threshold**: `DEFAULT_ANGLE_THRESHOLD = 3.0` degrees. Corners sharper than this threshold get different edge colors on each side.
- **Seed**: An LCG-derived seed is passed per glyph to randomize color assignment, avoiding systematic bias. CrystalGraphics does not pass a seed (the JNI binding uses `angleThreshold` only, not the 3-arg `(shape, threshold, seed)` form).

### 2.4. wrapBox() — Box Sizing and Transform

**Source**: `GlyphGeometry.cpp:wrapBox()`

This is the **heart of MTSDF generation correctness**. The algorithm:

```
1. scale = glyphAttributes.scale * geometryScale
2. range = glyphAttributes.range / geometryScale
3. fullPadding = (innerPadding + outerPadding) / geometryScale

4. Start with raw shape bounds (l, b, r, t)
5. Expand by range.lower (which is negative, so this widens):
   l += range.lower; b += range.lower;
   r -= range.lower; t -= range.lower;
6. If miterLimit > 0:
   shape.boundMiters(l, b, r, t, -range.lower, miterLimit, polarity=1)
7. Expand by fullPadding (per-side)

8. Compute pixel-space box dimensions + translate:
   If pxAlignOriginX:
     sl = floor(scale*l - 0.5)
     sr = ceil(scale*r + 0.5)
     boxWidth = sr - sl
     translateX = -sl / scale
   Else:
     w = scale * (r - l)
     boxWidth = ceil(w) + 1
     translateX = -l + 0.5*(boxWidth - w) / scale
   (Same pattern for Y axis)
```

**Critical subtlety in step 6**: `boundMiters()` is called with:
- `border = -range.lower` (which is positive, since range.lower is negative)
- `miterLimit = miterLimit`
- `polarity = 1`

This means the miter expansion uses the SDF range as the border distance. The miter expansion happens **after** the range expansion, on the already-expanded bounds. This is important because CrystalGraphics calls `getBoundsMiters()` on the raw shape bounds (before range expansion), then uses those miter-expanded bounds in `CgMsdfGlyphLayout.compute()` — but the range expansion happens inside `compute()`, not before. We need to verify the ordering matches.

### 2.5. Plane Bounds (getQuadPlaneBounds)

**Source**: `GlyphGeometry.cpp:getQuadPlaneBounds()`

```cpp
void GlyphGeometry::getQuadPlaneBounds(double &l, double &b,
                                        double &r, double &t) const {
    if (box.rect.w > 0 && box.rect.h > 0) {
        double invBoxScale = 1/box.scale;
        l = geometryScale*(-box.translate.x
            + (box.outerPadding.l + 0.5) * invBoxScale);
        b = geometryScale*(-box.translate.y
            + (box.outerPadding.b + 0.5) * invBoxScale);
        r = geometryScale*(-box.translate.x
            + (-box.outerPadding.r + box.rect.w - 0.5) * invBoxScale);
        t = geometryScale*(-box.translate.y
            + (-box.outerPadding.t + box.rect.h - 0.5) * invBoxScale);
    }
}
```

The plane bounds represent the **visible glyph area** in font-unit space (normalized by `geometryScale`). They account for:
- The translate offset
- The outer padding (not part of the visible quad)
- The 0.5-pixel inset (texel center correction)
- The `geometryScale` scaling back to font-normalized coordinates

When there's no outer padding (the common case for us), this simplifies to:
```
l = geometryScale * (-translate.x + 0.5 / scale)
b = geometryScale * (-translate.y + 0.5 / scale)
r = geometryScale * (-translate.x + (boxWidth - 0.5) / scale)
t = geometryScale * (-translate.y + (boxHeight - 0.5) / scale)
```

### 2.6. Generation Call

**Source**: msdfgen `generate-msdf.cpp`

For MTSDF, the generator produces a 4-channel bitmap:
- **R, G, B**: Multi-channel signed distance (the MSDF channels)
- **A (alpha)**: True signed distance (the "T" in MTSDF)

The `MSDFGeneratorConfig` controls:
- `overlapSupport` (default: `true` without Skia)
- `ErrorCorrectionConfig`:
  - `mode`: `EDGE_PRIORITY` (default) — prioritizes edge accuracy over distance field smoothness
  - `distanceCheckMode`: `AT_EDGE` (default) — checks error at edge transitions
  - `minDeviationRatio`: `1.11111111111111111` (10/9, the default)
  - `minImproveRatio`: `1.11111111111111111` (10/9, the default)

### 2.7. Y-Axis Convention

**Source**: `main.cpp`

```cpp
config.yDirection = msdfgen::Y_UPWARD;  // Default
```

msdfgen generates bitmaps in math convention (row 0 = bottom, Y-up). The output needs flipping for GPU upload in Y-down texture space.

---

## 3. CrystalGraphics Divergence Audit

### D1. Contour Winding Validation — **HIGH SEVERITY**

**Upstream**: After `normalize()`, uses a distance-based winding test:
```cpp
Point2 outerPoint(bounds.l-(bounds.r-bounds.l)-1,
                  bounds.b-(bounds.t-bounds.b)-1);
if (SimpleTrueShapeDistanceFinder::oneShotDistance(shape, outerPoint) > 0) {
    // Reverse ALL contours
}
```

**CrystalGraphics** (`CgMsdfGenerator.prepareShapeForMsdf()`):
```java
shape.normalize();
shape.orientContours();  // ← Different!
```

**Why this matters**: `orientContours()` uses the Scanline-based algorithm to orient each contour independently (outer = CCW, holes = CW). The upstream method instead tests a guaranteed-exterior point and reverses ALL contours if the winding is globally wrong. These are **fundamentally different approaches**:

- `orientContours()` can fail on overlapping contours where the scanline can't reliably determine inside/outside for each individual contour.
- The upstream distance-test approach treats the entire shape as a unit and only needs to answer "is the exterior point inside or outside?" — which is more robust for shapes with overlapping sub-paths.

**For test-font.ttf**: If any glyphs have overlapping contours (common in fonts that use component composition), `orientContours()` may produce incorrect winding for some contours, leading to distance field inversions and visible artifacts.

**Fix**: Replace `shape.orientContours()` with the upstream distance-test pattern. The Java binding already provides `shape.getOneShotDistance(x, y)` (though note: our JNI implementation computes unsigned min distance, not true signed distance — **this needs verification against `SimpleTrueShapeDistanceFinder::oneShotDistance`**).

### D2. getBoundsMiters() Ordering — **MEDIUM SEVERITY**

**Upstream** (`wrapBox()`): Miter expansion happens **after** range expansion:
```cpp
l += range.lower;  b += range.lower;   // Range expansion first
r -= range.lower;  t -= range.lower;
if (miterLimit > 0)
    shape.boundMiters(l, b, r, t, -range.lower, miterLimit, 1);  // Then miter
```

**CrystalGraphics** (`preparePagedGlyph()`):
```java
double[] bounds = shape.getBounds();
if (config.getMiterLimit() > 0.0f) {
    bounds = shape.getBoundsMiters(bounds, 0.0, config.getMiterLimit(), 0);
    // border=0.0, polarity=0  ← Both differ from upstream!
}
// bounds passed to CgMsdfGlyphLayout.compute() which then does range expansion
```

**Three issues**:
1. **`border = 0.0`** — Upstream passes `-range.lower` (positive value = half the pixel range in shape units). Passing 0.0 means the miter expansion has no border offset to work with, potentially under-expanding bounds at sharp corners.
2. **`polarity = 0`** — Upstream passes `1`. The `polarity` parameter in `boundMiters()` controls whether to expand only outer miters (1), only inner (-1), or both (0). Upstream only expands outer miters, which is correct for box sizing. CrystalGraphics expands both, which over-allocates but is not harmful.
3. **Ordering**: Miters are computed on raw bounds, then range expansion happens in `CgMsdfGlyphLayout.compute()`. This is **functionally equivalent** IF the border parameter is correctly set, because `boundMiters()` only expands bounds outward from the original contour corners, and range expansion is additive. However, with `border=0.0`, the miter result may differ from the upstream computation.

**Fix**: Pass `border = halfRange / scale` (which equals `pxRange / (2.0 * scale)`) and `polarity = 1` to `getBoundsMiters()`. Alternatively, move the `getBoundsMiters()` call into `CgMsdfGlyphLayout.compute()` after range expansion, exactly matching upstream order.

### D3. FONT_SCALING_EM_NORMALIZED vs FONT_SCALING_NONE — **HIGH SEVERITY**

**Upstream**: `loadGlyph(shape, font, index, FONT_SCALING_NONE, &advance)`
- Shape coordinates are in raw font units (e.g., 0–2048 for a typical TrueType font).
- `geometryScale` (= `fontScale`, typically `1.0/unitsPerEm`) is applied via `scale = glyphAttributes.scale * geometryScale` in `wrapBox()`.
- The `advance` is multiplied by `geometryScale` immediately after loading.

**CrystalGraphics**: `font.loadGlyphByIndex(key.getGlyphId(), FONT_SCALING_EM_NORMALIZED)`
- Shape coordinates are pre-normalized to 0–1 range (1.0 = 1 EM).
- `geometryScale` concept is implicit: already 1.0 because coordinates are already EM-normalized.

**Analysis**: This is **not necessarily wrong** — it's a valid choice. The key question is whether the wrapBox math stays consistent. With EM-normalized shapes:
- `scale = targetPx` (because `geometryScale = 1.0` effectively)
- `range = pxRange / (2.0 * scale)` in shape units is `pxRange / (2.0 * targetPx)`

This **does** match the upstream math when `geometryScale = 1.0/unitsPerEm` and `scale = size * (1.0/unitsPerEm) = size/unitsPerEm`, because the EM-normalized shape already has the 1/unitsPerEm baked in.

**However**: The `planeBounds` computation and the bearing computation must be consistent with this choice. If any part of the pipeline assumes raw font units while another assumes EM-normalized, the result is a scale error. This is the **most likely cause of the test-font.ttf atlas mismatch** — see D6.

**Verdict**: Using `FONT_SCALING_EM_NORMALIZED` is fine **IF AND ONLY IF** all downstream math consistently treats coordinates as EM-normalized (where 1.0 = 1 EM). The critical check is whether `planeBounds` are being computed correctly for this coordinate system.

### D4. pxRange Default: 16.0 vs Upstream 2.0 — **LOW SEVERITY**

**Upstream default**: `DEFAULT_PIXEL_RANGE = 2.0`  
**CrystalGraphics default**: `DEFAULT_PX_RANGE = 16.0f`

A larger pxRange means:
- Wider SDF field border around each glyph → larger box sizes
- More gradual distance falloff → smoother anti-aliasing at large render scales
- More atlas space consumed per glyph

This is an **intentional design choice** for CrystalGraphics (runtime scaling from a single atlas size), not a bug. The value 16.0 is generous and appropriate for a runtime MSDF system that renders at various sizes from one atlas.

**No fix needed** — but be aware that atlas sizing comparisons against upstream at pxRange=2.0 will show different glyph box sizes.

### D5. miterLimit Default: 2.0 vs Upstream 1.0 — **LOW SEVERITY**

**Upstream default**: `DEFAULT_MITER_LIMIT = 1.0`  
**CrystalGraphics default**: `DEFAULT_MITER_LIMIT = 2.0f`

A higher miter limit means bounds expand further at sharp corners, which means slightly larger boxes but no clipping at acute angles. This is conservative and safe.

**No fix needed** — 2.0 is a defensible choice. Just be aware when comparing box sizes against upstream.

### D6. planeBounds Computation Differs — **HIGH SEVERITY**

**Upstream** (`getQuadPlaneBounds()`):
```cpp
double invBoxScale = 1 / box.scale;
l = geometryScale * (-box.translate.x + (outerPadding.l + 0.5) * invBoxScale);
b = geometryScale * (-box.translate.y + (outerPadding.b + 0.5) * invBoxScale);
r = geometryScale * (-box.translate.x + (-outerPadding.r + box.rect.w - 0.5) * invBoxScale);
t = geometryScale * (-box.translate.y + (-outerPadding.t + box.rect.h - 0.5) * invBoxScale);
```

With no outer padding and `geometryScale = 1.0` (EM-normalized), this simplifies to:
```
l = -translate.x + 0.5 / scale
b = -translate.y + 0.5 / scale
r = -translate.x + (boxWidth - 0.5) / scale
t = -translate.y + (boxHeight - 0.5) / scale
```

**CrystalGraphics** (`CgMsdfGlyphLayout.compute()`):
```java
double invScale = 1.0 / scale;
double planeLeft   = -translateX + 0.5 * invScale;
double planeBottom = -translateY + 0.5 * invScale;
double planeRight  = -translateX + (boxWidth - 0.5) * invScale;
double planeTop    = -translateY + (boxHeight - 0.5) * invScale;
```

**This matches upstream** for the case of no outer padding and geometryScale=1.0. ✅

**BUT** — in `preparePagedGlyph()`:
```java
float planeLeft   = (float) (layout.getPlaneLeft() * scale);   // ← Multiplied by scale!
float planeBottom = (float) (layout.getPlaneBottom() * scale);
float planeRight  = (float) (layout.getPlaneRight() * scale);
float planeTop    = (float) (layout.getPlaneTop() * scale);
```

The plane bounds from `CgMsdfGlyphLayout` are in **EM-normalized font units**, but then they're multiplied by `scale` (= `targetPx`) to convert to **pixel units**. After this multiplication:
```
planeLeft_px = (-translateX + 0.5/scale) * scale
             = -translateX*scale + 0.5
```

This is the plane bounds in pixel space. Whether this is correct depends on what the downstream consumer expects. If the consumer (`CgPagedGlyphAtlas.allocateMsdf()`) expects EM-normalized bounds, this multiplication is wrong. If it expects pixel bounds, it's fine.

**The bearing computation also uses scaled plane bounds**:
```java
float bearingX = (float) (layout.getPlaneLeft() * scale);
float bearingY = (float) (layout.getPlaneTop() * scale);
```

This means `bearingX` and `bearingY` are in pixel units. In upstream, plane bounds are in `geometryScale`-normalized font units (i.e., EM-normalized when geometryScale = 1/unitsPerEm). The consumer is expected to multiply by the render scale to get pixel positions.

**Verdict**: This needs careful verification. The plane bounds should be stored in EM-normalized units (matching upstream convention) and the pixel conversion should happen at render time, not at generation time. If the atlas stores pixel-space plane bounds, the glyph can only render correctly at the exact scale it was generated at, defeating the purpose of MTSDF's scale-independence. **This is likely the core mismatch.**

### D7. Missing Scanline Sign-Correction Pass — **MEDIUM SEVERITY**

**Upstream** (`glyph-generators.cpp:42-55`): When `scanlinePass = true` (default without Skia):
1. Generate MTSDF with error correction disabled
2. Run `distanceSignCorrection()` — scanline-based sign repair
3. Re-run error correction with `DO_NOT_CHECK_DISTANCE`

**CrystalGraphics**: No scanline pass. The JNI bridge calls `generateMTSDF()` with full error correction in a single pass. There is no `distanceSignCorrection()` JNI binding.

**Why this matters**: The scanline pass fixes sign errors that occur when overlapping contours create ambiguous inside/outside regions. Without it, complex glyphs (especially compound glyphs with overlapping sub-paths) may have small regions where the distance sign is wrong, producing the "tiny wedge" artifacts documented in `MTSDF_INTERSECTION_INVESTIGATION.md`.

**Fix**: Add a JNI binding for `distanceSignCorrection()` and restructure the generation to match the upstream 3-step workflow: generate (no EC) → scanline → EC (no distance check).

### D8. Edge Coloring Seed Not Passed — **LOW SEVERITY**

**Upstream** (`main.cpp:1335-1347`): Per-glyph LCG seed derived from glyph index:
```cpp
glyph.edgeColoring(config.edgeColoring, config.angleThreshold,
                    lcgValue(config.coloringSeed, glyph.getIndex()));
```

**CrystalGraphics**: Uses 1-argument form `shape.edgeColoringInkTrap(threshold)` without a seed.

**Why this matters**: The default seed (0) is deterministic but may produce suboptimal channel assignments for some glyph topologies. The per-glyph seed prevents systematic bias.

**Fix**: Low priority. Add a seed parameter to the JNI edge coloring bindings when convenient.

---

## 4. Critical Rules for MTSDF Correctness

### Rule 1: Shape Preprocessing Order

**Correct order (without Skia)**:
1. `shape.normalize()` — Remove degenerate edges
2. Compute bounds: `bounds = shape.getBounds()`
3. Winding validation via distance test on guaranteed-exterior point
4. Edge coloring (ink trap, with per-glyph seed for randomization)

**NOT**: `normalize()` → `orientContours()` → `validate()` → edge coloring.  
The `orientContours()` step is unreliable for overlapping contours. The upstream distance-test approach is more robust.

### Rule 2: wrapBox Expansion Order

**Correct order**:
1. Start with raw shape bounds
2. Expand by SDF range (`range.lower`, which is negative)
3. Apply miter expansion on the range-expanded bounds, with `border = -range.lower` and `polarity = 1`
4. Add padding (inner + outer) if any
5. Compute pixel-space dimensions and translate

### Rule 3: Projection/Transform Semantics

msdfgen's `Projection` maps shape coordinates to pixel coordinates:
```
pixel = scale * (shapeCoord + translate)
```
- `scale` = `Vector2(scaleX, scaleY)` — typically uniform
- `translate` = `Vector2(tx, ty)` — positions the glyph within the bitmap

The `Range` is in **shape units** (not pixel units):
```
rangeLower = -pxRange / (2.0 * scale)
rangeUpper = +pxRange / (2.0 * scale)
```

### Rule 4: planeBounds Are in Font-Normalized Units

Upstream `getQuadPlaneBounds()` returns values in `geometryScale`-normalized font units. For EM-normalized fonts (geometryScale = 1/unitsPerEm), these values are in the same EM-normalized space as the original shape coordinates. They represent the visible quad corners in font space, not in pixel space.

### Rule 5: Error Correction — RGB Only, Alpha Untouched

Upstream defaults to `EDGE_PRIORITY` error correction with `AT_EDGE` distance checking. The CrystalGraphics comment says error correction was disabled due to crashes, but the current config actually **enables** it (`ERROR_CORRECTION_EDGE_PRIORITY`). This is correct.

**Critical MTSDF rule**: Error correction equalizes **only RGB channels** (setting all three to the median). The alpha (true SDF) channel is **never** touched by error correction.

**Source**: `msdfgen/core/MSDFErrorCorrection.cpp:459-479` — when a texel is flagged erroneous:
```cpp
pixel[0] = median;  // R
pixel[1] = median;  // G
pixel[2] = median;  // B
// pixel[3] (alpha) is NOT modified
```

This is critical for Java code that may apply its own post-generation error correction. If any custom correction overwrites the alpha channel, the true SDF will be corrupted.

### Rule 6: Edge Coloring Seed

Upstream passes a per-glyph seed to the edge coloring function:
```cpp
glyph.edgeColoring(config.edgeColoring, config.angleThreshold,
                    lcgValue(config.coloringSeed, glyph.getIndex()));
```

This prevents systematic color assignment bias across glyphs. CrystalGraphics uses the 1-argument form (`shape.edgeColoringInkTrap(threshold)`) which does not pass a seed. The msdfgen default seed is 0, which is deterministic but may produce suboptimal colorings for some glyph topologies.

### Rule 7: overlapSupport + scanlinePass Relationship

Without Skia preprocessing:
- `overlapSupport = true` — the distance field generator handles overlapping contours
- `scanlinePass = true` — an additional scanline-based sign correction pass runs after generation

CrystalGraphics enables `overlapSupport` but does **not** expose `scanlinePass` through the JNI bridge. Looking at the JNI code, `generateMTSDF()` uses `MSDFGeneratorConfig(overlapSupport, ecConfig)`, and `MSDFGeneratorConfig` inherits from `GeneratorConfig` which has `overlapSupport` but **scanlinePass is separate** — it's applied in msdf-atlas-gen's `generateSingle()` function, not in msdfgen's core generator.

**Upstream scanline pass workflow** (`glyph-generators.cpp:42-55`):
1. Generate MTSDF with error correction **disabled** initially
2. Run `distanceSignCorrection()` — scanline-based sign repair
3. Re-run error correction with `DO_NOT_CHECK_DISTANCE` mode

**Impact**: The scanline pass is a post-generation correction that fixes sign errors in the distance field. Without it, some complex glyphs may have incorrectly-signed regions. This is mitigated by `overlapSupport` but not eliminated.

**Version note**: msdfgen 1.12.1 fixed a bug where standalone MTSDF with `-scanline` failed to apply error correction correctly. The current upstream workflow is generation → scanline → error correction (RGB only).

### Rule 8: Shape.normalize() Semantics Are Non-Trivial

`Shape::normalize()` is not general cleanup. It performs two MSDF-specific adjustments:

1. **Single-edge contour split**: If a contour has exactly one edge, split it into three parts via `splitInThirds()`. This is required because MSDF needs at least 3 edges per contour for proper channel separation.

2. **Convergent-edge repair**: For multi-edge contours, compare the exit direction of each edge with the entry direction of the next. If `dot(prevDir, curDir) < MSDFGEN_CORNER_DOT_EPSILON - 1` (nearly 180° reversal), push the edges apart. Quadratic curves are promoted to cubics before adjustment (`deconvergeEdge()`).

**Source**: `msdfgen/core/Shape.cpp:65-92`

**Version sensitivity**: normalize() was made idempotent in 1.12, and a convergent-edge adjustment bug was fixed in 1.13.

### Rule 9: Upstream Inter-Glyph Spacing Default

For MSDF/MTSDF types, msdf-atlas-gen uses **spacing = 0** between packed glyphs:
```cpp
// msdf-atlas-gen/main.cpp:1064-1066
if (type is MSDF or MTSDF) spacing = 0;
```

This means MSDF/MTSDF glyphs rely entirely on their own SDF range border for isolation — no extra pixel gap between atlas rects. CrystalGraphics uses `DEFAULT_SPACING_PX = 1`, which is conservative but wastes a pixel per glyph boundary. This is harmless for correctness but differs from upstream.

---

## 5. Actionable Recommendations

### 5.1. Fix Contour Winding (D1) — **Priority: HIGH**

Replace `shape.orientContours()` with the upstream distance-test pattern:

```java
// After normalize(), compute bounds
double[] bounds = shape.getBounds();
double shapeL = bounds[0], shapeB = bounds[1];
double shapeR = bounds[2], shapeT = bounds[3];

// Pick a point guaranteed outside the shape bounds
double outerX = shapeL - (shapeR - shapeL) - 1.0;
double outerY = shapeB - (shapeT - shapeB) - 1.0;

// Test if the outer point is inside (positive distance = inside for SDFs)
double dist = shape.getOneShotDistance(outerX, outerY);
if (dist > 0) {
    // Shape winding is inverted — reverse all contours
    for (int i = 0; i < shape.getContourCount(); i++) {
        shape.getContour(i).reverse();
    }
}
```

**CAVEAT**: Requires verifying `Shape.getOneShotDistance()` in the JNI — see §5.6.

### 5.2. Fix getBoundsMiters() Parameters (D2) — **Priority: HIGH**

In `preparePagedGlyph()`, change the `getBoundsMiters()` call to pass correct parameters:

```java
// Current (wrong):
bounds = shape.getBoundsMiters(bounds, 0.0, config.getMiterLimit(), 0);

// Fixed (match upstream):
double halfRange = config.getPxRange() / 2.0;
double scale = config.getAtlasScalePx();
double border = halfRange / scale;  // = -range.lower in shape units
bounds = shape.getBoundsMiters(bounds, border, config.getMiterLimit(), 1);
```

**OR** move the `getBoundsMiters()` call into `CgMsdfGlyphLayout.compute()` after range expansion, to exactly match upstream ordering.

### 5.3. Verify planeBounds Convention (D6) — **Priority: HIGH**

Determine whether `CgPagedGlyphAtlas.allocateMsdf()` expects EM-normalized plane bounds or pixel-space plane bounds. If EM-normalized:

```java
// Remove the scale multiplication:
float planeLeft = (float) layout.getPlaneLeft();     // EM-normalized
float planeBottom = (float) layout.getPlaneBottom();
float planeRight = (float) layout.getPlaneRight();
float planeTop = (float) layout.getPlaneTop();
```

If the rendering pipeline needs pixel-space bounds, the conversion should happen at render time using the current render scale, not at generation time using the atlas scale.

### 5.4. Add Edge Coloring Seed — **Priority: LOW**

Add a seed parameter to the edge coloring JNI bindings and use a per-glyph seed:

```java
long seed = lcgSeed(baseSeed, glyphId);
shape.edgeColoringInkTrap(threshold, seed);
```

This matches upstream behavior and may improve edge coloring quality for some glyph topologies.

### 5.5. Add Scanline Sign-Correction Pass (D7) — **Priority: MEDIUM**

The upstream workflow for non-Skia generation is:

1. `generateMTSDF()` with error correction **disabled**
2. `distanceSignCorrection(bitmap, shape, projection, FILL_NONZERO)` — scanline sign repair
3. `msdfErrorCorrection(bitmap, shape, projection, range, ecConfig)` with `DO_NOT_CHECK_DISTANCE`

This requires:
- A new JNI method wrapping `distanceSignCorrection()`
- Restructuring `preparePagedGlyph()` to use the 3-step workflow
- Ensuring the `DO_NOT_CHECK_DISTANCE` error correction mode is exposed in `MsdfConstants`

This is likely the fix for the "tiny wedge" artifacts at dense intersections in complex scripts.

### 5.6. Verify JNI oneShotDistance — **Priority: HIGH (blocker for §5.1)**

The current JNI `nShapeOneShotDistance` implementation:
```cpp
double minDist = 1e240;
for (contour : shape->contours) {
    for (edge : contour.edges) {
        SignedDistance dist = edge->signedDistance(origin, param);
        if (fabs(dist.distance) < fabs(minDist))
            minDist = dist.distance;
    }
}
return minDist;
```

This returns the **signed distance to the nearest edge**, which is signed but not the same as `SimpleTrueShapeDistanceFinder::oneShotDistance()`. The upstream function uses the shape's fill rule and scanline intersection counting to determine true inside/outside status, producing a properly-signed distance that respects winding. The per-edge approach above may return incorrect signs for complex shapes with overlapping contours.

**Recommendation**: Add a new JNI method `nShapeTrueOneShotDistance` that calls `SimpleTrueShapeDistanceFinder::oneShotDistance()`. This is critical for the winding fix in §5.1.

---

## Appendix A: Upstream Default Values Reference

| Parameter | Upstream Default | CrystalGraphics Default | Match? |
|-----------|-----------------|------------------------|--------|
| Edge coloring | `edgeColoringInkTrap` | `INK_TRAP` | ✅ |
| Angle threshold | `3.0°` | `3.0°` | ✅ |
| Miter limit | `1.0` | `2.0` | ⚠️ Higher (conservative) |
| pxRange | `2.0` | `16.0` | ⚠️ Higher (intentional) |
| pxAlignOriginX | `false` | `false` | ✅ |
| pxAlignOriginY | `true` | `true` | ✅ |
| Font scaling | `FONT_SCALING_NONE` | `FONT_SCALING_EM_NORMALIZED` | ⚠️ Different (see D3) |
| overlapSupport | `true` (without Skia) | `true` | ✅ |
| Error correction mode | `EDGE_PRIORITY` | `EDGE_PRIORITY` | ✅ |
| Distance check mode | `AT_EDGE` | `AT_EDGE` | ✅ |
| minDeviationRatio | `10/9 ≈ 1.111` | `10/9 ≈ 1.111` | ✅ |
| minImproveRatio | `10/9 ≈ 1.111` | `10/9 ≈ 1.111` | ✅ |
| scanlinePass | `true` (without Skia) | Not exposed | ❌ Missing |
| Edge coloring seed | Per-glyph LCG | Not passed (default 0) | ⚠️ |
| Y direction | `Y_UPWARD` | Implicit (flip in Java) | ✅ |
| Contour winding | Distance-test reversal | `orientContours()` | ❌ Different |
| Inter-glyph spacing | `0` (MSDF/MTSDF) | `1` | ⚠️ Conservative |
| Error correction alpha | RGB only (alpha untouched) | Via native (correct) | ✅ |

## Appendix B: Quick Reference — CrystalGraphics File Locations

| File | Role |
|------|------|
| `CgMsdfGenerator.java` | Main generator (cell + paged paths) |
| `CgMsdfGlyphLayout.java` | wrapBox equivalent |
| `CgMsdfAtlasConfig.java` | Configuration parameters |
| `CgMsdfEdgeColoringMode.java` | Edge coloring enum |
| `msdfgen_jni.cpp` | Native JNI bridge |
| `Generator.java` | Java-side JNI entry points |
| `Shape.java` | Shape wrapper |
| `Transform.java` | Projection + range wrapper |
| `FreeTypeIntegration.java` | Font loading |

## Appendix C: Upstream Source Locations

| Topic | File | Key Function |
|-------|------|-------------|
| Glyph loading + preprocessing | `GlyphGeometry.cpp` | `load()` |
| Box sizing | `GlyphGeometry.cpp` | `wrapBox()` |
| Plane bounds | `GlyphGeometry.cpp` | `getQuadPlaneBounds()` |
| Edge coloring | `msdfgen/core/edge-coloring.cpp` | `edgeColoringInkTrap()` |
| MTSDF generation | `msdfgen/core/msdfgen.cpp` | `generateMTSDF()` |
| Error correction | `msdfgen/core/msdf-error-correction.cpp` | `msdfErrorCorrection()` |
| Atlas packing | `TightAtlasPacker.cpp` | `pack()` / `tryPack()` |
| Shape normalization | `msdfgen/core/Shape.cpp` | `normalize()` |
| Miter bounds | `msdfgen/core/Shape.cpp` | `boundMiters()` |
| Config defaults | `msdf-atlas-gen/main.cpp` | Top-level `#define`s |
