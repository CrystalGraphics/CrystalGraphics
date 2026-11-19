// A pane of glass's maths, shared by every shader that draws a backdrop filter (the UI's element, a world material):
// the luminosity blend, the lens's height profile and the edge's normal. No buffers, no samplers and no
// fragment-only builtins: any stage includes it.
#pragma once

#include "crystalgraphics:shaders/lib/sdf.glsl"

// W3C's non-separable luminosity blend (Lum, ClipColor, SetLum): the backdrop keeps its hue and takes the tint's
// brightness, what acrylic and Mica are mostly made of.
float cg_lum(vec3 c) { return dot(c, vec3(0.3, 0.59, 0.11)); }
vec3 cg_clipColor(vec3 c) {
    float l = cg_lum(c);
    float n = min(c.r, min(c.g, c.b));
    float x = max(c.r, max(c.g, c.b));
    if (n < 0.0) c = l + (c - l) * l / max(l - n, 1e-5);
    if (x > 1.0) c = l + (c - l) * (1.0 - l) / max(x - l, 1e-5);
    return c;
}
vec3 cg_setLum(vec3 c, float l) { return cg_clipColor(c + vec3(l - cg_lum(c))); }

// The glass's height across the bezel, x = 0 at the edge and 1 where it goes flat: Apple's convex squircle.
float cg_glassHeight(float x) {
    float t = 1.0 - clamp(x, 0.0, 1.0);
    return pow(max(0.0, 1.0 - t * t * t * t), 0.25);
}

// The rounded box's outward normal by central difference over e, about a pixel: no derivative builtins, which once
// reached a vertex stage.
vec2 cg_sdfGradient(vec2 p, vec2 halfSize, vec4 rx, vec4 ry, float e) {
    float dx = sdf_rounded_box(p + vec2(e, 0.0), halfSize, rx, ry) - sdf_rounded_box(p - vec2(e, 0.0), halfSize, rx, ry);
    float dy = sdf_rounded_box(p + vec2(0.0, e), halfSize, rx, ry) - sdf_rounded_box(p - vec2(0.0, e), halfSize, rx, ry);
    return normalize(vec2(dx, dy) + vec2(1e-6, 0.0));
}
