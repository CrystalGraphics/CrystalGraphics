// One downsample of the bloom chain: a level from the one above it (or from the emission target, for the first), by the
// 13-tap filter. KARIS is the first step's, so a firefly cannot dominate its block. One triangle over the level.
// CgBloomChain draws it.
#type none
#pragma cg_feature KARIS
#include "crystalgraphics:shaders/lib/post/bloom.glsl"

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Overlay"

Properties {
    _Source ("The level above, or the emission", sampler2D) = "black"
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
        vec2 texel = 1.0 / vec2(textureSize(_Source, 0));
#ifdef KARIS
        fragColor = vec4(post_downsample13(_Source, i.uv, texel, true), 1.0);
#else
        fragColor = vec4(post_downsample13(_Source, i.uv, texel, false), 1.0);
#endif
    }
}
