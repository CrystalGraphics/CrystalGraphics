#pragma once
// Naming: cg_PascalCase = GLSL identifiers; CG_UPPER_SNAKE = macros

// Per-frame uniforms (view + proj + time + resolution + camera). Block index wired post-link via
// glUniformBlockBinding. FIELD ORDER MUST MATCH CgRenderPipeline.FRAME_FORMAT -- std140 offsets are
// positional, so a field added to one and not the other silently reads its neighbour.
layout(std140) uniform CgFrameBlock {
    mat4 cg_ViewMatrix;
    mat4 cg_ProjMatrix;
    vec4 cg_Time;        // (t/20, t, t*2, t*3) - seconds, scaled like Unity _Time
    vec2 cg_Resolution;  // viewport size in pixels
    vec4 cg_CameraPos;   // world-space camera position in .xyz; .w unused, see CG_CAMERA_WORLD_POS
};

// -- Per-Instance Object Data (SSBO path: GL 4.3+/ARB) ----------------------
struct CgObjectData {
    mat4 modelMatrix;
    mat4 normalMatrix;
    vec4 custom0;
    vec4 custom1;
    vec4 custom2;
    vec4 custom3;
};

#ifdef CG_USE_SSBO
layout(std430) readonly buffer CgObjectDataBuffer {
    CgObjectData cg_Objects[];
};

// -- Per-Instance Object Data (TBO fallback path: material baseline GL 3.3) --
#else
uniform samplerBuffer CgObjectDataBuffer;

CgObjectData cg_FetchObjectData(int instanceId) {
    int base = instanceId * 12;
    CgObjectData data;
    data.modelMatrix = mat4(
        texelFetch(CgObjectDataBuffer, base),
        texelFetch(CgObjectDataBuffer, base + 1),
        texelFetch(CgObjectDataBuffer, base + 2),
        texelFetch(CgObjectDataBuffer, base + 3)
    );
    data.normalMatrix = mat4(
        texelFetch(CgObjectDataBuffer, base + 4),
        texelFetch(CgObjectDataBuffer, base + 5),
        texelFetch(CgObjectDataBuffer, base + 6),
        texelFetch(CgObjectDataBuffer, base + 7)
    );
    data.custom0 = texelFetch(CgObjectDataBuffer, base + 8);
    data.custom1 = texelFetch(CgObjectDataBuffer, base + 9);
    data.custom2 = texelFetch(CgObjectDataBuffer, base + 10);
    data.custom3 = texelFetch(CgObjectDataBuffer, base + 11);
    return data;
}
#endif

// -- Instance ID bridge -----------------------------------------------------
// In the vertex stage, gl_InstanceID is directly available.
// In the fragment stage, cg_InstanceId is a flat varying wired by the compiler.
#ifdef CG_VERTEX_STAGE
#define CG_INSTANCE_ID gl_InstanceID
#else
flat in int cg_InstanceId;
#define CG_INSTANCE_ID cg_InstanceId
#endif

// -- Object Data Definition-----------------------------------------------------
#ifdef CG_USE_SSBO
#define CG_OBJECT_DATA cg_Objects[CG_INSTANCE_ID]
#else
#define CG_OBJECT_DATA cg_FetchObjectData(CG_INSTANCE_ID)
#endif

// -- Data Macros ------------------------------------------------------
#define CG_OBJECT_TO_WORLD (CG_OBJECT_DATA.modelMatrix)
#define CG_NORMAL_MATRIX mat3(CG_OBJECT_DATA.normalMatrix)
#define CG_OBJECT_CUSTOM0 (CG_OBJECT_DATA.custom0)
#define CG_OBJECT_CUSTOM1 (CG_OBJECT_DATA.custom1)
#define CG_OBJECT_CUSTOM2 (CG_OBJECT_DATA.custom2)
#define CG_OBJECT_CUSTOM3 (CG_OBJECT_DATA.custom3)

// -- Scene samplers (auto-bound by the engine; do not redeclare or bind manually) -----------
// cg_DepthBuffer: scene depth snapshot (DEPTH24_STENCIL8) captured just before the opaque pass.
// Bound to CgBindingPoints.DEPTH_TEXTURE_UNIT in both vertex and fragment stages of every pass.
// Do NOT use that texture unit in material Properties.
uniform sampler2D cg_DepthBuffer;

// -- Time and Resolution Macros ---------------------------------------------
#define CG_TIME           (cg_Time.y)   // most useful: raw seconds
#define CG_TIME_VEC4      (cg_Time)     // all four: t/20, t, t*2, t*3
#define CG_RESOLUTION     (cg_Resolution)

// -- Convenience Macros ------------------------------------------------------
#define CG_MATRIX_MVP (cg_ProjMatrix * cg_ViewMatrix * CG_OBJECT_TO_WORLD)

// Where the camera is, in world space.
//
// A UNIFORM, not derived. It was briefly `-(transpose(mat3(cg_ViewMatrix)) * cg_ViewMatrix[3].xyz)`,
// which is correct -- a view matrix is rigid, so its inverse translation is -Rt * t -- and which costs a
// 3x3 transpose and a matrix-vector multiply IN EVERY FRAGMENT THAT READS IT. The CPU already has the
// answer: CgFrameData.deriveFromViewMatrix computes cameraPos once per frame, and it was being thrown
// away. Uploading four floats a frame beats recomputing them a few million times.
//
// Still a macro at the call site, so shader code never touches the .xyz swizzle or the padding.
#define CG_CAMERA_WORLD_POS (cg_CameraPos.xyz)

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

// -- CG_TEXEL_AA -- texel antialiasing for rotated pixel art -----------------------------------------
//
// The edge helpers above soften a quad's OUTLINE. Its texels are another matter: a nearest-sampled
// sprite rotated eight degrees is a staircase along every line inside it, because each screen pixel
// snaps to one texel. Linear filtering trades that for blur everywhere. This is the pixel-art
// compromise -- nearest everywhere except within one screen pixel of a texel boundary, where the two
// texels blend linearly. Blocks stay flat and crisp; their boundaries get exactly the antialiasing a
// geometric edge gets. Axis-aligned at an integer scale it reproduces nearest bit for bit, and a
// consumer gates it on CG_QUAD_EDGE_ROTATED regardless, so nothing at rest changes.
//
// Four taps, because the atlases this is for are nearest-filtered and hardware linear is not there
// to do the blend. `rect` is the quad's texel rectangle (uv0, uv1 in texels): the taps are held inside
// it, so a sprite in an atlas never reads its neighbour.
//
// fwidth is fragment-only, hence the stage guard -- cg_env is compiled into both stages.
#ifndef CG_VERTEX_STAGE
vec2 cg_texel_aa_position(vec2 texel, vec4 rect) {
    vec2 width = max(fwidth(texel), vec2(1.0e-6));
    // Pivot on the nearest texel BOUNDARY: further than half a screen pixel from it the sample sits on
    // the texel centre of its own side (flat), and within that band it slides linearly across to the
    // other side's centre (the blend). Pivoting on the centre instead puts the blend in the middle of
    // every texel and leaves the boundaries hard, which is precisely backwards.
    vec2 boundary = floor(texel + 0.5);
    vec2 p = boundary + clamp((texel - boundary) / (width * CG_QUAD_EDGE_FILTER), -0.5, 0.5);
    return clamp(p, rect.xy, rect.zw);
}
// The two texel columns (or rows) a bilinear tap at `p` reads, and the weight of the second -- with
// both held inside the texels the rect covers, whether the rect is stated on texel boundaries or,
// as a sprite sheet usually is, inset to texel centres. Either way a sprite never reads its neighbour.
void cg_texel_aa_taps(float p, float lo, float hi, out float i0, out float i1, out float f) {
    float base = floor(p - 0.5);
    f = p - 0.5 - base;
    float first = floor(lo), last = max(first, ceil(hi) - 1.0);
    i0 = clamp(base, first, last);
    i1 = clamp(base + 1.0, first, last);
}
vec4 cg_texel_aa_sample(sampler2D tex, vec2 uv, vec4 uvRect) {
    vec2 size = vec2(textureSize(tex, 0));
    vec4 rect = uvRect * size.xyxy;
    vec2 p = cg_texel_aa_position(uv * size, rect);
    vec2 i0, i1, f;
    cg_texel_aa_taps(p.x, rect.x, rect.z, i0.x, i1.x, f.x);
    cg_texel_aa_taps(p.y, rect.y, rect.w, i0.y, i1.y, f.y);
    vec4 a = texture(tex, (vec2(i0.x, i0.y) + 0.5) / size), b = texture(tex, (vec2(i1.x, i0.y) + 0.5) / size);
    vec4 c = texture(tex, (vec2(i0.x, i1.y) + 0.5) / size), d = texture(tex, (vec2(i1.x, i1.y) + 0.5) / size);
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
vec4 cg_texel_aa_sample(sampler2DArray tex, vec3 uvw, vec4 uvRect) {
    vec2 size = vec2(textureSize(tex, 0).xy);
    vec4 rect = uvRect * size.xyxy;
    vec2 p = cg_texel_aa_position(uvw.xy * size, rect);
    vec2 i0, i1, f;
    cg_texel_aa_taps(p.x, rect.x, rect.z, i0.x, i1.x, f.x);
    cg_texel_aa_taps(p.y, rect.y, rect.w, i0.y, i1.y, f.y);
    vec4 a = texture(tex, vec3((vec2(i0.x, i0.y) + 0.5) / size, uvw.z));
    vec4 b = texture(tex, vec3((vec2(i1.x, i0.y) + 0.5) / size, uvw.z));
    vec4 c = texture(tex, vec3((vec2(i0.x, i1.y) + 0.5) / size, uvw.z));
    vec4 d = texture(tex, vec3((vec2(i1.x, i1.y) + 0.5) / size, uvw.z));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
#endif
#define CG_QUAD_UV_RECT vec4(min(QUAD_DATA(CG_INSTANCE_ID).uv0, QUAD_DATA(CG_INSTANCE_ID).uv1), max(QUAD_DATA(CG_INSTANCE_ID).uv0, QUAD_DATA(CG_INSTANCE_ID).uv1))

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


