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
// visibility, each one's history soft-clamped to the same neighbourhood's mean and spread and blended at the same rate,
// then their squares, the moments the spatial filter reads their spread from as it reads the lighting's.
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
    _LastVis ("Last frame's accumulated visibility, then its squares", sampler2D) = "white"
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
        vec4 historyVis = vec4(0.0);
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
                    historyVis += texelFetch(_LastVis, t, 0) * b;
                    historyWeight += b;
                }
            }
        }

        if (historyWeight <= 1.0e-4) {
            if (!resolved) return;
            float lum = luminance(centre.rgb);
            o.lighting = vec4(centre.rgb, 1.0);
            o.moments = vec4(lum, min(lum * lum, 65504.0), depth, 1.0);
            o.visibility = vec4(nowVis.xy, nowVis.xy * nowVis.xy);
            return;
        }
        history /= historyWeight;
        historyMoments /= historyWeight;
        historyVis /= historyWeight;
        if (!resolved) {
            // No sample this frame: the history as it was.
            o.lighting = history;
            o.moments = vec4(historyMoments.xy, depth, historyMoments.w);
            o.visibility = historyVis;
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
        // The visibilities, as the lighting: the history soft-clamped to the neighbourhood's mean and spread, then
        // blended at the lighting's rate. A clamp to the bare range would hold a penumbra to its four rays' noise.
        vec2 visSum = nowVis.xy, visDeltaSq = vec2(0.0);
        float visCount = 1.0;
        for (int y = -2; y <= 2; y++) {
            for (int x = -2; x <= 2; x++) {
                if (x == 0 && y == 0 || abs(x) == 2 && abs(y) == 2) continue;
                ivec2 q = px + ivec2(x, y);
                if (any(lessThan(q, ivec2(0))) || any(greaterThanEqual(q, size))) continue;
                vec4 v = texelFetch(_NowVis, q, 0);
                if (v.w < 0.5) continue;
                vec2 delta = v.xy - nowVis.xy;
                visSum += v.xy;
                visDeltaSq += delta * delta;
                visCount += 1.0;
            }
        }
        vec2 visMean = visSum / visCount, visDeltaMean = visMean - nowVis.xy;
        vec2 visStdDev = sqrt(max(visDeltaSq / visCount - visDeltaMean * visDeltaMean, vec2(0.0)));
        vec2 visLow = min(visMean - _Frames.z * visStdDev, nowVis.xy), visHigh = max(visMean + _Frames.z * visStdDev, nowVis.xy);
        vec2 visClamped = clamp(historyVis.xy, visLow, visHigh);
        float visConfidence = clamp(1.0 - length(abs(visClamped - historyVis.xy) / max((visHigh - visLow) * 0.5, vec2(0.1))), 0.0, 1.0);
        vec2 visKept = mix(visClamped, historyVis.xy, visConfidence);
        o.visibility = vec4(mix(visKept, nowVis.xy, alpha), mix(historyVis.zw, nowVis.xy * nowVis.xy, alpha));
    }
}
