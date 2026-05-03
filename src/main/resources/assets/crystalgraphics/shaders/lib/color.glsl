#pragma once
#include "crystalgraphics:shaders/lib/math.glsl"

// ITU-R BT.709 luminance (sRGB primaries, D65). Matches Unity URP Color.hlsl Luminance().
float luminance(vec3 linear_rgb) {
    return dot(linear_rgb, vec3(0.2126729, 0.7151522, 0.0721750));
}

// Precise piecewise sRGB <-> linear. Matches Unity SRGBToLinear / LinearToSRGB.
float srgb_to_linear(float c) {
    float lo = c / 12.92;
    float hi = positive_pow((c + 0.055) / 1.055, 2.4);
    return (c <= 0.04045) ? lo : hi;
}
vec3 srgb_to_linear(vec3 c) {
    return vec3(srgb_to_linear(c.r), srgb_to_linear(c.g), srgb_to_linear(c.b));
}
vec4 srgb_to_linear(vec4 c) { return vec4(srgb_to_linear(c.rgb), c.a); }

float linear_to_srgb(float c) {
    float lo = c * 12.9232102;
    float hi = 1.055 * positive_pow(c, 1.0 / 2.4) - 0.055;
    return (c <= 0.0031308) ? lo : hi;
}
vec3 linear_to_srgb(vec3 c) {
    return vec3(linear_to_srgb(c.r), linear_to_srgb(c.g), linear_to_srgb(c.b));
}
vec4 linear_to_srgb(vec4 c) { return vec4(linear_to_srgb(c.rgb), c.a); }

// Fast approximations (Chilliant 2012). ~3 MAD ops each.
// Used verbatim in Godot tonemap_inc.glsl and Unity FastSRGB*/FastLinear*.
vec3 fast_srgb_to_linear(vec3 c) {
    return c * (c * (c * 0.305306011 + 0.682171111) + 0.012522878);
}
vec3 fast_linear_to_srgb(vec3 c) {
    return max(vec3(1.055) * pow(max(c, vec3(0.0)), vec3(0.416666667)) - vec3(0.055), vec3(0.0));
}

// RGB <-> HSV branchless swizzle trick (Ian Taylor / chilliant.blogspot.com).
// Hue [0,1], Sat [0,1], Val [0, +inf for HDR].
vec3 rgb_to_hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + 1e-4)), d / (q.x + 1e-4), q.x);
}

vec3 hsv_to_rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

// shift in [0,1] — 1.0 = full 360° rotation
vec3 rotate_hue(vec3 rgb, float shift) {
    vec3 hsv = rgb_to_hsv(rgb);
    hsv.x = fract(hsv.x + shift);
    return hsv_to_rgb(hsv);
}

// t=0 → full gray, t=1 → original color
vec3 desaturate(vec3 rgb, float t) {
    return mix(vec3(luminance(rgb)), rgb, t);
}
