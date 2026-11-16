// A look's post materials laid on its solids alone (CgLookPost): the scene's graded copy where this look's solid is the
// visible surface, its id in custom stencil bits 5-7 and custom depth at the scene's depth (an occluder in front leaves
// the stencil but not the depth); the scene as it was elsewhere.
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
    _Graded        ("The scene, graded by the look's post materials", sampler2D) = "black"
    _CustomDepth   ("Custom depth", sampler2D) = "black"
    _CustomStencil ("Custom depth's stencil: bits 5-7 a graded solid's look id", usampler2D) = "zero"
    _Look          ("This look's id (CgLookIds)", float) = 0
}

struct v2f { vec2 uv; };

void vertex(out v2f o) {
    o.uv = post_fullscreen_uv(CG_VERTEX_ID);
    gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
}

void fragment(in v2f i, out vec4 fragColor) {
    ivec2 at = ivec2(gl_FragCoord.xy);
    vec4 scene = texelFetch(cg_SceneColor, at, 0);
    bool mine = ((texelFetch(_CustomStencil, at, 0).r >> 5u) & 7u) == uint(_Look + 0.5);
    if (mine) {
        // Relative in eye depth, as the look weights compare them: the scene's may be a host's 24-bit depth
        float custom = cg_LinearEyeDepth(texelFetch(_CustomDepth, at, 0).r), drawn = CG_SCENE_EYE_DEPTH(i.uv);
        mine = abs(custom - drawn) <= 1.0e-3 * drawn + 1.0e-4;
    }
    fragColor = mine ? vec4(texelFetch(_Graded, at, 0).rgb, scene.a) : scene;
}
