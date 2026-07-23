#pragma once

#include "crystalgraphics:shaders/lib/math.glsl"

// Signed distance to the edge of an axis-aligned rounded box, centered at the origin.
// `halfSize` is the box's half-extent (outer radius corners included). `radius` is the
// (uniform) outer corner radius, in the same units as `p`/`halfSize`. Negative inside the
// box, positive outside, zero exactly on the edge — standard SDF convention.
//
// Reference: Inigo Quilez, "2D distance functions" (rounded box).
float sdf_rounded_box(vec2 p, vec2 halfSize, float radius) {
    radius = min(radius, min(halfSize.x, halfSize.y));
    vec2 q = abs(p) - halfSize + radius;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

// Same as above but with an independent radius per corner. `radii` is
// (top-left, top-right, bottom-right, bottom-left) — CSS border-radius order. Local space is
// Y-down (matches UV: v=0 at top), so "top" is negative-y here — opposite of Inigo Quilez's
// original Y-up reference this is adapted from.
float sdf_rounded_box(vec2 p, vec2 halfSize, vec4 radii) {
    vec2 topBottom = (p.x > 0.0) ? radii.yz : radii.xw; // right: (TR,BR); left: (TL,BL)
    float radius = (p.y < 0.0) ? topBottom.x : topBottom.y; // top half picks first, bottom picks second
    return sdf_rounded_box(p, halfSize, radius);
}

// 1.0 inside the shape, 0.0 outside, antialiased across ~1px at the edge using screen-space
// derivatives. `dist` is an `sdf_*` distance (negative inside, per the convention above).
float sdf_coverage(float dist) {
    float aa = fwidth(dist) * 0.5;
    return 1.0 - smoothstep(-aa, aa, dist);
}
