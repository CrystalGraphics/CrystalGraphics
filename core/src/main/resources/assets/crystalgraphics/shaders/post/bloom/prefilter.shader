// A halving before the bloom chain, where the emission is more than twice the chain's top level: Castano's 3x3 Gaussian
// from four bilinear taps, as Filament's first step. KARIS is the first halving's, so a firefly cannot dominate its
// block. One triangle over the half-size target. CgBloomChain draws it.
#type none
#pragma cg_feature KARIS
#include "crystalgraphics:shaders/lib/post/bloom.glsl"

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Overlay"

Properties {
    _Source ("The emission, or the halving before", sampler2D) = "black"
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
        fragColor = vec4(post_downsample4(_Source, i.uv, texel, 0.75, true), 1.0);
#else
        fragColor = vec4(post_downsample4(_Source, i.uv, texel, 0.75, false), 1.0);
#endif
    }
}
