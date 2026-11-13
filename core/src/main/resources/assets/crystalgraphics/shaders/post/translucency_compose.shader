// Separate translucency laid over the scene (CgTranslucencyComposition): Unreal's ComposeSeparateTranslucency, the
// scene times what the translucency left of it plus the light it added. _Translucency holds rgb premultiplied and a
// its coverage, so the blend is ONE ONE_MINUS_SRC_ALPHA; over a graded layer its alpha composes the same way.
#domain global
#include "crystalgraphics:shaders/lib/post/post_material.glsl"
Material { Media On }

RenderState {
    Blend ONE ONE_MINUS_SRC_ALPHA
    DepthTest ALWAYS
    DepthWrite OFF
    Cull OFF
}

Properties {
    _Translucency ("rgb the light separate translucency added, a how much it covered", sampler2D) = "black"
}

struct v2f { vec2 uv; };

void vertex(out v2f o) {
    o.uv = post_fullscreen_uv(CG_VERTEX_ID);
    gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
}

void fragment(in v2f i, out vec4 fragColor) {
    fragColor = texelFetch(_Translucency, ivec2(gl_FragCoord.xy), 0);
}
