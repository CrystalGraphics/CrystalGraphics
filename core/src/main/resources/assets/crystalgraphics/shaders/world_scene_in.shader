// The HDR scene's first pass: the host's 8-bit colour, as drawn before the transparent stage, decoded into the linear
// scene texel for texel. With DEPTH (a host that lends no depth: framebuffer 0, multisampled) it also writes the host's
// depth into the scene's own, under a depth state the caller sets. CgSceneTarget draws it; nothing else should.
#type none
#pragma cg_feature DEPTH
#include "crystalgraphics:shaders/lib/post/composite.glsl"

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" "SceneColorMargin" = "0.001" }
Queue = "Overlay"

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
        vec4 host = CG_SCENE_COLOR(i.uv);
        fragColor = vec4(post_decode_srgb(host.rgb), host.a);
#ifdef DEPTH
        gl_FragDepth = texelFetch(cg_DepthBuffer, ivec2(gl_FragCoord.xy), 0).r;
#endif
    }
}
