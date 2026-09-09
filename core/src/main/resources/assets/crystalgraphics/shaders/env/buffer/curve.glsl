// CgVectorRenderer's shader environment.
//
// INJECTED BY `#pragma cg_use curve`, immediately after the instance buffer's own
// declaration -- see CgEngineBufferRegistry, which holds the path, and
// CgMaterialShaderCompiler.appendEngineBufferEnv, which emits the include. A shader that does
// not declare the pragma never sees any of this, which is the point: only a minority of
// shaders draw through the renderer, and cg_env.glsl is included by all of them.
//
// Compiled into BOTH stages, like everything cg_env pulls in. Fragment-only code guards itself
// with `#ifndef CG_VERTEX_STAGE` -- never `#ifdef CG_FRAGMENT_STAGE`, which raw .vert/.frag
// get neither of.
#pragma once

// -- CgVectorRenderer convenience macros --------------------------------------
// CgVectorRenderer (gl/render/CgVectorRenderer.java) is an SSBO/TBO-backed instanced renderer for
// small 2D vector primitives -- not "curves" specifically, though that is still most of what draws
// through it today. The buffer's schema is generic on purpose: vec3 p0/p1/p2 (a handful of points,
// pose baked in CPU-side), vec4 color0/color1, vec2 widths, float feather, float flags. Same
// hardcoded-macro-name arrangement as the CG_QUAD_* block above: CURVE_DATA = MACRO_NAME. The
// macro family keeps the CG_CURVE_ prefix from when this buffer was curve-only; renaming it is a
// bigger, riskier change than renaming the Java class was, and the macros still say exactly what
// they resolve to regardless of the C-style prefix's history.
//
// TWO READINGS OF ONE RECORD, CHOSEN BY THE PACKED flags FIELD -- see CgVectorRenderer's own class
// doc for the "why one renderer, not two" reasoning, and cg_curve_pad below for where the fill
// reading is actually branched on:
//   * STROKE (the default): p0/p1/p2 are quadratic Bezier control points, color0/color1 are the
//     gradient endpoints, widths is (start,end) HALF-width, flags packs a cap style per end.
//   * FILL (CG_STROKE_FLAG_FILL set, see stroke.glsl): p0/p1/p2 are a triangle's vertices, color0
//     is the flat fill colour, widths.x is corner radius. color1 and widths.y are UNUSED in this
//     reading -- not a waste, spare capacity a future third reading (a gradient fill, say) can
//     claim for free, the same way the fill reading itself claimed one bit and one repurposed
//     field rather than needing a new buffer. Whatever consumes that capacity next belongs as
//     another macro in this same block, not as a CG_SOMETHING_ELSE_* block beside it.
//
// These are DEFINED unconditionally but only RESOLVE in a shader that declares:
//
//     #pragma cg_use curve
//
// Using one without the pragma is rejected at parse time naming the missing line -- and needs no
// parser change to do it, because CgShaderParser derives the "CG_CURVE_" family textually from
// "CURVE_DATA".
//
// -- STAGE AVAILABILITY -- THE ONE REAL DIFFERENCE FROM CG_QUAD_* --------------
// Every macro here EXCEPT CG_CURVE_WORLD_POS resolves in BOTH the vertex and fragment stages.
// That is deliberate and load-bearing: a curve's stroke is an analytic SDF that must be evaluated
// per pixel, so the fragment stage needs the control points themselves. It re-reads them straight
// out of CURVE_DATA(CG_INSTANCE_ID) rather than receiving them as varyings, because the .shader
// v2f struct DSL has no flat qualifier to offer (cg_InstanceId is the only compiler-generated flat
// varying) and interpolating control points would be both wasteful and wrong to reason about.
// This works on both buffer paths with no compiler change -- CgMaterialShaderCompiler's
// appendAttachedBuffers runs for the fragment source as well as the vertex source.
//
// CG_CURVE_WORLD_POS is vertex-only: it consumes cg_Position, and it does NOT read a quad the way
// CG_QUAD_WORLD_POS does. There is no per-instance origin/right/up for a curve -- the bounding box
// is DERIVED here from the convex hull of the three points, expanded by cg_curve_pad's reach for
// the current cap/fill mode (see that function) so the antialiased edge is never clipped. A
// quadratic Bezier is contained by its own control hull and a triangle IS its own hull trivially,
// so the identical derivation is exactly right for both readings with no branch here -- only the
// PADDING amount differs by mode, which is why that logic lives in cg_curve_pad and not here. That
// is also why the CPU writes no bounds: they cannot desync from the points if they are computed
// from them. Planar by construction -- see CgVectorRenderer's class doc on why v1 is 2D.
//
//   Vertex:    gl_Position = cg_ProjMatrix * vec4(CG_CURVE_WORLD_POS, 1.0);
//
//   Fragment, stroke reading (the common case -- see curve_instance_coverage in stroke.glsl,
//   which is the one place both curve.shader and gui_curve.shader should actually call this from):
//              float t; float d = sdf_bezier(i.posXy, CG_CURVE_P0.xy, CG_CURVE_P1.xy, CG_CURVE_P2.xy, t);
//              float halfW = mix(CG_CURVE_WIDTHS.x, CG_CURVE_WIDTHS.y, t);
//              vec4 col = mix(CG_CURVE_COLOR0, CG_CURVE_COLOR1, t);
//
//   Fragment, fill reading (see fill_coverage in stroke.glsl):
//              float d = sdf_triangle(i.posXy, CG_CURVE_P0.xy, CG_CURVE_P1.xy, CG_CURVE_P2.xy);
//              d -= CG_CURVE_WIDTHS.x;                 // corner radius, not a stroke width here
//              vec4 col = CG_CURVE_COLOR0;              // color1 unused in this reading
#define CG_CURVE_P0 (CURVE_DATA(CG_INSTANCE_ID).p0)
#define CG_CURVE_P1 (CURVE_DATA(CG_INSTANCE_ID).p1)
#define CG_CURVE_P2 (CURVE_DATA(CG_INSTANCE_ID).p2)
#define CG_CURVE_COLOR0 (CURVE_DATA(CG_INSTANCE_ID).color0)
#define CG_CURVE_COLOR1 (CURVE_DATA(CG_INSTANCE_ID).color1)
#define CG_CURVE_WIDTHS (CURVE_DATA(CG_INSTANCE_ID).widths)
#define CG_CURVE_FEATHER (CURVE_DATA(CG_INSTANCE_ID).feather)
#define CG_CURVE_FLAGS (CURVE_DATA(CG_INSTANCE_ID).flags)
// (originX, originY, dirX, dirY) of a linear gradient, in the same space as p0/p1/p2 and scaled so
// t = dot(p - origin, dir) runs 0..1 from color0 to color1. Read only when CG_STROKE_FLAG_GRADIENT is
// set alongside the fill bit -- this is the "spare capacity a future third reading can claim" the note
// above anticipated, and it claims color1 back with it.
#define CG_CURVE_GRADIENT (CURVE_DATA(CG_INSTANCE_ID).gradient)

// Half-extent the stroke adds beyond the control hull: the widest the stroke ever gets, plus the
// feather ramp, plus one unit of slack.
//
// THE sqrt(2) IS LOAD-BEARING, and a plain halfWidth is NOT enough. A square cap's corner sits at
// halfWidth along the tangent PLUS halfWidth along the normal, so on a 45-degree stroke it reaches
// halfWidth*sqrt(2) along a single axis -- and this box is axis-aligned. Padding by halfWidth alone
// clips the far corners of every diagonal square cap, at a rate that grows with stroke width and is
// exactly zero for axis-aligned strokes, which is precisely the case a test row is most likely to
// use. Round and butt caps only need halfWidth; paying sqrt(2) for all three costs a slightly larger
// quad and removes a whole class of orientation-dependent bug.
//
// CAP_ARROW REACHES FAR FURTHER THAN sqrt(2), AND MUST BE CHECKED FOR EXPLICITLY. The wedge in
// stroke.glsl's _stroke_cap_dist extends CG_STROKE_ARROW_LENGTH (6) half-widths past the endpoint,
// not ~1.4. Padding by sqrt(2) when an arrow cap is in use derives a bounding quad that ends well
// short of the wedge's actual tip -- the vertex stage clips geometry the fragment stage would
// otherwise draw correctly, so an arrowhead's point is silently cut flat. This is exactly the kind
// of bug that renders something plausible (a stroke that LOOKS like an arrow, just a slightly short
// one) rather than failing, which is why it is worth this comment rather than a passing "6.0 covers
// every cap" bump: paying 6x padding unconditionally on every ordinary stroke this engine draws --
// the overwhelming majority, none of which use CAP_ARROW -- would be real wasted overdraw for a
// cap style most curves never touch, so this checks the packed flags instead of assuming the worst.
//
// The 6.0 below is a literal, not a shared macro with stroke.glsl's CG_STROKE_ARROW_LENGTH:
// cg_env.glsl is auto-included before any material's own #include lines (see the class doc on
// CgUiPaintContext... no, on CgQuadRenderer/CgVectorRenderer), so it must stay meaningful even for a
// shader that never includes stroke.glsl. If CG_STROKE_ARROW_LENGTH ever changes, this must change
// with it -- there is deliberately no single source of truth to keep this file self-contained.
//
// Packing reminder: CAP_ARROW = 3, packed 2 bits per end (start in bits 0-1, end in bits 2-3, see
// CgCurveSplitter#packCaps) -- so "either end is an arrow" is `(flags & 3) == 3 || ((flags >> 2) &
// 3) == 3`. This also correctly evaluates false for a FILL instance (bit 4 set, low 4 bits 0),
// which is why the check does not need to test the FILL flag separately.
//
// A CELL (bit 8, CgVectorRenderer.FLAG_CELL -- a literal here for the same reason as the 6.0) keeps its
// fourth corner in `widths`, so `widths` is a position rather than a reach: the hull takes it as a
// point and pads by the one pixel an exact-area edge can extend to.
bool cg_curve_is_cell(float flags) {
    return (int(flags + 0.5) & 256) != 0;
}
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
#define CG_CURVE_WORLD_POS (CG_CURVE_HULL_MIN + vec3(cg_Position.xy, 0.0) * (CG_CURVE_HULL_MAX - CG_CURVE_HULL_MIN))
