// CC2's effect family (NSUNS4 0001f0xx), the archetype nsuns4_kit:shaders/cc2_effect.shader names: _Tex0 through its
// UV window, times the vertex colour, alpha-tested by _Clip, _Glare of it glowing (the game's mask target, here the
// bloom). Each keyword is one of the family's features (research nsuns4 VFX_ENGINE.md §4):
//   SECOND_LAYER      a second texture on _UV1Window, cross-faded by _BlendRate1/_BlendRate2 (_BlendType.x 1 multiplies)
//   LIGHTMAP          the mesh's lightmap on its second UV set; LIGHTMAP_BLEND a splat mixed in by the lightmap's alpha
//   RIM_FALLOFF       alpha times a ramp by the view-space facing ratio, row _Falloff; OPAQUE_FALLOFF the colour instead
//   SCREEN_TEXTURE    a texture by screen position (_UvScaleScreen.xy, offset _UV2Window.xy), mixed by its alpha x _UV3Window.x;
//                     SCREEN_TEXTURE_2 a second one (_UvScaleScreen.zw, _UV2Window.zw, _UV3Window.y)
//   SOFT_PARTICLE     faded into what is behind it over _VolumeParam.x blocks
//   DIRECTIONAL_LIT   lit by the sun; POINT_LIT and RECEIVE_SHADOW draw as unlit: the engine has neither for effects
// Textures in order after _Tex0: the second layer, the lightmap, its splat, the ramp, the screen textures, each where its
// keyword is on. The blend is the material's (state.blend); the shader authors none.
#type spatial
#pragma cg_feature SECOND_LAYER
#pragma cg_feature LIGHTMAP
#pragma cg_feature LIGHTMAP_BLEND
#pragma cg_feature RIM_FALLOFF
#pragma cg_feature OPAQUE_FALLOFF
#pragma cg_feature SCREEN_TEXTURE
#pragma cg_feature SCREEN_TEXTURE_2
#pragma cg_feature SOFT_PARTICLE
#pragma cg_feature DIRECTIONAL_LIT
#pragma cg_feature POINT_LIT
#pragma cg_feature RECEIVE_SHADOW

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

Properties {
    _Tex0          ("Base", sampler2D) = "white"
    _Tex1          ("Texture 1", sampler2D) = "white"
    _Tex2          ("Texture 2", sampler2D) = "white"
    _Tex3          ("Texture 3", sampler2D) = "white"
    _Tex4          ("Texture 4", sampler2D) = "white"
    _Tex5          ("Texture 5", sampler2D) = "white"
    _Tex6          ("Texture 6", sampler2D) = "white"
    _Tex7          ("Texture 7", sampler2D) = "white"
    _Tex8          ("Texture 8", sampler2D) = "white"
    _Tex9          ("Texture 9", sampler2D) = "white"
    _Color         ("Tint", color) = (1, 1, 1, 1)
    _UV0Window     ("Base UV: offset xy, scale zw", vec4) = (0, 0, 1, 1)
    _UV1Window     ("Second layer UV: offset xy, scale zw", vec4) = (0, 0, 1, 1)
    _UV2Window     ("Screen textures' offsets", vec4) = (0, 0, 0, 0)
    _UV3Window     ("Screen textures' strengths in x and y", vec4) = (1, 1, 0, 0)
    _UvScaleScreen ("Screen textures' scales", vec4) = (1, 1, 1, 1)
    _BlendType     ("Second layer: x 1 multiplies, else mixes", vec4) = (0, 0, 0, 0)
    _BlendRate1    ("Second layer's colour share", float) = 0.0
    _BlendRate2    ("Second layer's alpha share", float) = 0.0
    _Falloff       ("The ramp's row", float) = 0.0
    _VolumeParam   ("Soft particle: x its fade distance in blocks", vec4) = (1, 0, 0, 0)
    _Glare         ("How much glows, 0..1", float) = 0.0
    _Clip          ("The alpha it must pass, 0..1", float) = 0.0
    _OutlineID     ("Its outline group (the game's mask green)", float) = 1.0
}

struct v2f { vec2 uv0; vec2 uv1; vec2 uv2; vec4 colour; vec3 viewNormal; vec3 world; };

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
#ifdef LIGHTMAP
    #define FX_I_LIGHTMAP 1
#else
    #define FX_I_LIGHTMAP 0
#endif
#ifdef LIGHTMAP_BLEND
    #define FX_I_SPLAT 1
#else
    #define FX_I_SPLAT 0
#endif
#if defined(RIM_FALLOFF) || defined(OPAQUE_FALLOFF)
    #define FX_I_RAMP 1
#else
    #define FX_I_RAMP 0
#endif
#ifdef SCREEN_TEXTURE
    #define FX_I_SCREEN 1
#else
    #define FX_I_SCREEN 0
#endif
#define FX_T_LAYER    1
#define FX_T_LIGHTMAP (FX_T_LAYER + FX_I_LAYER)
#define FX_T_SPLAT    (FX_T_LIGHTMAP + FX_I_LIGHTMAP)
#define FX_T_RAMP     (FX_T_SPLAT + FX_I_SPLAT)
#define FX_T_SCREEN   (FX_T_RAMP + FX_I_RAMP)
#define FX_T_SCREEN2  (FX_T_SCREEN + FX_I_SCREEN)

    // Texture i: the index is a constant per variant, so the compiler keeps one sample.
    vec4 fx_tex(int i, vec2 uv) {
        if (i == 1) return texture(_Tex1, uv);
        if (i == 2) return texture(_Tex2, uv);
        if (i == 3) return texture(_Tex3, uv);
        if (i == 4) return texture(_Tex4, uv);
        if (i == 5) return texture(_Tex5, uv);
        if (i == 6) return texture(_Tex6, uv);
        if (i == 7) return texture(_Tex7, uv);
        if (i == 8) return texture(_Tex8, uv);
        if (i == 9) return texture(_Tex9, uv);
        return texture(_Tex0, uv);
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.world = world.xyz;
        o.uv0 = cg_TexCoord0 * _UV0Window.zw + _UV0Window.xy;
        o.uv1 = cg_TexCoord0 * _UV1Window.zw + _UV1Window.xy;
        o.uv2 = cg_TexCoord1;
        o.colour = cg_Color * _Color;
        o.viewNormal = mat3(cg_ViewMatrix) * (CG_NORMAL_MATRIX * cg_Normal);
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
#ifdef LIGHTMAP
        vec4 lightmap = fx_tex(FX_T_LIGHTMAP, i.uv2);
  #ifdef LIGHTMAP_BLEND
        c.rgb = mix(c.rgb, fx_tex(FX_T_SPLAT, i.uv0).rgb, lightmap.a);
  #endif
        c.rgb *= lightmap.rgb;
#endif
#if defined(RIM_FALLOFF) || defined(OPAQUE_FALLOFF)
        float facing = min(1.0, abs(normalize(i.viewNormal).z));
        vec4 ramp = fx_tex(FX_T_RAMP, vec2(facing, _Falloff));
  #ifdef RIM_FALLOFF
        c.a *= ramp.g;
  #else
        c.rgb *= ramp.rgb;
  #endif
#endif
#if defined(SCREEN_TEXTURE) || defined(SCREEN_TEXTURE_2)
        vec2 screen = gl_FragCoord.xy / CG_RESOLUTION;
#endif
#ifdef SCREEN_TEXTURE
        vec2 suv = screen * _UvScaleScreen.xy + _UvScaleScreen.xy * _UV2Window.xy;
        vec4 scr = fx_tex(FX_T_SCREEN, vec2(suv.x, 1.0 - suv.y));
        c.rgb = mix(c.rgb, scr.rgb, scr.a * _UV3Window.x);
#endif
#ifdef SCREEN_TEXTURE_2
        vec2 suv2 = screen * _UvScaleScreen.zw + _UvScaleScreen.zw * _UV2Window.zw;
        vec4 scr2 = fx_tex(FX_T_SCREEN2, vec2(suv2.x, 1.0 - suv2.y));
        c.rgb = mix(c.rgb, scr2.rgb, scr2.a * _UV3Window.y);
#endif
#ifdef SOFT_PARTICLE
        float behind = CG_SCENE_EYE_DEPTH(gl_FragCoord.xy / CG_RESOLUTION) - cg_LinearEyeDepth(gl_FragCoord.z);
        c.a *= clamp(behind / max(_VolumeParam.x, 1e-3), 0.0, 1.0);
#endif
#ifdef DIRECTIONAL_LIT
        vec3 n = normalize(cross(dFdx(i.world), dFdy(i.world)));
        c.rgb *= 0.35 + 0.65 * max(dot(n, CG_SUN_DIRECTION), 0.0) * CG_DAYLIGHT;
#endif
        if (c.a <= _Clip) discard;
        vec3 glow = c.rgb * c.a * _Glare;
#if CG_FOG_MODE == 0
        fragColor = c;
#else
        fragColor = vec4(c.rgb * c.a, c.a);
#endif
        CG_GLOW(glow);
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
