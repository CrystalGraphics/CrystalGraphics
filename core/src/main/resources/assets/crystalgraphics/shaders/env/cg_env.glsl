#pragma once
// Naming: cg_PascalCase = GLSL identifiers; CG_UPPER_SNAKE = macros

// Per-frame uniforms (view + proj + time + resolution). Block index wired post-link via glUniformBlockBinding.
layout(std140) uniform CgFrameBlock {
    mat4 cg_ViewMatrix;
    mat4 cg_ProjMatrix;
    vec4 cg_Time;        // (t/20, t, t*2, t*3) - seconds, scaled like Unity _Time
    vec2 cg_Resolution;  // viewport size in pixels
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

// -- CgQuadRenderer convenience macros ---------------------------------------
// CgQuadRenderer (gl/render/CgQuadRenderer.java) is a general SSBO/TBO-backed instanced
// quad renderer with a fixed per-instance schema: vec3 origin/right/up (world-space quad
// origin + two edge vectors, CPU-baked per instance via Quad.pose(...) — see
// CGTEXTRENDERER_INSTANCING_FOUNDATIONS.md Decision 2), vec2 uv0/uv1, vec4 color. These
// macros hardcode both the attach() macro name (QUAD_DATA, = CgQuadRenderer.MACRO_NAME —
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
// nothing — an unexpanded macro is not a declaration, so it burns no binding point and no
// texture unit, unlike the buffer itself (which is exactly why the buffer is opt-in and these
// are not). Using one without the pragma is rejected at parse time with a message naming the
// missing line, so the half-state never reaches the GLSL compiler.
//
// CG_QUAD_NORMAL — every quad instance is flat (a plane spanned by right/up), so its face
// normal is fully derivable from data already present; no per-instance normal field is stored
// (nothing to desync from the actual right/up if only one were ever updated).
//
// CG_QUAD_ATLAS_LAYER — sampler2DArray layer index for atlas-backed quad consumers (e.g.
// text.shader). 0 for ordinary sampler2D consumers, which never reference this macro at
// all. Not bridged as a `flat` fragment-stage varying the way CG_INSTANCE_ID is: every
// vertex of one quad instance writes the textually identical QUAD_DATA(...).atlasLayer
// value (a true per-instance constant, not a per-vertex quantity), so interpolating
// across the quad's two triangles reproduces that same value everywhere — there is no
// per-vertex data to lose by not marking it flat. (The .shader v2f struct DSL has no
// flat-qualifier syntax to ask for regardless — see CgMaterialShaderCompiler, which only
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

// -- CgCurveRenderer convenience macros --------------------------------------
// CgCurveRenderer (gl/render/CgCurveRenderer.java) is an SSBO/TBO-backed instanced renderer for
// quadratic Bezier strokes: vec3 p0/p1/p2 (control points, pose baked in CPU-side), vec4
// color0/color1 (gradient along the curve), vec2 widths (start/end HALF-width, tapered), float
// feather (edge softness), float flags (cap style). Same hardcoded-macro-name arrangement as the
// CG_QUAD_* block above: CURVE_DATA = CgCurveRenderer.MACRO_NAME.
//
// These are DEFINED unconditionally but only RESOLVE in a shader that declares:
//
//     #pragma cg_use curve
//
// Using one without the pragma is rejected at parse time naming the missing line -- and needs no
// parser change to do it, because CgShaderParser derives the "CG_CURVE_" family textually from
// "CURVE_DATA".
//
// ── STAGE AVAILABILITY — THE ONE REAL DIFFERENCE FROM CG_QUAD_* ──────────────
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
// is DERIVED here from the convex hull of the three control points (a Bezier is contained by its
// control hull), expanded by max(widths) + feather so the antialiased edge is never clipped. That
// is why the CPU writes no bounds: they cannot desync from the control points if they are computed
// from them. Planar by construction -- see CgCurveRenderer's class doc on why v1 curves are 2D.
//
//   Vertex:    gl_Position = cg_ProjMatrix * vec4(CG_CURVE_WORLD_POS, 1.0);
//   Fragment:  float t; float d = sdf_bezier(i.posXy, CG_CURVE_P0.xy, CG_CURVE_P1.xy, CG_CURVE_P2.xy, t);
//              float halfW = mix(CG_CURVE_WIDTHS.x, CG_CURVE_WIDTHS.y, t);
//              vec4 col = mix(CG_CURVE_COLOR0, CG_CURVE_COLOR1, t);
#define CG_CURVE_P0 (CURVE_DATA(CG_INSTANCE_ID).p0)
#define CG_CURVE_P1 (CURVE_DATA(CG_INSTANCE_ID).p1)
#define CG_CURVE_P2 (CURVE_DATA(CG_INSTANCE_ID).p2)
#define CG_CURVE_COLOR0 (CURVE_DATA(CG_INSTANCE_ID).color0)
#define CG_CURVE_COLOR1 (CURVE_DATA(CG_INSTANCE_ID).color1)
#define CG_CURVE_WIDTHS (CURVE_DATA(CG_INSTANCE_ID).widths)
#define CG_CURVE_FEATHER (CURVE_DATA(CG_INSTANCE_ID).feather)
#define CG_CURVE_FLAGS (CURVE_DATA(CG_INSTANCE_ID).flags)

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
#define CG_CURVE_PAD (max(CG_CURVE_WIDTHS.x, CG_CURVE_WIDTHS.y) * 1.41422 + CG_CURVE_FEATHER + 1.0)
#define CG_CURVE_HULL_MIN (min(min(CG_CURVE_P0, CG_CURVE_P1), CG_CURVE_P2) - vec3(CG_CURVE_PAD, CG_CURVE_PAD, 0.0))
#define CG_CURVE_HULL_MAX (max(max(CG_CURVE_P0, CG_CURVE_P1), CG_CURVE_P2) + vec3(CG_CURVE_PAD, CG_CURVE_PAD, 0.0))
#define CG_CURVE_WORLD_POS (CG_CURVE_HULL_MIN + vec3(cg_Position.xy, 0.0) * (CG_CURVE_HULL_MAX - CG_CURVE_HULL_MIN))


