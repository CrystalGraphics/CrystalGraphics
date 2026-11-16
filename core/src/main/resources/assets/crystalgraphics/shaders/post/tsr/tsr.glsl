// What every TSR kernel shares (CgTemporalSuperResolution): the colour spaces the history is compared and kept in, its
// dither, and its filters. Pixels are rows bottom first, as every target of the engine's: a pixel's screen position is
// its centre's normalized device coordinates. The depth and motion of the first two passes: tsr_geometry.glsl.
#pragma once

#include "crystalgraphics:shaders/lib/velocity.glsl"

// The largest a float of the history's R11G11B10 holds (its 10-bit channel's top).
const float TSR_MAX_10BIT_FLOAT = 64512.0;

vec2 tsr_screen(vec2 pixel, vec4 size) {
    return (pixel + 0.5) * size.zw * 2.0 - 1.0;
}

// -- Colour spaces -------------------------------------------------------------------------------------------------
// The guide is kept in x / (x + 0.17), a gamma-like curve close to a tonemapper's, so its 10 bits are spent where the
// eye sees; the rejection measures in its square, linear enough not to over-reject the shadows.

vec3 tsr_to_guide(vec3 linear) {
    return linear / (linear + 0.17);
}

vec3 tsr_from_guide(vec3 g) {
    return g * min(0.17 / max(1.0 - g, 1.0e-6), 65504.0);
}

vec3 tsr_guide_to_shading(vec3 g) {
    return g * g;
}

vec3 tsr_to_shading(vec3 linear) {
    return tsr_guide_to_shading(tsr_to_guide(linear));
}

// x / (x + 1) and its inverse: the space the guide is blended in.
vec3 tsr_perceptual(vec3 linear) {
    return linear / (linear + 1.0);
}

vec3 tsr_from_perceptual(vec3 c) {
    return c * min(1.0 / max(1.0 - c, 1.0e-6), 65504.0);
}

// Luma times 4, and Karis's tone weight on it (High Quality Temporal Supersampling, 2014): a bright sample weighs less,
// so one firefly does not own the blend.
float tsr_luma4(vec3 c) {
    return c.g * 2.0 + c.r + c.b;
}

float tsr_tone_weight(float luma4) {
    return max(1.0e-4, 1.0 / (luma4 + 4.0));
}

float tsr_tone_weight(vec3 c) {
    return tsr_tone_weight(tsr_luma4(c));
}

// -- Dither --------------------------------------------------------------------------------------------------------
// pcg3d (Jarzynski and Olano, Hash Functions for GPU Rendering, 2020): 16 random bits for a pixel and frame.

uvec3 tsr_pcg3d(uvec3 v) {
    v = v * 1664525u + 1013904223u;
    v.x += v.y * v.z;
    v.y += v.z * v.x;
    v.z += v.x * v.y;
    v ^= v >> 16u;
    v.x += v.y * v.z;
    v.y += v.z * v.x;
    v.z += v.x * v.y;
    return v;
}

float tsr_random(ivec2 pixel, int frame) {
    return float(tsr_pcg3d(uvec3(ivec3(pixel, frame))).x & 0xFFFFu) / 65536.0;
}

// A unorm channel of step, rounded up or down at random by e (0 to 1) rather than always down when stored.
vec3 tsr_dither_unorm(vec3 c, float e, float step) {
    return c + step * (e - 0.5);
}

// A float channel's last mantissa bit at c (2^-mantissa of its exponent), times e: rounded at random when truncated.
vec3 tsr_dither_float(vec3 c, float e, vec3 mantissa) {
    vec3 unit = uintBitsToFloat(floatBitsToUint(c * mantissa) & 0xFF800000u);
    return c + unit * e;
}

// -- Kernels -------------------------------------------------------------------------------------------------------

// Bicubic Catmull-Rom of a bilinear texture in five taps (the corners dropped, their weight to the centre: Stubbe's
// form), at uv of a texture of size: each tap's uv into uvs, its weight into weights.
void tsr_catmull_rom(vec2 uv, vec4 size, out vec2 uvs[5], out float weights[5]) {
    vec2 at = uv * size.xy;
    vec2 i = floor(at - 0.5) + 1.0;
    vec2 f = at - i, f2 = f * f, ff = f + f;
    vec2 mid = (1.25 - f2) * f + 0.5;
    vec2 t0 = (0.25 * f2 - 0.0625) * (1.125 - 0.5 * f2.yx);
    vec2 w0 = t0 - ff * t0, w3 = t0 + ff * t0;
    vec2 c = (i - 0.5) * size.zw;
    mid *= size.zw;
    uvs[0] = c + vec2(-size.z, mid.y);
    uvs[1] = c + vec2(2.0 * size.z, mid.y);
    uvs[2] = c + mid;
    uvs[3] = c + vec2(mid.x, -size.w);
    uvs[4] = c + vec2(mid.x, 2.0 * size.w);
    weights[0] = w0.x;
    weights[1] = w3.x;
    weights[2] = 1.0 - w0.x - w3.x - w0.y - w3.y;
    weights[3] = w0.y;
    weights[4] = w3.y;
}

// The weight of an input sample delta pixels from where the output pixel sits, an input pixel spanning upscale output
// pixels: 1 - 1.9 x^2 + 0.9 x^4 of the squared distance, a smooth bump falling to 0 at one output pixel.
float tsr_sample_weight(float upscale, vec2 delta, float least) {
    float x2 = clamp(upscale * upscale * dot(delta, delta), 0.0, 1.0);
    return clamp((0.9 * x2 - 1.9) * x2 + 1.0 + least, 0.0, 1.0);
}
