// The world's shadowed lighting accumulated over frames, as Unreal's MegaLights does (MegaLightsDenoiserTemporal.usf's
// DenoiserTemporalCS): last frame's, reprojected to where this pixel's surface was then and kept only where the depth
// there agrees (r.MegaLights.Temporal.HistoryDistanceThreshold), is clamped in YCoCg to this frame's neighbourhood
// (5x5 less its corners, mean +- one standard deviation, the centre always inside), softly by how far outside it was,
// and blended with this frame's at 1 / frames, frames growing to MaxFramesAccumulated and cut toward
// MinFramesAccumulatedForHistoryMiss where the history missed. The luminance's moments are blended alike, for the
// spatial pass's variance.
//
// Written into a history of three slots: RT0 the lighting and the frames accumulated (0 where nothing is lit); RT1 the
// luminance and its square, the eye depth, and the history's confidence; RT2 the sun's and the block light's
// visibility, each one's history clamped to this frame's 3x3 range and blended at the same rate.
#type none
#include "crystalgraphics:shaders/lib/math.glsl"
#include "crystalgraphics:shaders/lib/color.glsl"
#include "crystalgraphics:shaders/lib/light_shadow.glsl"

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Linear" }
Queue = "Overlay"

Properties {
    _Rays    ("Unit-depth view rays at the target's edges: left, bottom, right, top", vec4) = (-1, -1, 1, 1)
    _ToView0 ("Eye-relative world to the bobbed view, the first row, its shift in w", vec4) = (1, 0, 0, 0)
    _ToView1 ("The second row",                                    vec4) = (0, 1, 0, 0)
    _ToView2 ("The third row",                                     vec4) = (0, 0, 1, 0)
    _Depth   ("The perspective's m22, m32, m23 and m33: window depth to eye depth", vec4) = (1, 0, -1, 0)
    _Eye     ("The eye from the view's position: eye-relative to camera-relative", vec4) = (0, 0, 0, 0)
    _Now     ("This frame's shaded lighting, alpha 1 where valid",   sampler2D) = "black"
    _Last    ("Last frame's accumulated lighting and frames",       sampler2D) = "black"
    _LastMoments ("Last frame's moments, eye depth and confidence", sampler2D) = "black"
    _NowVis  ("This frame's sun and block light visibility, alpha 1 where valid", sampler2D) = "white"
    _LastVis ("Last frame's accumulated visibility",                sampler2D) = "white"
    _Fresh   ("1 where the history holds no frame of this view",     float) = 1.0
    _Frames  ("MinFramesAccumulatedForHistoryMiss, MaxFramesAccumulated, NeighborhoodClampScale, HistoryDistanceThreshold", vec4) = (4, 12, 1, 0.03)
}

struct v2f { vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend OFF
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    struct Accumulated {
        vec4 lighting : RT0;
        vec4 moments : RT1;
        vec4 visibility : RT2;
    };

    vec3 to_world_dir(vec3 v) {
        return vec3(dot(vec3(_ToView0.x, _ToView1.x, _ToView2.x), v), dot(vec3(_ToView0.y, _ToView1.y, _ToView2.y), v),
                dot(vec3(_ToView0.z, _ToView1.z, _ToView2.z), v));
    }

    float eye_depth(ivec2 px) {
        float window = texelFetch(cg_DepthBuffer, px, 0).r;
        if (cg_DepthParams.x > 0.5 ? window <= 0.0 : window >= 1.0) return 0.0;
        float ndc = cg_DepthParams.y > 0.5 ? window : window * 2.0 - 1.0;
        return _Depth.z == 0.0 ? (_Depth.y - ndc) / _Depth.x
                : (_Depth.y - _Depth.x / _Depth.z * _Depth.w) / (ndc - _Depth.x / _Depth.z);
    }

    void vertex(out v2f o) {
        vec2 p = vec2(float((CG_VERTEX_ID & 1) << 2) - 1.0, float((CG_VERTEX_ID & 2) << 1) - 1.0);
        o.uv = p * 0.5 + 0.5;
        gl_Position = vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out Accumulated o) {
        ivec2 px = ivec2(gl_FragCoord.xy), size = textureSize(_Now, 0);
        o.lighting = vec4(0.0);
        o.moments = vec4(0.0, 0.0, 0.0, 1.0);
        o.visibility = vec4(1.0);
        float depth = eye_depth(px);
        if (depth <= 0.0) return;
        vec4 nowVis = texelFetch(_NowVis, px, 0);
        o.moments.z = depth;
        vec4 centre = texelFetch(_Now, px, 0);
        bool resolved = centre.a > 0.5;
        vec3 centreYCoCg = cg_rgb_to_ycocg(centre.rgb);

        // The history: this surface's point, camera-relative, in last frame's clip space; the four texels round it,
        // each kept where its depth then lies within the threshold of the point's (GetHistorySampleValidity).
        vec4 history = vec4(0.0), historyMoments = vec4(0.0);
        float historyWeight = 0.0;
        vec2 historyVis = vec2(0.0);
        if (_Fresh < 0.5 && CG_HISTORY_VALID) {
            vec2 uv = (vec2(px) + 0.5) / vec2(size);
            vec3 p = vec3(mix(_Rays.xy, _Rays.zw, uv) * depth, -depth);
            vec3 w = to_world_dir(p - vec3(_ToView0.w, _ToView1.w, _ToView2.w)) + _Eye.xyz;
            vec4 then = cg_PreviousClip(w);
            if (then.w > 0.0) {
                vec2 at = (then.xy / then.w * 0.5 + 0.5) * vec2(size) - 0.5;
                ivec2 t00 = ivec2(floor(at));
                vec2 f = at - vec2(t00);
                for (int k = 0; k < 4; k++) {
                    ivec2 t = t00 + ivec2(k & 1, k >> 1);
                    if (any(lessThan(t, ivec2(0))) || any(greaterThanEqual(t, size))) continue;
                    vec4 m = texelFetch(_LastMoments, t, 0);
                    if (m.z <= 0.0 || abs(m.z - then.w) > _Frames.w * then.w) continue;
                    float b = ((k & 1) != 0 ? f.x : 1.0 - f.x) * ((k >> 1) != 0 ? f.y : 1.0 - f.y);
                    history += texelFetch(_Last, t, 0) * b;
                    historyMoments += m * b;
                    historyVis += texelFetch(_LastVis, t, 0).xy * b;
                    historyWeight += b;
                }
            }
        }

        if (historyWeight <= 1.0e-4) {
            if (!resolved) return;
            float lum = luminance(centre.rgb);
            o.lighting = vec4(centre.rgb, 1.0);
            o.moments = vec4(lum, min(lum * lum, 65504.0), depth, 1.0);
            o.visibility = vec4(nowVis.xy, 0.0, 1.0);
            return;
        }
        history /= historyWeight;
        historyMoments /= historyWeight;
        historyVis /= historyWeight;
        if (!resolved) {
            // No sample this frame: the history as it was.
            o.lighting = history;
            o.moments = vec4(historyMoments.xy, depth, historyMoments.w);
            o.visibility = vec4(historyVis, 0.0, 1.0);
            return;
        }

        // GetNeighborhood: mean and spread of the valid neighbours, the centre always counted and always inside.
        vec3 sum = centreYCoCg, deltaSq = vec3(0.0);
        float weightSum = 1.0;
        for (int y = -2; y <= 2; y++) {
            for (int x = -2; x <= 2; x++) {
                if (x == 0 && y == 0 || abs(x) == 2 && abs(y) == 2) continue;
                ivec2 q = px + ivec2(x, y);
                if (any(lessThan(q, ivec2(0))) || any(greaterThanEqual(q, size))) continue;
                vec4 n = texelFetch(_Now, q, 0);
                if (n.a < 0.5) continue;
                vec3 c = cg_rgb_to_ycocg(n.rgb), delta = c - centreYCoCg;
                sum += c;
                deltaSq += delta * delta;
                weightSum += 1.0;
            }
        }
        vec3 mean = sum / weightSum, deltaMean = mean - centreYCoCg;
        vec3 stdDev = sqrt(max(deltaSq / weightSum - deltaMean * deltaMean, vec3(0.0)));
        vec3 low = min(mean - _Frames.z * stdDev, centreYCoCg), high = max(mean + _Frames.z * stdDev, centreYCoCg);
        vec3 extent = (high - low) * 0.5;

        // The soft clamp: confidence by how far outside the neighbourhood the history was.
        vec3 historyYCoCg = cg_rgb_to_ycocg(history.rgb);
        vec3 clamped = clamp(historyYCoCg, low, high);
        float confidence = clamp(1.0 - length(abs(clamped - historyYCoCg) / max(extent, vec3(0.1))), 0.0, 1.0);
        vec3 kept = cg_ycocg_to_rgb(mix(clamped, historyYCoCg, confidence));

        float frames = min(history.a + 1.0, mix(_Frames.x, _Frames.y, confidence));
        float alpha = 1.0 / frames, lum = luminance(centre.rgb);
        vec3 lighting = mix(kept, centre.rgb, alpha);
        vec2 moments = mix(min(historyMoments.xy, vec2(65504.0)), vec2(lum, min(lum * lum, 65504.0)), alpha);
        // MakeFinite: nothing past a half float's range goes into the history.
        lighting = clamp(lighting, vec3(0.0), vec3(65504.0));
        o.lighting = vec4(lighting, frames);
        o.moments = vec4(moments, depth, confidence);
        // The visibilities: each history held to this frame's 3x3 range, then blended at the lighting's rate.
        vec2 visLow = nowVis.xy, visHigh = nowVis.xy;
        for (int k = 0; k < 9; k++) {
            ivec2 q = clamp(px + ivec2(k % 3 - 1, k / 3 - 1), ivec2(0), size - 1);
            vec4 v = texelFetch(_NowVis, q, 0);
            if (v.w < 0.5) continue;
            visLow = min(visLow, v.xy);
            visHigh = max(visHigh, v.xy);
        }
        o.visibility = vec4(mix(clamp(historyVis, visLow, visHigh), nowVis.xy, alpha), 0.0, 1.0);
    }
}
