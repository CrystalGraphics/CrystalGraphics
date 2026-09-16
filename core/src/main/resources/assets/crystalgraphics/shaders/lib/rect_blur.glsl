// =============================================================================
// rect_blur.glsl -- the coverage of a Gaussian-blurred rectangle, analytically.
//
// Ported from Skia Graphite (BSD-3-Clause, see THIRD-PARTY.md): the fragment function
// $rect_blur_coverage_fn in sksl_graphite_frag.sksl, set up by AnalyticBlurMask::MakeRect, with the
// integral table of skgpu::CreateIntegralTable (src/gpu/BlurUtils.cpp).
//
// ONE DELIBERATE CHANGE: the table is evaluated as the function it tabulates,
//   T(t) = 0.5 * (erf((3 - 6t) / sqrt(2)) + 1),  exactly 1 from t = 0 in and 0 from t = 1 out, the two
//   end texels CreateIntegralTable pins,
// because a second sampler would split the batch it is drawn in. erf is Abramowitz and Stegun 7.1.26,
// whose error (1.5e-7) is far below the table's eight bits.
//
// Coordinates are whatever space the rect is given in; the text shadow passes the quad's unit
// parameter, with sigma per axis, so the blur transforms with the quad.
// =============================================================================
#pragma once

#ifndef CG_VERTEX_STAGE
float rect_blur_erf(float x) {
    float s = sign(x);
    float a = abs(x);
    float t = 1.0 / (1.0 + 0.3275911 * a);
    float y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t
                    + 0.254829592) * t * exp(-a * a);
    return s * y;
}

// The integral table's value at t: 1 at and inside the inset edge, 0 six sigma past it.
float rect_blur_integral(float t) {
    if (t <= 0.0) return 1.0;
    if (t >= 1.0) return 0.0;
    return 0.5 * (rect_blur_erf((3.0 - 6.0 * t) * 0.70710678) + 1.0);
}

// $rect_blur_coverage_fn. rect is the blurred rect INSET by three sigma (L, T, R, B); invSixSigma is per axis.
float rect_blur_coverage(vec2 coords, vec4 rect, vec2 invSixSigma) {
    float xCoverage;
    float yCoverage;
    if (rect.x <= rect.z && rect.y <= rect.w) {
        // The nearest edge on each axis decides; the inset puts that edge at t = 0.
        vec2 pos = max(rect.xy - coords, coords - rect.zw);
        xCoverage = rect_blur_integral(invSixSigma.x * pos.x);
        yCoverage = rect_blur_integral(invSixSigma.y * pos.y);
    } else {
        // Narrower than six sigma: both edges reach the fragment, so both half-planes are subtracted.
        vec4 d = vec4(rect.xy - coords, coords - rect.zw);
        xCoverage = 1.0 - rect_blur_integral(invSixSigma.x * d.x) - rect_blur_integral(invSixSigma.x * d.z);
        yCoverage = 1.0 - rect_blur_integral(invSixSigma.y * d.y) - rect_blur_integral(invSixSigma.y * d.w);
    }
    return xCoverage * yCoverage;
}

// An outer shadow of a rect; insetRect is already inset by three sigma.
float rect_shadow_outer(vec2 param, vec2 invSixSigma, vec4 insetRect) {
    return rect_blur_coverage(param, insetRect, invSixSigma);
}

// An inset shadow of the unit rect: the canvas outside a hole shrunk by the spread and moved by the offset.
// A zero invSixSigma on both axes is no blur, and the hole is its sharp rect.
float rect_shadow_inset(vec2 param, vec2 offset, vec2 invSixSigma, vec2 spread) {
    vec4 hole = vec4(spread + offset, vec2(1.0) - spread + offset);
    float holeCoverage;
    if (invSixSigma.x <= 0.0 || invSixSigma.y <= 0.0) {
        vec2 inside = step(hole.xy, param) * step(param, hole.zw);
        holeCoverage = inside.x * inside.y;
    } else {
        vec2 threeSigma = 0.5 / invSixSigma;
        holeCoverage = rect_blur_coverage(param, vec4(hole.xy + threeSigma, hole.zw - threeSigma), invSixSigma);
    }
    return 1.0 - holeCoverage;
}
#endif
