# MSDF Runtime Scale Handling — Industry Practice Research

**Date**: 2026-04-05  
**Scope**: How professional MSDF/SDF implementations handle runtime scale, focusing on whether `screenPxRange`-style shader logic is standard practice.  
**Purpose**: Resolve the apparent contradiction between "MSDF is scalable" and "the shader must compute `screenPxRange` at runtime."  
**Prior context**: `docs/font/MSDF_ATLAS_SIZING_RESEARCH.md`, `.sisyphus/plans/msdf-single-scale-sizing-followup.md`

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [The Core Question](#2-the-core-question)
3. [msdfgen Reference Implementation](#3-msdfgen-reference-implementation)
4. [Unity / TextMeshPro](#4-unity--textmeshpro)
5. [Godot Engine](#5-godot-engine)
6. [Hazel Engine (TheCherno)](#6-hazel-engine-thecherno)
7. [LÖVR (VR Framework)](#7-lövr-vr-framework)
8. [Other Implementations Survey](#8-other-implementations-survey)
9. [Conceptual Analysis: pxRange vs screenPxRange](#9-conceptual-analysis-pxrange-vs-screenpxrange)
10. [Why screenPxRange Is Not a Contradiction to "MSDF Is Scalable"](#10-why-screenpxrange-is-not-a-contradiction-to-msdf-is-scalable)
11. [What Is Fixed at Atlas Time vs What Must Be Computed at Runtime](#11-what-is-fixed-at-atlas-time-vs-what-must-be-computed-at-runtime)
12. [Practical Takeaway for CrystalGraphics](#12-practical-takeaway-for-crystalgraphics)

---

## 1. Executive Summary

**Yes, every professional MSDF implementation uses runtime `screenPxRange`-style logic or its functional equivalent. This is not optional — it is the standard, universal approach.**

Every engine and framework examined — msdfgen's own reference, Unity/TextMeshPro, Godot, Hazel, LÖVR, Nuake, PixiJS, glitch, the Matrix digital rain effect, and the `awesome-msdf` collection — computes the relationship between the SDF's pixel range and the actual screen pixel coverage at render time, either per-fragment (using `fwidth()` derivatives) or per-vertex (using projection math).

This is not a deficiency or workaround. It is a fundamental requirement of correct SDF anti-aliasing, and it is precisely *how* MSDF achieves scale independence.

---

## 2. The Core Question

The user's confusion is understandable:

> "If MSDF is scalable, why do I need to compute anything at runtime? Shouldn't the atlas just work at any size?"

The answer requires separating two different concerns:

1. **Edge reconstruction** — MSDF stores *distance-to-edge*, which is continuous and resolution-independent. The shader reconstructs the exact edge at any scale by thresholding at `distance == 0.5`. This part is inherently scalable.

2. **Anti-aliasing width** — To produce smooth (not aliased) edges, the shader must blend pixels near the edge. The width of this blend zone must be exactly **one screen pixel** wide. But the number of screen pixels that correspond to one texel varies with render scale. The shader must compute this ratio at runtime.

The `screenPxRange` function answers the question: **"How many screen pixels does the stored distance field range span at the current render scale?"** Without this, the anti-aliasing is either too wide (blurry) or too narrow (aliased).

---

## 3. msdfgen Reference Implementation

### Source

- **Repository**: [github.com/Chlumsky/msdfgen](https://github.com/Chlumsky/msdfgen)
- **File**: `README.md`, "Using a multi-channel distance field" section
- **Author**: Viktor Chlumský (original MSDF inventor)

### The Reference Shader

The msdfgen README provides the canonical GLSL fragment shader:

```glsl
uniform float pxRange; // set to distance field's pixel range

float screenPxRange() {
    vec2 unitRange = vec2(pxRange) / vec2(textureSize(msdf, 0));
    vec2 screenTexSize = vec2(1.0) / fwidth(texCoord);
    return max(0.5 * dot(unitRange, screenTexSize), 1.0);
}

void main() {
    vec3 msd = texture(msdf, texCoord).rgb;
    float sd = median(msd.r, msd.g, msd.b);
    float screenPxDistance = screenPxRange() * (sd - 0.5);
    float opacity = clamp(screenPxDistance + 0.5, 0.0, 1.0);
    color = mix(bgColor, fgColor, opacity);
}
```

### Author's Own Commentary

From the README (emphasis original):

> Here, `screenPxRange()` represents the distance field range in output screen pixels. For example, if the pixel range was set to 2 when generating a 32×32 distance field, and it is used to draw a quad that is 72×72 pixels on the screen, it should return 4.5 (because 72/32 × 2 = 4.5). **For 2D rendering, this can generally be replaced by a precomputed uniform value.**
>
> For rendering in a **3D perspective only**, where the texture scale varies across the screen, you may want to implement this function with fragment derivatives.

### Key Observations

1. **The author of MSDF explicitly provides `screenPxRange()` as the reference approach.**
2. For 2D rendering, a CPU-side precomputed uniform is acceptable (because the atlas-to-screen ratio is constant per draw call).
3. For 3D/perspective rendering, the `fwidth()`-based derivative approach is required (because the ratio varies per fragment).
4. The `max(..., 1.0)` clamp prevents the anti-aliasing width from going sub-pixel, which would cause aliasing.

### Alternative Formulation (from thesis)

Chlumský's thesis also provides a simpler variant:

```glsl
float w = clamp(d / fwidth(d) + 0.5, 0.0, 1.0);
```

This is mathematically equivalent but has a practical problem: `fwidth(d)` approaches zero in constant-distance regions, producing NaN via 0/0. The `screenPxRange()` formulation avoids this by computing the derivative of the UV coordinates rather than the distance value itself, which is more numerically stable. (See Fractolog blog analysis below.)

---

## 4. Unity / TextMeshPro

### Source

- **Repository**: [github.com/UnityTechnologies/Presentation](https://github.com/UnityTechnologies/Presentation)
- **File**: `TextMesh Pro/Resources/Shaders/TMP_SDF-Mobile Overlay.shader` (and other TMP_SDF-*.shader variants)
- **Also found in**: [github.com/UnityTechnologies/InputSystem_Warriors](https://github.com/UnityTechnologies/InputSystem_Warriors) (same shader set)

### How TMP Does It

TextMeshPro uses a **vertex shader approach** rather than a per-fragment `fwidth()` approach. The key computation is:

```hlsl
// In the vertex shader:
float4 vPosition = UnityObjectToClipPos(vert);

float2 pixelSize = vPosition.w;
pixelSize /= float2(_ScaleX, _ScaleY) * abs(mul((float2x2)UNITY_MATRIX_P, _ScreenParams.xy));

float scale = rsqrt(dot(pixelSize, pixelSize));
scale *= abs(input.texcoord1.y) * _GradientScale * 1.5;

if (UNITY_MATRIX_P[3][3] == 0)
    scale = lerp(abs(scale) * (1 - _PerspectiveFilter), scale,
                 abs(dot(UnityObjectToWorldNormal(input.normal.xyz),
                         normalize(WorldSpaceViewDir(vert)))));
```

Where:
- **`_GradientScale`** is the SDF atlas's pixel range (equivalent to `pxRange`) — this is the "Gradient Scale" property visible in TMP's Font Asset inspector, set at atlas generation time
- **`input.texcoord1.y`** encodes per-glyph scale information (glyph-to-atlas size ratio)
- **`pixelSize`** is derived from the projection matrix, representing how many screen pixels one unit of the quad spans
- **`scale`** is the final screen-pixel-range value, passed to the fragment shader as an interpolated varying

### Fragment Shader Usage

The fragment shader then uses the interpolated `scale` (stored as `input.param.x`) to compute the signed distance in screen-pixel units:

```hlsl
// In the fragment shader:
half d = tex2D(_MainTex, input.texcoord0.xy).a * input.param.x;
half4 c = input.faceColor * saturate(d - input.param.w);
```

### Key Differences from msdfgen Reference

| Aspect | msdfgen reference | TextMeshPro |
|--------|------------------|-------------|
| **Where scale is computed** | Fragment shader (`fwidth`) | Vertex shader (projection math) |
| **SDF type** | MSDF (multi-channel) | SDF (single-channel alpha) |
| **Per-fragment accuracy** | Yes (per-fragment derivatives) | No (interpolated from vertices) |
| **Perspective correction** | Inherent via `fwidth` | Explicit `_PerspectiveFilter` lerp |
| **Performance** | Slightly higher (derivative evaluation per fragment) | Slightly lower (computation per vertex only) |
| **Parameter name** | `pxRange` / `screenPxRange` | `_GradientScale` / `scale` |

### Critical Insight: Same Concept, Different Implementation

TMP's `_GradientScale * pixelSize` computation is **functionally identical** to msdfgen's `screenPxRange()`. Both answer the same question: "how many screen pixels does the SDF's distance range span?" TMP just computes it per-vertex using projection math instead of per-fragment using `fwidth()`.

**TMP also uses only SDF (single-channel), not MSDF.** The `_GradientScale` property is the SDF's pixel range baked into the font asset at generation time. At runtime, TMP multiplies this by the screen-to-texel ratio to get the effective screen pixel range. This is the exact same logical operation as `screenPxRange()`.

### TMP's `_ScaleRatioA`

TMP adds a further refinement: `_ScaleRatioA` is a precomputed ratio that normalizes effect parameters (outline width, softness, etc.) against the atlas's sampling density. This ensures effects remain visually consistent across different `_GradientScale` values. It is a higher-level convenience but relies on the same underlying screen-pixel-range computation.

### Source URLs

- Shader source: `https://github.com/UnityTechnologies/Presentation/blob/master/TextMesh%20Pro/Resources/Shaders/TMP_SDF-Mobile%20Overlay.shader`
- Also: `https://github.com/UnityTechnologies/InputSystem_Warriors/blob/master/InputSystem_Warriors_Project/Assets/TextMesh%20Pro/Resources/Shaders/TMP_SDF-Mobile.shader`

---

## 5. Godot Engine

### Source

- **Repository**: [github.com/godotengine/godot](https://github.com/godotengine/godot)
- **File**: `servers/rendering/renderer_rd/shaders/canvas.glsl` (Vulkan/RD renderer, canvas 2D path)
- **Also**: `scene/resources/font.h`, `scene/resources/font.cpp`, `editor/import/resource_importer_dynamic_font.cpp`

### How Godot Does It

Godot uses MSDF (via msdfgen integration) for font rendering starting in Godot 4.x. The canvas shader contains the MSDF rendering path with a derivative-based `screenPxRange` computation that is nearly character-for-character identical to msdfgen's reference:

```glsl
// In canvas.glsl, fragment shader, MSDF path:
if (sc_use_msdf()) {
    float px_range = params.msdf.x;
    float outline_thickness = params.msdf.y;

    vec4 msdf_sample = texture(sampler2D(color_texture, texture_sampler), uv);
    vec2 msdf_size = vec2(textureSize(sampler2D(color_texture, texture_sampler), 0));
    vec2 dest_size = vec2(1.0) / fwidth(uv);
    float px_size = max(0.5 * dot((vec2(px_range) / msdf_size), dest_size), 1.0);
    float d = msdf_median(msdf_sample.r, msdf_sample.g, msdf_sample.b);

    if (outline_thickness > 0) {
        float cr = clamp(outline_thickness, 0.0, (px_range / 2.0) - 1.0) / px_range;
        d = min(d, msdf_sample.a);
        float a = clamp((d - 0.5 + cr) * px_size, 0.0, 1.0);
        color.a = a * color.a;
    } else {
        float a = clamp((d - 0.5) * px_size + 0.5, 0.0, 1.0);
        color.a = a * color.a;
    }
}
```

### Mapping to msdfgen's Reference

| Godot name | msdfgen equivalent | Description |
|------------|-------------------|-------------|
| `px_range` (from `params.msdf.x`) | `pxRange` | Generation-time pixel range, passed as uniform |
| `msdf_size` | `textureSize(msdf, 0)` | Atlas texture dimensions |
| `dest_size` (= `1.0/fwidth(uv)`) | `screenTexSize` | Screen pixels per UV unit |
| `px_size` | `screenPxRange()` return value | Effective screen pixel range |
| `msdf_median()` | `median()` | Standard MSDF median operation |

**Godot's implementation is a near-verbatim copy of the msdfgen reference `screenPxRange()` function.**

### Godot's MSDF Generation Configuration

From `scene/resources/font.h`:
```cpp
int msdf_pixel_range = 16;  // Default: 16 pixels
int msdf_size = 48;          // Default: 48 px/EM atlas generation size
```

From `editor/import/resource_importer_dynamic_font.cpp`:
```cpp
r_options->push_back(ImportOption(PropertyInfo(Variant::INT, "msdf_pixel_range", 
    PROPERTY_HINT_RANGE, "1,100,1"), 8));   // Import default: 8
r_options->push_back(ImportOption(PropertyInfo(Variant::INT, "msdf_size",
    PROPERTY_HINT_RANGE, "1,250,1"), 48));  // Import default: 48
```

### Godot's Model: Single Atlas, Runtime Scaling

Godot generates MSDF at a single `msdf_size` (48 px/EM default) and renders at any requested size using `px_size` (= `screenPxRange`) to scale the anti-aliasing. The `msdf_pixel_range` (default 8 or 16) is passed as a uniform and used at runtime to compute the correct screen-space distance.

This is exactly the "single atlas per font, runtime screenPxRange" model that CrystalGraphics' follow-up plan proposes.

### Source URLs

- Canvas shader: `https://github.com/godotengine/godot/blob/master/servers/rendering/renderer_rd/shaders/canvas.glsl`
- Font header: `https://github.com/godotengine/godot/blob/master/scene/resources/font.h`
- Font import: `https://github.com/godotengine/godot/blob/master/editor/import/resource_importer_dynamic_font.cpp`

---

## 6. Hazel Engine (TheCherno)

### Source

- **Repository**: [github.com/TheCherno/Hazel](https://github.com/TheCherno/Hazel)
- **File**: `Hazelnut/assets/shaders/Renderer2D_Text.glsl`
- **License**: Apache-2.0

### Implementation

Hazel uses a near-verbatim copy of msdfgen's reference shader:

```glsl
float screenPxRange() {
    const float pxRange = 2.0; // set to distance field's pixel range
    vec2 unitRange = vec2(pxRange) / vec2(textureSize(u_FontAtlas, 0));
    vec2 screenTexSize = vec2(1.0) / fwidth(Input.TexCoord);
    return max(0.5 * dot(unitRange, screenTexSize), 1.0);
}

void main() {
    vec3 msd = texture(u_FontAtlas, Input.TexCoord).rgb;
    float sd = median(msd.r, msd.g, msd.b);
    float screenPxDistance = screenPxRange() * (sd - 0.5);
    float opacity = clamp(screenPxDistance + 0.5, 0.0, 1.0);
    if (opacity == 0.0) discard;

    vec4 bgColor = vec4(0.0);
    o_Color = mix(bgColor, Input.Color, opacity);
}
```

### Key Note

Hazel hardcodes `pxRange = 2.0` as a constant rather than passing it as a uniform. This works because the engine uses a fixed `pxRange` at atlas generation time. The `screenPxRange()` function is otherwise identical to the msdfgen reference.

### Source URL

- `https://github.com/TheCherno/Hazel/blob/master/Hazelnut/assets/shaders/Renderer2D_Text.glsl`

---

## 7. LÖVR (VR Framework)

### Source

- **Repository**: [github.com/bjornbytes/lovr](https://github.com/bjornbytes/lovr)
- **File**: `etc/shaders/font.frag`
- **License**: MIT

### Implementation

LÖVR uses a clean, minimal MSDF shader with `screenPxRange()`:

```glsl
float screenPxRange() {
    vec2 screenTexSize = vec2(1.) / fwidth(UV);
    return max(.5 * dot(Material.sdfRange, screenTexSize), 1.);
}

vec4 lovrmain() {
    vec3 msdf = getPixel(ColorTexture, UV).rgb;
    float sdf = median(msdf.r, msdf.g, msdf.b);
    float screenPxDistance = screenPxRange() * (sdf - .5);
    float alpha = clamp(screenPxDistance + .5, 0., 1.);
    if (alpha <= 0.) discard;
    return vec4(Color.rgb, Color.a * alpha);
}
```

### Key Note

LÖVR precomputes the `unitRange` (= `pxRange / textureSize`) as a material property (`Material.sdfRange`), following msdfgen's performance recommendation. The `screenPxRange()` function still uses `fwidth()` derivatives at runtime.

### Source URL

- `https://github.com/bjornbytes/lovr/blob/dev/etc/shaders/font.frag`

---

## 8. Other Implementations Survey

### Nuake Engine

**File**: `Data/Shaders/sdf_text.shader` and `Data/Shaders/ui_text.shader`  
**Source**: [github.com/antopilo/Nuake](https://github.com/antopilo/Nuake)

Uses the same `screenPxRange()` pattern with `pxRange` as a uniform:

```glsl
uniform float pxRange;
float screenPxRange(vec2 coord) {
    vec2 unitRange = vec2(pxRange) / vec2(textureSize(msdf, 0));
    vec2 screenTexSize = vec2(1.0) / fwidth(coord);
    return max(0.5 * dot(unitRange, screenTexSize), 1.0);
}
```

### Glitch (Go Game Framework)

**File**: `shaders/msdf.fs`  
**Source**: [github.com/unitoftime/glitch](https://github.com/unitoftime/glitch)

Uses the same pattern with hardcoded `distanceRange = 10.0`.

### Matrix Digital Rain (Rezmason)

**File**: `shaders/glsl/rainPass.frag.glsl`  
**Source**: [github.com/Rezmason/matrix](https://github.com/Rezmason/matrix)

Uses the same pattern for rendering MSDF glyphs in a Matrix-style effect:

```glsl
vec2 unitRange = vec2(msdfPxRange) / glyphMSDFSize;
vec2 screenTexSize = vec2(1.0) / fwidth(uv);
float screenPxRange = max(0.5 * dot(unitRange, screenTexSize), 1.0);
```

### awesome-msdf (MSDF Reference Collection)

**File**: Multiple shaders in `shaders/` directory  
**Source**: [github.com/Blatko1/awesome-msdf](https://github.com/Blatko1/awesome-msdf)

Every shader in this collection uses `screenPxRange()` with `fwidth()`. This is a curated educational repository, and the author treats `screenPxRange()` as the canonical approach.

### Godot's 3D Material Path (BaseMaterial3D)

**File**: `scene/resources/material.cpp`  
**Source**: [github.com/godotengine/godot](https://github.com/godotengine/godot)

Godot's `BaseMaterial3D` also supports MSDF for 3D material albedo textures (not just fonts). The same `msdf_pixel_range` and `fwidth()`-based approach is used in the generated 3D shader code. This means Godot uses `screenPxRange` logic in *both* its 2D canvas renderer and its 3D spatial renderer.

### Summary Table

| Engine/Framework | `screenPxRange` equivalent? | Where computed | `pxRange` source | SDF type |
|-----------------|---------------------------|----------------|-----------------|----------|
| **msdfgen reference** | `screenPxRange()` | Fragment (fwidth) | Uniform | MSDF |
| **Unity/TextMeshPro** | `scale = pixelSize * _GradientScale` | Vertex (projection math) | Font asset property | SDF |
| **Godot Engine** | `px_size = max(0.5 * dot(...), 1.0)` | Fragment (fwidth) | Canvas uniform | MSDF |
| **Hazel Engine** | `screenPxRange()` | Fragment (fwidth) | Hardcoded constant | MSDF |
| **LÖVR** | `screenPxRange()` | Fragment (fwidth) | Material property | MSDF |
| **Nuake** | `screenPxRange()` | Fragment (fwidth) | Uniform | MSDF |
| **Glitch** | `screenPxRange()` | Fragment (fwidth) | Hardcoded constant | MSDF |
| **Matrix (Rezmason)** | Inline equivalent | Fragment (fwidth) | Uniform | MSDF |
| **awesome-msdf** | `screenPxRange()` | Fragment (fwidth) | Hardcoded or uniform | MSDF |

**Count: 9 out of 9 implementations use screenPxRange or its functional equivalent.**

The only variation is *where* the computation happens (vertex vs fragment) and *how* `pxRange` is supplied (uniform vs constant). The mathematical operation is always the same.

---

## 9. Conceptual Analysis: pxRange vs screenPxRange

### pxRange — Fixed at Atlas Generation Time

`pxRange` (also called "pixel range", "distance range", or "gradient scale") is a **generation-time constant**. It defines how many **texels** in the atlas texture are used to encode the signed distance field's transition zone around each glyph edge.

Example: With `pxRange = 4` and a 32×32 glyph cell, the outermost 4 texels around each glyph edge encode distance values from -1.0 to +1.0 (with 0.5 being the exact edge). The remaining texels are either fully inside (value ≈ 1.0) or fully outside (value ≈ 0.0).

**pxRange is baked into the atlas. It cannot change at runtime.**

### screenPxRange — Computed at Render Time

`screenPxRange` answers: **"At the current render scale, how many screen pixels does the atlas's `pxRange` span?"**

The formula is:

```
screenPxRange = pxRange × (screenPixelsPerGlyphQuad / atlasTexelsPerGlyphCell)
```

Or equivalently, using derivatives:

```
unitRange = pxRange / atlasTextureSize
screenTexSize = 1.0 / fwidth(uv)
screenPxRange = 0.5 * dot(unitRange, screenTexSize)
```

### Why the Distinction Matters

| Scenario | pxRange | Atlas texels per glyph | Screen pixels per glyph | screenPxRange | Anti-aliasing quality |
|----------|---------|----------------------|------------------------|---------------|---------------------|
| 1:1 scale | 4 | 48 | 48 | 4.0 | Correct (1px AA band) |
| 2× upscale | 4 | 48 | 96 | 8.0 | Correct (slightly over-smoothed, still good) |
| 0.5× downscale | 4 | 48 | 24 | 2.0 | Correct (tight AA band) |
| 0.25× downscale | 4 | 48 | 12 | 1.0 | Minimum viable (clamped by `max(..., 1.0)`) |
| 0.1× downscale | 4 | 48 | ~5 | 0.4 → clamped to 1.0 | Aliased (SDF resolution limit) |

Without `screenPxRange`, the shader would have to assume a fixed relationship between atlas texels and screen pixels. At 2× upscale, the AA band would be half the correct width (too sharp/aliased). At 0.5× downscale, it would be twice the correct width (too blurry).

---

## 10. Why screenPxRange Is Not a Contradiction to "MSDF Is Scalable"

This is the key conceptual insight.

### What "MSDF is scalable" means

MSDF encodes the signed distance to the glyph edge as a continuous function sampled at texel centers. When the shader thresholds at `d == 0.5`, it reconstructs the edge at whatever resolution the output is rendered at. **The edge position itself is resolution-independent.** This is why MSDF text stays sharp at arbitrary scales — the edge never "pixelates" like a bitmap would.

### What "MSDF is scalable" does NOT mean

It does **not** mean the shader can ignore how many screen pixels correspond to one atlas texel. MSDF stores *distances*, not *opacities*. To convert a distance into a visible anti-aliased pixel, the shader must decide: "How wide should the blend zone around the edge be?"

The correct answer is always "about 1 screen pixel wide." But the shader doesn't inherently know what "1 screen pixel" is in atlas-texel units. That depends on the render scale.

### The Analogy

Think of a topographic map with contour lines:

- The contour lines (edges) are resolution-independent — they represent the same mountains regardless of how much you zoom in.
- But if you want to *draw* those contour lines with a pen, the pen width should be a fixed physical thickness (say, 0.3mm), not a fixed fraction of the map.
- The pen width in "map units" changes as you zoom: zoomed in → pen is narrow in map units; zoomed out → pen is wide in map units.

`screenPxRange` is the "pen width in map units." The map itself (the SDF) is scalable, but the pen width must be adapted to the current view.

### Why This Is Not a Deficiency

The runtime computation is:
1. **Cheap**: One `fwidth()` call plus a dot product per fragment (or per vertex in TMP's approach).
2. **Automatic**: No CPU involvement needed. The shader derives everything from the UV coordinates.
3. **Required for correctness**: Without it, you'd either need separate atlases per render size (bitmap approach) or accept broken anti-aliasing.

The alternative — generating a separate atlas for each render size — would require N atlases for N sizes, N times the memory, and N times the generation time. That's the bitmap font approach. MSDF avoids all of that by deferring only the anti-aliasing width computation to runtime.

---

## 11. What Is Fixed at Atlas Time vs What Must Be Computed at Runtime

### Fixed at Atlas Generation Time

| Property | Description | Can change at runtime? |
|----------|-------------|----------------------|
| **Distance field values** | The encoded signed distances in each texel | No |
| **pxRange** | How many texels encode the transition zone | No |
| **Atlas scale (px/EM)** | Resolution of the glyph cells | No |
| **Glyph placement / UV coords** | Where each glyph sits in the atlas | No |
| **Edge positions** (implicit) | The 0.5-threshold contour | No (it's inherent in the data) |

### Must Be Computed at Runtime

| Property | Description | How computed |
|----------|-------------|-------------|
| **screenPxRange** | How many screen pixels the SDF range spans | `pxRange × (screenSize / atlasSize)` via `fwidth()` or projection math |
| **Edge opacity** | Whether a fragment is inside, outside, or on the edge | `clamp(screenPxRange * (d - 0.5) + 0.5, 0, 1)` |
| **Anti-aliasing band width** | How many screen pixels of blend zone surround the edge | Determined by `screenPxRange` |

### The Key Takeaway

**The SDF data is scale-independent. The anti-aliasing is not.** Anti-aliasing always requires knowledge of the screen pixel grid, which is inherently a runtime property. `screenPxRange` is the bridge between the scale-independent distance field and the scale-dependent screen pixel grid.

---

## 12. Practical Takeaway for CrystalGraphics

### CrystalGraphics' Current State

Per the prior research and the follow-up plan, CrystalGraphics' MSDF fragment shader **already** computes `screenPxRange` correctly using the canonical `fwidth()`-based approach:

```glsl
// From CrystalGraphics' msdf_text.frag (existing implementation)
// u_pxRange / textureSize(u_atlas, 0)
// 1.0 / fwidth(v_uv)
```

This is correct and should not be changed.

### Confirmation: CrystalGraphics Is Already Aligned With Industry Practice

1. **The derivative-based `screenPxRange` shader is standard.** CrystalGraphics uses it. Every other implementation uses it. This is confirmed correct.

2. **The shader does not need CPU-side `screenPxRange` computation.** The `fwidth()` approach automatically handles varying render scales, perspective projection, and even non-uniform scaling. This is explicitly what the msdfgen author recommends for 3D rendering.

3. **`u_pxRange` should remain the generation-time constant (4.0).** This is the uniform that the shader needs to know. The shader combines it with `textureSize` and `fwidth(uv)` to derive the screen-space range. There is no need to precompute a "screen pixel range" on the CPU.

### What the Follow-Up Plan Should Preserve

The follow-up plan (`.sisyphus/plans/msdf-single-scale-sizing-followup.md`) already states:

> "The existing derivative-based `screenPxRange` shader path remains correct and unchanged in principle."
> "Do **not** add CPU-side `screenPxRange` computation."
> "Do **not** replace `fwidth`-based `screenPxRange` logic."

**This is confirmed correct by industry-wide evidence.** No engine computes `screenPxRange` on the CPU for the MSDF path. The shader does it.

### What the Single-Atlas Model Gets Right

The single-atlas-per-font model (generate at one scale, render at many) is exactly how Godot, TextMeshPro, Hazel, LÖVR, and every other implementation works:

- **Godot**: `msdf_size = 48`, renders at any size, `px_range` passed as uniform → shader computes `px_size` via `fwidth()`
- **TextMeshPro**: `_GradientScale` baked into font asset, renders at any size, vertex shader computes scale from projection matrix
- **Hazel**: `pxRange = 2.0` hardcoded, renders at any size, `screenPxRange()` via `fwidth()`

CrystalGraphics' plan to decouple MSDF atlas identity from render target size and use a fixed atlas scale (48 px/EM) is standard practice. The shader's `screenPxRange` computation is what makes this work.

### For 2D Rendering (Minecraft UI): A Precomputed Uniform Is Acceptable

For purely 2D rendering (Minecraft's GUI layer, chat, scoreboard), the atlas-to-screen ratio is constant per draw call. The msdfgen author explicitly notes:

> "For 2D rendering, this can generally be replaced by a precomputed uniform value."

CrystalGraphics could optionally compute `screenPxRange` as a CPU-side uniform for the 2D UI path:

```java
float screenPxRange = pxRange * (renderSizePx / atlasGlyphSizePx);
```

However, the `fwidth()` approach works for both 2D and 3D and is not meaningfully slower on modern hardware. **There is no strong reason to add a separate 2D path** unless profiling reveals that `fwidth()` is a bottleneck, which is extremely unlikely.

### For 3D World-Space Text: fwidth() Is Required

CrystalGraphics' world-space text rendering (`CgWorldTextRenderContext`) renders text in 3D perspective where the atlas-to-screen ratio varies per fragment. The `fwidth()` approach is mandatory here. There is no alternative.

### Final Recommendation

| Aspect | Recommendation | Confidence |
|--------|---------------|------------|
| Keep `fwidth()`-based `screenPxRange` in shader | **Yes** | Very high (universal practice) |
| Add CPU-side `screenPxRange` | **No** (unnecessary) | Very high |
| Upload `pxRange` as uniform | **Yes** (already done) | Very high |
| Upload `textureSize` as uniform | **Optional** (can use `textureSize()` GLSL function, but uniform avoids the function call on old hardware) | Medium |
| Single MSDF atlas per font at fixed scale | **Yes** (Godot does 48, TMP does configurable) | Very high |
| Separate atlas per render size | **No** (defeats MSDF purpose) | Very high |

---

## Appendix A: The Fractolog Explanation

The blog post at [fractolog.com/2025/01/msdf-fragment-shader-antialiasing/](https://www.fractolog.com/2025/01/msdf-fragment-shader-antialiasing/) provides an excellent step-by-step mathematical derivation of why `screenPxRange()` works. Key points:

1. **`fwidth(uv)` measures how fast UV coordinates change across adjacent screen pixels.** If one screen pixel corresponds to 1/72 of the UV range, then `fwidth(uv.x) ≈ 1/72` and `1/fwidth(uv.x) ≈ 72`.

2. **`unitRange = pxRange / textureSize` converts the pixel range to UV space.** If `pxRange = 2` and `textureSize = 32`, then `unitRange = 2/32 = 1/16`.

3. **`dot(unitRange, screenTexSize)` combines both dimensions.** For a 32×32 texture rendered at 72×72: `0.5 * dot(vec2(1/16), vec2(72, 72)) = 0.5 * (4.5 + 4.5) = 4.5`.

4. **The `d/fwidth(d)` alternative (from Chlumský's thesis) is mathematically equivalent but numerically inferior.** In constant-distance regions, `fwidth(d)` approaches zero, causing NaN. The UV-derivative approach avoids this because UV coordinates always change across fragments.

## Appendix B: Source Reference Table

| Source | URL | Key Evidence |
|--------|-----|-------------|
| msdfgen README | `https://github.com/Chlumsky/msdfgen/blob/master/README.md` | Canonical `screenPxRange()` function and explanation |
| TMP Shader (Mobile Overlay) | `https://github.com/UnityTechnologies/Presentation/blob/master/TextMesh%20Pro/Resources/Shaders/TMP_SDF-Mobile%20Overlay.shader` | Vertex-shader scale computation using `_GradientScale` |
| Godot canvas.glsl | `https://github.com/godotengine/godot/blob/master/servers/rendering/renderer_rd/shaders/canvas.glsl` | Fragment-shader `px_size` computation identical to `screenPxRange()` |
| Godot font.h | `https://github.com/godotengine/godot/blob/master/scene/resources/font.h` | `msdf_pixel_range = 16`, `msdf_size = 48` defaults |
| Godot font importer | `https://github.com/godotengine/godot/blob/master/editor/import/resource_importer_dynamic_font.cpp` | Import defaults: `msdf_pixel_range = 8`, `msdf_size = 48` |
| Hazel Text Shader | `https://github.com/TheCherno/Hazel/blob/master/Hazelnut/assets/shaders/Renderer2D_Text.glsl` | Verbatim `screenPxRange()` |
| LÖVR font.frag | `https://github.com/bjornbytes/lovr/blob/dev/etc/shaders/font.frag` | `screenPxRange()` with precomputed `sdfRange` |
| Nuake sdf_text.shader | `https://github.com/antopilo/Nuake/blob/main/Data/Shaders/sdf_text.shader` | `screenPxRange()` with `pxRange` uniform |
| Matrix digital rain | `https://github.com/Rezmason/matrix/blob/master/shaders/glsl/rainPass.frag.glsl` | Inline `screenPxRange` computation |
| awesome-msdf collection | `https://github.com/Blatko1/awesome-msdf/blob/main/shaders/basic.frag` | Multiple `screenPxRange()` examples |
| Fractolog blog | `https://www.fractolog.com/2025/01/msdf-fragment-shader-antialiasing/` | Mathematical derivation of why `screenPxRange()` works |
| Godot material.cpp | `https://github.com/godotengine/godot/blob/master/scene/resources/material.cpp` | MSDF support in 3D materials with same pattern |
