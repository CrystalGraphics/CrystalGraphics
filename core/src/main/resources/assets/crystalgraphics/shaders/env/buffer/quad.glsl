// CgQuadRenderer's shader environment.
//
// INJECTED BY `#pragma cg_use quad`, immediately after the instance buffer's own
// declaration -- see CgEngineBufferRegistry, which holds the path, and
// CgMaterialShaderCompiler.appendEngineBufferEnv, which emits the include. A shader that does
// not declare the pragma never sees any of this, which is the point: only a minority of
// shaders draw through the renderer, and cg_env.glsl is included by all of them.
//
// Compiled into BOTH stages, like everything cg_env pulls in. Fragment-only code guards itself
// with `#ifndef CG_VERTEX_STAGE` -- never `#ifdef CG_FRAGMENT_STAGE`, which raw .vert/.frag
// get neither of.
#pragma once

// -- CgQuadRenderer convenience macros ---------------------------------------
// CgQuadRenderer (gl/render/CgQuadRenderer.java) is a general SSBO/TBO-backed instanced
// quad renderer with a fixed per-instance schema: vec3 origin/right/up (world-space quad
// origin + two edge vectors, CPU-baked per instance via Quad.pose(...) -- see
// plan/text-instancing.md Decision 2), vec2 uv0/uv1, vec4 color. These
// macros hardcode both the attach() macro name (QUAD_DATA, = CgQuadRenderer.MACRO_NAME --
// fixed, not caller-chosen) and CG_INSTANCE_ID, so no `QuadInstance inst = QUAD_DATA(...)`
// declaration is needed in the shader at all. Zero-argument, so QUAD_DATA(CG_INSTANCE_ID)
// is textually repeated per use; this is the same repeated-texelFetch-on-the-same-index
// pattern the TBO struct getter codegen already relies on being driver-CSE'd (see
// gl/material/parse/CgGlslEmitter.java's own comment on appendTboFieldFetch). Vertex stage
// only, and only valid when the material's #type provides a 2D cg_Position/cg_TexCoord0
// (i.e. CgQuadRenderer's own unit quad mesh, #type pos2_uv2_col4ub).
//
// These are DEFINED unconditionally, here, but only RESOLVE in a shader that declares:
//
//     #pragma cg_use quad
//
// which is what attaches the buffer QUAD_DATA refers to. Defining them unconditionally costs
// nothing -- an unexpanded macro is not a declaration, so it burns no binding point and no
// texture unit, unlike the buffer itself (which is exactly why the buffer is opt-in and these
// are not). Using one without the pragma is rejected at parse time with a message naming the
// missing line, so the half-state never reaches the GLSL compiler.
//
// CG_QUAD_NORMAL -- every quad instance is flat (a plane spanned by right/up), so its face
// normal is fully derivable from data already present; no per-instance normal field is stored
// (nothing to desync from the actual right/up if only one were ever updated).
//
// CG_QUAD_ATLAS_LAYER -- sampler2DArray layer index for atlas-backed quad consumers (e.g.
// text.shader). 0 for ordinary sampler2D consumers, which never reference this macro at
// all. Not bridged as a `flat` fragment-stage varying the way CG_INSTANCE_ID is: every
// vertex of one quad instance writes the textually identical QUAD_DATA(...).atlasLayer
// value (a true per-instance constant, not a per-vertex quantity), so interpolating
// across the quad's two triangles reproduces that same value everywhere -- there is no
// per-vertex data to lose by not marking it flat. (The .shader v2f struct DSL has no
// flat-qualifier syntax to ask for regardless -- see CgMaterialShaderCompiler, which only
// wires one compiler-generated flat varying, cg_InstanceId itself.)
//
//   gl_Position = cg_ProjMatrix * vec4(CG_QUAD_WORLD_POS, 1.0);
//   o.uv = CG_QUAD_UV;
//   o.color = CG_QUAD_COLOR;
//   o.normalWs = CG_QUAD_NORMAL;
//   o.atlasLayer = CG_QUAD_ATLAS_LAYER;
#define CG_QUAD_WORLD_POS (QUAD_DATA(CG_INSTANCE_ID).origin + cg_Position.x * QUAD_DATA(CG_INSTANCE_ID).right + cg_Position.y * QUAD_DATA(CG_INSTANCE_ID).up)
#define CG_QUAD_UV (mix(QUAD_DATA(CG_INSTANCE_ID).uv0, QUAD_DATA(CG_INSTANCE_ID).uv1, cg_TexCoord0))
#define CG_QUAD_COLOR (QUAD_DATA(CG_INSTANCE_ID).color)
#define CG_QUAD_NORMAL (normalize(cross(QUAD_DATA(CG_INSTANCE_ID).right, QUAD_DATA(CG_INSTANCE_ID).up)))
#define CG_QUAD_ATLAS_LAYER (QUAD_DATA(CG_INSTANCE_ID).atlasLayer)

// -- CG_QUAD_EDGE_* -- analytic edge antialiasing for SCREEN-SPACE quads ----------------------------
//
// A quad's edges are decided by the rasteriser: a pixel is in or out. Axis-aligned that is right --
// the edge snaps to a pixel, and two quads meeting on a fractional boundary snap to the SAME pixel, so
// a scrolling list has no seams. Rotated or sheared it is a staircase. These helpers do for a quad
// what a Cell does for a tessellated fill: grow it by half a pixel in the vertex stage so the edge
// pixels are shaded at all, then compute in the fragment stage the exact area a straight edge leaves
// of each pixel, from the interpolated parameter and the quad's own extents. Axis-aligned quads are
// left exactly alone -- the decision is made per instance from right/up.
//
// EVERY EDGE OF A ROTATED QUAD IS SOFT. There was once a per-edge opt-out, for a quad that abuts
// another and must keep their shared edge hard -- two softened edges meeting compose to three quarters
// and read as a hairline. Nothing tiles with separate quads any more: a nine-slice is one draw that
// remaps its regions per pixel, so the seams are inside a fragment shader rather than between quads.
//
// SCREEN SPACE ONLY: "half a pixel" is half a unit of the space right/up are in, which is a window
// pixel under an ortho projection and nothing in particular under a perspective one. A 3D quad
// material has no business calling these. Text does not either: a glyph's edge is the atlas's, and
// the quad around it is transparent margin.
//
//   Vertex:    vec2 param = CG_QUAD_EDGE_PARAM;                 // grown when soft, else cg_Position
//              gl_Position = cg_ProjMatrix * vec4(CG_QUAD_EDGE_WORLD_POS(param), 1.0);
//              o.param = param;                                 // carry it, not uv
//   Fragment:  vec2 uv = CG_QUAD_EDGE_UV(i.param);              // clamped: the pad never samples past the rect
//              alpha *= CG_QUAD_EDGE_COVERAGE(i.param);
//
// The parameter is interpolated and the uv derived from it in the fragment, not the other way round:
// clamping at the vertices would compress the texture across the grown quad by the pad.
bool cg_quad_edge_rotated(vec3 right, vec3 up) {
    return abs(right.y) > 1.0e-4 || abs(up.x) > 1.0e-4;
}
// Perpendicular extent of the quad across its u and across its v, in the units of right/up: the
// distance from the left edge to the right edge measured along their normal, and top to bottom.
vec2 cg_quad_edge_extent(vec3 right, vec3 up) {
    float area = abs(right.x * up.y - right.y * up.x);
    return vec2(area / max(length(up.xy), 1.0e-6), area / max(length(right.xy), 1.0e-6));
}
// Width of the reconstruction filter, in pixels. One is the box filter -- the exact area a straight
// edge covers -- and it is also the roping every thin rotated line shows, its brightness beading as
// it drifts across rows. Slightly wider trades a little sharpness for a smoother line, on rotated
// content only. The half-pixel pad grows with it.
#define CG_QUAD_EDGE_FILTER 1.5
vec2 cg_quad_edge_param(vec2 local, vec3 right, vec3 up) {
    if (!cg_quad_edge_rotated(right, up)) return local;
    vec2 e = (0.5 * CG_QUAD_EDGE_FILTER) / max(cg_quad_edge_extent(right, up), vec2(1.0e-6));
    return local * (1.0 + 2.0 * e) - e;
}
float cg_quad_edge_coverage(vec2 param, vec3 right, vec3 up) {
    if (!cg_quad_edge_rotated(right, up)) return 1.0;
    vec2 h = cg_quad_edge_extent(right, up);
    // Per pair of opposite edges: the area inside each, summed, less the whole pixel -- exact for a
    // straight edge through a pixel, and for the two together when they are further apart than one.
    vec2 near = clamp(0.5 + param * h / CG_QUAD_EDGE_FILTER, 0.0, 1.0);
    vec2 far = clamp(0.5 + (1.0 - param) * h / CG_QUAD_EDGE_FILTER, 0.0, 1.0);
    vec2 c = near + far - 1.0;
    return clamp(c.x, 0.0, 1.0) * clamp(c.y, 0.0, 1.0);
}
#define CG_QUAD_EDGE_PARAM cg_quad_edge_param(cg_Position.xy, QUAD_DATA(CG_INSTANCE_ID).right, QUAD_DATA(CG_INSTANCE_ID).up)
#define CG_QUAD_EDGE_WORLD_POS(param) (QUAD_DATA(CG_INSTANCE_ID).origin + (param).x * QUAD_DATA(CG_INSTANCE_ID).right + (param).y * QUAD_DATA(CG_INSTANCE_ID).up)
#define CG_QUAD_EDGE_UV(param) (mix(QUAD_DATA(CG_INSTANCE_ID).uv0, QUAD_DATA(CG_INSTANCE_ID).uv1, clamp(param, 0.0, 1.0)))
#define CG_QUAD_EDGE_COVERAGE(param) cg_quad_edge_coverage(param, QUAD_DATA(CG_INSTANCE_ID).right, QUAD_DATA(CG_INSTANCE_ID).up)
#define CG_QUAD_EDGE_ROTATED cg_quad_edge_rotated(QUAD_DATA(CG_INSTANCE_ID).right, QUAD_DATA(CG_INSTANCE_ID).up)

#define CG_QUAD_UV_RECT vec4(min(QUAD_DATA(CG_INSTANCE_ID).uv0, QUAD_DATA(CG_INSTANCE_ID).uv1), max(QUAD_DATA(CG_INSTANCE_ID).uv0, QUAD_DATA(CG_INSTANCE_ID).uv1))
