// One upsample of the bloom chain: the level below, tent-filtered, added onto this level's own downsample. The blend
// keeps _Keep of what the level holds (out alpha is the destination's factor) and adds _Weight of the tent, so each
// level's share of the final glow is set here rather than in a sum at the end. CHEAP is Low's four-tap upsample
// instead of the tent. One triangle over the level. CgBloomChain draws it.
#type none
#pragma cg_feature CHEAP
#include "crystalgraphics:shaders/lib/post/bloom.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Overlay"

Properties {
    _Source ("The level below", sampler2D) = "black"
    _Weight ("Share of the level below added", float) = 1.0
    _Keep   ("Share of this level's own downsample kept", float) = 1.0
}

struct v2f { vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE SRC_ALPHA
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
        vec2 texel = 1.0 / vec2(textureSize(_Source, 0));
#ifdef CHEAP
        fragColor = vec4(post_upsample4(_Source, i.uv, texel) * _Weight, _Keep);
#else
        fragColor = vec4(post_tent(_Source, i.uv, texel) * _Weight, _Keep);
#endif
    }
}
