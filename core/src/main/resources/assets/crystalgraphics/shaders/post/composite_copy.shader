// The post stack's composite, copy form: reads the target as drawn (cg_SceneColor), decodes it from sRGB, lays every
// active look over it in linear light, and writes it back encoded and dithered. The correct form, and the one a look
// that brightens by multiplying or reads its neighbours needs; it costs a copy of the target. Each look is a feature
// keyword, as in composite.shader. It samples only its own pixel, so its SceneColorMargin is the least the parser
// takes. CgPostComposite draws it; nothing else should.
//
//   BLOOM  the bloom chain's level 0, tinted, added in linear light; or mixed in, energy-conserving
#type none
#pragma cg_feature BLOOM
#include "crystalgraphics:shaders/lib/post/composite.glsl"

Tags { "RenderType" = "Transparent" "SceneColorMargin" = "0.001" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Overlay"

Properties {
    _Bloom     ("The bloom chain", sampler2D) = "black"
    _Intensity ("Bloom's strength; its mix share when conserving", float) = 1.0
    _Tint      ("Bloom's tint", color) = (1, 1, 1, 1)
    _Conserve  ("1 to mix the bloom in, keeping the picture's energy; 0 to add it", float) = 0.0
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
        vec3 c = post_decode_srgb(scene.rgb);
#ifdef BLOOM
        vec3 bloom = textureLod(_Bloom, i.uv, 0.0).rgb * _Tint.rgb;
        c = _Conserve > 0.5 ? mix(c, bloom, clamp(_Intensity, 0.0, 1.0)) : c + bloom * _Intensity;
#endif
        vec3 encoded = post_dither8(post_encode_srgb(c), gl_FragCoord.xy, floor(CG_TIME * 60.0));
        fragColor = vec4(encoded, scene.a);
    }
}
