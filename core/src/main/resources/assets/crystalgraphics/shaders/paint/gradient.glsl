// A gradient's maths, shared by every shader that paints one (UI elements, materials): where a point falls along its
// line, how a run of stops mixes, how it spreads past its ends, and its dither. No buffers and no fragment-only
// builtins: any stage includes it. A gradient from CgGradient's table is read by gradient_table.glsl.
//
// - CSS's gradient line (Images 3 section 3.4.1): through the centre, |W sin a| + |H cos a| long, so 0 and 1 land on
//   the corners. RADIAL: the distance from the centre over the radii (section 3.5). CONIC: the turn about the centre,
//   0 up and clockwise on a Y-down box (Images 4 section 3.3, Skia's SkSweepGradient). TWO_POINT: Skia's linear
//   gradient, the projection onto the segment.
// - Stops mix premultiplied (section 3.4.3): a straight lerp toward `transparent` passes through a dark half-colour.
#pragma once

#include "crystalgraphics:shaders/lib/noise.glsl"

#define CG_GRADIENT_LINEAR 0
#define CG_GRADIENT_RADIAL 1
#define CG_GRADIENT_CONIC 2
#define CG_GRADIENT_TWO_POINT 3

#define CG_SPREAD_PAD 0
#define CG_SPREAD_REPEAT 1
#define CG_SPREAD_REFLECT 2

// CSS's gradient line at `degrees` over a box of `box`: t = 0.5 + dot(uv - 0.5, axis) is 0 and 1 at the corners.
vec2 cg_gradient_axis(float degrees, vec2 box) {
    float r = radians(degrees);
    float s = sin(r), c = cos(r);
    float len = abs(box.x * s) + abs(box.y * c);
    return len <= 0.0 ? vec2(1.0, 0.0) : vec2(box.x * s, -box.y * c) / len;
}

// t along CSS's line for uv, a fraction of the box.
float cg_gradient_linear_t(vec2 uv, vec2 axis) {
    return 0.5 + dot(uv - 0.5, axis);
}

// t outward from `centre` over `radii`, all in one space.
float cg_gradient_radial_t(vec2 p, vec2 centre, vec2 radii) {
    return length((p - centre) / max(radii, vec2(1e-4)));
}

// t round `centre`, one clockwise turn from `startTurns`; p and centre in the box's own units, so it turns evenly.
float cg_gradient_conic_t(vec2 p, vec2 centre, float startTurns) {
    vec2 d = p - centre;
    return fract(atan(d.x, -d.y) / 6.28318530718 - startTurns);
}

// t along the segment p0 to p1, all in the box's own units.
float cg_gradient_two_point_t(vec2 p, vec2 p0, vec2 p1) {
    vec2 d = p1 - p0;
    return dot(p - p0, d) / max(dot(d, d), 1e-8);
}

// t folded into [first, last] by the spread: PAD leaves it (the end stops hold), REPEAT wraps, REFLECT mirrors.
float cg_gradient_spread(float t, float first, float last, int spread) {
    float period = last - first;
    if (spread == CG_SPREAD_PAD || period <= 0.0) return t;
    float u = (t - first) / period;
    if (spread == CG_SPREAD_REPEAT) return first + fract(u) * period;
    float m = u - 2.0 * floor(u * 0.5);
    return first + (m > 1.0 ? 2.0 - m : m) * period;
}

// One interval: c mixed toward `next` by where t falls between p0 and p1, clamped, so the ends hold. Two stops at one
// position are a hard stop: max() keeps the division finite.
vec4 cg_gradient_step(vec4 c, vec4 next, float t, float p0, float p1) {
    return mix(c, next, clamp((t - p0) / max(p1 - p0, 1e-5), 0.0, 1.0));
}

// Half a level of noise at `fragCoord`, inside the colour, so a shape's coverage scales it: an 8-bit target bands a
// shallow ramp without it (Skia dithers by default).
vec4 cg_gradient_dither(vec4 premultiplied, vec2 fragCoord) {
    return premultiplied + vec4((hash12(fragCoord) - 0.5) / 255.0);
}
