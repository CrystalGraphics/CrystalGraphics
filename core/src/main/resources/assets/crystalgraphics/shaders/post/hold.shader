// A crossfade's hold (CgPostCrossfade): the target as drawn, cg_SceneColor, copied into the held frame texel for texel.
#type none
#include "crystalgraphics:shaders/lib/post/post_material.glsl"

Tags { "RenderType" = "Transparent" "SceneColorMargin" = "0.001" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Linear" }
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
        o.uv = post_fullscreen_uv(CG_VERTEX_ID);
        gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(texelFetch(cg_SceneColor, ivec2(gl_FragCoord.xy), 0).rgb, 1.0);
    }
}
