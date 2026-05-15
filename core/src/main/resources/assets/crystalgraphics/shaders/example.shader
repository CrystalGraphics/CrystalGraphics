// ═════════════════════════════════════════════════════════════════════════════
// CrystalShader — example.shader
// Shows the pass-based .shader format with all material-level and pass-level
// features available in the current pipeline.
// ═════════════════════════════════════════════════════════════════════════════

// ── Shader type ───────────────────────────────────────────────────────────────
// "spatial" = 3D geometry shader. Future: canvas (2D), compute.
#type spatial

// ── Feature flags ─────────────────────────────────────────────────────────────
// Declare compile-time feature flags. Each becomes a #define injected into both
// vertex and fragment when enabled at runtime via material.enableKeyword("NAME").
// Max 8 flags per shader. OFF by default — no #define injected unless enabled.
//
// Java:
//   material.enableKeyword("RECEIVE_SHADOWS");   // → #define RECEIVE_SHADOWS 1
//   material.disableKeyword("RECEIVE_SHADOWS");  // → no define
//   material.isKeywordEnabled("FOG_ON");         // → false by default
//
// Each unique (passName × keyword-set) combination is a separately compiled
// program, cached lazily on first use under a flat ProgramKey.
#pragma cg_feature RECEIVE_SHADOWS
#pragma cg_feature FOG_ON
#pragma cg_feature NORMAL_MAP

// ── Material-level tags ───────────────────────────────────────────────────────
// "RenderType" controls the shadow auto-generation ladder:
//   Opaque (default) — castShadows=true and queue<3000 → auto-generate ShadowCaster pass
//   Transparent      — no auto-gen (translucent objects don't cast hard shadows)
// "CastShadows" = "Off" disables shadow auto-generation regardless of RenderType.
Tags {
    "RenderType" = "Opaque"
    // "CastShadows" = "Off"    // uncomment to suppress shadow auto-gen
}

// ── Render queue ──────────────────────────────────────────────────────────────
// Background=1000 | Geometry=2000 | AlphaTest=2450 | Transparent=3000 | Overlay=4000
Queue = "Geometry"

// ── Properties ────────────────────────────────────────────────────────────────
// Defaults applied to the material UBO immediately at load time.
// A freshly loaded material is visually correct before any applyProperties() call.
//
// Java: material.applyProperties(b -> b.set1f("_Roughness", 0.8f));
Properties {
    // Samplers — individual uniform sampler* declarations in generated GLSL.
    // Defaults: "white" | "black" | "normal" | "transparent"
    _MainTex    ("Main Texture",    sampler2D)      = "white"
    _NormalMap  ("Normal Map",      sampler2D)      = "normal"

    // Non-samplers — packed into layout(std140) uniform CgMaterialBlock { ... }
    _Color      ("Tint Color",      color)          = (1.0, 1.0, 1.0, 1.0)
    _Emission   ("Emission",        vec4)           = (0.0, 0.0, 0.0, 0.0)
    _Offset     ("UV Offset",       vec2)           = (0.0, 0.0)
    _Roughness  ("Roughness",       float)          = 0.5
    _Metallic   ("Metallic",        float)          = 0.0
    _FogDensity ("Fog Density",     Range(0, 1))    = 0.05
}

// ── Shared interpolants ───────────────────────────────────────────────────────
// Declared here at material scope, inherited by all Pass blocks below.
// A Pass may override by declaring its own struct v2f { } inside the Pass block.
struct v2f {
    vec2 uv;
    vec3 worldPos;
    vec3 normalWs;
};

// ═════════════════════════════════════════════════════════════════════════════
// PASS BLOCKS
// Each Pass { } owns its render state, vertex, and fragment.
// Tags { "LightMode" = "..." } routes the pass into the correct rendering stage:
//   Forward      — standard forward-lit draw (default when LightMode is absent)
//   ShadowCaster — depth-from-light (typically auto-generated; no need to author)
//   Depth        — early depth pre-pass
// ═════════════════════════════════════════════════════════════════════════════

Pass {
    // ── Pass-level tags ───────────────────────────────────────────────────────
    // "Name" = user-assigned name used as the ProgramKey pass dimension.
    //   Auto-assigned when absent: Forward passes → "Pass0", "Pass1", …
    // "LightMode" = "Forward" (default when absent or unrecognised)
    Tags {
        "LightMode" = "Forward"
        "Name"      = "BaseColor"
    }

    // ── Per-pass render state ─────────────────────────────────────────────────
    RenderState {
        // ── Blend ─────────────────────────────────────────────────────────────
        // Global (all render targets):
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA
        // Separate RGB + Alpha factors:
        // Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA, ONE ONE_MINUS_SRC_ALPHA
        // Per-MRT target (index prefix 0..7):
        // Blend 0 SRC_ALPHA ONE_MINUS_SRC_ALPHA
        // Blend 1 ONE ONE

        // ── BlendEquation ─────────────────────────────────────────────────────
        // ADD | SUB | REV_SUB | MIN | MAX
        BlendEquation ADD

        // ── Depth ─────────────────────────────────────────────────────────────
        // LESS | LEQUAL | EQUAL | GEQUAL | GREATER | NOT_EQUAL | ALWAYS | NEVER
        DepthTest LEQUAL
        DepthWrite ON

        // ── Cull ──────────────────────────────────────────────────────────────
        // BACK | FRONT | OFF
        Cull BACK

        // ── ColorMask ─────────────────────────────────────────────────────────
        // R, G, B, A in any combination, or 0 for none
        ColorMask RGBA

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

    // ── Vertex stage ──────────────────────────────────────────────────────────
    // Engine-injected via cg_env.glsl (automatic #include):
    //   cg_Position, cg_Normal, cg_TexCoord0  — vertex attribute aliases
    //   CG_OBJECT_TO_WORLD, CG_NORMAL_MATRIX  — per-instance matrices (SSBO/TBO)
    //   CG_MATRIX_MVP                         — proj × view × model (macro)
    //   CG_FRAME_VIEW, CG_FRAME_PROJ          — frame uniforms (UBO)
    //   cg_Time, cg_Resolution                — frame uniforms
    void vertex(out v2f o) {
        vec4 worldPos4 = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        gl_Position    = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
        o.worldPos     = worldPos4.xyz;
        o.normalWs     = normalize(CG_NORMAL_MATRIX * cg_Normal);
        o.uv           = cg_TexCoord0 + _Offset;
    }

    // ── Fragment stage — single render target ─────────────────────────────────
    // One out vec4 → layout(location=0) → GL_COLOR_ATTACHMENT0 or backbuffer.
    //
    // Feature flags gate code at compile time — each unique (passName × keyword-set)
    // combination is a separately compiled and cached program. No runtime branch cost.
    //
    // MRT alternative: replace `out vec4 fragColor` with `out GBuffer o` and declare
    // struct GBuffer { vec4 albedo : RT0; vec4 normal : RT1; }; above vertex().
    void fragment(in v2f i, out vec4 fragColor) {
        vec4 albedo = texture(_MainTex, i.uv) * _Color;

#ifdef NORMAL_MAP
        vec3 n = normalize(texture(_NormalMap, i.uv).rgb * 2.0 - 1.0);
#else
        vec3 n = normalize(i.normalWs);
#endif

#ifdef RECEIVE_SHADOWS
        // Shadow map sampling would go here — cg_ShadowViewProjMatrix,
        // cg_LightDirection, cg_ShadowParams are available from the frame UBO.
        float shadow = 1.0;
        albedo.rgb  *= shadow;
#endif

#ifdef FOG_ON
        float dist    = length(i.worldPos);
        float fogFact = exp(-_FogDensity * dist);
        albedo.rgb    = mix(vec3(0.7), albedo.rgb, clamp(fogFact, 0.0, 1.0));
#endif

        fragColor = vec4(albedo.rgb + _Emission.rgb, albedo.a);
    }
}

// ── ShadowCaster pass — auto-generated ───────────────────────────────────────
// Because this shader has Tags { "RenderType" = "Opaque" } and does NOT declare
// an explicit ShadowCaster Pass block, the material pipeline auto-generates one
// during recompile() when castShadows=true (the default) and renderQueue < 3000.
//
// The generated pass:
//   - Simple vertex (no custom attributes or discard): minimal position-only transform
//       gl_Position = cg_ShadowViewProjMatrix * CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
//   - Fragment: depth-only (empty body — rasterizer writes depth automatically)
//
// To opt out of auto-gen, add "CastShadows" = "Off" to the top-level Tags block,
// or author an explicit ShadowCaster Pass block above.
//
// To force a custom shadow vertex (e.g. vertex animation must affect shadow silhouette),
// author an explicit ShadowCaster Pass below. The auto-gen will be skipped entirely
// when an explicit one is present.
//
// Pass {
//     Tags { "LightMode" = "ShadowCaster" }
//     RenderState {
//         Cull OFF      // double-sided for thin surfaces
//         ColorMask 0   // write depth only
//         DepthTest LEQUAL
//         DepthWrite ON
//     }
//     void vertex(out v2f o) {
//         // Run your custom vertex animation here.
//         // The engine overrides gl_Position with the shadow matrix automatically
//         // when using compileShadowAutoGen(); if you author this pass manually
//         // you must compute gl_Position yourself:
//         gl_Position = cg_ShadowViewProjMatrix * CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
//         o.uv = cg_TexCoord0;
//         o.worldPos = vec3(0.0);
//         o.normalWs = vec3(0.0);
//     }
//     void fragment(in v2f i, out vec4 fragColor) {
//         // Depth-only: leave fragColor untouched or discard for alpha-tested shadows:
//         // if (texture(_MainTex, i.uv).a < 0.5) discard;
//         fragColor = vec4(0.0);
//     }
// }
