// Temporal AA's output (CgTemporalAA): the resolved colour out of the history, with the picture's own alpha in place of
// the history's age, sharpened by AMD FidelityFX RCAS (lib/rcas.glsl, ported from FSR 1, MIT).
#domain global
#include "crystalgraphics:shaders/env/material/post.glsl"
#include "crystalgraphics:shaders/lib/velocity.glsl"
#include "crystalgraphics:shaders/lib/rcas.glsl"
Material { SceneColorMargin 0.001  Media On }

RenderState {
    Blend OFF
    DepthTest ALWAYS
    DepthWrite OFF
    Cull OFF
}

Properties {
    _History ("This frame's resolve", sampler2D) = "black"
    _Sharpen ("x RCAS's sharpness, 2^-stops (1 its most); 0 copies unsharpened. y 1 where every engine solid wrote velocity", vec4) = (0, 0, 0, 0)
    _Velocity ("What the velocity pass wrote, 0 where it wrote nothing", sampler2D) = "black"
    _Responsive ("Where a blended draw covered: 1 Responsive AA, 0.5 one that keeps its history", sampler2D) = "black"
}

struct v2f { vec2 uv; };

void vertex(out v2f o) {
    o.uv = post_fullscreen_uv(CG_VERTEX_ID);
    gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
}

vec3 rcas_load(ivec2 q, ivec2 last) {
    return rcas_encode(texelFetch(_History, clamp(q, ivec2(0), last), 0).rgb);
}

void fragment(in v2f i, out vec4 fragColor) {
    ivec2 at = ivec2(gl_FragCoord.xy);
    float alpha = texelFetch(cg_SceneColor, at, 0).a;
    // Unsharpened where the engine drew nothing: the resolve passed it through, as temporal AA off shows it
    if (_Sharpen.x == 0.0 || _Sharpen.y > 0.5 && !cg_EngineDrew(_Velocity, _Responsive, at)) {
        fragColor = vec4(texelFetch(_History, at, 0).rgb, alpha);
        return;
    }
    ivec2 last = textureSize(_History, 0) - 1;
    vec3 sharp = rcas(rcas_load(at + ivec2(0, -1), last), rcas_load(at + ivec2(-1, 0), last), rcas_load(at, last),
            rcas_load(at + ivec2(1, 0), last), rcas_load(at + ivec2(0, 1), last), _Sharpen.x);
    fragColor = vec4(rcas_decode(sharp), alpha);
}
