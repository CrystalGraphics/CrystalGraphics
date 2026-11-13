// CgCurveBatch's macros: included after the `curve` engine buffer's declaration (CgEngineBufferRegistry), every stage.
// Fragment-only code guards with `#if !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE)`.
#pragma once

// One record, two readings by its flags (stroke.glsl reads them):
//   * stroke (default): p0/p1/p2 a quadratic Bezier's control points, color0/color1 its gradient, widths its start and
//     end half-widths, flags a cap a end;
//   * fill (CG_STROKE_FLAG_FILL): p0/p1/p2 a triangle, color0 its colour, widths.x its corner radius.
// Every macro reads in both stages, since a stroke is an SDF evaluated per pixel; CG_CURVE_WORLD_POS is vertex-only.
// The quad drawn is the points' hull padded by cg_curve_pad, so the CPU writes no bounds.
//
//   Vertex:    gl_Position = cg_ProjMatrix * vec4(CG_CURVE_WORLD_POS, 1.0);
//   Fragment:  float t; float d = sdf_bezier(i.posXy, CG_CURVE_P0.xy, CG_CURVE_P1.xy, CG_CURVE_P2.xy, t);
//              float halfW = mix(CG_CURVE_WIDTHS.x, CG_CURVE_WIDTHS.y, t);
//
// The points are in the record's spatial node's space, read in the pass's target through the palette (palette.glsl);
// a width grows with the node's scale.
#define CG_CURVE_NODE (CURVE_DATA(CG_INSTANCE_ID).node)
#define CG_CURVE_P0 cg_spatial_point(CG_CURVE_NODE, CURVE_DATA(CG_INSTANCE_ID).p0)
#define CG_CURVE_P1 cg_spatial_point(CG_CURVE_NODE, CURVE_DATA(CG_INSTANCE_ID).p1)
#define CG_CURVE_P2 cg_spatial_point(CG_CURVE_NODE, CURVE_DATA(CG_INSTANCE_ID).p2)
#define CG_CURVE_COLOR0 (CURVE_DATA(CG_INSTANCE_ID).color0)
#define CG_CURVE_COLOR1 (CURVE_DATA(CG_INSTANCE_ID).color1)
#define CG_CURVE_WIDTHS cg_curve_widths(CG_CURVE_NODE, CURVE_DATA(CG_INSTANCE_ID).widths, CURVE_DATA(CG_INSTANCE_ID).flags)
// Its own opacity times its effect group's, as CG_QUAD_OPACITY.
#define CG_CURVE_OPACITY ((1.0 - CURVE_DATA(CG_INSTANCE_ID).fade) * cg_effect_opacity(CG_CURVE_NODE))
#define CG_CURVE_FEATHER (CURVE_DATA(CG_INSTANCE_ID).feather)
#define CG_CURVE_FLAGS (CURVE_DATA(CG_INSTANCE_ID).flags)
// A fill's linear gradient (origin.xy, dir.xy), t = dot(p - origin, dir) running 0..1 from color0 to color1; read only
// under CG_STROKE_FLAG_GRADIENT.
#define CG_CURVE_GRADIENT cg_curve_gradient(CG_CURVE_NODE, CURVE_DATA(CG_INSTANCE_ID).gradient)

// A cell (CgCurveBatch.FLAG_CELL, 256) keeps its fourth corner in widths: a point, not a length.
bool cg_curve_is_cell(float flags) {
    return (int(flags + 0.5) & 256) != 0;
}
vec2 cg_curve_widths(float node, vec2 widths, float flags) {
    if (cg_curve_is_cell(flags)) return cg_spatial_point(node, vec3(widths, 0.0)).xy;
    return widths * cg_spatial_scale(node);
}
vec4 cg_curve_gradient(float node, vec4 gradient) {
    return vec4(cg_spatial_point(node, vec3(gradient.xy, 0.0)).xy, cg_spatial_covector(node, gradient.zw));
}
// How far the stroke reaches past the hull. sqrt(2): a square cap's corner on a diagonal stroke reaches that far along
// one axis of this axis-aligned box. An arrow cap (3, either end) reaches 6 half-widths, CG_STROKE_ARROW_LENGTH: a
// literal here, since this file is read without stroke.glsl, so change both together. A cell pads by one pixel.
float cg_curve_pad(vec2 widths, float feather, float flags) {
    int f = int(flags + 0.5);
    if ((f & 256) != 0) return 1.0;
    bool hasArrowCap = (f & 3) == 3 || ((f >> 2) & 3) == 3;
    float reach = hasArrowCap ? 6.0 : 1.41422;
    return max(widths.x, widths.y) * reach + feather + 1.0;
}
vec3 cg_curve_hull_min(vec3 p0, vec3 p1, vec3 p2, vec2 widths, float flags) {
    vec3 m = min(min(p0, p1), p2);
    if (cg_curve_is_cell(flags)) m = min(m, vec3(widths, p0.z));
    return m;
}
vec3 cg_curve_hull_max(vec3 p0, vec3 p1, vec3 p2, vec2 widths, float flags) {
    vec3 m = max(max(p0, p1), p2);
    if (cg_curve_is_cell(flags)) m = max(m, vec3(widths, p0.z));
    return m;
}
#define CG_CURVE_PAD cg_curve_pad(CG_CURVE_WIDTHS, CG_CURVE_FEATHER, CG_CURVE_FLAGS)
#define CG_CURVE_HULL_MIN (cg_curve_hull_min(CG_CURVE_P0, CG_CURVE_P1, CG_CURVE_P2, CG_CURVE_WIDTHS, CG_CURVE_FLAGS) - vec3(CG_CURVE_PAD, CG_CURVE_PAD, 0.0))
#define CG_CURVE_HULL_MAX (cg_curve_hull_max(CG_CURVE_P0, CG_CURVE_P1, CG_CURVE_P2, CG_CURVE_WIDTHS, CG_CURVE_FLAGS) + vec3(CG_CURVE_PAD, CG_CURVE_PAD, 0.0))
#define CG_CURVE_WORLD_POS (CG_CURVE_HULL_MIN + vec3(CG_VERTEX_CORNER, 0.0) * (CG_CURVE_HULL_MAX - CG_CURVE_HULL_MIN))
