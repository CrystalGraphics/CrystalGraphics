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
//
// ─────────────────────────────────────────────────────────────────────────────
// ATLAS STORAGE IS RGBA8, NOT RGBA16F — READ THIS BEFORE USING THE 4TH CHANNEL
// ─────────────────────────────────────────────────────────────────────────────
// The distance-field atlas allocates GL_RGBA8 (CgPagedGlyphAtlas), so every channel
// carries 256 levels, not half-float precision. It used to be RGBA16F; the switch
// halved atlas memory (88 MB -> 44 MB on a 3-font CJK workload) and was validated by
// measurement, NOT by assumption — see CgMsdfFieldStorageTest: quantising to 8 bits
// introduced zero structural defects across six atlas scales on dense kanji (no counter
// closed, no stroke merged), costing 0.04 percentage points of ink-relative edge error
// at the shipping 80px scale.
//
// That validation covers ONLY the .rgb channels this shader reads today. The median-of-3
// edge is razor thin — roughly one screen pixel wide — so 8-bit quantisation there is
// invisible, which is why it measured clean.
//
// The 4th channel is a different risk profile. It is a true SDF intended for WIDE effects
// (outlines, glows, soft shadows), where the gradient is deliberately spread over many
// screen pixels. Stretching 256 levels across a wide, shallow ramp is exactly where 8-bit
// banding shows up, and nothing has tested it because nothing samples it yet.
//
// So: if you start reading .a here and see banding or stair-stepping in an outline or
// glow falloff, the atlas format is the first suspect, not your effect math. Options in
// order of preference: dither the ramp in-shader; narrow the effect's distance range so
// fewer levels cover it; or, last resort, move the atlas back to RGBA16F_LINEAR in
// CgPagedGlyphAtlas and pay the 2x memory. Extend CgMsdfQualityProbe to score the 4th
// channel before deciding — it currently only does median-of-3.
#pragma cg_feature MSDF_MODE

Tags {
    "RenderType" = "Transparent"
}

// Overlay (>= TRANSPARENT_THRESHOLD) — load-bearing: keeps attemptShadowAutoGen/
// attemptDepthAutoGen bailing out via their isOpaque check, so text never gets an
// auto-generated ShadowCaster/Depth pass.
Queue = "Overlay"

Properties {
    _MainTex ("Atlas Texture", sampler2DArray) = "white"
    _PxRange ("MSDF/MTSDF Pixel Range",  float) = 0.0
}

struct v2f {
    vec2 uv;
    vec4 color;
    float atlasLayer;
};

Pass {
    Tags {
        "LightMode" = "Forward"
        "Name"      = "Text"
    }

    RenderState {
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA, ONE ONE_MINUS_SRC_ALPHA
        Cull OFF

        // Placeholder only — never actually observed at draw time. Depth behavior differs
        // between 2D UI text (no depth) and world text (depth-tested, not written), and a
        // Pass's RenderState is fixed at author time, not per-keyword-permutation — so
        // CgTextRenderer overrides real depth state in Java via CgDepthState.NONE/TEST_ONLY
        // bracketing material.bind()/unbind(). Do not try to express that split here.
        DepthTest LEQUAL
        DepthWrite ON
    }

    // Per-glyph model-view is baked CPU-side into origin/right/up (see CgQuadRenderer.Quad#pose,
    // called by CgTextRenderer#addQuadFromPlacement) and delivered per-instance via the
    // CG_QUAD_* macros below (cg_env.glsl) — no per-draw uniform transform.
    //
    // u_Projection reaches this shader via CgTextRenderer's attached, shared static "TextData"
    // CgUniformBuffer (flat STD140 scope — referenced directly, no block prefix). Deliberately
    // NOT the engine's shared cg_ProjMatrix (CgFrameBlock): that's frame-owner state (the actual
    // scene camera), and CgTextRenderContext's projection is renderer-local (often an
    // orthographic UI projection unrelated to the scene camera) — reusing cg_ProjMatrix would
    // clobber whatever the real frame projection is for anything else sharing that frame.
    void vertex(out v2f o) {
        gl_Position = u_Projection * vec4(CG_QUAD_WORLD_POS, 1.0);
        o.uv         = CG_QUAD_UV;
        o.color      = CG_QUAD_COLOR;
        o.atlasLayer = CG_QUAD_ATLAS_LAYER;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 uvw = vec3(i.uv, i.atlasLayer);
#ifdef MSDF_MODE
        vec3 field = texture(_MainTex, uvw).rgb;
        float signedDistance = max(min(field.r, field.g), min(max(field.r, field.g), field.b));

        vec2 atlasSize = vec2(textureSize(_MainTex, 0).xy);
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
        float alpha = texture(_MainTex, uvw).r * i.color.a;
        fragColor = vec4(i.color.rgb, alpha);
#endif
    }
}
