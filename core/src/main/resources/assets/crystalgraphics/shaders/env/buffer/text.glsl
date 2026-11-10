// One layer of a text style on one glyph quad: its coverage, times a paint (CgTextBatch). What text.shader draws with,
// and what a Text-domain material calls with its own paint, as Slate applies a font material's colour under the
// glyph's coverage (SlateElementPixelShader.usf GetSdfTextElementColor).
//
// A quad's slots (cg_use quad), as CgTextBatch writes them:
//   CG_QUAD_COLOR   the layer's colour: the paint's (or white under a gradient or material) times the draw's tint
//                   and the layer's opacity
//   CG_QUAD_CUSTOM0 a fill or outline: (a, b, c, row) with row > 0, the gradient t = a + b*u + c*v over the quad's
//                   corners, row its ramp in _Gradients; a material layer's (u0, v0, du, dv) of the run; 0 for none
//                   a shadow: its kind as a negative w (below)
//   CG_QUAD_CUSTOM1 (width in atlas texels, align, ring, pxRange)
//                   align: 0 centred, 1 outside, 2 inside; + 3 for a round join      @see CgTextLayer.Align, Join
//                   ring:  1 draws the ring alone (an outline over a fill); 0 the grown glyph
//                   pxRange: the range the glyph's own atlas band was made at        @see CgMsdfAtlasConfig#WIDE_PX_RANGE
//   CG_QUAD_CUSTOM2 atlas texels per em, for CgTextGamma's size fade; 0 except on a fill
// A width in texels is stretched with the glyph by an anisotropic transform.
//
// A shadow's -kind in custom0.w; its opacity is the colour's alpha:
//   kind  mode    custom0                            custom1                                      quad
//   -1    field   (unused)                           (strokeTexels, align, spreadTexels, pxRange) glyph, offset
//   -2    field   (offsetU, offsetV, sigmaTexels)    (strokeTexels, align, spreadTexels, pxRange) glyph
//   -3    bitmap  (unused)                           (unused)                                     the cell
//   -4    either  (invSixSigmaU, invSixSigmaV, 0)    inset rect (L, T, R, B)                      rect + 3 sigma
//   -5    either  (offsetU, offsetV, invSixSigmaU)   (invSixSigmaV, spreadU, spreadV, 0)          the rect
// -4 and -5 sample nothing and are tested first. @see CgTextShadowPlan
//
// The text token's env file (cg_use text, which brings quad and clip): included after their declarations, which a
// material's own #include is not. MSDF_MODE is the shader's own feature.

#include "crystalgraphics:shaders/lib/texel.glsl"
#include "crystalgraphics:shaders/lib/rect_blur.glsl"
#include "crystalgraphics:shaders/lib/text_gamma.glsl"

// Fragment-only: derivatives. Guarded by the stages that cannot have them.
#if !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE)

/** Where this fragment falls along a fill's or outline's gradient, 0 to 1; param is the quad's corner. */
float cg_text_gradient_t(vec2 param) {
    return clamp(CG_QUAD_CUSTOM0.x + CG_QUAD_CUSTOM0.y * param.x + CG_QUAD_CUSTOM0.z * param.y, 0.0, 1.0);
}

/** A material layer's 0 to 1 across the whole text (Slate's font-material UVs); 0 on a shadow. */
vec2 cg_text_run_uv(vec2 param) {
    return CG_QUAD_CUSTOM0.w < 0.0 ? vec2(0.0) : CG_QUAD_CUSTOM0.xy + param * CG_QUAD_CUSTOM0.zw;
}

/** The glyph's signed distance at uv, 0.5 on its edge, more inside: a distance-field atlas's median. */
float cg_text_distance(sampler2DArray atlas, vec2 uv, float atlasLayer) {
    vec3 f = texture(atlas, vec3(uv, atlasLayer)).rgb;
    return max(min(f.r, f.g), min(max(f.r, f.g), f.b));
}

/**
 * The layer on this fragment, premultiplied: paint (straight alpha) under the quad's coverage. Discards what it
 * covers nothing of.
 */
vec4 cg_text_layer(sampler2DArray atlas, vec2 uv, float atlasLayer, vec2 param, vec4 paint, vec4 gammaSmall,
                   vec4 gammaLarge) {
    // Before any discard: it takes derivatives.
    float boxClip = CG_CLIP_QUAD_COVERAGE;
    vec3 uvw = vec3(uv, atlasLayer);
    float shadowKind = CG_QUAD_CUSTOM0.w;
    float coverage;
    if (shadowKind < -3.5) {
        coverage = shadowKind > -4.5
                ? rect_shadow_outer(param, CG_QUAD_CUSTOM0.xy, CG_QUAD_CUSTOM1)
                : rect_shadow_inset(param, CG_QUAD_CUSTOM0.xy, vec2(CG_QUAD_CUSTOM0.z, CG_QUAD_CUSTOM1.x),
                                    CG_QUAD_CUSTOM1.yz);
        float a = paint.a * coverage;
        if (a <= (1.0 / 255.0)) discard;
        return vec4(paint.rgb * a, a) * boxClip;
    }
#ifdef MSDF_MODE
    vec4 fieldSample = texture(atlas, uvw);
    vec3 field = fieldSample.rgb;
    float signedDistance = max(min(field.r, field.g), min(max(field.r, field.g), field.b));

    vec4 strokeParams = CG_QUAD_CUSTOM1;
    float pxRange = strokeParams.w;
    vec2 atlasSize = vec2(textureSize(atlas, 0).xy);

    // The Jacobian, not fwidth: one screenPxRange for both axes is wrong under scale, skew and rotation. Direction
    // from the field's gradient, magnitude from the uv Jacobian, as Skia's GrDistanceFieldA8TextGeoProc does for
    // non-similarity transforms.
    vec2 distGrad = vec2(dFdx(signedDistance), dFdy(signedDistance));
    float gradLenSq = dot(distGrad, distGrad);
    distGrad = gradLenSq < 1.0e-8 ? vec2(0.70710678) : distGrad * inversesqrt(gradLenSq);
    vec2 jdx = dFdx(uv) * atlasSize;   // texels crossed per screen px in x
    vec2 jdy = dFdy(uv) * atlasSize;
    vec2 texelStep = vec2(distGrad.x * jdx.x + distGrad.y * jdy.x, distGrad.x * jdx.y + distGrad.y * jdy.y);
    float screenPxRange = max(pxRange / max(length(texelStep), 1.0e-6), 1.0);
    float screenPxDist = screenPxRange * (signedDistance - 0.5);
    float opacity = clamp(screenPxDist + 0.5, 0.0, 1.0);

    // What the field describes past the edge, with a texel of headroom (a bilinear tap reads half a texel either side;
    // Skia's SK_DistanceFieldInset), or a screen pixel when minified; in field units, so an anisotropic transform
    // cannot push a width past it. @see CgStrokeFieldRangeTest, CgAnisotropicFieldRangeTest
    float reachField = max(0.5 - max(1.0 / max(screenPxRange, 1.0e-6), 1.0 / max(pxRange, 1.0e-6)), 0.0);
    float widthField = strokeParams.x / max(pxRange, 1.0e-6);
    float roundJoin = step(2.5, strokeParams.y);
    float align = strokeParams.y - 3.0 * roundJoin;
    float outward = align < 0.5 ? widthField * 0.5 : align < 1.5 ? widthField : 0.0;
    float inward = widthField - outward;

    if (shadowKind < -0.5) {
        float spreadField = strokeParams.z / max(pxRange, 1.0e-6);
        if (shadowKind > -1.5) {
            // Outer: the glyph (with its outline's outer edge, outlined) grown by the spread along the true distance.
            float edge = roundJoin > 0.5 && outward > 0.0 ? screenPxRange * (fieldSample.a - 0.5) : screenPxDist;
            coverage = clamp(edge + min(outward, reachField) * screenPxRange + 0.5, 0.0, 1.0);
            if (spreadField > 0.0) {
                float grown = min(outward + spreadField, reachField);
                coverage = max(coverage, clamp(screenPxRange * (fieldSample.a - 0.5) + grown * screenPxRange + 0.5,
                                               0.0, 1.0));
            }
        } else {
            // Inset: inside the glyph (less its outline's inner part), the canvas outside a hole of the glyph shrunk
            // by the outline and spread, seen through the offset.
            float clipCoverage = clamp(screenPxDist - min(inward, reachField) * screenPxRange + 0.5, 0.0, 1.0);
            vec2 holeUv = uv - CG_QUAD_CUSTOM0.xy;
            vec4 cellRect = CG_QUAD_UV_RECT;
            float holeCoverage = 0.0;
            if (all(greaterThanEqual(holeUv, cellRect.xy)) && all(lessThanEqual(holeUv, cellRect.zw))) {
                vec4 holeField = texture(atlas, vec3(holeUv, atlasLayer));
                float shrink = min(inward + spreadField, reachField);
                float holeSigmaTexels = CG_QUAD_CUSTOM0.z;
                if (holeSigmaTexels > 0.0) {
                    // A blurred hole: a Gaussian past the shrunk edge from the true distance, with half a screen
                    // pixel of antialiasing added in quadrature.
                    float holeTexels = (holeField.a - 0.5 - shrink) * pxRange;
                    float aaTexels = 0.5 * pxRange / max(screenPxRange, 1.0e-6);
                    float holeSigma = sqrt(holeSigmaTexels * holeSigmaTexels + aaTexels * aaTexels);
                    holeCoverage = 0.5 * (1.0 + rect_blur_erf(holeTexels / (holeSigma * 1.41421356)));
                } else {
                    float holeDistance = spreadField > 0.0 ? holeField.a
                            : max(min(holeField.r, holeField.g), min(max(holeField.r, holeField.g), holeField.b));
                    holeCoverage = clamp(screenPxRange * (holeDistance - 0.5) - shrink * screenPxRange + 0.5, 0.0, 1.0);
                }
            }
            coverage = clipCoverage * (1.0 - holeCoverage);
        }
    } else if (strokeParams.x > 0.0) {
        // An outline: the glyph grown to the outer edge, or the ring alone over a fill. The ring is a difference of two
        // coverages, not a band test, so a sub-pixel outline fades instead of flickering. Only a round join's outer
        // edge reads the true distance (MTSDF's alpha), which rounds a corner the median mitres.
        // @see CgStrokeFieldRangeTest#medianAndTrueDistanceDisagreeAtCorners
        float outerDist = roundJoin > 0.5 && outward > 0.0 ? screenPxRange * (fieldSample.a - 0.5) : screenPxDist;
        float ringOuter = clamp(outerDist + min(outward, reachField) * screenPxRange + 0.5, 0.0, 1.0);
        float ringInner = clamp(screenPxDist - min(inward, reachField) * screenPxRange + 0.5, 0.0, 1.0);
        coverage = strokeParams.z > 0.5 ? max(ringOuter - ringInner, 0.0) : ringOuter;
    } else {
        // A fill. The em on screen: texels per em over texels per screen pixel, from the Jacobian's area.
        float emPx = CG_QUAD_CUSTOM2 / sqrt(max(abs(jdx.x * jdy.y - jdx.y * jdy.x), 1.0e-8));
        coverage = text_gamma_fill(opacity, emPx, gammaSmall, gammaLarge, u_TextGammaSmall, u_TextGammaLarge,
                                   u_TextGammaRamp);
    }
#else
    if (shadowKind < -2.5) {
        // A shadow cell: bilinear by hand, half a texel inside its rect, since the atlas samples nearest.
        vec2 cellSize = vec2(textureSize(atlas, 0).xy);
        vec4 cellRect = CG_QUAD_UV_RECT;
        vec2 halfTexel = 0.5 / cellSize;
        vec2 st = clamp(uv, cellRect.xy + halfTexel, cellRect.zw - halfTexel) * cellSize - 0.5;
        vec2 base = floor(st);
        vec2 weight = st - base;
        float c00 = texture(atlas, vec3((base + vec2(0.5, 0.5)) / cellSize, atlasLayer)).r;
        float c10 = texture(atlas, vec3((base + vec2(1.5, 0.5)) / cellSize, atlasLayer)).r;
        float c01 = texture(atlas, vec3((base + vec2(0.5, 1.5)) / cellSize, atlasLayer)).r;
        float c11 = texture(atlas, vec3((base + vec2(1.5, 1.5)) / cellSize, atlasLayer)).r;
        coverage = mix(mix(c00, c10, weight.x), mix(c01, c11, weight.x), weight.y);
    } else {
        // Rotated, a nearest-sampled bitmap glyph takes the texel filter rather than a staircase (lib/texel.glsl).
        float bitmap = CG_QUAD_EDGE_ROTATED
                ? cg_texel_aa_sample(atlas, uvw, CG_QUAD_UV_RECT, CG_QUAD_EDGE_FILTER).r
                : texture(atlas, uvw).r;
        vec2 bitmapSize = vec2(textureSize(atlas, 0).xy);
        vec2 bdx = dFdx(uv) * bitmapSize;
        vec2 bdy = dFdy(uv) * bitmapSize;
        float emPx = CG_QUAD_CUSTOM2 / sqrt(max(abs(bdx.x * bdy.y - bdx.y * bdy.x), 1.0e-8));
        coverage = text_gamma_fill(bitmap, emPx, gammaSmall, gammaLarge, u_TextGammaSmall, u_TextGammaLarge,
                                   u_TextGammaRamp);
    }
#endif
    float a = paint.a * coverage;
    if (a <= (1.0 / 255.0)) discard;
    vec4 color = vec4(paint.rgb * a, a) * boxClip;
    // A world label's depth pass writes its solid core alone (CgTextBatch.drawCapturedDepth).
    if (color.a < u_TextCut.x) discard;
    return color;
}

#endif
