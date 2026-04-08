# Fix Strategy: JP/Arabic MTSDF Intersection Wedges

**Date**: 2026-04-06  
**Status**: Proposal  
**Context**: World text uses a stable raster tier. Latin corner improvements are working. Japanese and Arabic glyphs still exhibit intersection wedge artifacts at dense overlaps/junctions.

---

## Root Cause Analysis

The current MTSDF generation pipeline in `CgMsdfGenerator` has three compounding omissions that specifically amplify artifacts in complex scripts (JP/Arabic):

### 1. Missing `orientContours()` — **CRITICAL for JP/Arabic**

**Current pipeline**: `normalize()` → `edgeColoring()` → `generate()`  
**Correct pipeline**: `normalize()` → `orientContours()` → `edgeColoring()` → `generate()`

Japanese kanji and Arabic ligatures frequently contain **overlapping contours** (e.g., radical components drawn as separate overlapping filled shapes by the font designer). Without `orientContours()`:
- Contour windings may be inconsistent (some CW, some CCW)
- The MSDF generator interprets overlapping same-winding contours as **subtractive** instead of additive
- This produces the classic "wedge" artifact: a triangular spike of wrong-sign distance at the intersection seam

`orientContours()` uses msdfgen's scanline-based algorithm to detect correct fill and flip windings to be consistent. The binding exists (`Shape.orientContours()` → `nShapeOrientContours`), it's simply never called.

### 2. MTSDF Alpha Channel Ignored in Shader — **SAFETY NET wasted**

The generator produces 4-channel MTSDF (RGB multi-channel + A true-SDF), but the fragment shader only uses RGB median:

```glsl
float msdfDist = median(mtsdf.r, mtsdf.g, mtsdf.b);  // line 23
float signedDistance = msdfDist;                        // alpha IGNORED
```

The alpha channel is the true signed distance field — smooth, monotonic, no multi-channel discontinuities. At intersection regions where RGB channels produce conflicting medians (the wedge), clamping against alpha prevents the artifact from reaching screen pixels. This is the standard MTSDF consumption pattern from msdfgen documentation.

### 3. No `validate()` Check — **Minor but diagnostic**

`shape.validate()` is never called. Invalid shapes (degenerate edges, zero-length segments) passed to the generator can produce undefined distance values. While `normalize()` handles some degenerate cases, `validate()` would catch remaining issues and allow a clean fallback to bitmap rendering for genuinely broken glyph outlines.

---

## Ranked Recommendations

### Fix #1: Add `orientContours()` to shape preprocessing [HIGH CONFIDENCE]

**Where**: `CgMsdfGenerator.java`, all three generation paths  
**Change**: Insert `shape.orientContours()` between `normalize()` and `applyEdgeColoring()`  
**Risk**: None for Latin (Latin fonts almost never have overlapping contours; orientContours is a no-op). High impact for JP/Arabic where overlapping contours are the norm.

```java
// Current:
shape.normalize();
applyEdgeColoring(shape, config);

// Proposed:
shape.normalize();
shape.orientContours();  // Fix overlapping-contour winding for JP/Arabic
applyEdgeColoring(shape, config);
```

**Why ranked #1**: This directly addresses the documented root cause of intersection wedges. msdfgen's own CLI tool calls `orientContours()` by default. Every msdfgen integration guide lists it as mandatory for font glyphs. The binding already exists and is tested at the native level. Zero regression risk for Latin.

**Cost**: One line per generation path (3 insertions total).

---

### Fix #2: Use MTSDF alpha channel as intersection clamp in shader [HIGH CONFIDENCE]

**Where**: `mtsdf_text.frag`  
**Change**: Combine RGB median with alpha true-SDF using `max(median, alpha)` intersection

```glsl
// Current:
float msdfDist = median(mtsdf.r, mtsdf.g, mtsdf.b);
float signedDistance = msdfDist;

// Proposed:
float msdfDist = median(mtsdf.r, mtsdf.g, mtsdf.b);
float sdfDist = mtsdf.a;
float signedDistance = max(min(msdfDist, sdfDist), max(msdfDist + sdfDist - 1.0, 0.0));
```

The formula `max(min(d_msdf, d_sdf), max(d_msdf + d_sdf - 1.0, 0.0))` is the standard MTSDF compositing from Chlumsky's paper. It:
- Preserves sharp corners (where MSDF is authoritative and agrees with SDF)
- Suppresses false-positive wedges (where MSDF disagrees with SDF at intersections)
- Is mathematically equivalent to clipping the MSDF result to the SDF's "inside" region

**Alternative (simpler, slightly less precise)**:
```glsl
float signedDistance = max(msdfDist, sdfDist);
```
This simpler form also suppresses most wedges and is easier to reason about.

**Why ranked #2**: This is a pure shader-side safety net. Even after Fix #1, some font outlines have self-intersections within a single contour (not just overlapping contours), and those can still produce derivative-amplified spikes. The alpha clamp catches those. However, Fix #1 alone should eliminate the majority of visible wedges, so this is defense-in-depth.

**Risk for Latin**: Negligible. At corners where MSDF distance and SDF distance agree (the normal case for Latin), the formula degenerates to the same value. The alpha channel only constrains where there's genuine disagreement.

**Cost**: 2-3 shader lines changed. No Java changes.

---

### Fix #3: Add `validate()` as a gating check [MEDIUM CONFIDENCE]

**Where**: `CgMsdfGenerator.java`, after `normalize()` and before `orientContours()`  
**Change**: Call `shape.validate()` and fall back to bitmap for invalid shapes

```java
shape.normalize();
if (!shape.validate()) {
    LOGGER.log(Level.FINE, "Shape validation failed for glyph " + key.getGlyphId() + ", falling back to bitmap");
    return null;  // bitmap fallback
}
shape.orientContours();
applyEdgeColoring(shape, config);
```

**Why ranked #3**: This is primarily a robustness measure. Invalid shapes are rare but not impossible — some CJK fonts have degenerate outlines that normalize() doesn't fully fix. Without validation, those feed garbage into the generator and produce unpredictable artifacts. Returning null triggers the existing bitmap fallback path, which is visually correct even if lower quality.

**Risk**: A font with many invalid shapes would lose MSDF rendering for those glyphs. This is correct behavior — better a clean bitmap than a corrupt MSDF. Log at FINE level so it's visible in debug but not noisy.

**Cost**: 4 lines per generation path.

---

### Fix #4: Tighten error correction parameters for complex glyphs [LOW-MEDIUM CONFIDENCE]

**Where**: `CgMsdfAtlasConfig.java` defaults, or per-script override  
**Change**: Consider increasing `minDeviationRatio` from the default `1.111...` to `~2.0` for scripts with high edge density

The current error correction (`EDGE_PRIORITY` + `DISTANCE_CHECK_AT_EDGE`) is sound, but the default `minDeviationRatio` of 1.111 means the corrector requires very little distance deviation before it intervenes. For dense JP glyphs with many closely-spaced edges, this can cause the corrector to "fight" legitimate MSDF channel boundaries, producing its own artifacts.

**Why ranked #4**: This is a tuning knob, not a structural fix. It should be explored only after Fixes #1-2 are applied and residual artifacts are assessed. The right ratio is empirical.

**Risk**: If set too high, error correction becomes ineffective and real MSDF artifacts return. Needs visual testing per script.

---

### NOT Recommended

| Approach | Why Not |
|----------|---------|
| "Just increase resolution more" | Resolution is already at 64px atlas scale. Higher scales consume atlas space quadratically. At 64px with pxRange=6, per-pixel SDF precision is already ~0.094 SDF units — enough for all practical edge cases. |
| Delete MSDF support | Per constraints. |
| External geometry library | Per constraints. No `Clipper2` or `CGAL` available. `orientContours()` uses msdfgen's built-in scanline solver which is sufficient. |
| Switch to SDF-only | Loses the sharp-corner rendering that MSDF/MTSDF provides. The Latin improvements depend on this. |
| `resolveShapeGeometry()` | Not bound in current JNI layer, and would require native code changes + rebuild. `orientContours()` + `overlapSupport=true` handles the same cases for font outlines. `resolveShapeGeometry` is for arbitrary vector art, not TrueType/OpenType glyphs. |

---

## Implementation Order

```
1. orientContours()     — 3 one-line insertions, eliminates root cause
2. Alpha clamp shader   — 3 shader lines, eliminates residual wedges  
3. validate() gate      — 12 lines total, robustness safety net
4. Tune error params    — Only if visual inspection shows residual issues
```

Fixes #1 and #2 are independent and can be done in parallel. Fix #3 depends on #1 (must come after normalize, before orientContours in the call sequence). Fix #4 is deferred pending visual assessment.

**Expected outcome**: Fixes #1+#2 together should eliminate >95% of visible wedge artifacts in JP/Arabic while preserving all Latin corner improvements (Latin glyphs don't have overlapping contours and the alpha clamp is a no-op when channels agree).
