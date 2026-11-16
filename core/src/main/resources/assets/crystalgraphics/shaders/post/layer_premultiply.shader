// A graded layer's colour back as light (CgLayerLooks): drawn over the layer, the blend multiplies by its cover and
// keeps it.
#domain global
#include "crystalgraphics:shaders/env/material/post.glsl"
Material { SceneColorMargin 0.001 }

RenderState {
    Blend DST_ALPHA ZERO
    DepthTest ALWAYS
    DepthWrite OFF
    Cull OFF
}

struct v2f { vec2 uv; };

void vertex(out v2f o) {
    o.uv = post_fullscreen_uv(CG_VERTEX_ID);
    gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
}

void fragment(in v2f i, out vec4 fragColor) {
    fragColor = vec4(texelFetch(cg_SceneColor, ivec2(gl_FragCoord.xy), 0).rgb, 1.0);
}
