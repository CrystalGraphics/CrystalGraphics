#pragma once
// Text gamma and contrast: Skia's SkTMaskGamma_build_correcting_lut (src/core/SkMaskGamma.cpp), evaluated per
// fragment instead of baked into a 256-entry table per quantised text luminance.
//
// A glyph's coverage is blended in the target's encoded space, which thins light-on-dark and dark-on-light text
// alike. The correction assumes the background contrasts with the text (Skia's "perceptual inverse" guess), boosts
// coverage by a contrast term that fades out as the text approaches white, then solves for the coverage whose
// encoded-space blend lands where a blend in the gamma space would have.
//
// Applied to light text only, faded in across mid-grey: on dark text the gamma term thins more than the contrast
// term restores.
//
// params = (gamma, contrast, 1 / gamma, enabled). gamma 1 and contrast 0 is the identity.

// Per text colour: (src, linSrc, linDst, contrast). Computed once per instance, in the vertex stage.
vec4 text_gamma_terms(vec3 color, vec4 params) {
    float gamma = params.x;
    // SkColorSpaceLuminance::computeLuminance: Rec. 709 weights in the gamma space.
    float linSrc = dot(pow(max(color, vec3(0.0)), vec3(gamma)), vec3(0.2126, 0.7152, 0.0722));
    float src = pow(linSrc, params.z);
    float linDst = pow(1.0 - src, gamma);
    return vec4(src, linSrc, linDst, params.y * linDst);
}

float text_gamma_coverage(float coverage, vec4 terms, vec4 params) {
    float src = terms.x;
    float weight = params.w * smoothstep(0.4, 0.6, src);
    if (weight <= 0.0) return coverage;
    float dst = 1.0 - src;
    float srca = coverage + (1.0 - coverage) * terms.w * coverage;
    float corrected = srca;
    // Text as bright as the background it is guessed against: only the contrast applies.
    if (abs(src - dst) >= (1.0 / 256.0)) {
        float linOut = terms.y * srca + (1.0 - srca) * terms.z;
        float outEncoded = pow(linOut, params.z);
        // Undo what the blend will do.
        corrected = clamp((outEncoded - dst) / (src - dst), 0.0, 1.0);
    }
    return mix(coverage, corrected, weight);
}
