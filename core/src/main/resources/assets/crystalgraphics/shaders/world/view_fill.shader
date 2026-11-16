// The world's background for a view drawn below 100% (CgViewRenderTarget.fill): the host's picture so far, cg_SceneColor,
// resampled bilinearly to the size the world draws at, so the clear or sky the host drew shows behind the world, and the
// far plane written into its depth (a host with no world of its own has drawn no depth).
#domain global
#include "crystalgraphics:shaders/env/material/post.glsl"
Material { SceneColorMargin 0.001  Media On }

RenderState {
    Blend OFF
    DepthTest ALWAYS
    DepthWrite ON
    Cull OFF
}

Properties {
    _Fill ("xy the host's size over the size drawn at, zw the host's size", vec4) = (1, 1, 1, 1)
}

struct v2f { vec2 uv; };

void vertex(out v2f o) {
    o.uv = post_fullscreen_uv(CG_VERTEX_ID);
    gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
}

void fragment(in v2f i, out vec4 fragColor) {
    vec2 p = gl_FragCoord.xy * _Fill.xy - 0.5;
    ivec2 a = ivec2(floor(p)), last = ivec2(_Fill.zw) - 1;
    vec2 f = p - floor(p);
    vec4 c00 = texelFetch(cg_SceneColor, clamp(a, ivec2(0), last), 0);
    vec4 c10 = texelFetch(cg_SceneColor, clamp(a + ivec2(1, 0), ivec2(0), last), 0);
    vec4 c01 = texelFetch(cg_SceneColor, clamp(a + ivec2(0, 1), ivec2(0), last), 0);
    vec4 c11 = texelFetch(cg_SceneColor, clamp(a + ivec2(1, 1), ivec2(0), last), 0);
    fragColor = mix(mix(c00, c10, f.x), mix(c01, c11, f.x), f.y);
    gl_FragDepth = cg_DepthParams.x > 0.5 ? 0.0 : 1.0;
}
