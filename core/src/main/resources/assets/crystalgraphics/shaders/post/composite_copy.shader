// The post stack's composite, copy form: reads the target as drawn (cg_SceneColor), decodes it from sRGB, lays every
// active look over it in linear light, and writes it back encoded and dithered. The correct form, and the one a look
// that brightens by multiplying or reads its neighbours needs; it costs a copy of the target. Each look is a feature
// keyword, as in composite.shader. Chromatic aberration reads up to 2% of the screen's height away, its margin.
// CgPostComposite draws it; nothing else should.
//
//   CHROMATIC  red and blue read apart from _Focus, the further the more
//   FLASH      the picture times _Exposure, in linear light; bloom is added after, unscaled
//   BLOOM      the bloom chain's level 0, tinted, added in linear light; or mixed in, energy-conserving
//   VIGNETTE   the corners darkened by _Vignette
//   IMPACT     _Impact.x of the way to an impact frame (_Impact.y: 0 negative, 1 black and white, 2 speed lines)
#type none
#pragma cg_feature BLOOM
#pragma cg_feature FLASH
#pragma cg_feature VIGNETTE
#pragma cg_feature CHROMATIC
#pragma cg_feature IMPACT
#include "crystalgraphics:shaders/lib/post/composite.glsl"

Tags { "RenderType" = "Transparent" "SceneColorMargin" = "0.02" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Overlay"

Properties {
    _Bloom     ("The bloom chain", sampler2D) = "black"
    _Intensity ("Bloom's strength; its mix share when conserving", float) = 1.0
    _Tint      ("Bloom's tint", color) = (1, 1, 1, 1)
    _Conserve  ("1 to mix the bloom in, keeping the picture's energy; 0 to add it", float) = 0.0
    _Exposure  ("The flash: what the picture is multiplied by", float) = 1.0
    _Vignette  ("How dark the corners go, 0 to 1", float) = 0.0
    _Chromatic ("Chromatic aberration, 0 to 1", float) = 0.0
    _Impact    ("x: share of the impact frame; y: its look", vec4) = (0, 0, 0, 0)
    _Focus     ("xy: where aberration and speed lines centre, 0 to 1", vec4) = (0.5, 0.5, 0, 0)
}

struct v2f { vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend OFF
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        vec2 p = vec2(float((CG_VERTEX_ID & 1) << 2) - 1.0, float((CG_VERTEX_ID & 2) << 1) - 1.0);
        o.uv = p * 0.5 + 0.5;
        gl_Position = vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec4 scene = CG_SCENE_COLOR(i.uv);
#ifdef CHROMATIC
        vec2 d = (i.uv - _Focus.xy) * (_Chromatic * 0.02);
        vec3 c = vec3(post_decode_srgb(CG_SCENE_COLOR(i.uv + d).rgb).r, post_decode_srgb(scene.rgb).g,
                      post_decode_srgb(CG_SCENE_COLOR(i.uv - d).rgb).b);
#else
        vec3 c = post_decode_srgb(scene.rgb);
#endif
#ifdef FLASH
        c *= _Exposure;
#endif
#ifdef BLOOM
        vec3 bloom = textureLod(_Bloom, i.uv, 0.0).rgb * _Tint.rgb;
        c = _Conserve > 0.5 ? mix(c, bloom, clamp(_Intensity, 0.0, 1.0)) : c + bloom * _Intensity;
#endif
#ifdef VIGNETTE
        c *= post_vignette(i.uv, CG_RESOLUTION, _Vignette);
#endif
        vec3 encoded = post_encode_srgb(c);
#ifdef IMPACT
        encoded = mix(encoded, post_impact(encoded, _Impact.y, i.uv, _Focus.xy, CG_RESOLUTION, CG_TIME), _Impact.x);
#endif
        fragColor = vec4(post_dither8(encoded, gl_FragCoord.xy, floor(CG_TIME * 60.0)), scene.a);
    }
}
