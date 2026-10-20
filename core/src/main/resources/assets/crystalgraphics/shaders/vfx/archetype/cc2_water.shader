// CC2's water (NSUNS4 0007f006-0c, research nsuns4 VFX_ENGINE.md §4): _WaterColor over its maps, taken in keyword
// order after _Tex0 (the base): height, normal, bottom, environment. The height scrolls the normal's UV by
// _WaterParam.x, the normal bends the bottom and environment reads by _WaterParam.y, the environment mixes in by
// _WaterParam.z. WATER_SHADOW, RECEIVE_SHADOW and POINT_LIT draw unlit: the engine has none of them for effects.
#type spatial
#pragma cg_feature WATER_HEIGHT
#pragma cg_feature WATER_NORMAL
#pragma cg_feature WATER_BOTTOM
#pragma cg_feature WATER_ENV
#pragma cg_feature WATER_SHADOW
#pragma cg_feature RECEIVE_SHADOW
#pragma cg_feature POINT_LIT

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

Properties {
    _Tex0       ("Base", sampler2D) = "white"
    _Tex1       ("Texture 1", sampler2D) = "white"
    _Tex2       ("Texture 2", sampler2D) = "white"
    _Tex3       ("Texture 3", sampler2D) = "white"
    _Tex4       ("Texture 4", sampler2D) = "white"
    _UV0Window  ("Base UV: offset xy, scale zw", vec4) = (0, 0, 1, 1)
    _UV1Window  ("Normal UV: offset xy, scale zw", vec4) = (0, 0, 1, 1)
    _WaterColor ("Colour", color) = (1, 1, 1, 1)
    _WaterParam ("x height scroll, y bend, z environment share", vec4) = (0.05, 0.05, 0.3, 0)
    _Glare      ("How much glows, 0..1", float) = 0.0
    _Clip       ("The alpha it must pass, 0..1", float) = 0.0
    _OutlineID  ("Its outline group", float) = 1.0
}

struct v2f { vec2 uv0; vec2 uv1; vec4 colour; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        DepthTest LEQUAL
    }

#ifdef WATER_HEIGHT
    #define FX_I_HEIGHT 1
#else
    #define FX_I_HEIGHT 0
#endif
#ifdef WATER_NORMAL
    #define FX_I_NORMAL 1
#else
    #define FX_I_NORMAL 0
#endif
#ifdef WATER_BOTTOM
    #define FX_I_BOTTOM 1
#else
    #define FX_I_BOTTOM 0
#endif
#define FX_T_HEIGHT 1
#define FX_T_NORMAL (FX_T_HEIGHT + FX_I_HEIGHT)
#define FX_T_BOTTOM (FX_T_NORMAL + FX_I_NORMAL)
#define FX_T_ENV    (FX_T_BOTTOM + FX_I_BOTTOM)

    vec4 fx_tex(int i, vec2 uv) {
        if (i == 1) return texture(_Tex1, uv);
        if (i == 2) return texture(_Tex2, uv);
        if (i == 3) return texture(_Tex3, uv);
        if (i == 4) return texture(_Tex4, uv);
        return texture(_Tex0, uv);
    }

    void vertex(out v2f o) {
        o.uv0 = cg_TexCoord0 * _UV0Window.zw + _UV0Window.xy;
        o.uv1 = cg_TexCoord0 * _UV1Window.zw + _UV1Window.xy;
        o.colour = cg_Color * _WaterColor;
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec2 nuv = i.uv1;
#ifdef WATER_HEIGHT
        nuv += (fx_tex(FX_T_HEIGHT, i.uv0).r - 0.5) * _WaterParam.x;
#endif
        vec2 bend = vec2(0.0);
#ifdef WATER_NORMAL
        bend = (fx_tex(FX_T_NORMAL, nuv).xy * 2.0 - 1.0) * _WaterParam.y;
#endif
        vec4 c = texture(_Tex0, i.uv0 + bend) * i.colour;
#ifdef WATER_BOTTOM
        c.rgb *= fx_tex(FX_T_BOTTOM, i.uv0 + bend).rgb;
#endif
#ifdef WATER_ENV
        c.rgb = mix(c.rgb, fx_tex(FX_T_ENV, gl_FragCoord.xy / CG_RESOLUTION + bend).rgb, _WaterParam.z);
#endif
        if (c.a <= _Clip) discard;
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
