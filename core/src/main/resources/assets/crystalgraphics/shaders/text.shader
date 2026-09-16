// =============================================================================
// CrystalShader -- text.shader
// Consolidated bitmap/MSDF/MTSDF text material, replacing the three raw shaders
// (bitmap_text/msdf_text/mtsdf_text .vert/.frag) previously hand-maintained by
// CgTextRenderer. See CrystalGraphics/docs_research/plan/text-material.md.
// =============================================================================

#type pos2_uv2_col4ub
#pragma cg_use quad

// One keyword covers both distance-field atlas types (MSDF and MTSDF) -- the fragment
// logic is byte-for-byte identical for both today (same median-based reconstruction,
// same texture().rgb read; MTSDF's extra alpha channel isn't exploited yet). Bitmap
// mode is "not enabled". Reintroduce a separate MTSDF_MODE keyword only if/when MTSDF's
// alpha channel actually needs different fragment logic (see
// docs_research/font/MTSDF_SHADER_RECONSTRUCTION_RESEARCH.md).
//
// -----------------------------------------------------------------------------
// ATLAS STORAGE IS RGBA8, NOT RGBA16F -- READ THIS BEFORE USING THE 4TH CHANNEL
// -----------------------------------------------------------------------------
// The distance-field atlas allocates GL_RGBA8 (CgGlyphAtlas), so every channel
// carries 256 levels, not half-float precision. It used to be RGBA16F; the switch
// halved atlas memory (88 MB -> 44 MB on a 3-font CJK workload) and was validated by
// measurement, NOT by assumption -- see CgMsdfFieldStorageTest: quantising to 8 bits
// introduced zero structural defects across six atlas scales on dense kanji (no counter
// closed, no stroke merged), costing 0.04 percentage points of ink-relative edge error
// at the shipping 80px scale.
//
// That validation covers ONLY the .rgb channels this shader reads today. The median-of-3
// edge is razor thin -- roughly one screen pixel wide -- so 8-bit quantisation there is
// invisible, which is why it measured clean.
//
// The 4th channel is a different risk profile. It is a true SDF intended for WIDE effects
// (outlines, glows, soft shadows), where the gradient is deliberately spread over many
// screen pixels. Stretching 256 levels across a wide, shallow ramp is exactly where 8-bit
// banding shows up, and nothing has tested it because nothing samples it yet.
//
// So: if you start reading .a here and see banding or stair-stepping in an outline or
// glow falloff, the atlas format is the first suspect, not your effect math. Options in
// order of preference: dither the ramp in-shader; narrow the effect's distance range so
// fewer levels cover it; or, last resort, move the atlas back to RGBA16F_LINEAR in
// CgGlyphAtlas and pay the 2x memory. Extend CgMsdfQualityProbe to score the 4th
// channel before deciding -- it currently only does median-of-3.
#pragma cg_feature MSDF_MODE

#include "crystalgraphics:shaders/lib/texel.glsl"
#include "crystalgraphics:shaders/lib/rect_blur.glsl"

Tags {
    "RenderType" = "Transparent"
}

// Overlay (>= TRANSPARENT_THRESHOLD) -- load-bearing: keeps attemptShadowAutoGen/
// attemptDepthAutoGen bailing out via their isOpaque check, so text never gets an
// auto-generated ShadowCaster/Depth pass.
Queue = "Overlay"

Properties {
    _MainTex ("Atlas Texture", sampler2DArray) = "white"

    // NEITHER THE STROKE NOR THE RANGE IS HERE. It is per INSTANCE, in the quad's two custom slots, because a
    // material property is shared by every quad in a batch -- a stroke that changed per draw forced a
    // flush, a keyword toggle and a property re-apply between draws that were otherwise identical.
    // Carried on the instance, a stroked glyph and an unstroked one go out in one call.
    //
    //   CG_QUAD_CUSTOM0 = the outline's colour, rgba
    //   CG_QUAD_CUSTOM1 = (width in atlas TEXELS, align, over, pxRange)
    //       align:   0 centred on the contour, 1 outside it, 2 inside it  @see CgStrokeAlign
    //       over:    non-zero to paint the stroke over the fill           @see paint-order
    //       pxRange: the range the GLYPH's own atlas band was generated at. Per instance because a
    //                face carrying a dense script is banded narrower than the rest, and two bands
    //                have to go out in one draw. @see CgMsdfAtlasConfig#WIDE_PX_RANGE
    //
    // Width is texels rather than screen px because a screen-space width needs one scale factor to
    // convert and an anisotropic transform has none; in texels it is local, and the transform
    // stretches the ring with the glyph. @see CgTextStroke#widthEm, quad.glsl
    //
    // A TEXT-SHADOW INSTANCE says so with a NEGATIVE custom0.w, which no stroke colour's alpha can be.
    // Its colour is CG_QUAD_COLOR, whose alpha alone is the shadow's opacity. -kind names what it is:
    //
    //   kind  mode    custom0                            custom1                                     quad
    //   -1    field   (unused)                           (strokeTexels, align, spreadTexels, pxRange) glyph, offset
    //   -2    field   (offsetU, offsetV, sigmaTexels)    (strokeTexels, align, spreadTexels, pxRange) glyph
    //   -3    bitmap  (unused)                           (unused)                                    the cell
    //   -4    either  (invSixSigmaU, invSixSigmaV, 0)    inset rect (L, T, R, B)                     rect + 3 sigma
    //   -5    either  (offsetU, offsetV, invSixSigmaU)   (invSixSigmaV, spreadU, spreadV, 0)         the rect
    //
    // -4 and -5 are in the quad's unit parameter and sample nothing, so they are tested first, in either
    // mode. A sharp shadow of unstroked, unspread text is not an instance kind at all: it is an ordinary
    // glyph in the shadow's colour. @see CgTextRenderer#planShadows
}

struct v2f {
    vec2 uv;
    vec4 color;
    float atlasLayer;
    // The unit quad's own parameter, for the analytic rect shadow kinds.
    vec2 param;
};

Pass {
    Tags {
        "LightMode" = "Forward"
        "Name"      = "Text"
    }

    RenderState {
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA, ONE ONE_MINUS_SRC_ALPHA
        Cull OFF

        // Placeholder only -- never actually observed at draw time. Depth behavior differs
        // between 2D UI text (no depth) and world text (depth-tested, not written), and a
        // Pass's RenderState is fixed at author time, not per-keyword-permutation -- so
        // CgTextRenderer overrides real depth state in Java via CgDepthState.NONE/TEST_ONLY
        // bracketing material.bind()/unbind(). Do not try to express that split here.
        DepthTest LEQUAL
        DepthWrite ON
    }

    // Per-glyph model-view is baked CPU-side into origin/right/up (see CgQuadRenderer.Quad#pose,
    // called by CgTextRenderer#addQuadFromPlacement) and delivered per-instance via the
    // CG_QUAD_* macros below (cg_env.glsl) -- no per-draw uniform transform.
    //
    // u_Projection reaches this shader via CgTextRenderer's attached, shared static "TextData"
    // CgUniformBuffer (flat STD140 scope -- referenced directly, no block prefix). Deliberately
    // NOT the engine's shared cg_ProjMatrix (CgFrameBlock): that's frame-owner state (the actual
    // scene camera), and CgTextRenderContext's projection is renderer-local (often an
    // orthographic UI projection unrelated to the scene camera) -- reusing cg_ProjMatrix would
    // clobber whatever the real frame projection is for anything else sharing that frame.
    void vertex(out v2f o) {
        gl_Position = u_Projection * vec4(CG_QUAD_WORLD_POS, 1.0);
        o.uv         = CG_QUAD_UV;
        o.color      = CG_QUAD_COLOR;
        o.atlasLayer = CG_QUAD_ATLAS_LAYER;
        o.param      = cg_Position.xy;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 uvw = vec3(i.uv, i.atlasLayer);
        float shadowKind = CG_QUAD_CUSTOM0.w;
        if (shadowKind < -3.5) {
            float rectCoverage = shadowKind > -4.5
                    ? rect_shadow_outer(i.param, CG_QUAD_CUSTOM0.xy, CG_QUAD_CUSTOM1)
                    : rect_shadow_inset(i.param, CG_QUAD_CUSTOM0.xy, vec2(CG_QUAD_CUSTOM0.z, CG_QUAD_CUSTOM1.x),
                                        CG_QUAD_CUSTOM1.yz);
            float rectAlpha = i.color.a * rectCoverage;
            if (rectAlpha <= (1.0 / 255.0)) discard;
            fragColor = vec4(i.color.rgb, rectAlpha);
            return;
        }
#ifdef MSDF_MODE
        vec3 field = texture(_MainTex, uvw).rgb;
        float signedDistance = max(min(field.r, field.g), min(max(field.r, field.g), field.b));

        vec4 strokeParams = CG_QUAD_CUSTOM1;
        float pxRange = strokeParams.w;

        vec2 atlasSize = vec2(textureSize(_MainTex, 0).xy);

        // THE JACOBIAN, not fwidth. msdfgen's canonical screenPxRange averages the two uv derivatives
        // into ONE number for both axes, which is only true of a similarity transform. Under
        // scale(2,1) or a skew a screen pixel spans different amounts of the field along each axis,
        // so a single number leaves the edge too soft one way and too hard the other -- and it is
        // wrong under plain ROTATION too, where fwidth's abs-sum overestimates by up to sqrt(2) and
        // blurs a rotated stem. Skia carries the matrix for the same reason, in
        // GrDistanceFieldA8TextGeoProc's non-similarity path, which this follows.
        //
        // Direction from the field's own gradient, magnitude from the Jacobian. The gradient of a
        // median-of-3 is discontinuous where the channels swap at a corner, so taking only its
        // DIRECTION from there bounds what that costs while the uv derivatives stay smooth. Under an
        // isotropic transform the direction cancels out of the length, so the common case is exactly
        // as stable as the formula this replaces -- and measurably identical to it.
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
            // A SHADOW OF THE STROKED, SPREAD SHAPE, thresholded on the glyph's own field. The reach clamp is
            // the stroke's own, so a shadow never asks the field for a distance it stopped storing.
            float shadowWidthField = strokeParams.x / max(pxRange, 1.0e-6);
            float shadowSpreadField = strokeParams.z / max(pxRange, 1.0e-6);
            float shadowReach = max(0.5 - max(1.0 / max(screenPxRange, 1.0e-6), 1.0 / max(pxRange, 1.0e-6)), 0.0);
            float shadowAlign = strokeParams.y;
            float shadowOutward = shadowAlign < 0.5 ? shadowWidthField * 0.5
                                : shadowAlign < 1.5 ? shadowWidthField : 0.0;
            float shadowInward = shadowWidthField - shadowOutward;
            float shadowCoverage;
            if (shadowKind > -1.5) {
                // Outer: the fill united with the ring's outer edge -- the median, so a stroke's mitred corner is
                // shadowed as it is drawn -- then grown by the spread along the TRUE distance, which rounds.
                shadowCoverage = clamp(screenPxDist + min(shadowOutward, shadowReach) * screenPxRange + 0.5, 0.0, 1.0);
                if (shadowSpreadField > 0.0) {
                    float trueDistance = texture(_MainTex, uvw).a;
                    float grown = min(shadowOutward + shadowSpreadField, shadowReach);
                    shadowCoverage = max(shadowCoverage,
                            clamp(screenPxRange * (trueDistance - 0.5) + grown * screenPxRange + 0.5, 0.0, 1.0));
                }
            } else {
                // Inset: inside the ring's inner edge, the canvas outside a hole the size of the glyph shrunk by the
                // stroke and the spread, seen through the offset. Outside the glyph's own cell the hole is empty.
                float clipCoverage = clamp(screenPxDist - min(shadowInward, shadowReach) * screenPxRange + 0.5, 0.0, 1.0);
                vec2 holeUv = i.uv - CG_QUAD_CUSTOM0.xy;
                vec4 cellRect = CG_QUAD_UV_RECT;
                float holeCoverage = 0.0;
                if (all(greaterThanEqual(holeUv, cellRect.xy)) && all(lessThanEqual(holeUv, cellRect.zw))) {
                    vec4 holeField = texture(_MainTex, vec3(holeUv, i.atlasLayer));
                    float shrink = min(shadowInward + shadowSpreadField, shadowReach);
                    float holeSigmaTexels = CG_QUAD_CUSTOM0.z;
                    if (holeSigmaTexels > 0.0) {
                        // A BLURRED HOLE from the true distance: a Gaussian falloff past the shrunk edge,
                        // exact along a straight edge. Half a screen pixel of antialiasing is added in
                        // quadrature, so the edge stays smooth however small the blur is on screen.
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
            fragColor = vec4(i.color.rgb, shadowAlpha);
            return;
        }

        // A RUNTIME BRANCH, deliberately not a keyword. A compile-time variant would be another
        // dimension the batch has to break on, which is the cost this whole per-instance move
        // exists to remove -- and the two sides differ by a handful of ALU ops on a fragment that
        // has already paid for a texture fetch.
        vec4 strokeColor = CG_QUAD_CUSTOM0;
        float strokeWidthTexels = strokeParams.x;
        float strokeAlign = strokeParams.y;
        float strokeOver = strokeParams.z;
        if (strokeWidthTexels > 0.0 && strokeColor.a > 0.0) {
            // THE RING IS A DIFFERENCE OF TWO COVERAGES, not a band test. Thresholding |distance| picks
            // up the field's own antialiasing twice -- once at each side of the ring -- and a
            // sub-pixel-wide stroke then flickers between fully present and absent as it crosses the
            // grid. Two clamped coverages subtracted stay exact at every width, including widths under
            // one pixel, where the ring correctly comes out partly transparent instead of dropping out.
            //
            // THE SAME FIELD THE FILL READS, deliberately, so the ring's inner edge lands exactly on
            // the fill's own edge rather than a hair off it.
            //
            // THE MEDIAN, NOT MTSDF's TRUE DISTANCE, and the difference between them is a JOIN STYLE.
            // msdf-atlas-gen's feature table lists "rounded outlines" among the soft effects the fourth
            // channel is for and the sharp MSDF is not; Godot regressed font outlines moving MTSDF to
            // MSDF (godotengine/godot#109757). Measured here on the reconstructed band rather than
            // texel-by-texel, which is what an earlier reading got wrong: the two disagree over 0.5% of
            // an A's band, 0.7% of an E's, and EXACTLY 0% of an o's -- they part company at corners and
            // nowhere else. The median mitres a corner, a true distance rounds it.
            //
            // Mitred is the default this API owes: -webkit-text-stroke is a path stroke and Skia's
            // default join is mitre. It also keeps the ring's inner edge exactly on the fill's own edge,
            // since both thresholds read one reconstruction -- reading alpha for the ring and the median
            // for the fill would open a hairline at every corner, which is the seam this whole composite
            // exists to close. A round join is therefore a FEATURE THAT EXISTS TO BE BUILT, reading
            // alpha for both edges, not a difference that cannot be seen.
            // @see CgStrokeFieldRangeTest#medianAndTrueDistanceDisagreeAtCorners
            float strokeDist = screenPxDist;

            // CLAMPED TO WHAT THE FIELD CAN DESCRIBE, and this is not a nicety -- it is the difference
            // between degrading and failing. Outside the stored range the field SATURATES: signedDistance
            // pins to 0 and screenPxDist to -screenPxRange/2, everywhere, however far out the fragment
            // really is. So the outer threshold `strokeDist + outward + 0.5` stops reaching zero as soon
            // as outward passes screenPxRange/2 - 0.5, and every fragment of the padded cell reads as
            // inside the ring: the glyph comes out as a solid rectangle of stroke colour, not a thick
            // outline. Measured at font-size 44 against the pxRange 6 / 80px pairing this shipped with
            // first, that boundary was about 1.15 screen px -- 1px drew correctly and 2px filled the cell.
            //
            // Clamping here rather than in Java because the bound is a SCREEN-SPACE quantity: it falls out
            // of screenPxRange, which depends on fwidth and is therefore only known per fragment.
            //
            // HEADROOM IS A TEXEL, NOT A PIXEL, because the shoulder it keeps clear of belongs to the
            // texel grid: a bilinear tap reads half a texel either side, so a threshold closer than that
            // to the end of the range averages a clipped texel with a live one however many screen
            // pixels away it looks. Past the shoulder the field is flat, and a flat field turns eight-bit
            // value error into a level set that follows the grid -- the outer edge comes out scalloped
            // while the fill beside it, thresholding where the field still has slope, stays smooth.
            //
            // Measured at font-size 64: an 8px request resolves 5.7% of its outer contour from
            // footprints holding a clipped texel, against 84% with a screen pixel of headroom. It costs
            // maximum width -- the clean reach is one texel short of the range the field carries,
            // 0.056em on the narrow band and 0.131em on the wide one -- and a wider stroke stops widening
            // instead of going ragged, which is what CgTextStroke has always promised. Skia keeps two
            // texels back for the same reason, in the geometry rather than the threshold:
            // SK_DistanceFieldInset, "the rect we render with is inset from the distance field glyph
            // size to allow for bilerp".
            //
            // One SCREEN pixel remains the floor: minified text has several texels per pixel, where the
            // grid is no longer what limits the edge. @see CgStrokeFieldRangeTest
            // ONE REACH FOR BOTH DIRECTIONS, including on synthetic bold, which looks like it should
            // need two and does not. Bold is a bias added to the whole field, which moves the contour
            // and both saturation ends together -- measured on a real bold field, the outward reach is
            // 5.50 texels either way. @see CgSyntheticBoldReachTest
            // BOTH SIDES IN FIELD UNITS. The reach is the same two floors as before divided
            // through by screenPxRange -- a TEXEL of headroom where the grid is what limits the edge,
            // one SCREEN pixel where minification means it is not -- so only the floor is screen
            // dependent now. That is what stops an anisotropic transform pushing the request past the
            // reach: the width no longer grows with one axis while the reach grows with the other,
            // which landed the outer edge on the saturation shoulder and drew it following the texel
            // grid. @see CgAnisotropicFieldRangeTest
            float strokeWidthField = strokeWidthTexels / max(pxRange, 1.0e-6);
            float reachField = max(0.5 - max(1.0 / max(screenPxRange, 1.0e-6),
                                             1.0 / max(pxRange, 1.0e-6)), 0.0);

            float wantOutward = strokeAlign < 0.5 ? strokeWidthField * 0.5   // CENTER
                              : strokeAlign < 1.5 ? strokeWidthField         // OUTSET
                                                   : 0.0;                     // INSET
            float outward = min(wantOutward, reachField);
            float inward  = min(strokeWidthField - wantOutward, reachField);

            float ringOuter = clamp(strokeDist + outward * screenPxRange + 0.5, 0.0, 1.0);
            float ringInner = clamp(strokeDist - inward  * screenPxRange + 0.5, 0.0, 1.0);
            float strokeCoverage = max(ringOuter - ringInner, 0.0);

            // NOT SOURCE-OVER. An outset ring and the fill are DISJOINT REGIONS OF ONE SHAPE, not two
            // layers: they meet along the glyph's own edge, where each carries partial coverage from its
            // own antialiasing. Composited as layers that gives a + b(1-a) where disjoint areas owe
            // a + b -- at the edge, where both are near a half, 0.75 against 1.0. A quarter-coverage
            // hairline then traces every outline, visible even when stroke and fill are the SAME colour.
            // CgUiSprite's note on the old nine-quad seams is the same arithmetic; there neither quad
            // could know what its neighbour drew, and here both are in one fragment.
            //
            // So the silhouette's coverage is the union, and the two colours divide it by how much of it
            // each occupies. Where they genuinely overlap -- a centred or inset ring does lie over the
            // fill -- paint order decides which is measured first and which takes the remainder.
            float unionCoverage = max(ringOuter, opacity);
            float strokeWeight = strokeOver > 0.5 ? strokeCoverage : unionCoverage - opacity;
            float fillWeight   = strokeOver > 0.5 ? unionCoverage - strokeCoverage : opacity;
            strokeWeight = clamp(strokeWeight, 0.0, 1.0);
            fillWeight   = clamp(fillWeight,   0.0, 1.0);

            // Each region's own colour alpha still applies, which is what keeps a transparent
            // text-fill-color hollow rather than merely unpainted: the weight survives, the alpha is 0.
            float strokeA = strokeWeight * strokeColor.a;
            float fillA   = fillWeight * i.color.a;

            float outA = strokeA + fillA;
            if (outA <= (1.0 / 255.0)) discard;
            // Straight alpha out, matching this pass's SRC_ALPHA blend -- so the weighted sum is divided
            // back out rather than left premultiplied, which would darken every stroked glyph.
            vec3 outRgb = (strokeColor.rgb * strokeA + i.color.rgb * fillA) / outA;

            fragColor = vec4(outRgb, min(outA, 1.0));
        } else {
            if (alpha <= (1.0 / 255.0)) discard;

            fragColor = vec4(i.color.rgb, alpha);
        }
#else
        if (shadowKind < -2.5) {
            // A SHADOW CELL, bilinear by hand and held half a texel inside its own rect: it may be drawn at a
            // fractional offset or stretched back from a downsampled blur, and the atlas samples nearest.
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
            fragColor = vec4(i.color.rgb, cellAlpha);
            return;
        }
        // A bitmap glyph is nearest-sampled pixel art; rotated, its texels get the antialiasing a
        // geometric edge gets rather than a staircase. See CG_TEXEL_AA in cg_env.glsl. At rest --
        // axis-aligned, which is every glyph in a document -- this is the plain fetch it always was.
        float coverage = CG_QUAD_EDGE_ROTATED
                ? cg_texel_aa_sample(_MainTex, uvw, CG_QUAD_UV_RECT, CG_QUAD_EDGE_FILTER).r
                : texture(_MainTex, uvw).r;
        float alpha = coverage * i.color.a;
        fragColor = vec4(i.color.rgb, alpha);
#endif
    }
}
