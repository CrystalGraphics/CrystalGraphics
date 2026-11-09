// The canvas's text: bitmap, MSDF and MTSDF glyphs, their outlines, shadows and lines (CgTextBatch).

#type none
#pragma cg_use quad
#pragma cg_use clip
#pragma cg_use text

// MSDF_MODE covers MSDF and MTSDF; bitmap is off. The atlas is RGBA8 (CgGlyphAtlas), measured clean for the
// median of .rgb (CgMsdfFieldStorageTest). .a, the true distance, drives wide effects, where 8 bits may band: dither,
// narrow the range, or move the atlas back to RGBA16F, in that order.
#pragma cg_feature MSDF_MODE
// A retained world label's glyphs (CgWorldText), drawn as OBJECT instances: each record is in its label's own space,
// and its node is the label's object record, whose model matrix places it under the firing's view.
#pragma cg_feature WORLD_LABEL
#pragma cg_permutation MSDF_MODE WORLD_LABEL   // four fixed instances (CgTextMaterials), never toggled

#include "crystalgraphics:shaders/lib/texel.glsl"
#include "crystalgraphics:shaders/lib/rect_blur.glsl"
#include "crystalgraphics:shaders/lib/text_gamma.glsl"

Tags {
    "RenderType" = "Transparent"
}

// At or past 2500: no generated depth pass.
Queue = "Overlay"

Properties {
    _MainTex ("Atlas Texture", sampler2DArray) = "white"

    // The stroke and range are per instance, so stroked and plain glyphs share a draw:
    //   CG_QUAD_CUSTOM0 = the outline's colour
    //   CG_QUAD_CUSTOM1 = (width in atlas texels, align, over, pxRange)
    //       align:   0 centred, 1 outside, 2 inside; + 3 for a round join   @see CgStrokeAlign, CgStrokeJoin
    //       over:    non-zero paints the stroke over the fill
    //       pxRange: the range the glyph's own atlas band was made at       @see CgMsdfAtlasConfig#WIDE_PX_RANGE
    //   CG_QUAD_CUSTOM2 = atlas texels per em, for CgTextGamma's size fade; 0 except on a glyph's fill
    // Width is in texels, which an anisotropic transform stretches with the glyph.
    //
    // A shadow says so with a negative custom0.w; its opacity is CG_QUAD_COLOR's alpha. -kind:
    //   kind  mode    custom0                            custom1                                     quad
    //   -1    field   (unused)                           (strokeTexels, align, spreadTexels, pxRange) glyph, offset
    //   -2    field   (offsetU, offsetV, sigmaTexels)    (strokeTexels, align, spreadTexels, pxRange) glyph
    //   -3    bitmap  (unused)                           (unused)                                    the cell
    //   -4    either  (invSixSigmaU, invSixSigmaV, 0)    inset rect (L, T, R, B)                     rect + 3 sigma
    //   -5    either  (offsetU, offsetV, invSixSigmaU)   (invSixSigmaV, spreadU, spreadV, 0)         the rect
    // -4 and -5 sample nothing and are tested first. @see CgTextShadowPlan
}

struct v2f {
    vec2 uv;
    vec4 color;
    float atlasLayer;
    // The unit quad's own parameter, for the analytic rect shadow kinds.
    vec2 param;
    // text_gamma_terms of this glyph's colour under each CgTextGamma level; constant across the quad.
    vec4 gammaSmall;
    vec4 gammaLarge;
};

Pass {
    Tags {
        "LightMode" = "Forward"
        "Name"      = "Text"
    }

    RenderState {
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA, ONE ONE_MINUS_SRC_ALPHA
        Cull OFF

        // Tested, never written: a draw's shadows, lines and glyphs are coplanar and would depth-fight
        // (TextMeshPro's ZWrite Off).
        DepthTest LEQUAL
        DepthWrite OFF
    }

    // The pose is baked into each record; a label's placement already holds the view. TextData (cg_use text) is
    // CgTextGamma's two levels and a depth pass's cut; gamma corrects the fill's coverage only.
    void vertex(out v2f o) {
#ifdef WORLD_LABEL
        int label = int(QUAD_DATA(CG_INSTANCE_ID).node);
        mat4 place = cg_Objects[label].modelMatrix;
        vec3 local = QUAD_DATA(CG_INSTANCE_ID).origin + CG_VERTEX_CORNER.x * QUAD_DATA(CG_INSTANCE_ID).right
                + CG_VERTEX_CORNER.y * QUAD_DATA(CG_INSTANCE_ID).up;
        gl_Position = cg_ProjMatrix * (place * vec4(local, 1.0));
#else
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(CG_QUAD_WORLD_POS, 1.0);
#endif
        o.uv         = CG_QUAD_UV;
        o.color      = CG_QUAD_COLOR;
        o.atlasLayer = CG_QUAD_ATLAS_LAYER;
        o.param      = CG_VERTEX_CORNER;
        o.gammaSmall = text_gamma_terms(CG_QUAD_COLOR.rgb, u_TextGammaSmall);
        o.gammaLarge = text_gamma_terms(CG_QUAD_COLOR.rgb, u_TextGammaLarge);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        // Before any discard: it takes derivatives.
        float boxClip = CG_CLIP_QUAD_COVERAGE;
        vec3 uvw = vec3(i.uv, i.atlasLayer);
        float shadowKind = CG_QUAD_CUSTOM0.w;
        if (shadowKind < -3.5) {
            float rectCoverage = shadowKind > -4.5
                    ? rect_shadow_outer(i.param, CG_QUAD_CUSTOM0.xy, CG_QUAD_CUSTOM1)
                    : rect_shadow_inset(i.param, CG_QUAD_CUSTOM0.xy, vec2(CG_QUAD_CUSTOM0.z, CG_QUAD_CUSTOM1.x),
                                        CG_QUAD_CUSTOM1.yz);
            float rectAlpha = i.color.a * rectCoverage;
            if (rectAlpha <= (1.0 / 255.0)) discard;
            fragColor = vec4(i.color.rgb, rectAlpha * boxClip);
            return;
        }
#ifdef MSDF_MODE
        vec4 fieldSample = texture(_MainTex, uvw);
        vec3 field = fieldSample.rgb;
        float signedDistance = max(min(field.r, field.g), min(max(field.r, field.g), field.b));

        vec4 strokeParams = CG_QUAD_CUSTOM1;
        float pxRange = strokeParams.w;

        vec2 atlasSize = vec2(textureSize(_MainTex, 0).xy);

        // The Jacobian, not fwidth: one screenPxRange for both axes is wrong under scale, skew and rotation. Direction
        // from the field's gradient, magnitude from the uv Jacobian, as Skia's GrDistanceFieldA8TextGeoProc does for
        // non-similarity transforms.
        vec2 distGrad = vec2(dFdx(signedDistance), dFdy(signedDistance));
        float gradLenSq = dot(distGrad, distGrad);
        distGrad = gradLenSq < 1.0e-8 ? vec2(0.70710678) : distGrad * inversesqrt(gradLenSq);

        vec2 jdx = dFdx(i.uv) * atlasSize;   // texels crossed per screen px in x
        vec2 jdy = dFdy(i.uv) * atlasSize;
        vec2 texelStep = vec2(distGrad.x * jdx.x + distGrad.y * jdy.x,
                              distGrad.x * jdx.y + distGrad.y * jdy.y);

        float screenPxRange = max(pxRange / max(length(texelStep), 1.0e-6), 1.0);

        float screenPxDist = screenPxRange * (signedDistance - 0.5);
        float opacity = clamp(screenPxDist + 0.5, 0.0, 1.0);
        float alpha = i.color.a * opacity;

        if (shadowKind < -0.5) {
            // A shadow of the stroked, spread shape, on the glyph's own field, within the stroke's reach.
            float shadowWidthField = strokeParams.x / max(pxRange, 1.0e-6);
            float shadowSpreadField = strokeParams.z / max(pxRange, 1.0e-6);
            float shadowReach = max(0.5 - max(1.0 / max(screenPxRange, 1.0e-6), 1.0 / max(pxRange, 1.0e-6)), 0.0);
            float shadowRound = step(2.5, strokeParams.y);
            float shadowAlign = strokeParams.y - 3.0 * shadowRound;
            float shadowOutward = shadowAlign < 0.5 ? shadowWidthField * 0.5
                                : shadowAlign < 1.5 ? shadowWidthField : 0.0;
            float shadowInward = shadowWidthField - shadowOutward;
            float shadowCoverage;
            if (shadowKind > -1.5) {
                // Outer: the fill united with the ring's outer edge, grown by the spread along the true distance.
                float shadowEdge = shadowRound > 0.5 && shadowOutward > 0.0
                        ? screenPxRange * (fieldSample.a - 0.5) : screenPxDist;
                shadowCoverage = clamp(shadowEdge + min(shadowOutward, shadowReach) * screenPxRange + 0.5, 0.0, 1.0);
                if (shadowSpreadField > 0.0) {
                    float trueDistance = texture(_MainTex, uvw).a;
                    float grown = min(shadowOutward + shadowSpreadField, shadowReach);
                    shadowCoverage = max(shadowCoverage,
                            clamp(screenPxRange * (trueDistance - 0.5) + grown * screenPxRange + 0.5, 0.0, 1.0));
                }
            } else {
                // Inset: inside the ring's inner edge, the canvas outside a hole of the glyph shrunk by the stroke and
                // spread, seen through the offset.
                float clipCoverage = clamp(screenPxDist - min(shadowInward, shadowReach) * screenPxRange + 0.5, 0.0, 1.0);
                vec2 holeUv = i.uv - CG_QUAD_CUSTOM0.xy;
                vec4 cellRect = CG_QUAD_UV_RECT;
                float holeCoverage = 0.0;
                if (all(greaterThanEqual(holeUv, cellRect.xy)) && all(lessThanEqual(holeUv, cellRect.zw))) {
                    vec4 holeField = texture(_MainTex, vec3(holeUv, i.atlasLayer));
                    float shrink = min(shadowInward + shadowSpreadField, shadowReach);
                    float holeSigmaTexels = CG_QUAD_CUSTOM0.z;
                    if (holeSigmaTexels > 0.0) {
                        // A blurred hole: a Gaussian past the shrunk edge from the true distance, with half a screen
                        // pixel of antialiasing added in quadrature.
                        float holeTexels = (holeField.a - 0.5 - shrink) * pxRange;
                        float aaTexels = 0.5 * pxRange / max(screenPxRange, 1.0e-6);
                        float holeSigma = sqrt(holeSigmaTexels * holeSigmaTexels + aaTexels * aaTexels);
                        holeCoverage = 0.5 * (1.0 + rect_blur_erf(holeTexels / (holeSigma * 1.41421356)));
                    } else {
                        float holeDistance = shadowSpreadField > 0.0 ? holeField.a
                                : max(min(holeField.r, holeField.g), min(max(holeField.r, holeField.g), holeField.b));
                        holeCoverage = clamp(screenPxRange * (holeDistance - 0.5) - shrink * screenPxRange + 0.5, 0.0, 1.0);
                    }
                }
                shadowCoverage = clipCoverage * (1.0 - holeCoverage);
            }
            float shadowAlpha = i.color.a * shadowCoverage;
            if (shadowAlpha <= (1.0 / 255.0)) discard;
            fragColor = vec4(i.color.rgb, shadowAlpha * boxClip);
            return;
        }

        // A runtime branch, not a keyword: a variant would break the batch.
        vec4 strokeColor = CG_QUAD_CUSTOM0;
        float strokeWidthTexels = strokeParams.x;
        float strokeRound = step(2.5, strokeParams.y);
        float strokeAlign = strokeParams.y - 3.0 * strokeRound;
        float strokeOver = strokeParams.z;
        if (strokeWidthTexels > 0.0 && strokeColor.a > 0.0) {
            // The ring is a difference of two coverages, not a band test, so a sub-pixel stroke fades instead of
            // flickering. Its inner edge reads the same median as the fill; only a round join's outer edge reads the
            // true distance, since the median mitres a corner and the true distance rounds it.
            // @see CgStrokeFieldRangeTest#medianAndTrueDistanceDisagreeAtCorners
            float strokeDist = screenPxDist;

            // Clamped to what the field describes: past its range the field saturates and the ring fills the cell.
            // The reach keeps a texel of headroom (a bilinear tap reads half a texel either side; Skia's
            // SK_DistanceFieldInset), or a screen pixel when minified; in field units, so an anisotropic transform
            // cannot push the width past it. Bold biases the whole field, so one reach serves both directions.
            // @see CgStrokeFieldRangeTest, CgAnisotropicFieldRangeTest, CgSyntheticBoldReachTest
            float strokeWidthField = strokeWidthTexels / max(pxRange, 1.0e-6);
            float reachField = max(0.5 - max(1.0 / max(screenPxRange, 1.0e-6),
                                             1.0 / max(pxRange, 1.0e-6)), 0.0);

            float wantOutward = strokeAlign < 0.5 ? strokeWidthField * 0.5   // CENTER
                              : strokeAlign < 1.5 ? strokeWidthField         // OUTSET
                                                   : 0.0;                     // INSET
            float outward = min(wantOutward, reachField);
            float inward  = min(strokeWidthField - wantOutward, reachField);

            // A round join takes the outer edge from the true distance (MTSDF's alpha), rounding corners.
            float outerDist = strokeRound > 0.5 && outward > 0.0 ? screenPxRange * (fieldSample.a - 0.5) : strokeDist;
            float ringOuter = clamp(outerDist + outward * screenPxRange + 0.5, 0.0, 1.0);
            float ringInner = clamp(strokeDist - inward  * screenPxRange + 0.5, 0.0, 1.0);
            float strokeCoverage = max(ringOuter - ringInner, 0.0);

            // The ring and the fill are disjoint regions of one shape, not layers: source-over would give a + b(1 - a)
            // at their shared edge, a hairline. So the silhouette's coverage is their union, divided by what each
            // occupies; where they overlap, paint order decides which takes the remainder.
            float unionCoverage = max(ringOuter, opacity);
            float strokeWeight = strokeOver > 0.5 ? strokeCoverage : unionCoverage - opacity;
            float fillWeight   = strokeOver > 0.5 ? unionCoverage - strokeCoverage : opacity;
            strokeWeight = clamp(strokeWeight, 0.0, 1.0);
            fillWeight   = clamp(fillWeight,   0.0, 1.0);

            // Each colour's alpha still applies: a transparent fill stays hollow.
            float strokeA = strokeWeight * strokeColor.a;
            float fillA   = fillWeight * i.color.a;

            float outA = strokeA + fillA;
            if (outA <= (1.0 / 255.0)) discard;
            // Straight alpha out, for the SRC_ALPHA blend.
            vec3 outRgb = (strokeColor.rgb * strokeA + i.color.rgb * fillA) / outA;

            fragColor = vec4(outRgb, min(outA, 1.0) * boxClip);
        } else {
            // The em on screen: texels per em over texels per screen pixel, from the Jacobian's area.
            float emPx = CG_QUAD_CUSTOM2 / sqrt(max(abs(jdx.x * jdy.y - jdx.y * jdy.x), 1.0e-8));
            alpha = i.color.a * text_gamma_fill(opacity, emPx, i.gammaSmall, i.gammaLarge,
                                                u_TextGammaSmall, u_TextGammaLarge, u_TextGammaRamp);
            if (alpha <= (1.0 / 255.0)) discard;

            fragColor = vec4(i.color.rgb, alpha * boxClip);
        }
#else
        if (shadowKind < -2.5) {
            // A shadow cell: bilinear by hand, half a texel inside its rect, since the atlas samples nearest.
            vec2 cellSize = vec2(textureSize(_MainTex, 0).xy);
            vec4 cellRect = CG_QUAD_UV_RECT;
            vec2 halfTexel = 0.5 / cellSize;
            vec2 st = clamp(i.uv, cellRect.xy + halfTexel, cellRect.zw - halfTexel) * cellSize - 0.5;
            vec2 base = floor(st);
            vec2 weight = st - base;
            float c00 = texture(_MainTex, vec3((base + vec2(0.5, 0.5)) / cellSize, i.atlasLayer)).r;
            float c10 = texture(_MainTex, vec3((base + vec2(1.5, 0.5)) / cellSize, i.atlasLayer)).r;
            float c01 = texture(_MainTex, vec3((base + vec2(0.5, 1.5)) / cellSize, i.atlasLayer)).r;
            float c11 = texture(_MainTex, vec3((base + vec2(1.5, 1.5)) / cellSize, i.atlasLayer)).r;
            float cellCoverage = mix(mix(c00, c10, weight.x), mix(c01, c11, weight.x), weight.y);
            float cellAlpha = i.color.a * cellCoverage;
            if (cellAlpha <= (1.0 / 255.0)) discard;
            fragColor = vec4(i.color.rgb, cellAlpha * boxClip);
            return;
        }
        // Rotated, a nearest-sampled bitmap glyph takes the texel filter rather than a staircase (lib/texel.glsl).
        float coverage = CG_QUAD_EDGE_ROTATED
                ? cg_texel_aa_sample(_MainTex, uvw, CG_QUAD_UV_RECT, CG_QUAD_EDGE_FILTER).r
                : texture(_MainTex, uvw).r;
        vec2 bitmapSize = vec2(textureSize(_MainTex, 0).xy);
        vec2 bdx = dFdx(i.uv) * bitmapSize;
        vec2 bdy = dFdy(i.uv) * bitmapSize;
        float emPx = CG_QUAD_CUSTOM2 / sqrt(max(abs(bdx.x * bdy.y - bdx.y * bdy.x), 1.0e-8));
        float alpha = text_gamma_fill(coverage, emPx, i.gammaSmall, i.gammaLarge,
                                      u_TextGammaSmall, u_TextGammaLarge, u_TextGammaRamp) * i.color.a;
        fragColor = vec4(i.color.rgb, alpha * boxClip);
#endif
        // A world label's depth pass writes its solid core alone (CgTextBatch.drawCapturedDepth).
        if (fragColor.a < u_TextCut.x) discard;
    }
}
