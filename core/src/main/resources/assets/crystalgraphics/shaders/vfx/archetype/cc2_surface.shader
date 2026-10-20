// CC2's surface (NSUNS4 0007f00e, research nsuns4 VFX_ENGINE.md §4): a diffuse map (_Tex0) times _SurfaceColor, a
// second map (_Tex1) its detail, lit by the sun scaled by _SurfaceParam.x. POINT_LIT draws as the sun alone: the engine
// has no point lights for effects.
#type spatial
#pragma cg_feature POINT_LIT

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

Properties {
    _Tex0          ("Diffuse", sampler2D) = "white"
    _Tex1          ("Detail", sampler2D) = "white"
    _UV0Window     ("UV: offset xy, scale zw", vec4) = (0, 0, 1, 1)
    _SurfaceColor  ("Colour", color) = (1, 1, 1, 1)
    _SurfaceParam  ("x light strength", vec4) = (1, 0, 0, 0)
    _Glare         ("How much glows, 0..1", float) = 0.0
    _OutlineID     ("Its outline group", float) = 1.0
}

struct v2f { vec2 uv0; vec4 colour; vec3 normal; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        DepthTest LEQUAL
    }

    void vertex(out v2f o) {
        o.uv0 = cg_TexCoord0 * _UV0Window.zw + _UV0Window.xy;
        o.colour = cg_Color * _SurfaceColor;
        o.normal = CG_NORMAL_MATRIX * cg_Normal;
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec4 c = texture(_Tex0, i.uv0) * texture(_Tex1, i.uv0) * i.colour;
        float light = 0.35 + 0.65 * max(dot(normalize(i.normal), CG_SUN_DIRECTION), 0.0) * CG_DAYLIGHT;
        c.rgb *= mix(1.0, light, clamp(_SurfaceParam.x, 0.0, 1.0));
#if CG_FOG_MODE == 0
        fragColor = c;
#else
        fragColor = vec4(c.rgb * c.a, c.a);
#endif
        CG_GLOW(c.rgb * c.a * _Glare);
    }
}

Pass {
    Tags { "LightMode" = "Emissive" }
    RenderState {
        Blend ONE ONE
        DepthTest LEQUAL
        DepthWrite OFF
    }
}
