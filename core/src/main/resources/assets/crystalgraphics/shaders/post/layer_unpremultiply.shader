// A graded layer's light as colour (CgLayerLooks): premultiplied light over its cover, so a look's post material grades
// a colour; nothing where nothing covers.
#domain global
#include "crystalgraphics:shaders/env/material/post.glsl"
Material { SceneColorMargin 0.001 }

RenderState {
    Blend OFF
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
    vec4 layer = texelFetch(cg_SceneColor, ivec2(gl_FragCoord.xy), 0);
    fragColor = vec4(layer.a > 1e-4 ? layer.rgb / layer.a : vec3(0.0), 1.0);
}
