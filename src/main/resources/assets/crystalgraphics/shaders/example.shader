#type spatial
Queue = "Transparent"
RenderState {
    // ── Blend ──────────────────────────────────────────────────────────────
    // Global (all render targets):
    Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA
    // Separate RGB + Alpha factors:
    // Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA, ONE ONE_MINUS_SRC_ALPHA
    // Per-MRT target (index 0..7, for future MRT):
    // Blend 0 SRC_ALPHA ONE_MINUS_SRC_ALPHA
    // Blend 1 ONE ONE
    // ── BlendEquation (was BlendOp) ────────────────────────────────────────
    // ADD | SUB | REV_SUB | MIN | MAX
    BlendEquation ADD
    // Separate RGB + Alpha equations:
    // BlendEquation ADD, ADD
    // Per-MRT:
    // BlendEquation 0 ADD
    // BlendEquation 1 ADD, REV_SUB
    // ── Depth ─────────────────────────────────────────────────────────────
    // LESS | LEQUAL | EQUAL | GEQUAL | GREATER | NOT_EQUAL | ALWAYS | NEVER
    DepthTest LEQUAL
    DepthWrite OFF
    // ── Cull ──────────────────────────────────────────────────────────────
    // BACK | FRONT | OFF
    Cull BACK
    // ── AlphaTest ─────────────────────────────────────────────────────────
    // func: LESS | LEQUAL | EQUAL | GEQUAL | GREATER | NOT_EQUAL | ALWAYS | NEVER
    AlphaTest GREATER 0.5
    // ── ColorMask ─────────────────────────────────────────────────────────
    // R, G, B, A in any combination, or 0 for none
    ColorMask RGBA
    // Per-MRT target:
    // ColorMask RGB 1
    // ColorMask 0 2
    // ── Stencil ───────────────────────────────────────────────────────────
    Stencil {
        Ref 1
        ReadMask 255
        WriteMask 255
        // Comp: LESS | LEQUAL | EQUAL | GEQUAL | GREATER | NOT_EQUAL | ALWAYS | NEVER
        Comp ALWAYS
        // Ops: KEEP | ZERO | REPLACE | INCR_SAT | DECR_SAT | INVERT | INCR_WRAP | DECR_WRAP
        Pass REPLACE
        Fail KEEP
        ZFail KEEP
    }
}
Properties {
    // Samplers — remain as individual uniform sampler* declarations
    // Default values are quoted strings: "white" | "black" | "normal" | "transparent"
    _MainTex    ("Main Texture",    sampler2D)     = "white"
    _NormalMap  ("Normal Map",      sampler2D)     = "normal"
    _TexArray   ("Texture Array",   sampler2DArray)
    _Volume     ("Volume Texture",  sampler3D)
    _Skybox     ("Cubemap",         samplerCube)
    // Non-samplers — all go into layout(std140) uniform CgMaterialBlock { ... }
    _Color      ("Tint Color",      color)         = (1.0, 1.0, 1.0, 1.0)
    _BaseColor  ("Base Color",      vec4)          = (0.2, 0.4, 0.8, 1.0)
    _Emission   ("Emission",        vec3)          = (0.0, 0.0, 0.0)
    _Offset     ("UV Offset",       vec2)          = (0.0, 0.0)
    _Roughness  ("Roughness",       float)         = 0.5
    _Metallic   ("Metallic",        float)         = 0.0
    _Count      ("Instance Count",  int)           = 0
    _Speed      ("Speed",           Range(0, 10))  = 1.0
};
struct v2f {
    vec2 uv;
    vec3 worldPos;
    vec3 normalWs;
};
void vertex(out v2f o) {
    vec4 worldPos4 = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
    gl_Position    = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
    o.worldPos     = worldPos4.xyz;
    o.normalWs     = normalize(CG_NORMAL_MATRIX * cg_Normal);
    o.uv           = cg_TexCoord0 + _Offset;
}
void fragment(in v2f i, out vec4 fragColor) {
    vec4 albedo = texture(_MainTex, i.uv) * _Color;
    fragColor   = vec4(albedo.rgb + _Emission, albedo.a);
}