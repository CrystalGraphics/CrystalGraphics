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
    vec4 cg_DepthParams; // x: 1 when depth is reversed (nearer is greater); y: 1 when clip depth runs 0..1
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
// cg_DepthBuffer: scene depth snapshot, in the host's depth format, captured just before the opaque pass.
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
// Exact for any perspective cg_ProjMatrix built for the pass's convention, reversed or not.
float cg_LinearEyeDepth(float windowDepth) {
    float ndc = cg_DepthParams.y > 0.5 ? windowDepth : windowDepth * 2.0 - 1.0;
    return cg_ProjMatrix[3][2] / (ndc + cg_ProjMatrix[2][2]);
}
#define CG_SCENE_EYE_DEPTH(uv) cg_LinearEyeDepth(texture(cg_DepthBuffer, (uv)).r)
#define CG_DEPTH_REVERSED      (cg_DepthParams.x > 0.5)

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

// -- Per-renderer environments ----------------------------------------------
// Everything above is universal. A renderer's own macros are NOT: they live beside the buffer
// they read, in env/buffer/<token>.glsl, and arrive only when a shader declares
// `#pragma cg_use <token>`. Three quarters of this file used to be CgQuadRenderer's and
// CgVectorRenderer's, paid for by every shader including the ones that draw neither.
