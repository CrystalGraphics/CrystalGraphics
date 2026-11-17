// A look's post materials laid on its pixels by its share of them (CgLookPost.weighted), after the look weights are
// drawn: the picture's graded copy mixed in by the look's resolved weight, so a silhouette's edge takes as much of the
// look as the temporal step gave it. The soft form of Unreal's custom-stencil test.
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
    _Graded  ("The picture, graded by the look's post materials", sampler2D) = "black"
    _Weights ("The look weights, resolved where a temporal step ran: one channel a look", sampler2D) = "black"
    _Channel ("1 in this look's channel, 0 in the rest", vec4) = (0, 0, 0, 0)
}

struct v2f { vec2 uv; };

void vertex(out v2f o) {
    o.uv = post_fullscreen_uv(CG_VERTEX_ID);
    gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
}

void fragment(in v2f i, out vec4 fragColor) {
    ivec2 at = ivec2(gl_FragCoord.xy);
    vec4 picture = texelFetch(cg_SceneColor, at, 0);
    // By position: the weights may be at the size drawn and the picture at the output's
    float w = dot(textureLod(_Weights, (vec2(at) + 0.5) / vec2(textureSize(cg_SceneColor, 0)), 0.0), _Channel);
    fragColor = vec4(mix(picture.rgb, texelFetch(_Graded, at, 0).rgb, w), picture.a);
}
