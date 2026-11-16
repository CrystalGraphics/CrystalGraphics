// The scene graded where a graded layer covers it (CgLayerLooks): the scene mixed toward its graded copy by the layer's
// cover, so a look's post material reaches its own draws' pixels alone.
#domain global
#include "crystalgraphics:shaders/env/material/post.glsl"
Material { SceneColorMargin 0.001 }

RenderState {
    Blend OFF
    DepthTest ALWAYS
    DepthWrite OFF
    Cull OFF
}

Properties {
    _Graded ("The scene, graded by the look's post materials", sampler2D) = "black"
    _Layer  ("The graded layer: a its cover", sampler2D) = "black"
}

struct v2f { vec2 uv; };

void vertex(out v2f o) {
    o.uv = post_fullscreen_uv(CG_VERTEX_ID);
    gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
}

void fragment(in v2f i, out vec4 fragColor) {
    ivec2 at = ivec2(gl_FragCoord.xy);
    vec4 scene = texelFetch(cg_SceneColor, at, 0);
    float cover = clamp(texelFetch(_Layer, at, 0).a, 0.0, 1.0);
    fragColor = vec4(mix(scene.rgb, texelFetch(_Graded, at, 0).rgb, cover), scene.a);
}
