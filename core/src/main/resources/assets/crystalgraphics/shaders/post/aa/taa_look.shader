// The look weights' temporal resolve under temporal AA (CgTemporalAA, CgLookWeights): this frame's weights blended into
// their history at the share the colour's resolve took there, so a look's edge settles with the edge it tones. The share
// is the resolve's own, rebuilt from the frames it kept (the resolved history's age) and the motion it fetched by; the
// history is clamped to the 3x3 of this frame's weights, as Unreal's temporal AA clamps alpha with the colour.
#domain global
#include "crystalgraphics:shaders/env/material/post.glsl"
#include "crystalgraphics:shaders/lib/velocity.glsl"
#include "crystalgraphics:shaders/post/aa/taa.glsl"

Material { SceneColorMargin 0.001 }

RenderState {
    Blend OFF
    DepthTest ALWAYS
    DepthWrite OFF
    Cull OFF
}

Properties {
    _History    ("The colour's history as this frame resolved it: alpha its age", sampler2D) = "black"
    _PrevLook   ("Last frame's resolved look weights", sampler2D) = "black"
    _Velocity   ("What the velocity pass wrote, 0 where it wrote nothing", sampler2D) = "black"
    _Responsive ("1 where a blended draw covered (Responsive AA)", sampler2D) = "black"
    _Resolve    ("As the resolve's: x the least share, y the share at speed, z 1 with a history to blend", vec4) = (0.04, 0.2, 1, 0.25)
    _Jitter     ("xy this frame's jitter in normalized device coordinates", vec4) = (0, 0, 0, 0)
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
    // This frame's weights filtered round the texel's centre by where the jitter took each sample, as the colour's are
    vec2 jitter = _Jitter.xy * 0.5 * size;
    vec4 lo = texelFetch(cg_SceneColor, at, 0), hi = lo, filtered = vec4(0.0);
    float filterWeight = 0.0;
    for (int k = 0; k < 9; k++) {   // LOOP-BOUND: the 3x3 round the texel
        ivec2 q = clamp(at + ivec2(k % 3 - 1, k / 3 - 1), ivec2(0), last);
        vec4 c = texelFetch(cg_SceneColor, q, 0);
        vec2 toSample = vec2(k % 3 - 1, k / 3 - 1) - jitter;
        float w = taa_catmull_rom(toSample.x) * taa_catmull_rom(toSample.y);
        filtered += c * w;
        filterWeight += w;
        lo = min(lo, c);
        hi = max(hi, c);
    }
    vec4 current = clamp(filtered / filterWeight, 0.0, 1.0);
    // The frames the resolve kept here, this one counted: 1 where it began again, 0 where it took the frame as it came
    float packed = texelFetch(_History, at, 0).a;
    float age = packed - floor(packed / TAA_AGES) * TAA_AGES;
    if (_Resolve.z < 0.5 || age < 1.5) {
        fragColor = current;
        return;
    }
    vec2 motion, farMotion;
    taa_motion(_Velocity, at, last, _Jitter.xy, motion, farMotion);
    vec2 from = (vec2(at) + 0.5) / size - motion;
    if (any(lessThan(from, vec2(0.0))) || any(greaterThan(from, vec2(1.0)))) {
        fragColor = current;
        return;
    }
    float speed = length(motion * size);
    bool responsive = texelFetch(_Responsive, at, 0).r > 0.5;
    float share = taa_share(_Resolve, speed, taa_still(speed, length(farMotion * size)), age - 1.0, responsive);
    vec4 history = clamp(textureLod(_PrevLook, from, 0.0), lo, hi);
    fragColor = mix(history, current, share);
}
