// CC2's toon family (NSUNS4 0000f000, 0x01f00a), the archetype nsuns4_kit:shaders/cc2_toon.shader names: _Tex0 through
// its UV window, cel shaded by the tone ramp _Tex1, whose row is _UV2Window.x and whose column is the light's
// half-Lambert, clamped to 1/32..31/32; colour = base x (tone x light + 0.5), as research nsuns4 RENDER_PIPELINE.md §4
// reads it from 0020f001. Alpha-tested by _Clip, _Glare of it glowing. Keywords:
//   SECOND_LAYER      a second texture on _UV1Window, cross-faded by _BlendRate1/_BlendRate2 (_BlendType.x 1 multiplies)
//   RIM_FALLOFF       base = mix(base, falloff.rgb, falloff.a), the falloff ramp read at |N.V| on row _Falloff
//   SCREEN_TEXTURE    a texture by screen position (_UvScaleScreen.xy, offset _UV2Window.zw), over it by its alpha x
//                     _UV3Window.x; SCREEN_TEXTURE_2 a second (_UvScaleScreen.zw, _UV3Window.y)
//   POINT_LIT         lit by the sun: the engine has no point lights for effects
// Textures after the ramp, in order, where each keyword is on: the second layer, the falloff ramp, the screen textures.
#type spatial
#pragma cg_feature SECOND_LAYER
#pragma cg_feature RIM_FALLOFF
#pragma cg_feature SCREEN_TEXTURE
#pragma cg_feature SCREEN_TEXTURE_2
#pragma cg_feature POINT_LIT

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" }

Properties {
    _Tex0            ("Base", sampler2D) = "white"
    _Tex1            ("Tone ramp", sampler2D) = "white"
    _Tex2            ("Texture 2", sampler2D) = "white"
    _Tex3            ("Texture 3", sampler2D) = "white"
    _Tex4            ("Texture 4", sampler2D) = "white"
    _Tex5            ("Texture 5", sampler2D) = "white"
    _Tex6            ("Texture 6", sampler2D) = "white"
    _Tex7            ("Texture 7", sampler2D) = "white"
    _Tex8            ("Texture 8", sampler2D) = "white"
    _Tex9            ("Texture 9", sampler2D) = "white"
    _Tex10           ("Texture 10", sampler2D) = "white"
    _Tex11           ("Texture 11", sampler2D) = "white"
    _Color           ("Tint", color) = (1, 1, 1, 1)
    _UV0Window       ("Base UV: offset xy, scale zw", vec4) = (0, 0, 1, 1)
    _UV1Window       ("Second layer UV: offset xy, scale zw", vec4) = (0, 0, 1, 1)
    _UV2Window       ("x the tone ramp's row; zw the screen texture's offset", vec4) = (0, 0, 0, 0)
    _UV3Window       ("Screen textures' strengths in x and y", vec4) = (1, 1, 0, 0)
    _UvScaleScreen   ("Screen textures' scales", vec4) = (1, 1, 1, 1)
    _ScreenProjParam ("Screen projection (kept)", vec4) = (0, 0, 0, 0)
    _BlendType       ("Second layer: x 1 multiplies, else mixes", vec4) = (0, 0, 0, 0)
    _BlendRate1      ("Second layer's colour share", float) = 0.0
    _BlendRate2      ("Second layer's alpha share", float) = 0.0
    _UseStColor      ("Uses the stage colour (kept)", vec4) = (0, 0, 0, 0)
    _Falloff         ("x the falloff ramp's row", vec4) = (0, 0, 0, 0)
    _Glare           ("How much glows, 0..1", float) = 0.0
    _Clip            ("The alpha it must pass, 0..1", float) = 0.0
    _OutlineID       ("x its outline group (the game's mask green)", vec4) = (1, 0, 0, 0)
}

struct v2f { vec2 uv0; vec2 uv1; vec4 colour; vec3 viewNormal; vec3 normal; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        DepthTest LEQUAL
    }

#ifdef SECOND_LAYER
    #define FX_I_LAYER 1
#else
    #define FX_I_LAYER 0
#endif
#ifdef RIM_FALLOFF
    #define FX_I_RAMP 1
#else
    #define FX_I_RAMP 0
#endif
#ifdef SCREEN_TEXTURE
    #define FX_I_SCREEN 1
#else
    #define FX_I_SCREEN 0
#endif
#define FX_T_LAYER   2
#define FX_T_RAMP    (FX_T_LAYER + FX_I_LAYER)
#define FX_T_SCREEN  (FX_T_RAMP + FX_I_RAMP)
#define FX_T_SCREEN2 (FX_T_SCREEN + FX_I_SCREEN)

    // Texture i: the index is a constant per variant, so the compiler keeps one sample.
    vec4 fx_tex(int i, vec2 uv) {
        if (i == 2) return texture(_Tex2, uv);
        if (i == 3) return texture(_Tex3, uv);
        if (i == 4) return texture(_Tex4, uv);
        if (i == 5) return texture(_Tex5, uv);
        return texture(_Tex0, uv);
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.uv0 = cg_TexCoord0 * _UV0Window.zw + _UV0Window.xy;
        o.uv1 = cg_TexCoord0 * _UV1Window.zw + _UV1Window.xy;
        o.colour = cg_Color * _Color;
        o.normal = normalize(CG_NORMAL_MATRIX * cg_Normal);
        o.viewNormal = mat3(cg_ViewMatrix) * o.normal;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec4 c = texture(_Tex0, i.uv0) * i.colour;
#ifdef SECOND_LAYER
        vec4 layer = fx_tex(FX_T_LAYER, i.uv1);
        vec3 over = _BlendType.x > 0.5 ? c.rgb * layer.rgb : layer.rgb;
        c.rgb = mix(c.rgb, over, layer.a * _BlendRate1);
        c.a = mix(c.a, c.a * layer.a, _BlendRate2);
#endif
        if (c.a <= _Clip) discard;
#ifdef RIM_FALLOFF
        vec4 falloff = fx_tex(FX_T_RAMP, vec2(min(1.0, abs(normalize(i.viewNormal).z)), _Falloff.x));
        c.rgb = mix(c.rgb, falloff.rgb, falloff.a);
#endif
        float u = clamp(dot(normalize(i.normal), CG_SUN_DIRECTION) * 0.5 + 0.5, 1.0 / 32.0, 31.0 / 32.0);
        vec3 tone = texture(_Tex1, vec2(u, _UV2Window.x)).rgb;
        c.rgb *= tone * CG_DAYLIGHT + 0.5;
#if defined(SCREEN_TEXTURE) || defined(SCREEN_TEXTURE_2)
        vec2 screen = gl_FragCoord.xy / CG_RESOLUTION;
#endif
#ifdef SCREEN_TEXTURE
        vec2 suv = screen * _UvScaleScreen.xy + _UV2Window.zw;
        vec4 scr = fx_tex(FX_T_SCREEN, vec2(suv.x, 1.0 - suv.y));
        c.rgb = mix(c.rgb, scr.rgb, scr.a * _UV3Window.x);
#endif
#ifdef SCREEN_TEXTURE_2
        vec2 suv2 = screen * _UvScaleScreen.zw + _UV2Window.zw;
        vec4 scr2 = fx_tex(FX_T_SCREEN2, vec2(suv2.x, 1.0 - suv2.y));
        c.rgb = mix(c.rgb, scr2.rgb, scr2.a * _UV3Window.y);
#endif
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
