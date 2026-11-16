// Temporal anti-aliasing's resolve (CgTemporalAA): this frame's jittered picture blended into the view's history. The
// history is fetched where this texel's surface was last frame (the velocity of the nearest texel round it, so an
// edge's motion is its foreground's), sampled through a Catmull-Rom filter so it does not soften frame on frame, and
// clipped toward the 3x3 neighbourhood's spread in YCoCg so a colour no longer under the texel cannot ghost. The two
// are mixed with weights that fall with brightness, so one bright texel cannot flicker the average; this frame's share
// rises with speed, as a fast surface has less history to trust.
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
    _History  ("Last frame's resolve", sampler2D) = "black"
    _Velocity ("What the velocity pass wrote, 0 where it wrote nothing", sampler2D) = "black"
    _Resolve  ("x this frame's least share, y its share at speed, z 1 with a history to blend", vec4) = (0.04, 0.2, 1, 0)
}

struct v2f { vec2 uv; };

void vertex(out v2f o) {
    o.uv = post_fullscreen_uv(CG_VERTEX_ID);
    gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
}

vec3 taa_ycocg(vec3 c) {
    return vec3(dot(c, vec3(0.25, 0.5, 0.25)), dot(c, vec3(0.5, 0.0, -0.5)), dot(c, vec3(-0.25, 0.5, -0.25)));
}

vec3 taa_rgb(vec3 c) {
    return vec3(c.x + c.y - c.z, c.x + c.z, c.x - c.y - c.z);
}

// A colour's weight in the blend, falling with brightness: an HDR texel weighs as its tonemapped self
float taa_weight(vec3 ycocg) {
    return 1.0 / (1.0 + max(ycocg.x, 0.0));
}

// The history at uv through a Catmull-Rom filter in five bilinear taps (the corners' small weights dropped)
vec3 taa_history(vec2 uv, vec2 size) {
    vec2 at = uv * size;
    vec2 centre = floor(at - 0.5) + 0.5;
    vec2 f = at - centre;
    vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
    vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
    vec2 w3 = f * f * (-0.5 + 0.5 * f);
    vec2 w12 = w1 + w2;
    vec2 t0 = (centre - 1.0) / size, t3 = (centre + 2.0) / size, t12 = (centre + w2 / w12) / size;
    vec3 sum = textureLod(_History, vec2(t12.x, t0.y), 0.0).rgb * (w12.x * w0.y)
            + textureLod(_History, vec2(t0.x, t12.y), 0.0).rgb * (w0.x * w12.y)
            + textureLod(_History, t12, 0.0).rgb * (w12.x * w12.y)
            + textureLod(_History, vec2(t3.x, t12.y), 0.0).rgb * (w3.x * w12.y)
            + textureLod(_History, vec2(t12.x, t3.y), 0.0).rgb * (w12.x * w3.y);
    float total = w12.x * w0.y + w0.x * w12.y + w12.x * w12.y + w3.x * w12.y + w12.x * w3.y;
    return max(sum / total, vec3(0.0));
}

void fragment(in v2f i, out vec4 fragColor) {
    ivec2 at = ivec2(gl_FragCoord.xy);
    ivec2 last = textureSize(cg_SceneColor, 0) - 1;
    vec2 size = vec2(last + 1);
    vec3 current = taa_ycocg(texelFetch(cg_SceneColor, at, 0).rgb);
    // The neighbourhood's mean and spread, and its nearest depth (the engine's: nearer is greater)
    vec3 mean = vec3(0.0), square = vec3(0.0), lo = current, hi = current;
    ivec2 nearest = at;
    float nearestDepth = cg_EngineDepth(texelFetch(cg_DepthBuffer, at, 0).r);
    for (int k = 0; k < 9; k++) {   // LOOP-BOUND: the 3x3 round the texel
        ivec2 q = clamp(at + ivec2(k % 3 - 1, k / 3 - 1), ivec2(0), last);
        vec3 c = taa_ycocg(texelFetch(cg_SceneColor, q, 0).rgb);
        mean += c;
        square += c * c;
        lo = min(lo, c);
        hi = max(hi, c);
        float d = cg_EngineDepth(texelFetch(cg_DepthBuffer, q, 0).r);
        if (d > nearestDepth) {
            nearestDepth = d;
            nearest = q;
        }
    }
    vec3 result = current;
    if (_Resolve.z > 0.5) {
        vec2 nearUv = (vec2(nearest) + 0.5) / size;
        vec2 motion = cg_Velocity(_Velocity, nearUv, texelFetch(cg_DepthBuffer, nearest, 0).r) * 0.5;
        vec2 from = (vec2(at) + 0.5) / size - motion;
        if (all(greaterThanEqual(from, vec2(0.0))) && all(lessThanEqual(from, vec2(1.0)))) {
            vec3 history = taa_ycocg(taa_history(from, size));
            // Clip toward the mean, inside the spread a standard deviation and a quarter out, and the box itself
            mean /= 9.0;
            vec3 sigma = sqrt(max(square / 9.0 - mean * mean, vec3(0.0)));
            vec3 boxLo = max(lo, mean - 1.25 * sigma), boxHi = min(hi, mean + 1.25 * sigma);
            vec3 centre = 0.5 * (boxHi + boxLo), extent = max(0.5 * (boxHi - boxLo), vec3(1.0e-4));
            vec3 offset = history - centre;
            vec3 units = abs(offset / extent);
            float out_ = max(units.x, max(units.y, units.z));
            if (out_ > 1.0) history = centre + offset / out_;
            float speed = length(motion * size);
            float share = mix(_Resolve.x, _Resolve.y, clamp(speed / 8.0, 0.0, 1.0));
            float wc = share * taa_weight(current), wh = (1.0 - share) * taa_weight(history);
            result = (current * wc + history * wh) / (wc + wh);
        }
    }
    fragColor = vec4(max(taa_rgb(result), vec3(0.0)), 1.0);
}
