// A cut's crossfade (CgPostCrossfade): the held frame laid over the picture at POST_WEIGHT, which falls to 0 over the
// crossfade, blended in the target's encoding.
#domain global
#include "crystalgraphics:shaders/lib/post/post_material.glsl"
Material { Media On }

RenderState {
    Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA
    DepthTest ALWAYS
    DepthWrite OFF
    Cull OFF
}

Properties {
    _Held ("The frame held at the cut", sampler2D) = "black"
}

struct v2f { vec2 uv; };

void vertex(out v2f o) {
    o.uv = post_fullscreen_uv(CG_VERTEX_ID);
    gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
}

void fragment(in v2f i, out vec4 fragColor) {
    fragColor = vec4(texelFetch(_Held, ivec2(gl_FragCoord.xy), 0).rgb, POST_WEIGHT);
}
