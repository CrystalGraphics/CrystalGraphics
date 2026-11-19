// CgUiElements' record, read by vertex pulling: element n is vertices 6n..6n+5 of its draw, two triangles over its
// rect grown by a pixel so its edge ramp fits. Included after the `element` engine buffer's declaration, every stage.
// The record: CgInstanceKind.ELEMENT, whose fields and flags must agree with these.
#pragma once

#include "crystalgraphics:shaders/lib/sdf.glsl"
#include "crystalgraphics:shaders/canvas/primitives/rect_blur.glsl"
#include "crystalgraphics:shaders/paint/grid.glsl"

// Fill kinds, flags & 15. CgUiElements.FILL_*.
#define CG_ELEMENT_COLOR 0
#define CG_ELEMENT_TEXTURE 1
#define CG_ELEMENT_MASK 2
#define CG_ELEMENT_GRADIENT 3
#define CG_ELEMENT_SHADOW 4
#define CG_ELEMENT_INSET_SHADOW 5
#define CG_ELEMENT_GRID 6
// Bits above the kind. CgUiElements.FLAG_*.
#define CG_ELEMENT_ROUNDED 16
#define CG_ELEMENT_BORDER 32
#define CG_ELEMENT_PREMULTIPLIED 64
#define CG_ELEMENT_CHECKER 128

#ifdef CG_VERTEX_STAGE
// The element this vertex belongs to, and its corner of the rect, (0,0) (1,0) (1,1) (0,0) (1,1) (0,1).
#define CG_ELEMENT_INDEX (CG_BASE_INSTANCE + CG_VERTEX_ID / 6)
vec2 cg_element_corner() {
    int v = CG_VERTEX_ID % 6;
    return vec2(v == 1 || v == 2 || v == 4 ? 1.0 : 0.0, v == 2 || v == 4 || v == 5 ? 1.0 : 0.0);
}
#endif

#define CG_ELEMENT(n) ELEMENT_DATA(n)

vec4 cg_element_unpack(uint argb) {
    return vec4(float((argb >> 16) & 255u), float((argb >> 8) & 255u), float(argb & 255u), float(argb >> 24)) / 255.0;
}

// The pad, in the element's units, its quad is grown by on each side: a window pixel under its node's scale.
float cg_element_pad(float node) {
    return 1.0 / max(cg_spatial_scale(node), 1e-4);
}

#if !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE)
// A blurred box's coverage at p (the box centred on 0), sigma > 0: exact for square corners (Graphite's rect blur,
// rect_blur.glsl's erf), the erf of the rounded box's distance for round ones.
float cg_element_shadow(vec2 p, vec2 halfSize, vec4 rx, vec4 ry, float sigma, bool rounded) {
    if (!rounded) {
        vec2 lo = (-halfSize - p) / (sigma * 1.41421356);
        vec2 hi = (halfSize - p) / (sigma * 1.41421356);
        vec2 c = 0.5 * (vec2(rect_blur_erf(hi.x), rect_blur_erf(hi.y)) - vec2(rect_blur_erf(lo.x), rect_blur_erf(lo.y)));
        return c.x * c.y;
    }
    float d = sdf_rounded_box(p, halfSize, rx, ry);
    return 0.5 - 0.5 * rect_blur_erf(d / (sigma * 1.41421356));
}

// The border colour of the side p is nearest, the sides top, right, bottom, left.
vec4 cg_element_side(uvec4 colors, vec2 p, vec2 halfSize) {
    float dx = halfSize.x - abs(p.x);
    float dy = halfSize.y - abs(p.y);
    uint c = dy < dx ? (p.y < 0.0 ? colors.x : colors.z) : (p.x < 0.0 ? colors.w : colors.y);
    return cg_element_unpack(c);
}
#endif
