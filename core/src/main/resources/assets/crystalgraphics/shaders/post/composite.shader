// The post stack's composite: the one full-screen pass a frame that lays every screen-wide look over the target. Each
// look is a feature keyword, so a frame compiles and runs only what is active. One triangle over the screen.
// CgPostComposite draws it; nothing else should.
//
//   BLOOM  the bloom chain's level 0, every level already summed into it, added over the target
#type none
#pragma cg_feature BLOOM

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Overlay"

Properties {
    _Bloom     ("The bloom chain", sampler2D) = "black"
    _Intensity ("Bloom's strength", float) = 1.0
}

struct v2f { vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
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
        vec3 c = vec3(0.0);
#ifdef BLOOM
        // The chain's upsample already summed every level into level 0, smoothed by its tents.
        c += textureLod(_Bloom, i.uv, 0.0).rgb * _Intensity;
#endif
        fragColor = vec4(c, 0.0);
    }
}
