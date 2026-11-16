// Temporal AA's glow (CgTemporalAA.glow): bloom's light, each jittered frame's excess past bloom's threshold, followed
// by the resolve's motion and averaged over the jitter's frames. Thresholded after the resolve, a light thinner than a
// texel, spread to its coverage, falls under the threshold and loses its glow; thresholded per frame and averaged, it
// glows as it does without temporal AA, and holds still from frame to frame. A texel's average excess never passes its
// average light, which the resolve has: the glow is held under the resolved picture's brightest round the texel, so a
// light that has moved away leaves no glow behind it, while one still there keeps all of its own.
#domain global
#include "crystalgraphics:shaders/env/material/post.glsl"
#include "crystalgraphics:shaders/lib/velocity.glsl"
Material { SceneColorMargin 0.001  Media On }

RenderState {
    Blend OFF
    DepthTest ALWAYS
    DepthWrite OFF
    Cull OFF
}

Properties {
    _History  ("Last frame's glow", sampler2D) = "black"
    _Resolved ("This frame's resolve, the glow's bound", sampler2D) = "black"
    _Velocity ("What the velocity pass wrote, 0 where it wrote nothing", sampler2D) = "black"
    _Responsive ("1 where a blended draw covered (Responsive AA)", sampler2D) = "black"
    _Glow     ("x this frame's share, y bloom's threshold, z 1 with a history to blend", vec4) = (0.0625, 1, 1, 0)
    _Jitter   ("xy this frame's jitter in normalized device coordinates; z 1 where every engine solid wrote velocity", vec4) = (0, 0, 0, 0)
}

struct v2f { vec2 uv; };

void vertex(out v2f o) {
    o.uv = post_fullscreen_uv(CG_VERTEX_ID);
    gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
}

void fragment(in v2f i, out vec4 fragColor) {
    ivec2 at = ivec2(gl_FragCoord.xy);
    ivec2 last = textureSize(cg_SceneColor, 0) - 1;
    vec2 size = vec2(last + 1);
    vec3 excess = max(texelFetch(cg_SceneColor, at, 0).rgb - _Glow.y, vec3(0.0));
    vec3 result = excess;
    // What the engine did not draw glows as this frame's, as the resolve passes it through; under a blended draw a
    // quarter of the new frame, as the resolve keeps it
    bool responsive = texelFetch(_Responsive, at, 0).r > 0.5;
    if (_Glow.z > 0.5 && (_Jitter.z < 0.5 || responsive || cg_EngineDrew(_Velocity, at))) {
        // The nearest surface round the texel moves its glow, as the resolve's (the engine's depth: nearer is greater)
        ivec2 nearest = at;
        float nearestDepth = cg_EngineDepth(texelFetch(cg_DepthBuffer, at, 0).r);
        for (int k = 0; k < 9; k++) {   // LOOP-BOUND: the 3x3 round the texel
            ivec2 q = clamp(at + ivec2(k % 3 - 1, k / 3 - 1), ivec2(0), last);
            float d = cg_EngineDepth(texelFetch(cg_DepthBuffer, q, 0).r);
            if (d > nearestDepth) {
                nearestDepth = d;
                nearest = q;
            }
        }
        vec2 back = cg_Velocity(_Velocity, (vec2(nearest) + 0.5) / size, texelFetch(cg_DepthBuffer, nearest, 0).r) - _Jitter.xy;
        vec2 from = (vec2(at) + 0.5) / size - back * 0.5;
        if (all(greaterThanEqual(from, vec2(0.0))) && all(lessThanEqual(from, vec2(1.0)))) {
            // The resolve is unjittered and this frame's samples sit up to half a texel off theirs: its 3x3's brightest
            vec3 bound = vec3(0.0);
            for (int k = 0; k < 9; k++) {   // LOOP-BOUND: the 3x3 round the texel
                bound = max(bound, texelFetch(_Resolved, clamp(at + ivec2(k % 3 - 1, k / 3 - 1), ivec2(0), last), 0).rgb);
            }
            result = mix(min(textureLod(_History, from, 0.0).rgb, bound), excess, responsive ? max(_Glow.x, 0.25) : _Glow.x);
        }
    }
    fragColor = vec4(result, 1.0);
}
