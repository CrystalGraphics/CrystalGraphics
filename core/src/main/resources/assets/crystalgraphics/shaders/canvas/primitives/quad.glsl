// CgQuadBatch's macros: included after the `quad` engine buffer's declaration (CgEngineBufferRegistry), every stage.
// Fragment-only code guards with `#if !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE)`.
#pragma once

// One instance a quad (CgMesh.quads(1), VertexType none): its corner is CG_VERTEX_CORNER. The record's corner and
// edges are in its spatial node's space; these read them in the pass's target through the palette (palette.glsl).
//
//   gl_Position = cg_ProjMatrix * vec4(CG_QUAD_WORLD_POS, 1.0);
//   o.uv = CG_QUAD_UV;
//   o.color = CG_QUAD_COLOR;
#define CG_QUAD_NODE (QUAD_DATA(CG_INSTANCE_ID).node)
#define CG_QUAD_ORIGIN cg_spatial_point(CG_QUAD_NODE, QUAD_DATA(CG_INSTANCE_ID).origin)
#define CG_QUAD_RIGHT cg_spatial_vector(CG_QUAD_NODE, QUAD_DATA(CG_INSTANCE_ID).right)
#define CG_QUAD_UP cg_spatial_vector(CG_QUAD_NODE, QUAD_DATA(CG_INSTANCE_ID).up)
#define CG_QUAD_WORLD_POS (CG_QUAD_ORIGIN + CG_VERTEX_CORNER.x * CG_QUAD_RIGHT + CG_VERTEX_CORNER.y * CG_QUAD_UP)
#define CG_QUAD_UV (mix(QUAD_DATA(CG_INSTANCE_ID).uv0, QUAD_DATA(CG_INSTANCE_ID).uv1, CG_VERTEX_CORNER))
#define CG_QUAD_COLOR (QUAD_DATA(CG_INSTANCE_ID).color)
// Flat, so derived from right/up rather than stored.
#define CG_QUAD_NORMAL (normalize(cross(CG_QUAD_RIGHT, CG_QUAD_UP)))
// Its own opacity (stored as 1 - opacity) times its effect group's. A material that ignores it draws opaque.
#define CG_QUAD_OPACITY ((1.0 - QUAD_DATA(CG_INSTANCE_ID).fade) * cg_effect_opacity(CG_QUAD_NODE))
// The sampler2DArray layer of an atlas-backed quad (text); per instance, so it needs no flat varying.
#define CG_QUAD_ATLAS_LAYER (QUAD_DATA(CG_INSTANCE_ID).atlasLayer)

// Free per-quad values (Quad.custom0/custom1/custom2, zero unless written), readable in both stages. Use them, not a
// property or a keyword, for anything that varies per quad: either breaks the batch. Their layout is the material's.
#define CG_QUAD_CUSTOM0 (QUAD_DATA(CG_INSTANCE_ID).custom0)
#define CG_QUAD_CUSTOM1 (QUAD_DATA(CG_INSTANCE_ID).custom1)
#define CG_QUAD_CUSTOM2 (QUAD_DATA(CG_INSTANCE_ID).custom2)

// -- Analytic edge antialiasing, screen-space quads only ------------------------------------------------------------
// A rotated quad is grown by half a filter width in the vertex stage, and each fragment takes the area its edges leave
// of the pixel. Axis-aligned quads are left alone, so tiles meeting on a pixel boundary keep no seam. "Half a pixel" is
// half a unit of right/up's space: a window pixel under an ortho projection, nothing under a perspective one.
//
//   Vertex:    vec2 param = CG_QUAD_EDGE_PARAM;
//              gl_Position = cg_ProjMatrix * vec4(CG_QUAD_EDGE_WORLD_POS(param), 1.0);
//              o.param = param;                                 // carry the parameter, not the uv
//   Fragment:  vec2 uv = CG_QUAD_EDGE_UV(i.param);              // clamped, so the pad never samples past the rect
//              alpha *= CG_QUAD_EDGE_COVERAGE(i.param);
bool cg_quad_edge_rotated(vec3 right, vec3 up) {
    return abs(right.y) > 1.0e-4 || abs(up.x) > 1.0e-4;
}
// The quad's width across u and across v, in right/up's units.
vec2 cg_quad_edge_extent(vec3 right, vec3 up) {
    float area = abs(right.x * up.y - right.y * up.x);
    return vec2(area / max(length(up.xy), 1.0e-6), area / max(length(right.xy), 1.0e-6));
}
// The filter's width in pixels: 1 is the exact box, which ropes thin rotated lines; 1.5 smooths them.
#define CG_QUAD_EDGE_FILTER 1.5
vec2 cg_quad_edge_param(vec2 local, vec3 right, vec3 up) {
    if (!cg_quad_edge_rotated(right, up)) return local;
    vec2 e = (0.5 * CG_QUAD_EDGE_FILTER) / max(cg_quad_edge_extent(right, up), vec2(1.0e-6));
    return local * (1.0 + 2.0 * e) - e;
}
float cg_quad_edge_coverage(vec2 param, vec3 right, vec3 up) {
    if (!cg_quad_edge_rotated(right, up)) return 1.0;
    vec2 h = cg_quad_edge_extent(right, up);
    // Each pair of opposite edges: the area inside both, exact while they are over a pixel apart.
    vec2 near = clamp(0.5 + param * h / CG_QUAD_EDGE_FILTER, 0.0, 1.0);
    vec2 far = clamp(0.5 + (1.0 - param) * h / CG_QUAD_EDGE_FILTER, 0.0, 1.0);
    vec2 c = near + far - 1.0;
    return clamp(c.x, 0.0, 1.0) * clamp(c.y, 0.0, 1.0);
}
#define CG_QUAD_EDGE_PARAM cg_quad_edge_param(CG_VERTEX_CORNER, CG_QUAD_RIGHT, CG_QUAD_UP)
#define CG_QUAD_EDGE_WORLD_POS(param) (CG_QUAD_ORIGIN + (param).x * CG_QUAD_RIGHT + (param).y * CG_QUAD_UP)
#define CG_QUAD_EDGE_UV(param) (mix(QUAD_DATA(CG_INSTANCE_ID).uv0, QUAD_DATA(CG_INSTANCE_ID).uv1, clamp(param, 0.0, 1.0)))
#define CG_QUAD_EDGE_COVERAGE(param) cg_quad_edge_coverage(param, CG_QUAD_RIGHT, CG_QUAD_UP)
#define CG_QUAD_EDGE_ROTATED cg_quad_edge_rotated(CG_QUAD_RIGHT, CG_QUAD_UP)

#define CG_QUAD_UV_RECT vec4(min(QUAD_DATA(CG_INSTANCE_ID).uv0, QUAD_DATA(CG_INSTANCE_ID).uv1), max(QUAD_DATA(CG_INSTANCE_ID).uv0, QUAD_DATA(CG_INSTANCE_ID).uv1))
