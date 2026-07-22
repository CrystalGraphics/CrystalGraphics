// ═════════════════════════════════════════════════════════════════════════════
// CrystalShader — text.shader
// Consolidated bitmap/MSDF/MTSDF text material, replacing the three raw shaders
// (bitmap_text/msdf_text/mtsdf_text .vert/.frag) previously hand-maintained by
// CgTextRenderer. See CrystalGraphics/docs_research/CGTEXTRENDERER_MATERIAL_OVERHAUL_PLAN.md.
// ═════════════════════════════════════════════════════════════════════════════

#type pos2_uv2_col4ub

// One keyword covers both distance-field atlas types (MSDF and MTSDF) — the fragment
// logic is byte-for-byte identical for both today (same median-based reconstruction,
// same texture().rgb read; MTSDF's extra alpha channel isn't exploited yet). Bitmap
// mode is "not enabled". Reintroduce a separate MTSDF_MODE keyword only if/when MTSDF's
// alpha channel actually needs different fragment logic (see
// docs_research/font/MTSDF_SHADER_RECONSTRUCTION_RESEARCH.md).
#pragma cg_feature MSDF_MODE

Tags {
    "RenderType" = "Transparent"
}

// Overlay (>= TRANSPARENT_THRESHOLD) — load-bearing: keeps attemptShadowAutoGen/
// attemptDepthAutoGen bailing out via their isOpaque check, so text never gets an
// auto-generated ShadowCaster/Depth pass.
Queue = "Overlay"

Properties {
    _MainTex ("Atlas Texture", sampler2D) = "white"
    _PxRange ("MSDF/MTSDF Pixel Range",  float) = 0.0
}

struct v2f {
    vec2 uv;
    vec4 color;
};

Pass {
    Tags {
        "LightMode" = "Forward"
        "Name"      = "Text"
    }

    RenderState {
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA
        Cull OFF

        // Placeholder only — never actually observed at draw time. Depth behavior differs
        // between 2D UI text (no depth) and world text (depth-tested, not written), and a
        // Pass's RenderState is fixed at author time, not per-keyword-permutation — so
        // CgTextRenderer overrides real depth state in Java via CgDepthState.NONE/TEST_ONLY
        // bracketing material.bind()/unbind(). Do not try to express that split here.
        DepthTest LEQUAL
        DepthWrite ON
    }

    // u_projection/u_modelview reach this shader via CgTextRenderer's attached, shared
    // static "TextTransform" CgUniformBuffer (flat STD140 scope — referenced directly,
    // no block prefix). Do not declare them here; do not use cg_env.glsl's camera/instancing
    // macros (CG_MATRIX_MVP, CG_OBJECT_TO_WORLD, etc.) — text has neither a frame camera nor
    // a per-instance object record, each draw carries its own arbitrary transform.
    void vertex(out v2f o) {
        gl_Position = u_projection * u_modelview * vec4(a_pos, 0.0, 1.0);
        o.uv = a_uv;
        o.color = a_color;
    }

    void fragment(in v2f i, out vec4 fragColor) {
#ifdef MSDF_MODE
        vec3 field = texture(_MainTex, i.uv).rgb;
        float signedDistance = max(min(field.r, field.g), min(max(field.r, field.g), field.b));

        vec2 atlasSize = vec2(textureSize(_MainTex, 0));
        vec2 unitRange = vec2(_PxRange) / atlasSize;
        vec2 uvFwidth = max(fwidth(i.uv), vec2(1.0e-6));
        vec2 screenTexSize = vec2(1.0) / uvFwidth;
        float screenPxRange = max(0.5 * dot(unitRange, screenTexSize), 1.0);

        float screenPxDist = screenPxRange * (signedDistance - 0.5);
        float opacity = clamp(screenPxDist + 0.5, 0.0, 1.0);
        float alpha = i.color.a * opacity;

        if (alpha <= (1.0 / 255.0)) discard;

        fragColor = vec4(i.color.rgb, alpha);
#else
        float alpha = texture(_MainTex, i.uv).r * i.color.a;
        fragColor = vec4(i.color.rgb, alpha);
#endif
    }
}
