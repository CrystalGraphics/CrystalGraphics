// CC2's toon outline (NSUNS4 0000f001, 0000f002), the archetype nsuns4_kit:shaders/cc2_outline.shader names: an inverted
// hull, the mesh pushed out along its normals by _Width blocks and drawn with its front faces culled, coloured from the
// line-colour atlas _Tex0 through its UV window and alpha-tested by _Clip (research nsuns4 RENDER_PIPELINE.md §5).
// The game's width is a global (g_outlineParam); _Width stands in for it per material.
//   SCREEN_MASK   0000f002 masks the line by the game's mask target; the engine has none, so it draws unmasked
#type spatial
#pragma cg_feature SCREEN_MASK

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" }

Properties {
    _Tex0          ("Line colour atlas", sampler2D) = "white"
    _Tex1          ("Texture 1", sampler2D) = "white"
    _Tex2          ("Texture 2", sampler2D) = "white"
    _Tex3          ("Texture 3", sampler2D) = "white"
    _Tex4          ("Texture 4", sampler2D) = "white"
    _Tex5          ("Texture 5", sampler2D) = "white"
    _Tex6          ("Texture 6", sampler2D) = "white"
    _Tex7          ("Texture 7", sampler2D) = "white"
    _Tex8          ("Texture 8", sampler2D) = "white"
    _Tex9          ("Texture 9", sampler2D) = "white"
    _Tex10         ("Texture 10", sampler2D) = "white"
    _Tex11         ("Texture 11", sampler2D) = "white"
    _Color         ("Tint", color) = (1, 1, 1, 1)
    _UV0Window     ("Line UV: offset xy, scale zw", vec4) = (0, 0, 1, 1)
    _UV1Window     ("Second UV window (kept)", vec4) = (0, 0, 1, 1)
    _UV2Window     ("Third UV window (kept)", vec4) = (0, 0, 0, 0)
    _UV3Window     ("Fourth UV window (kept)", vec4) = (1, 1, 0, 0)
    _BlendRate1    ("Blend rate 1 (kept)", float) = 0.0
    _BlendRate2    ("Blend rate 2 (kept)", float) = 0.0
    _Width         ("The line's width in blocks", float) = 0.02
    _Glare         ("How much glows, 0..1", float) = 0.0
    _Clip          ("The alpha it must pass, 0..1", float) = 0.0
    _OutlineID     ("x its outline group (the game's mask green)", vec4) = (1, 0, 0, 0)
}

struct v2f { vec2 uv0; vec4 colour; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Cull FRONT
        DepthTest LEQUAL
    }

    void vertex(out v2f o) {
        vec3 n = normalize(CG_NORMAL_MATRIX * cg_Normal);
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        world.xyz += n * _Width;
        o.uv0 = cg_TexCoord0 * _UV0Window.zw + _UV0Window.xy;
        o.colour = cg_Color * _Color;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec4 c = texture(_Tex0, i.uv0) * i.colour;
        if (c.a <= _Clip) discard;
        fragColor = c;
        CG_GLOW(c.rgb * c.a * _Glare);
    }
}

// Its glare (CG_GLOW) into the world's bloom.
Pass {
    Tags { "LightMode" = "Emissive" }
    RenderState {
        Blend ONE ONE
        DepthTest LEQUAL
        DepthWrite OFF
    }
}
