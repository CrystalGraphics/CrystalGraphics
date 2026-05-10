// ═════════════════════════════════════════════════════════════════════════════
// CrystalShader — example.shader
// Shows every feature available through Wave 1 + Wave 2 of the material system.
// ═════════════════════════════════════════════════════════════════════════════

// ── Shader type ───────────────────────────────────────────────────────────────
// "spatial" = 3D geometry shader. Future: canvas (2D), compute.
#type spatial

// ── Feature flags (Wave 2) ────────────────────────────────────────────────────
// Declare compile-time feature flags. Each becomes a #define injected into both
// vertex and fragment when enabled at runtime via material.enableKeyword("NAME").
// Max 8 flags per shader. OFF by default — no #define injected unless enabled.
//
// Java:
//   material.enableKeyword("RECEIVE_SHADOWS");   // → #define RECEIVE_SHADOWS
//   material.disableKeyword("RECEIVE_SHADOWS");  // → no define
//   material.isKeywordEnabled("FOG_ON");         // → false by default
//
// Each unique (variant × keyword-set) combination is a separately compiled
// program, cached lazily on first use. No combinatorial explosion — only
// combinations you actually call bind() with get compiled.
#pragma cg_feature RECEIVE_SHADOWS
#pragma cg_feature FOG_ON
#pragma cg_feature NORMAL_MAP

// ── Render queue ──────────────────────────────────────────────────────────────
// Background=1000 | Geometry=2000 | AlphaTest=2450 | Transparent=3000 | Overlay=4000
Queue = "Geometry"

// ── Render state (Wave 1) ─────────────────────────────────────────────────────
RenderState {
    // ── Blend ─────────────────────────────────────────────────────────────────
    // Global (all render targets):
    Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA
    // Separate RGB + Alpha factors:
    // Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA, ONE ONE_MINUS_SRC_ALPHA
    // Per-MRT target (index prefix 0..7):
    // Blend 0 SRC_ALPHA ONE_MINUS_SRC_ALPHA
    // Blend 1 ONE ONE

    // ── BlendEquation ─────────────────────────────────────────────────────────
    // ADD | SUB | REV_SUB | MIN | MAX
    BlendEquation ADD
    // Separate RGB + Alpha equations:
    // BlendEquation ADD, ADD
    // Per-MRT:
    // BlendEquation 0 ADD
    // BlendEquation 1 ADD, REV_SUB

    // ── Depth ─────────────────────────────────────────────────────────────────
    // LESS | LEQUAL | EQUAL | GEQUAL | GREATER | NOT_EQUAL | ALWAYS | NEVER
    DepthTest LEQUAL
    DepthWrite ON

    // ── Cull ──────────────────────────────────────────────────────────────────
    // BACK | FRONT | OFF
    Cull BACK

    // ── AlphaTest ─────────────────────────────────────────────────────────────
    // func: LESS | LEQUAL | EQUAL | GEQUAL | GREATER | NOT_EQUAL | ALWAYS | NEVER
    // AlphaTest GREATER 0.5

    // ── ColorMask ─────────────────────────────────────────────────────────────
    // R, G, B, A in any combination, or 0 for none
    ColorMask RGBA
    // Per-MRT target:
    // ColorMask RGB 1
    // ColorMask 0 2

    // ── Stencil ───────────────────────────────────────────────────────────────
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

// ── Properties (Wave 1) ───────────────────────────────────────────────────────
// Defaults are applied to the material UBO immediately at load time (Wave 2).
// A freshly loaded material is visually correct before any applyProperties() call.
//
// Java: material.applyProperties(b -> b.set1f("_Roughness", 0.8f));  // overrides default
Properties {
    // Samplers — individual uniform sampler* declarations in generated GLSL.
    // Defaults: "white" | "black" | "normal" | "transparent"
    _MainTex    ("Main Texture",    sampler2D)      = "white"
    _NormalMap  ("Normal Map",      sampler2D)      = "normal"
    _TexArray   ("Texture Array",   sampler2DArray)
    _Volume     ("Volume Texture",  sampler3D)
    _Skybox     ("Cubemap",         samplerCube)

    // Non-samplers — packed into layout(std140) uniform CgMaterialBlock { ... }
    _Color      ("Tint Color",      color)          = (1.0, 1.0, 1.0, 1.0)
    _BaseColor  ("Base Color",      vec4)           = (0.2, 0.4, 0.8, 1.0)
    _Emission   ("Emission",        vec3)           = (0.0, 0.0, 0.0)
    _Offset     ("UV Offset",       vec2)           = (0.0, 0.0)
    _Roughness  ("Roughness",       float)          = 0.5
    _Metallic   ("Metallic",        float)          = 0.0
    _Count      ("Instance Count",  int)            = 0
    _Speed      ("Speed",           Range(0, 10))   = 1.0  // clamped to [0,10] on set
    _FogDensity ("Fog Density",     Range(0, 1))    = 0.05
};

// ── Interpolants ──────────────────────────────────────────────────────────────
struct v2f {
    vec2 uv;
    vec3 worldPos;
    vec3 normalWs;
};

// ── Vertex stage ──────────────────────────────────────────────────────────────
// Engine-injected via cg_env.glsl (automatic #include):
//   cg_Position, cg_Normal, cg_TexCoord0  — vertex attribute aliases
//   CG_OBJECT_TO_WORLD, CG_NORMAL_MATRIX  — per-instance matrices (SSBO/TBO)
//   CG_MATRIX_MVP                         — proj × view × model (macro)
//   CG_FRAME_VIEW, CG_FRAME_PROJ          — frame uniforms (UBO)
//   cg_Time, cg_Resolution                — frame uniforms
//
// Feature flags are available here too via #ifdef:
void vertex(out v2f o) {
    vec4 worldPos4 = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
    gl_Position    = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
    o.worldPos     = worldPos4.xyz;
    o.normalWs     = normalize(CG_NORMAL_MATRIX * cg_Normal);
    o.uv           = cg_TexCoord0 + _Offset;
}

// ─────────────────────────────────────────────────────────────────────────────
// FRAGMENT STAGE — two configurations shown below. Use ONE in a real shader.
// Both are shown active here for documentation purposes only.
// ─────────────────────────────────────────────────────────────────────────────

// ── 1. Single render target ───────────────────────────────────────────────────
// One `out vec4` → layout(location=0) → GL_COLOR_ATTACHMENT0 or backbuffer.
//
// DEPTH variant (Wave 2): the engine auto-generates a second compiled program
// for this same shader whose fragment is `void main() {}` (depth written
// automatically by the rasterizer). You never write it.
// Java: material.bind(CgShaderVariant.DEPTH);
//
// Feature flags gate code at compile time — each unique (variant × keyword-set)
// combination is a separately compiled and cached program. No runtime branch cost.
// Java: material.enableKeyword("RECEIVE_SHADOWS");  // compiled into new cache entry on next bind()
//
// void fragment(in v2f i, out vec4 fragColor) {
//     vec4 albedo = texture(_MainTex, i.uv) * _Color;
//
// #ifdef NORMAL_MAP
//     vec3 n = normalize(texture(_NormalMap, i.uv).rgb * 2.0 - 1.0);
// #else
//     vec3 n = normalize(i.normalWs);
// #endif
//
// #ifdef RECEIVE_SHADOWS
//     float shadow = 1.0; // shadow map sampling goes here
//     albedo.rgb  *= shadow;
// #endif
//
// #ifdef FOG_ON
//     float dist    = length(i.worldPos - cg_CameraPos);
//     float fogFact = exp(-_FogDensity * dist);
//     albedo.rgb    = mix(vec3(0.7), albedo.rgb, clamp(fogFact, 0.0, 1.0));
// #endif
//
//     fragColor = vec4(albedo.rgb + _Emission, albedo.a);
// }

// ── 2. Multiple render targets / G-buffer (Wave 2) ────────────────────────────
// Declare an output struct — each vec4 field gets a : RTN location annotation.
// The struct name is yours (GBuffer, ShadowOut, PostFxOut, etc.).
// Compiler strips the annotations and emits layout(location=N) out at global scope,
// then copies struct fields into them after calling fragment().
//
// Skipping a slot is natural — RT0 and RT2 with no RT1 needs no dummy field.
// Without annotations, fields are assigned locations 0, 1, 2… in order (positional).
// All-or-none: mix of annotated + un-annotated fields throws a parse error.
//
// Java setup (once):
//   CgFramebuffer gBuffer = CgFramebufferFactory.create(
//       CgFramebufferSpec.builder()
//           .addColorAttachment(CgColorAttachmentSpec.rgba8())    // → attachment 0
//           .addColorAttachment(CgColorAttachmentSpec.rgba16f())  // → attachment 1
//           .addColorAttachment(CgColorAttachmentSpec.rgba8())    // → attachment 2
//           .depthStencil(CgDepthStencilSpec.depth24())
//           .build());
//   gBuffer.bind();
//   gBuffer.drawBuffers(GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1, GL_COLOR_ATTACHMENT2);
//   gBuffer.unbind();
//
// Java per-frame:
//   gBuffer.bind();
//   material.bind();
//   mesh.drawInstanced(N);
//   material.unbind();
//   gBuffer.unbind();

struct GBuffer {
    vec4 albedo   : RT0;   // → layout(location=0) out vec4 _cg_RT0
    vec4 normal   : RT1;   // → layout(location=1) out vec4 _cg_RT1
    vec4 material : RT2;   // → layout(location=2) out vec4 _cg_RT2
};

void fragment(in v2f i, out GBuffer o) {
    vec4 tex = texture(_MainTex, i.uv) * _Color;
    vec3 n   = normalize(i.normalWs);

#ifdef NORMAL_MAP
    vec3 nMap = texture(_NormalMap, i.uv).rgb * 2.0 - 1.0;
    n = normalize(nMap);
#endif

    o.albedo   = vec4(tex.rgb, 1.0);
    o.normal   = vec4(n * 0.5 + 0.5, 0.0);              // encode [-1,1] → [0,1]
    o.material = vec4(_Roughness, _Metallic, 0.0, 0.0);  // packed G-buffer channel
}

// Compiler generates this (never written by the user):
//
//   struct GBuffer { vec4 albedo; vec4 normal; vec4 material; };  // annotations stripped
//   layout(location=0) out vec4 _cg_RT0;
//   layout(location=1) out vec4 _cg_RT1;
//   layout(location=2) out vec4 _cg_RT2;
//   void fragment(in v2f i, out GBuffer o) { ... }  // verbatim
//   void main() {
//       GBuffer _cg_mrtOut;
//       fragment(_v2f_local, _cg_mrtOut);
//       _cg_RT0 = _cg_mrtOut.albedo;
//       _cg_RT1 = _cg_mrtOut.normal;
//       _cg_RT2 = _cg_mrtOut.material;
//   }

// ── 3. Multi-pass chain (Wave 1 — nextPass / drawChain) ───────────────────────
// A second material runs immediately after this one, for the same mesh, in order.
//
// Java:
//   CgMaterial base    = CgMaterial.load("mymod:shaders/example.shader");
//   CgMaterial outline = CgMaterial.load("mymod:shaders/outline.shader");
//   base.setNextPass(outline);    // cycle guard built in — throws on circular chains
//
//   // Option A — explicit loop:
//   CgMaterial pass = base;
//   while (pass != null) {
//       pass.bind();
//       mesh.drawInstanced(N);
//       pass.unbind();
//       pass = pass.getNextPass();
//   }
//
//   // Option B — drawChain helper (same result, less boilerplate):
//   base.drawChain(() -> mesh.drawInstanced(N));
