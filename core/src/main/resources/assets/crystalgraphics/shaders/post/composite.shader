// The post stack's composite, blend form: the one full-screen pass a frame that lays every screen-wide look over the
// target, written as dst * (1 - alpha) + rgb so it needs no copy of the target. It adds in the target's own encoding.
// Each look is a feature keyword, so a frame compiles and runs only what is active. One triangle over the screen.
// CgPostComposite draws it; nothing else should. composite_copy.shader is the form that reads the target.
//
//   BLOOM  the bloom chain's level 0 (every level already summed into it), tinted, added; or mixed in, energy-conserving
#type none
#pragma cg_feature BLOOM
#include "crystalgraphics:shaders/lib/post/composite.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Linear" }
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
        Blend ONE ONE_MINUS_SRC_ALPHA
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        // A triangle covering the screen: (-1,-1), (3,-1), (-1,3).
        vec2 p = vec2(float((CG_VERTEX_ID & 1) << 2) - 1.0, float((CG_VERTEX_ID & 2) << 1) - 1.0);
        o.uv = p * 0.5 + 0.5;
        gl_Position = vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 added = vec3(0.0);
        float lost = 0.0;
#ifdef BLOOM
        vec3 bloom = textureLod(_Bloom, i.uv, 0.0).rgb * _Tint.rgb;
        float share = mix(_Intensity, clamp(_Intensity, 0.0, 1.0), _Conserve);
        added += bloom * share;
        lost = _Conserve * share;
#endif
        fragColor = vec4(post_round8(added, gl_FragCoord.xy, floor(CG_TIME * 60.0)), lost);
    }
}
