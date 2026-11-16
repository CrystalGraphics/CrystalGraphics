// A temporal upscaler's glow (CgTemporalGlow): bloom's light, each jittered frame's excess past bloom's threshold,
// followed by the resolve's motion and averaged over the jitter's frames, at the size drawn. Thresholded after the resolve, a light thinner than a
// texel, spread to its coverage, falls under the threshold and loses its glow; thresholded per frame and averaged, it
// glows as it does without temporal AA, and holds still from frame to frame. The glow is held under what the resolved
// light allows: a light x over coverage c on a background b averages L = c x + (1 - c) b, its excess at most
// L - min(threshold, b). With L the resolved 3x3's brightest and b its dimmest, a light still there keeps all of its
// own glow, and a texel showing plain background, however bright, keeps none: a light that moved off it leaves no trail.
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
    _Resolved ("This frame's resolve, the glow's bound: the size drawn or the output's", sampler2D) = "black"
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
            // The resolve is unjittered and this frame's samples sit up to half a texel off theirs: its 3x3, a texel of
            // this frame's apart where it is upscaled
            ivec2 resolvedLast = textureSize(_Resolved, 0) - 1;
            vec2 scale = vec2(resolvedLast + 1) / size;
            ivec2 centre = ivec2((vec2(at) + 0.5) * scale), apart = max(ivec2(round(scale)), ivec2(1));
            vec3 brightest = vec3(0.0), dimmest = vec3(65504.0);
            for (int k = 0; k < 9; k++) {   // LOOP-BOUND: the 3x3 round the texel
                ivec2 q = clamp(centre + ivec2(k % 3 - 1, k / 3 - 1) * apart, ivec2(0), resolvedLast);
                vec3 c = texelFetch(_Resolved, q, 0).rgb;
                brightest = max(brightest, c);
                dimmest = min(dimmest, c);
            }
            vec3 bound = max(brightest - min(vec3(_Glow.y), dimmest), vec3(0.0));
            result = mix(min(textureLod(_History, from, 0.0).rgb, bound), excess, responsive ? max(_Glow.x, 0.25) : _Glow.x);
        }
    }
    fragColor = vec4(result, 1.0);
}
