// CgGradientTable: a recording's gradients, read by index. A primitive that names `gradient` in its EngineBuffers
// reads one with cg_gradient(index, uv, box): its colour at uv (a fraction of the box) over a box of `box` units,
// premultiplied, its spread applied, undithered (cg_gradient_dither adds it where the shader wants).
//
// A gradient is words of four floats from its index: its header (shape, spread, stop count, dithered), its geometry
// (CgGradient.geometry), then a colour per stop (premultiplied), then its positions four to a word.
#pragma once

#include "crystalgraphics:shaders/paint/gradient.glsl"

float cg_gradient_t(int g, vec2 uv, vec2 box) {
    vec4 head = GRADIENT_DATA(g).v;
    vec4 geo = GRADIENT_DATA(g + 1).v;
    int shape = int(head.x + 0.5);
    if (shape == CG_GRADIENT_RADIAL) return cg_gradient_radial_t(uv, geo.xy, geo.zw);
    if (shape == CG_GRADIENT_CONIC) return cg_gradient_conic_t(uv * box, geo.xy * box, geo.z / 360.0);
    if (shape == CG_GRADIENT_TWO_POINT) return cg_gradient_two_point_t(uv * box, geo.xy * box, geo.zw * box);
    return cg_gradient_linear_t(uv, cg_gradient_axis(geo.x, box));
}

float cg_gradient_position(int g, int count, int k) {
    vec4 w = GRADIENT_DATA(g + 2 + count + k / 4).v;
    int lane = k - (k / 4) * 4;
    return lane == 0 ? w.x : lane == 1 ? w.y : lane == 2 ? w.z : w.w;
}

// Its colour at t, premultiplied, the spread folding t into its stops first.
vec4 cg_gradient_color(int g, float t) {
    vec4 head = GRADIENT_DATA(g).v;
    int count = int(head.z + 0.5);
    float first = cg_gradient_position(g, count, 0);
    float last = cg_gradient_position(g, count, count - 1);
    t = cg_gradient_spread(t, first, last, int(head.y + 0.5));
    vec4 c = GRADIENT_DATA(g + 2).v;
    float p0 = first;
    for (int k = 1; k < count; k++) {
        float p1 = cg_gradient_position(g, count, k);
        c = cg_gradient_step(c, GRADIENT_DATA(g + 2 + k).v, t, p0, p1);
        p0 = p1;
    }
    return c;
}

vec4 cg_gradient(int g, vec2 uv, vec2 box) {
    return cg_gradient_color(g, cg_gradient_t(g, uv, box));
}

// Whether gradient g asks to be dithered.
bool cg_gradient_dithered(int g) {
    return GRADIENT_DATA(g).v.w > 0.5;
}
