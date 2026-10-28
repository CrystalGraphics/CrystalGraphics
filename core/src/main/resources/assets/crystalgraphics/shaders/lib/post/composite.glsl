// What both forms of the post composite share: noise for dithering onto an 8-bit target, sRGB's exact curve, and the
// copy form's vignette and colour grading; its impact frames are lib/post/impact.glsl. Nothing here names cg_*: a shader passes its pixel,
// size and frame.
//
//     float frame = floor(CG_TIME * 60.0);
//     vec3 added = post_round8(bloom, gl_FragCoord.xy, frame);   // the blend form: what a blend adds, rounded
//     vec3 whole = post_dither8(encoded, gl_FragCoord.xy, frame); // the copy form: a value written whole
#pragma once

// Interleaved gradient noise (Jimenez, SIGGRAPH 2014), a new pattern each frame: 0..1, even over a 3x3 block.
float post_ign(vec2 pixel, float frame) {
    pixel += 5.588238 * mod(frame, 64.0);
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}

// Stochastic rounding of a term a blend adds onto an 8-bit target: floor(b * 255 + u) / 255. An 8-bit attachment clamps
// what a fragment writes to 0..1 before blending, so +-1 noise would lose its negative half and brighten the picture;
// this never goes negative and is unbiased, and the target already holds exact 8-bit values, so the sum is dithered.
vec3 post_round8(vec3 added, vec2 pixel, float frame) {
    vec3 u = vec3(post_ign(pixel, frame), post_ign(pixel + vec2(17.0, 5.0), frame), post_ign(pixel + vec2(3.0, 29.0), frame));
    return floor(max(added, 0.0) * 255.0 + u) * (1.0 / 255.0);
}

// Triangular dither, +-1 of 8 bits, for a value written whole.
vec3 post_dither8(vec3 c, vec2 pixel, float frame) {
    float n = post_ign(pixel, frame) - post_ign(pixel + vec2(59.0, 13.0), frame);
    return c + n * (1.0 / 255.0);
}

vec3 post_decode_srgb(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c));
}

vec3 post_encode_srgb(vec3 c) {
    c = max(c, 0.0);
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), c));
}

// Unreal's vignette (4.21 PostProcessCommon's VignetteSpace and ComputeVignetteMask): what is left of a pixel by the
// cosine-fourth law at VignetteIntensity, in a space where the corners sit on one circle whatever the aspect.
float post_vignette(vec2 uv, vec2 resolution, float intensity) {
    float a = resolution.y / resolution.x;
    vec2 p = (uv * 2.0 - 1.0) * vec2(1.0, a) * (sqrt(2.0) / sqrt(1.0 + a * a)) * intensity;
    float k = 1.0 / (dot(p, p) + 1.0);
    return k * k;
}

// ACEScg's luma: AP1 to XYZ's middle row.
const vec3 POST_AP1_Y = vec3(0.2722287168, 0.6740817658, 0.0536895174);

// Unreal's ColorCorrect (4.21 PostProcessCombineLUTs.usf) of ACEScg c at luma: saturation about luma, contrast about
// 0.18, gamma, gain and offset; the scalars already applied and gamma inverted on the CPU (CgColorGrade).
vec3 post_correct(vec3 c, float luma, vec3 saturation, vec3 contrast, vec3 invGamma, vec3 gain, vec3 offset) {
    c = max(vec3(0.0), mix(vec3(luma), c, saturation));
    c = pow(c * (1.0 / 0.18), contrast) * 0.18;
    c = pow(c, invGamma);
    return c * gain + offset;
}

// ColorCorrectAll's weights at luma: shadows to range.x, highlights from range.y; midtones are what is left.
vec2 post_grade_weights(float luma, vec2 range) {
    return vec2(1.0 - smoothstep(0.0, range.x, luma), smoothstep(range.y, 1.0, luma));
}
