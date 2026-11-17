// Temporal anti-aliasing's resolve (CgTemporalAA): this frame's jittered picture blended into the view's history. The
// history is fetched where this texel's surface was last frame (the velocity of the nearest texel round it, so an
// edge's motion is its foreground's), sampled through a Catmull-Rom filter so it does not soften frame on frame, and
// clipped toward the 3x3 neighbourhood's spread in YCoCg so a colour no longer under the texel cannot ghost. The two
// are mixed with weights that fall with brightness, so one bright texel cannot flicker the average; this frame's share
// rises with speed, as a fast surface has less history to trust. Where the neighbourhood's nearest and farthest texels
// move apart (parallax through a dithered or thin surface) no one fetch is right: the texel's age, kept in the history's
// alpha, is cut to as many frames as smear it a texel (three at least, a quarter's share), so the stale picture goes in
// the frames after rather than at 4% a frame. This frame's share is its 3x3 filtered round the texel's centre, each sample
// weighed by where the jitter took it (Catmull-Rom): a detail thinner than a texel, which one jittered sample in eight lands on, reaches
// the texel from its neighbours on the other seven too, rather than pulsing once a cycle. Geometry thinner than a texel
// (a far post) is under no sample of the 3x3 in some frames, and those frames the clip cuts it from the history: a still
// texel counts its cuts beside its age in the history's alpha, and once they recur the clip lets most of the history
// through, so the post holds at its coverage rather than vanishing and growing back. One cut, a real change, is not
// enough; a texel that moves counts none.
#domain global
#include "crystalgraphics:shaders/env/material/post.glsl"
#include "crystalgraphics:shaders/lib/velocity.glsl"
#include "crystalgraphics:shaders/post/aa/taa.glsl"
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
    _Responsive ("Where a blended draw covered: 1 Responsive AA, 0.5 one that keeps its history", sampler2D) = "black"
    _Resolve  ("x this frame's least share, y its share at speed, z 1 with a history to blend, w its share where the fetch is ambiguous", vec4) = (0.04, 0.2, 1, 0.25)
    _Jitter   ("xy this frame's jitter in normalized device coordinates; z 1 where every engine solid wrote velocity", vec4) = (0, 0, 0, 0)
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

// A colour's weight in the blend and the filter, falling with brightness, gently: past 1 the light falls off less than a
// tonemap would, as a bright detail thinner than a texel must keep the light bloom spreads (an 8 weighs a third of a 0)
float taa_weight(vec3 ycocg) {
    return 4.0 / (4.0 + max(ycocg.x, 0.0));
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
    // Under a blended draw, which writes no velocity: a quarter of the new frame, as Unreal's Responsive AA
    bool responsive = cg_MarkResponsive(texelFetch(_Responsive, at, 0).r);
    // What the engine did not draw (a host's world): as it came, and a history starting over
    if (_Jitter.z > 0.5 && !cg_EngineDrew(_Velocity, _Responsive, at)) {
        fragColor = vec4(max(texelFetch(cg_SceneColor, at, 0).rgb, vec3(0.0)), 0.0);
        return;
    }
    ivec2 last = textureSize(cg_SceneColor, 0) - 1;
    vec2 size = vec2(last + 1);
    vec3 current = taa_ycocg(texelFetch(cg_SceneColor, at, 0).rgb);
    // The neighbourhood's mean and spread
    vec3 mean = vec3(0.0), square = vec3(0.0), lo = current, hi = current, filtered = vec3(0.0);
    float filterWeight = 0.0;
    // Where the jitter took this frame's samples, in texels from their centres
    vec2 jitter = _Jitter.xy * 0.5 * size;
    for (int k = 0; k < 9; k++) {   // LOOP-BOUND: the 3x3 round the texel
        ivec2 q = clamp(at + ivec2(k % 3 - 1, k / 3 - 1), ivec2(0), last);
        vec3 c = taa_ycocg(texelFetch(cg_SceneColor, q, 0).rgb);
        mean += c;
        square += c * c;
        // A Gaussian close to Blackman-Harris over the sample's distance from this texel's centre, and weighed as its
        // tonemapped self: an HDR sample a texel away would otherwise light this one as the jitter swings its weight
        vec2 toSample = vec2(k % 3 - 1, k / 3 - 1) - jitter;
        float w = taa_catmull_rom(toSample.x) * taa_catmull_rom(toSample.y) * taa_weight(c);
        filtered += c * w;
        filterWeight += w;
        lo = min(lo, c);
        hi = max(hi, c);
    }
    current = filtered / filterWeight;
    vec3 result = current;
    float age = 1.0;
    if (_Resolve.z > 0.5) {
        vec2 motion, farMotion;
        taa_motion(_Velocity, at, last, _Jitter.xy, motion, farMotion);
        // Texels the near and far motions part by a frame: kept over n frames, the history smears by n times that
        float parted = length((motion - farMotion) * size);
        vec2 from = (vec2(at) + 0.5) / size - motion;
        if (all(greaterThanEqual(from, vec2(0.0))) && all(lessThanEqual(from, vec2(1.0)))) {
            vec3 history = taa_ycocg(taa_history(from, size));
            float packed = texelFetch(_History, clamp(ivec2(from * size), ivec2(0), last), 0).a;
            float cuts = floor(packed / TAA_AGES);
            float speed = length(motion * size);
            float still = taa_still(speed, length(farMotion * size));
            // Frames blended into the history there: a still texel's run to TAA_AGES - 1, a moving one's to the least
            // share's; no more than smear it a texel, nor fewer than the ambiguous share
            float kept = min(packed - cuts * TAA_AGES, mix(1.0 / _Resolve.x, TAA_AGES - 1.0, still));
            kept = min(kept, max(1.0 / _Resolve.w - 1.0, 1.0 / max(parted, 1.0e-4)));
            // Clip toward the mean, inside the spread a standard deviation and a quarter out, and the box itself
            mean /= 9.0;
            vec3 sigma = sqrt(max(square / 9.0 - mean * mean, vec3(0.0)));
            vec3 boxLo = max(lo, mean - 1.25 * sigma), boxHi = min(hi, mean + 1.25 * sigma);
            vec3 centre = 0.5 * (boxHi + boxLo), extent = max(0.5 * (boxHi - boxLo), vec3(1.0e-4));
            vec3 offset = history - centre;
            vec3 units = abs(offset / extent);
            float out_ = max(units.x, max(units.y, units.z));
            vec3 clipped = out_ > 1.0 ? centre + offset / out_ : history;
            // A cut takes a fifth of the luma. Recurring cuts let most of the history past the clip; a real change on
            // such a texel still goes in a few frames
            bool cut = abs(history.x - clipped.x) > 0.2 * max(max(history.x, clipped.x), 0.02);
            float relax = responsive ? 0.0 : 0.85 * still * smoothstep(14.0, 24.0, cuts) * step(8.0, kept);
            history = mix(clipped, history, relax);
            cuts = still > 0.5 ? clamp(cuts - 1.0 + (cut ? 12.0 : 0.0), 0.0, 31.0) : 0.0;
            float share = taa_share(_Resolve, speed, still, kept, responsive);
            age = min(responsive ? min(kept + 1.0, 3.0) : kept + 1.0, TAA_AGES - 1.0) + cuts * TAA_AGES;
            float wc = share * taa_weight(current), wh = (1.0 - share) * taa_weight(history);
            result = (current * wc + history * wh) / (wc + wh);
        }
    }
    fragColor = vec4(max(taa_rgb(result), vec3(0.0)), age);
}
