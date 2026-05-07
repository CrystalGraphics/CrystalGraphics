#pragma once
// Naming: cg_PascalCase = GLSL identifiers; CG_UPPER_SNAKE = macros

// Per-frame uniforms (view + proj + time + resolution). Binding point injected by compiler.
#ifdef CG_USE_SSBO
layout(std140, binding = CG_FRAME_BLOCK_BINDING) uniform CgFrameBlock {
#else
layout(std140) uniform CgFrameBlock {
#endif
    mat4 cg_ViewMatrix;
    mat4 cg_ProjMatrix;
    vec4 cg_Time;        // (t/20, t, t*2, t*3) — seconds, scaled like Unity _Time
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
layout(std430, binding = CG_OBJECT_BUFFER_BINDING) readonly buffer CgShaderBuffer {
    CgObjectData cg_Objects[];
};

// -- Per-Instance Object Data (TBO fallback path: material baseline GL 3.3) --
#else
uniform samplerBuffer cg_ObjectTBO;

CgObjectData cg_FetchObjectData(int instanceId) {
    int base = instanceId * 12;
    CgObjectData data;
    data.modelMatrix = mat4(
        texelFetch(cg_ObjectTBO, base),
        texelFetch(cg_ObjectTBO, base + 1),
        texelFetch(cg_ObjectTBO, base + 2),
        texelFetch(cg_ObjectTBO, base + 3)
    );
    data.normalMatrix = mat4(
        texelFetch(cg_ObjectTBO, base + 4),
        texelFetch(cg_ObjectTBO, base + 5),
        texelFetch(cg_ObjectTBO, base + 6),
        texelFetch(cg_ObjectTBO, base + 7)
    );
    data.custom0 = texelFetch(cg_ObjectTBO, base + 8);
    data.custom1 = texelFetch(cg_ObjectTBO, base + 9);
    data.custom2 = texelFetch(cg_ObjectTBO, base + 10);
    data.custom3 = texelFetch(cg_ObjectTBO, base + 11);
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

// -- Time and Resolution Macros ---------------------------------------------
#define CG_TIME           (cg_Time.y)   // most useful: raw seconds
#define CG_TIME_VEC4      (cg_Time)     // all four: t/20, t, t*2, t*3
#define CG_RESOLUTION     (cg_Resolution)

// -- Convenience Macros ------------------------------------------------------
#define CG_MATRIX_MVP (cg_ProjMatrix * cg_ViewMatrix * CG_OBJECT_TO_WORLD)

// -- Vertex Attribute Aliases (vertex stage only) ----------------------------
// No explicit layout(location=N) — attribute binding is done by
// CgShaderFactory.fromSource(vert, frag, format) before link.
#ifdef CG_VERTEX_STAGE
in vec3 cg_Position;
in vec2 cg_TexCoord0;
in vec3 cg_Normal;
#endif
