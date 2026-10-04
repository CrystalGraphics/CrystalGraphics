// What both forms of the post composite share: noise for dithering onto an 8-bit target, sRGB's exact curve, and the
// bloom term. Nothing here names cg_*: a shader passes its pixel and frame.
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
