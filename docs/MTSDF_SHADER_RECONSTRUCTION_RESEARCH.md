# MTSDF Shader Reconstruction: Best Practices for OpenGL 3.x / GLSL 130+

**Date**: 2026-04-07  
**Purpose**: Concrete GLSL implementation reference for rewriting `mtsdf_text.frag` in CrystalGraphics.  
**Target**: OpenGL 3.0 (`#version 130`) via LWJGL 2.9.3 in Minecraft 1.7.10.  
**Status**: **SUPERSEDED / CORRECTED** — the earlier geometric-intersection recommendation was validated against the scripted `TextScene3D` bracket repro and found to reintroduce rounded outer corners. The current body-reconstruction recommendation is pure RGB median with the alpha SDF reserved for non-body effects.

### Changes Applied (Corrected After Capture Validation)

| File | Change | Rationale |
|------|--------|-----------|
| `src/main/resources/assets/crystalgraphics/shader/mtsdf_text.frag` | Removed the smoothstep-based SDF fallback and later removed the geometric MSDF∩SDF body reconstruction too | Scripted `TextScene3D` captures showed both alpha-assisted body paths still rounded the bracket outer corner compared to pure median RGB. |
| `src/main/resources/assets/crystalgraphics/shader/mtsdf_text.frag` | Body coverage now uses `median(mtsdf.r, mtsdf.g, mtsdf.b)` directly | This restored the square `[` corner in the `bracket-corner-rounding` harness repro while keeping the derivative-based AA path intact. |
| `src/main/resources/assets/crystalgraphics/shader/mtsdf_text.frag` | Added a tiny `fwidth(v_uv)` guard inside `screenPxRange()` | Prevents zero-derivative instability on degenerate UV gradients without changing the normal AA path. |
| `src/main/resources/assets/crystalgraphics/shader/mtsdf_text.frag` | Updated `u_pxRange` comment from "e.g. 4.0" to "default 6.0" | Matches `CgMsdfAtlasConfig.DEFAULT_PX_RANGE = 6.0f`. |

---

## Table of Contents

1. [Background: MSDF vs MTSDF](#1-background-msdf-vs-mtsdf)
2. [The Median Function](#2-the-median-function)
3. [screenPxRange: Derivative-Based Calculation](#3-screenpxrange-derivative-based-calculation)
4. [MTSDF Reconstruction: Combining RGB Median with Alpha SDF](#4-mtsdf-reconstruction-combining-rgb-median-with-alpha-sdf)
5. [The Intersection/Wedge Problem (Arabic, CJK)](#5-the-intersectionwedge-problem-arabic-cjk)
6. [Complete Shader Examples](#6-complete-shader-examples)
7. [Failure Modes and Diagnostics](#7-failure-modes-and-diagnostics)
8. [CrystalGraphics-Specific Portability Notes](#8-crystalgraphics-specific-portability-notes)
9. [Sources](#9-sources)

---

## 1. Background: MSDF vs MTSDF

| Format | Channels | Contents | Sharp Corners | Smooth Outlines/Shadows |
|--------|----------|----------|---------------|------------------------|
| SDF    | 1 (R or A) | True signed distance | No (rounded) | Yes |
| MSDF   | 3 (RGB)   | Multi-channel signed distance | Yes | No (artifacts at threshold offsets) |
| MTSDF  | 4 (RGBA)  | RGB = multi-channel + A = true SDF | Yes | Yes |

**MTSDF** is the combination format introduced by Chlumsky's msdfgen. The RGB channels encode the multi-channel distance field (sharp corners), while the alpha channel encodes a conventional true signed distance field (smooth, monotonic, no channel-crossing artifacts). This gives the shader access to both representations simultaneously.

The alpha channel serves two critical purposes:
1. **Intersection artifact suppression**: Where RGB channels disagree due to overlapping contours or self-intersections, the alpha channel provides a reliable fallback.
2. **Smooth effects**: Outlines, glows, and shadows use the alpha (true SDF) channel for natural rounded falloff, while the body edge uses RGB median for sharp corners.

**Source**: msdfgen README ([github.com/Chlumsky/msdfgen](https://github.com/Chlumsky/msdfgen)), msdf-atlas-gen `--type mtsdf` documentation.

---

## 2. The Median Function

The median of three values is the fundamental MSDF reconstruction operation. It computes the distance value that two or more channels agree on, which is the intersection of the per-channel distance fields that produces sharp corners.

```glsl
float median(float r, float g, float b) {
    return max(min(r, g), min(max(r, g), b));
}
```

This branchless formulation is universal across all surveyed implementations. It returns the middle value of three inputs — the value that at least two channels agree is "inside" or "outside".

**Why median works**: Each MSDF channel encodes a different partition of the glyph's edge segments. A pixel is considered "inside" the glyph only if at least 2 of 3 channels agree it's inside. This 2-of-3 voting via median preserves sharp corners that a single SDF would round.

**Critical**: MSDF channels must be sampled in **linear space**, not sRGB, even if the image format suggests sRGB. CrystalGraphics uses `GL_RGBA16F` which is inherently linear — correct.

**Sources**: msdfgen README "Using a multi-channel distance field"; Chlumsky thesis §4.2; every surveyed production shader.

---

## 3. screenPxRange: Derivative-Based Calculation

`screenPxRange` converts the distance field's pixel range (the `pxRange` parameter used during atlas generation) into screen-space pixels. This is the scaling factor that maps SDF distance units to fragment-level opacity transitions.

### 3.1. The Standard Formula (Recommended)

```glsl
uniform float u_pxRange; // e.g., 6.0 — must match atlas generation

float screenPxRange(vec2 uv) {
    vec2 unitRange = vec2(u_pxRange) / vec2(textureSize(u_atlas, 0));
    vec2 screenTexSize = vec2(1.0) / fwidth(uv);
    return max(0.5 * dot(unitRange, screenTexSize), 1.0);
}
```

**Explanation** (from [Fractolog MSDF Fragment Shader Antialiasing](https://www.fractolog.com/2025/01/msdf-fragment-shader-antialiasing/)):

1. `unitRange`: How far in UV space corresponds to the full SDF range. If `pxRange=6` and atlas is 512px, then `unitRange = 6/512 ≈ 0.0117`. This represents the UV-space width over which the SDF transitions from min to max.

2. `screenTexSize = 1.0 / fwidth(uv)`: How many atlas texels map to one screen pixel. `fwidth(uv.x) = abs(dFdx(uv.x)) + abs(dFdy(uv.x))` gives the total UV change per screen pixel. Its reciprocal gives texels-per-pixel.

3. `0.5 * dot(unitRange, screenTexSize)`: The half-dot-product averages the X and Y contributions, giving the number of screen pixels that span half the SDF range — exactly the "transition width" for anti-aliasing.

4. `max(..., 1.0)`: Floor at 1.0 prevents the transition from becoming sub-pixel, which would cause hard aliased edges when the texture is heavily minified.

### 3.2. Why NOT `d/fwidth(d)` (The Thesis Shortcut)

Chlumsky's thesis suggests the simpler `screenPxDistance = d / fwidth(d)`, which is mathematically equivalent in the "interesting" SDF transition zone. However:

- **NaN hazard**: In flat SDF regions (far inside or outside the glyph), `fwidth(d) → 0`, making the division produce `Inf` or `NaN`. The `0 * Inf = NaN` case is common.
- **GPU driver variance**: Some drivers handle `0/0` differently (NaN vs 0 vs Inf). The explicit `unitRange`-based formula avoids all division-by-zero paths.
- **Production consensus**: Every surveyed production shader uses the `unitRange / fwidth(uv)` form, not `d / fwidth(d)`.

**Source**: Fractolog analysis blog; msdfgen README (recommends the explicit form for 3D); every surveyed shader (Hazel, lovr, ThinGL, Nuake, xemu, glitch).

### 3.3. Alternate: Higher-Accuracy Derivative (msdfgen README)

For perspective-projected 3D text where texture scale varies significantly across the quad:

```glsl
vec2 sqr(vec2 x) { return x * x; }

float screenPxRange(vec2 uv) {
    vec2 unitRange = vec2(u_pxRange) / vec2(textureSize(u_atlas, 0));
    // inversesqrt(sqr(dFdx) + sqr(dFdy)) avoids the fwidth Manhattan approximation
    vec2 screenTexSize = inversesqrt(sqr(dFdx(uv)) + sqr(dFdy(uv)));
    return max(0.5 * dot(unitRange, screenTexSize), 1.0);
}
```

This uses Euclidean derivative magnitude instead of the `fwidth` Manhattan approximation. For CrystalGraphics world-space text (which is perspective-projected), this is marginally more accurate but typically not visibly different. The `fwidth` version is sufficient.

**Source**: msdfgen README "Using a multi-channel distance field" (the 3D variant).

### 3.4. CrystalGraphics Current Implementation

The existing `mtsdf_text.frag` correctly uses the standard formula:

```glsl
vec2 atlasSize = vec2(textureSize(u_atlas, 0));
vec2 unitRange = vec2(u_pxRange) / atlasSize;
vec2 screenTexSize = vec2(1.0) / fwidth(v_uv);
float screenPxRange = max(0.5 * dot(unitRange, screenTexSize), 1.0);
```

**Verdict**: This is correct. No change needed for the screenPxRange calculation itself.

---

## 4. MTSDF Reconstruction: Combining RGB Median with Alpha SDF

This is where the current shader has a problematic custom formula. The RGB median and the alpha true-SDF must be combined correctly to get both sharp corners (from MSDF) and intersection safety (from SDF).

### 4.1. The Problem Space

At any given texel, the RGB median distance (`d_msdf`) and the alpha true-SDF distance (`d_sdf`) encode the same boundary, but from different representations:

- **Agreement regions** (most of the glyph): `d_msdf ≈ d_sdf`. Both say "inside" or "outside" consistently. The MSDF value is preferred because it preserves sharp corners.
- **Disagreement regions** (overlapping contours, self-intersections): `d_msdf` may show "outside" while `d_sdf` correctly shows "inside", or the MSDF median may spike to an incorrect value. These produce the **wedge artifacts** seen in Arabic and CJK glyphs.

### 4.2. Production Approaches (Ranked)

#### Approach A: Simple Clamp — `min(d_msdf, d_sdf)` [SIMPLEST, GOOD]

```glsl
float d_msdf = median(mtsdf.r, mtsdf.g, mtsdf.b);
float d_sdf = mtsdf.a;
float sd = min(d_msdf, d_sdf + OFFSET);
```

**Source**: [unitoftime/glitch `shaders/msdf.fs`](https://github.com/unitoftime/glitch/blob/master/shaders/msdf.fs) (MIT license)

The glitch engine uses `d_msdf = min(d_msdf, d_sdf + 0.1)` with the comment `// HACK: to fix glitch in msdf near edges`. The `+0.1` offset gives the SDF a small margin so it doesn't clip the MSDF at perfectly sharp corners where both values are exactly 0.5.

**Behavior**:
- At sharp corners where MSDF is authoritative: `d_msdf < d_sdf` (MSDF is closer to edge), so `min` returns `d_msdf` → sharp corner preserved.
- At intersection artifacts where MSDF spikes positive: `d_sdf` is smaller (correctly near edge), so `min` returns `d_sdf` → artifact suppressed.
- The `+0.1` offset prevents the SDF from over-constraining at legitimate sharp corners where it disagrees slightly with the MSDF.

**Tradeoff**: Simple but slightly rounds some corners that the pure MSDF would keep sharp, because `min` is conservative.

#### Approach B: Geometric Intersection — `max(min(d_msdf, d_sdf), max(d_msdf + d_sdf - 1.0, 0.0))` [RECOMMENDED]

```glsl
float d_msdf = median(mtsdf.r, mtsdf.g, mtsdf.b);
float d_sdf = mtsdf.a;
float sd = max(min(d_msdf, d_sdf), max(d_msdf + d_sdf - 1.0, 0.0));
```

This is the formula currently in CrystalGraphics's `mtsdf_text.frag` (line 25). It is mathematically the intersection of two SDF domains:

- `min(d_msdf, d_sdf)` — "inside both"
- `max(d_msdf + d_sdf - 1.0, 0.0)` — "at least one is deeply inside" (prevents over-erosion at corners)
- `max(...)` between them — take the less restrictive bound

**Behavior**: When both agree, this degenerates to approximately `d_msdf`. When they disagree at an intersection, it interpolates toward the more conservative (SDF) value without fully rounding corners.

**This is a sound formula.** However, the current shader wraps it with an additional `smoothstep`-based SDF fallback (lines 26-28) that may be the actual source of problems:

```glsl
// CURRENT (problematic lines 26-28):
float disagreement = abs(msdfDist - sdfDist);
float sdfFallback = smoothstep(0.03, 0.10, disagreement);
float signedDistance = mix(hybridDist, sdfDist, sdfFallback);
```

This `smoothstep` fallback mixes toward pure `sdfDist` when disagreement exceeds 0.03–0.10. The thresholds are very tight. For Arabic/CJK glyphs with legitimate medium-level disagreement between MSDF and SDF (due to dense edge packing, not artifacts), this aggressively rounds corners that should stay sharp. **The smoothstep fallback should be removed in favor of the geometric intersection alone.**

#### Approach C: Soft Blend via `mix` with Rounded/Sharp Controls [ADVANCED]

```glsl
float d_msdf = median(distances.r, distances.g, distances.b);
float d_sdf = distances.a;

// Blend between sharp (MSDF) and rounded (SDF) for body vs outline
float d_inner = mix(d_msdf, d_sdf, u_rounded_fonts);    // 0.0 = sharp, 1.0 = round
float d_outer = mix(d_msdf, d_sdf, u_rounded_outlines);
```

**Source**: [unitoftime/glitch](https://github.com/unitoftime/glitch/blob/master/shaders/msdf.fs) (MIT license)

This approach exposes a uniform to control how much rounding to apply. For text body rendering, `u_rounded_fonts = 0.0` uses pure MSDF. For outline/glow effects, `u_rounded_outlines = 1.0` uses pure SDF. This is the MTSDF's unique advantage: both are available per-fragment.

#### Approach D: Pure MSDF with SDF-only fallback — `max(d_msdf, d_sdf)` [SIMPLEST SAFETY]

```glsl
float d_msdf = median(mtsdf.r, mtsdf.g, mtsdf.b);
float d_sdf = mtsdf.a;
float sd = max(d_msdf, d_sdf);
```

Where MSDF produces a wedge artifact, `d_msdf` goes strongly negative (outside). The SDF at the same point is positive (correctly inside). `max` picks the SDF value, suppressing the wedge. Where both agree, both are positive and close, and `max` picks whichever is slightly larger — still correct.

**Tradeoff**: The simplest and most robust, but can slightly expand glyph boundaries in regions where SDF is slightly more positive than the MSDF median. In practice, at body threshold (0.5), this is usually imperceptible.

### 4.3. Recommendation for CrystalGraphics

**Use pure RGB median for body fill and reserve the alpha/SDF channel for separate non-body effects**:

```glsl
float signedDistance = median(mtsdf.r, mtsdf.g, mtsdf.b);
float screenPxDist = screenPxRange() * (signedDistance - 0.5);
float opacity = clamp(screenPxDist + 0.5, 0.0, 1.0);
```

This recommendation is based on direct repository validation, not theory alone:

- `shader-hybrid-control-bracket-corner-rounding.png` reproduced the rounded bracket cap with the geometric MSDF∩SDF body formula.
- `shader-median-pass-bracket-corner-rounding.png` restored the square outer corner after switching body coverage back to `median(rgb)`.

For CrystalGraphics body rendering, the MTSDF alpha channel is therefore best treated as **reserved for rounded-distance effects** (glow, outline, shadow, diagnostics), not as part of the main fill edge.

---

## 5. The Intersection/Wedge Problem (Arabic, CJK)

### 5.1. Root Cause Chain

Arabic and CJK glyphs produce MTSDF artifacts through multiple compounding mechanisms:

1. **Overlapping contours in font data**: Arabic ligatures and CJK radicals are frequently composed of multiple overlapping closed contours. TrueType/OpenType fonts allow this — the rasterizer uses even-odd or nonzero winding fill rules to resolve overlaps. MSDF generation must resolve them explicitly.

2. **Missing `orientContours()`**: Without orienting contour windings before generation, overlapping contours with inconsistent windings cause the MSDF generator to compute distances of the wrong sign at overlap regions. This is the primary source of wedge artifacts. (See `FIX_STRATEGY_JP_ARABIC_MTSDF_WEDGES.md` — Fix #1 has been applied.)

3. **MSDF channel discontinuities at dense edge junctions**: Even with correct winding, the median of three channels can produce a non-monotonic distance field at points where many edge segments converge. The SDF (alpha channel) does not have this problem because it's a single true distance field.

4. **Shader-side over-correction**: An overly aggressive shader fallback (like the current `smoothstep`-based sdfFallback) rounds corners that are legitimately sharp, making glyphs look blurry.

### 5.2. Generator-Side Mitigations

These are msdf-atlas-gen / msdfgen options relevant to the intersection problem:

| Option | Effect | CrystalGraphics Status |
|--------|--------|----------------------|
| `orientContours()` | Normalizes contour winding for correct overlap handling | ✅ Applied (since Fix Strategy #1) |
| `overlapSupport=true` | Generator handles overlapping contours natively | ✅ Enabled (`DEFAULT_OVERLAP_SUPPORT = true`) |
| `-scanline` / scanline pass | Additional sign correction pass | Not available in JNI bindings (handled by orientContours + overlap) |
| `-nopreprocess` | Disables self-intersection resolution | N/A (we want preprocessing enabled) |
| Error correction (EDGE_PRIORITY) | Post-generation artifact correction | ✅ Enabled |

### 5.3. Shader-Side Mitigations

The shader is the last line of defense. Even with perfect generator settings, some glyphs in some fonts will produce MSDF channel disagreements at dense junctions. For CrystalGraphics body rendering, the validated rule is now simpler:

1. **Use pure RGB median for body coverage**: the scripted bracket A/B captures showed that alpha-assisted body formulas reintroduced rounded convex corners.

2. **Do not add disagreement heuristics or alpha/SDF body fallbacks**: they create more problems than they solve for the main fill edge, especially on hard outer corners.

3. **Optional debug visualization**: For diagnosing persistent artifacts, temporarily output the disagreement:
   ```glsl
   // Debug mode: visualize MSDF/SDF disagreement
   float disagreement = abs(d_msdf - d_sdf);
   fragColor = vec4(disagreement * 5.0, 0.0, 0.0, 1.0); // Red = high disagreement
   ```

### 5.4. msdfgen Issue Tracker References

- [Issue #110: Overlapping glyph contours produce positive values](https://github.com/Chlumsky/msdfgen/issues/110) — Documents the exact artifact pattern seen in CrystalGraphics. The fix is `orientContours()` + overlap support at generation time.
- [Issue #74: Glitches in MSDF](https://github.com/Chlumsky/msdfgen/issues/74) — Shows MSDF channel discontinuities near dense edge junctions even in Latin glyphs (Arial Black 'x'). Error correction addresses these.
- [Issue #81: Combining MSDF with SDF in alpha channel](https://github.com/Chlumsky/msdfgen/issues/81) — The original feature request for MTSDF. Documents the use case of SDF for shadows, MSDF for body.

---

## 6. Complete Shader Examples

### 6.1. Minimal Correct MTSDF Body Shader (Recommended for CrystalGraphics)

```glsl
#version 130

in vec2 v_uv;
in vec4 v_color;

out vec4 fragColor;

uniform sampler2D u_atlas;  // GL_RGBA16F MTSDF atlas, GL_LINEAR filtering
uniform float u_pxRange;    // SDF range in atlas pixels (default 6.0)

float median(float r, float g, float b) {
    return max(min(r, g), min(max(r, g), b));
}

float screenPxRange() {
    vec2 unitRange = vec2(u_pxRange) / vec2(textureSize(u_atlas, 0));
    vec2 uvFwidth = max(fwidth(v_uv), vec2(1.0e-6));
    vec2 screenTexSize = vec2(1.0) / uvFwidth;
    return max(0.5 * dot(unitRange, screenTexSize), 1.0);
}

void main() {
    vec4 mtsdf = texture2D(u_atlas, v_uv);
    float signedDistance = median(mtsdf.r, mtsdf.g, mtsdf.b);

    float screenPxDist = screenPxRange() * (signedDistance - 0.5);
    float opacity = clamp(screenPxDist + 0.5, 0.0, 1.0);
    float alpha = v_color.a * opacity;

    if (alpha <= (1.0 / 255.0)) {
        discard;
    }

    fragColor = vec4(v_color.rgb, alpha);
}
```

**Key point**: CrystalGraphics body rendering now intentionally ignores `mtsdf.a` for fill reconstruction because the scripted bracket repro proved alpha-assisted body formulas round hard convex corners.

**Validation reference**: `gl-debug-harness/harness-output/text-3d/shader-hybrid-control-bracket-corner-rounding.png` vs `shader-median-pass-bracket-corner-rounding.png`.

**Source references**: msdfgen README (median + screenPxRange), repository A/B harness captures, and Oracle review of the final body formula.

### 6.2. MTSDF with Outline Support (Advanced)

```glsl
#version 130

in vec2 v_uv;
in vec4 v_color;

out vec4 fragColor;

uniform sampler2D u_atlas;
uniform float u_pxRange;
uniform vec4 u_outlineColor;
uniform float u_outlineWidth;  // In SDF distance units, e.g. 0.1
uniform float u_softness;      // Anti-alias softness, 0.0 = hard

float median(float r, float g, float b) {
    return max(min(r, g), min(max(r, g), b));
}

float screenPxRange() {
    vec2 unitRange = vec2(u_pxRange) / vec2(textureSize(u_atlas, 0));
    vec2 screenTexSize = vec2(1.0) / fwidth(v_uv);
    return max(0.5 * dot(unitRange, screenTexSize), 1.0);
}

void main() {
    vec4 mtsdf = texture2D(u_atlas, v_uv);
    float d_msdf = median(mtsdf.r, mtsdf.g, mtsdf.b);
    float d_sdf = mtsdf.a;

    // Use MSDF (sharp corners) for body edge
    float sd_body = max(min(d_msdf, d_sdf), max(d_msdf + d_sdf - 1.0, 0.0));
    // Use SDF (smooth) for outline edge — avoids angular outline artifacts
    float sd_outline = d_sdf;

    float spxr = screenPxRange();

    // Body: sharp threshold at 0.5
    float bodyDist = spxr * (sd_body - 0.5);
    float bodyAlpha = clamp(bodyDist + 0.5, 0.0, 1.0);

    // Outline: smooth threshold offset by outline width
    float outlineDist = spxr * (sd_outline - 0.5 + u_outlineWidth);
    float outlineAlpha = clamp(outlineDist + 0.5, 0.0, 1.0);

    // Composite: body over outline
    vec4 color = mix(u_outlineColor, v_color, bodyAlpha);
    float alpha = color.a * outlineAlpha;

    if (alpha <= (1.0 / 255.0)) {
        discard;
    }

    fragColor = vec4(color.rgb, alpha);
}
```

**Key insight**: The outline uses `d_sdf` (alpha channel, true SDF) rather than `d_msdf` because outlines rendered from MSDF produce angular/faceted artifacts at corners. The MTSDF format makes this dual-distance approach possible without a second texture lookup.

**Source**: msdfgen Issue #81; Red Blob Games SDF guide §6; unitoftime/glitch `u_rounded_outlines` pattern.

### 6.3. Reference: Production MSDF Shader from Hazel Engine

For comparison, the Hazel game engine (TheCherno) uses a simpler MSDF-only approach:

```glsl
// Source: TheCherno/Hazel, Renderer2D_Text.glsl (Apache-2.0)
// https://github.com/TheCherno/Hazel/blob/master/Hazelnut/assets/shaders/Renderer2D_Text.glsl

float screenPxRange() {
    const float pxRange = 2.0;
    vec2 unitRange = vec2(pxRange) / vec2(textureSize(u_FontAtlas, 0));
    vec2 screenTexSize = vec2(1.0) / fwidth(Input.TexCoord);
    return max(0.5 * dot(unitRange, screenTexSize), 1.0);
}

void main() {
    vec3 msd = texture(u_FontAtlas, Input.TexCoord).rgb;
    float sd = median(msd.r, msd.g, msd.b);
    float screenPxDistance = screenPxRange() * (sd - 0.5);
    float opacity = clamp(screenPxDistance + 0.5, 0.0, 1.0);
    if (opacity == 0.0)
        discard;
    color = vec4(Input.Color.rgb, Input.Color.a * opacity);
}
```

Note: This is MSDF-only (no alpha channel), so it has no intersection artifact mitigation. MTSDF is strictly better for complex scripts.

---

## 7. Failure Modes and Diagnostics

### 7.1. Wedge Artifacts at Contour Intersections

**Symptom**: Triangular spikes of fully opaque or fully transparent pixels at points where glyph strokes cross or overlap.

**Cause chain**:
1. Font has overlapping contours (Arabic ligatures, CJK radicals)
2. `orientContours()` not called → inconsistent winding → wrong-sign SDF at overlap
3. MSDF median produces positive distance (outside) where the glyph is inside
4. Alpha (true SDF) correctly shows inside → disagreement
5. If shader has aggressive fallback, it rounds everything; if no fallback, wedge is visible

**Fix**: Ensure `orientContours()` is called at generation time and keep body reconstruction on pure RGB median; avoid reintroducing alpha-assisted fill logic.

### 7.2. Fuzz / Halo at Small Sizes

**Symptom**: Glyphs appear to have a slight glow or fuzzy edges, especially visible on dark backgrounds.

**Cause**: `screenPxRange` falling to 1.0 (the floor) when the glyph is severely minified. The transition width becomes one full screen pixel, which is visible as a soft edge.

**Diagnosis**: Temporarily output `screenPxRange` as color:
```glsl
float spxr = screenPxRange();
fragColor = vec4(spxr / 10.0, 0.0, 0.0, 1.0); // Red intensity = range/10
```
If red is very dim (spxr ≈ 1.0), the glyph is too small for MSDF. Fall back to bitmap rendering.

**CrystalGraphics note**: The existing complexity heuristic in `CgMsdfGenerator.shouldUseMsdf()` already gates MSDF use by font size. If fuzzy edges appear, the threshold may need raising.

### 7.3. NaN / Black Fragments

**Symptom**: Random black or flickering pixels in text, especially at glyph borders.

**Cause**: `fwidth(v_uv)` returning zero on degenerate quads (zero-area triangles from text layout), causing division by zero in `screenTexSize`.

**Fix**: The `max(..., 1.0)` floor in `screenPxRange()` prevents `Inf * 0 = NaN` from reaching opacity. If NaN still appears, add an explicit guard:
```glsl
float opacity = clamp(screenPxDist + 0.5, 0.0, 1.0);
// Guard against NaN from degenerate geometry
if (opacity != opacity) opacity = 0.0; // NaN check
```

### 7.4. sRGB vs Linear Misinterpretation

**Symptom**: Glyphs appear too thin or too thick compared to the reference msdfgen test render.

**Cause**: The GPU is interpreting the MTSDF texture as sRGB (`GL_SRGB8_ALPHA8` or sRGB framebuffer) instead of linear. The non-linear gamma curve distorts the distance values.

**Fix**: Ensure the atlas texture uses `GL_RGBA16F` (as CrystalGraphics does) or `GL_RGBA8` with `GL_RGBA` internal format (not `GL_SRGB8_ALPHA8`). Also check that no sRGB framebuffer conversion is being applied.

**Source**: msdfgen README: "Make sure to interpret the MSDF color channels in linear space just like the alpha channel and not as sRGB."

### 7.5. Atlas Bleeding

**Symptom**: Colored fringe at glyph edges, or fragments of adjacent glyphs visible at borders.

**Cause**: GL_LINEAR filtering samples neighboring texels across glyph boundaries in the atlas.

**Fix**: Use `GL_CLAMP_TO_EDGE` and ensure sufficient spacing between glyphs in the atlas (CrystalGraphics uses `spacingPx=1`, which may be insufficient for `pxRange=6` — consider `spacingPx >= pxRange/2 = 3`).

---

## 8. CrystalGraphics-Specific Portability Notes

### 8.1. GLSL 130 Compatibility

CrystalGraphics targets `#version 130` (OpenGL 3.0). Key compatibility points:

| Feature | GLSL 130 Status | Notes |
|---------|-----------------|-------|
| `texture2D()` | ✅ Available | Deprecated in 150+, replaced by `texture()`. Use `texture2D()` for 130. |
| `textureSize()` | ✅ Available | Added in GLSL 130. Returns `ivec2`. |
| `fwidth()` | ✅ Available | Requires `GL_OES_standard_derivatives` on ES, but core in desktop GL 130. |
| `dFdx()`, `dFdy()` | ✅ Available | Core in GLSL 130. |
| `in`/`out` varyings | ✅ Available | Use `in`/`out` instead of `varying`/`gl_FragColor` (130 style). |
| `inversesqrt()` | ✅ Available | Core built-in. |
| `smoothstep()` | ✅ Available | Core built-in. |

### 8.2. Uniform Considerations

```java
// CgTextRenderer or equivalent setup:
// u_pxRange MUST match the value used during atlas generation
float pxRange = config.getPxRange(); // 6.0 from CgMsdfAtlasConfig.DEFAULT_PX_RANGE
GL20.glUniform1f(pxRangeLoc, pxRange);

// The shader's textureSize() call handles atlas resolution automatically.
// No need to pass atlas width/height as separate uniforms.
```

### 8.3. Performance on Minecraft Hardware Targets

The MTSDF shader adds negligible cost vs the MSDF-only shader:
- One extra `texture2D` channel read (`.a` — already fetched with `.rgba`)
- Two extra `min`/`max` operations

On Intel HD 3000 (minimum target), the bottleneck is texture bandwidth, not ALU. The final median-body shader is therefore comfortably cheap for target hardware.

### 8.4. Depth Buffer Interaction

The existing shader writes to `gl_FragDepth` for near-zero alpha fragments to prevent them from occluding world geometry. This pattern is correct and should be preserved:

```glsl
if (alpha <= 0.01) {
    gl_FragDepth = 1.0; // Push to far plane so it doesn't occlude
}
```

**Caveat**: Writing to `gl_FragDepth` disables early-z optimization on most GPUs. For dense text, this can cause overdraw performance issues. A potential optimization is to only write `gl_FragDepth` when the text is in world-space (not 2D HUD), but this requires a uniform flag.

---

## 9. Sources

### Primary (Official)

1. **Chlumsky, V.** "Multi-channel signed distance field generator" (msdfgen). MIT License.  
   Repository: https://github.com/Chlumsky/msdfgen  
   README section "Using a multi-channel distance field" — canonical median + screenPxRange shader.

2. **Chlumsky, V.** "Shape Decomposition for Multi-channel Distance Fields" (Master's thesis, 2015).  
   PDF: https://github.com/Chlumsky/msdfgen/files/3050967/thesis.pdf  
   §4.2: Geometric interpretation of multi-channel intersection; §5.1.2: Anti-aliasing with `d/fwidth(d)`.

3. **Chlumsky, V.** "Multi-channel signed distance field atlas generator" (msdf-atlas-gen).  
   Repository: https://github.com/Chlumsky/msdf-atlas-gen  
   `--type mtsdf`, `-overlap`, `-scanline` documentation.

### Production Shader Implementations

4. **TheCherno/Hazel** — `Renderer2D_Text.glsl` (Apache-2.0)  
   https://github.com/TheCherno/Hazel/blob/master/Hazelnut/assets/shaders/Renderer2D_Text.glsl  
   Clean MSDF-only shader, canonical screenPxRange pattern.

5. **unitoftime/glitch** — `shaders/msdf.fs` (MIT)  
   https://github.com/unitoftime/glitch/blob/master/shaders/msdf.fs  
   MTSDF shader with `min(d_msdf, d_sdf + 0.1)` hack and rounded-fonts blend controls.

6. **bjornbytes/lovr** — `etc/shaders/font.frag` (MIT)  
   https://github.com/bjornbytes/lovr/blob/dev/etc/shaders/font.frag  
   Minimal MSDF shader passing `sdfRange` as material uniform.

7. **RaphiMC/ThinGL** — `sdf_text.frag` (LGPL-3.0)  
   https://github.com/RaphiMC/ThinGL/blob/main/src/main/resources/thingl/shaders/regular/sdf_text.frag  
   MSDF with bold/outline support using `smoothstep` and `fwidth(dist)`.

8. **antopilo/Nuake** — `sdf_text.shader`, `ui_text.shader` (MIT)  
   https://github.com/antopilo/Nuake/blob/main/Data/Shaders/sdf_text.shader  
   Standard screenPxRange + median pattern with configurable pxRange uniform.

9. **Rezmason/matrix** — `rainPass.frag.glsl` (MIT)  
   https://github.com/Rezmason/matrix/blob/master/shaders/glsl/rainPass.frag.glsl  
   MSDF with dual-texture (glyph + glint) rendering.

10. **xemu-project/xemu** — `xemu-logo.frag`  
    https://github.com/xemu-project/xemu/blob/master/ui/shader/xemu-logo.frag  
    MSDF with outline effect using `screenPxDistance` offset.

### Analysis and Guides

11. **Fractolog** — "MSDF Fragment Shader Antialiasing" (2025)  
    https://www.fractolog.com/2025/01/msdf-fragment-shader-antialiasing/  
    Deep mathematical derivation of the `screenPxRange` formula; explains why `d/fwidth(d)` has NaN issues.

12. **Red Blob Games** — "Guide to SDF+MSDF Fonts" (2026)  
    https://www.redblobgames.com/articles/sdf-fonts/  
    Comprehensive practical guide; documents SDF vs MSDF tradeoffs; recommends SDF for glow/shadow effects.

### msdfgen Issue Tracker

13. **Issue #110**: Overlapping glyph contours produce positive values  
    https://github.com/Chlumsky/msdfgen/issues/110  
    Documents the exact overlapping-contour artifact pattern.

14. **Issue #81**: Combining MSDF with SDF encoded in the alpha channel  
    https://github.com/Chlumsky/msdfgen/issues/81  
    Original MTSDF feature request; documents SDF-for-shadows use case.

15. **Issue #74**: Glitches in MSDF  
    https://github.com/Chlumsky/msdfgen/issues/74  
    MSDF channel discontinuities near dense edge junctions.

16. **Discussion #141**: Correct way to use supersampling with MSDF textures  
    https://github.com/Chlumsky/msdfgen/discussions/141  
    Chlumsky's guidance on supersampling; clarifies that median should be computed per-sample, not on averaged texture values.
