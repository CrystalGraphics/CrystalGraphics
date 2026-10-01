#pragma once
// Naming: cg_PascalCase = GLSL identifiers; CG_UPPER_SNAKE = macros

// Per-pass uniforms (view + proj + time + resolution + camera), one block per pass. Block index wired post-link
// via glUniformBlockBinding. FIELD ORDER MUST MATCH CgPassConstants.FORMAT -- std140 offsets are positional, so a
// field added to one and not the other silently reads its neighbour.
layout(std140) uniform CgFrameBlock {
    mat4 cg_ViewMatrix;
    mat4 cg_ProjMatrix;
    vec4 cg_Time;        // (t/20, t, t*2, t*3) - seconds, scaled like Unity _Time
    vec2 cg_Resolution;  // viewport size in pixels
    vec4 cg_CameraPos;   // world-space camera position in .xyz; .w unused, see CG_CAMERA_WORLD_POS
    vec4 cg_DepthParams; // x: 1 when depth is reversed (nearer is greater); y: 1 when clip depth runs 0..1
    vec4 cg_WorldOrigin; // where world space's origin is in absolute coordinates: the camera, in a world pass
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
// The vertex stage adds cg_InstanceBase: where a batch's instances start in its kind's upload for the pass, 0 for
// a draw that uploaded its own. The fragment stage reads the sum as a flat varying the compiler wires.
#ifdef CG_VERTEX_STAGE
uniform int cg_InstanceBase;
#define CG_INSTANCE_ID (gl_InstanceID + cg_InstanceBase)
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
// cg_DepthBuffer: scene depth snapshot, in the host's depth format, taken at the start of the world stage that
// reads it: in an opaque pass the host's world, in a transparent pass with the world renderer's opaque draws too.
// Bound to CgBindingPoints.DEPTH_TEXTURE_UNIT in both vertex and fragment stages of every pass.
// Do NOT use that texture unit in material Properties.
uniform sampler2D cg_DepthBuffer;

// Raw depth is the host's convention -- Minecraft 26.2 is reversed-Z with a 0..1 clip range, earlier
// versions are not -- so compare depths as eye distances, never as raw values:
//
//     float scene = CG_SCENE_EYE_DEPTH(screenUv);
//     float self  = cg_LinearEyeDepth(gl_FragCoord.z);
//     float fade  = saturate((scene - self) / _FadeDistance);   // soft particles, water edges
//
// Exact for an orthographic projection, and for a perspective one in the pass's convention, reversed or not, times
// any affine transform of eye space: Minecraft multiplies its view bobbing and hurt shake into the projection, which
// scales [2][2] and tilts the w row. The perspective's own terms are recovered from both rows, since read off [2][2]
// alone a half-degree bob moved a point at render distance by half its distance or more.
float cg_LinearEyeDepth(float windowDepth) {
    float ndc = cg_DepthParams.y > 0.5 ? windowDepth : windowDepth * 2.0 - 1.0;
    if (cg_ProjMatrix[2][3] == 0.0) return (cg_ProjMatrix[3][2] - ndc) / cg_ProjMatrix[2][2];
    float p22 = -cg_ProjMatrix[2][2] / cg_ProjMatrix[2][3];
    float p32 = cg_ProjMatrix[3][2] + p22 * cg_ProjMatrix[3][3];
    return p32 / (ndc + p22);
}
#define CG_SCENE_EYE_DEPTH(uv) cg_LinearEyeDepth(texture(cg_DepthBuffer, (uv)).r)
#define CG_DEPTH_REVERSED      (cg_DepthParams.x > 0.5)

// -- Time and Resolution Macros ---------------------------------------------
#define CG_TIME           (cg_Time.y)   // most useful: raw seconds
#define CG_TIME_VEC4      (cg_Time)     // all four: t/20, t, t*2, t*3
#define CG_RESOLUTION     (cg_Resolution)

// -- Convenience Macros ------------------------------------------------------
#define CG_MATRIX_MVP (cg_ProjMatrix * cg_ViewMatrix * CG_OBJECT_TO_WORLD)

// Where the camera is, in world space: a uniform, since the CPU has it and a fragment would otherwise invert the
// view to get it. A world pass is camera-relative, as Minecraft draws, so there it is the origin.
#define CG_CAMERA_WORLD_POS (cg_CameraPos.xyz)

// A world-space point in absolute coordinates: for an effect that must not move with the camera. A float: at a
// million blocks it resolves to 1/16.
#define CG_ABSOLUTE_WORLD_POS(p) ((p) + cg_WorldOrigin.xyz)

// -- Per-renderer environments ----------------------------------------------
// Everything above is universal. A renderer's own macros are NOT: they live beside the buffer
// they read, in env/buffer/<token>.glsl, and arrive only when a shader declares
// `#pragma cg_use <token>`. Three quarters of this file used to be CgQuadRenderer's and
// CgVectorRenderer's, paid for by every shader including the ones that draw neither.
