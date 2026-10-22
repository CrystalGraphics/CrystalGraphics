// The world's shadowed lighting filtered across the screen where the temporal pass left it noisy, as Unreal's MegaLights
// does (MegaLightsDenoiserSpatial.usf's DenoiserSpatialCS): a few samples on a disk round each pixel, weighed by how
// near their plane is, how alike their normal and how near their luminance within the pixel's spread. Its spread is
// the temporal moments', or for the first frames after a disocclusion a 5x5 Gaussian of them, doubled, with twice the
// samples. A pixel filters only where its relative spread or its history's spatial spread says it is noisy. The sun's
// visibility (RT1) is filtered by depth and normal alone, in its penumbras and after a disocclusion.
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
    _Lighting ("The accumulated lighting and frames",              sampler2D) = "black"
    _Moments  ("The accumulated moments, eye depth and confidence", sampler2D) = "black"
    _Vis      ("The accumulated sun visibility",                   sampler2D) = "white"
    _Spatial ("KernelRadius, NumSamples, DepthWeightScale, MaxDisocclusionFrames", vec4) = (8, 4, 10000, 2)
    _Spread  ("DisocclusionDiffuseSpatialVarianceScale, HistorySpatialStdDevThreshold, MaxFramesAccumulated", vec4) = (2, 0.15, 12, 0)
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

    struct Filtered {
        vec4 lighting : RT0;
        vec4 visibility : RT1;
    };

    vec3 to_view_dir(vec3 w) { return vec3(dot(_ToView0.xyz, w), dot(_ToView1.xyz, w), dot(_ToView2.xyz, w)); }
    vec3 to_world_dir(vec3 v) {
        return vec3(dot(vec3(_ToView0.x, _ToView1.x, _ToView2.x), v), dot(vec3(_ToView0.y, _ToView1.y, _ToView2.y), v),
                dot(vec3(_ToView0.z, _ToView1.z, _ToView2.z), v));
    }

    float eye_depth(ivec2 px) {
        px = clamp(px, ivec2(0), ivec2(CG_RESOLUTION) - 1);
        float window = texelFetch(cg_DepthBuffer, px, 0).r;
        if (cg_DepthParams.x > 0.5 ? window <= 0.0 : window >= 1.0) return 0.0;
        float ndc = cg_DepthParams.y > 0.5 ? window : window * 2.0 - 1.0;
        return _Depth.z == 0.0 ? (_Depth.y - ndc) / _Depth.x
                : (_Depth.y - _Depth.x / _Depth.z * _Depth.w) / (ndc - _Depth.x / _Depth.z);
    }

    vec3 surface(ivec2 px) {
        float depth = eye_depth(px);
        vec2 uv = (vec2(clamp(px, ivec2(0), ivec2(CG_RESOLUTION) - 1)) + 0.5) / CG_RESOLUTION;
        return vec3(mix(_Rays.xy, _Rays.zw, uv) * depth, -depth);
    }

    vec3 normal_at(ivec2 px, vec3 p) {
        vec3 left = surface(px - ivec2(1, 0)), right = surface(px + ivec2(1, 0));
        vec3 down = surface(px - ivec2(0, 1)), up = surface(px + ivec2(0, 1));
        vec3 across = abs(left.z - p.z) < abs(right.z - p.z) ? p - left : right - p;
        vec3 rising = abs(down.z - p.z) < abs(up.z - p.z) ? p - down : up - p;
        vec3 n = cross(across, rising);
        n = dot(n, n) > 1.0e-20 ? n * inversesqrt(dot(n, n)) : normalize(-p);
        if (dot(n, p) > 0.0) n = -n;
        vec3 world = to_world_dir(n), size = abs(world);
        float most = max(size.x, max(size.y, size.z));
        if (most > 0.985) n = to_view_dir(sign(world) * step(vec3(most), size));
        return n;
    }

    void vertex(out v2f o) {
        vec2 p = vec2(float((CG_VERTEX_ID & 1) << 2) - 1.0, float((CG_VERTEX_ID & 2) << 1) - 1.0);
        o.uv = p * 0.5 + 0.5;
        gl_Position = vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out Filtered o) {
        ivec2 px = ivec2(gl_FragCoord.xy), size = textureSize(_Lighting, 0);
        vec4 centre = texelFetch(_Lighting, px, 0);
        float vis = texelFetch(_Vis, px, 0).x;
        o.lighting = vec4(0.0);
        o.visibility = vec4(1.0);
        float frames = centre.a;
        if (frames <= 0.0) return;
        o.lighting = vec4(centre.rgb, 1.0);
        o.visibility = vec4(vis, 0.0, 0.0, 1.0);

        // Boost the filter until enough frames are in for the temporal variance to be trusted.
        float most = min(_Spatial.w, _Spread.z - 1.0);
        float disocclusion = most > 0.0 ? 1.0 - clamp((frames - 1.0) / most, 0.0, 1.0) : 0.0;
        vec4 moments;
        if (disocclusion > 0.01) {
            moments = vec4(0.0);
            float weightSum = 0.0;
            for (int y = -2; y <= 2; y++) {
                for (int x = -2; x <= 2; x++) {
                    float w = exp2(-2.0 * float(x * x + y * y) / 9.0);
                    moments += texelFetch(_Moments, clamp(px + ivec2(x, y), ivec2(0), size - 1), 0) * w;
                    weightSum += w;
                }
            }
            moments /= weightSum;
        } else {
            moments = texelFetch(_Moments, px, 0);
        }
        float stdDev = sqrt(max(moments.y - moments.x * moments.x, 0.0));
        bool filtering;
        if (disocclusion > 0.01) {
            stdDev *= _Spread.x;
            filtering = stdDev / max(moments.x, 0.1) > 0.2;
        } else {
            // GetHistoryConfidenceSpatialStdDev: how unevenly the history was trusted round this pixel.
            float sum = 0.0, squares = 0.0, n = 0.0;
            for (int y = -2; y <= 2; y++) {
                for (int x = -2; x <= 2; x++) {
                    if (x == 0 && y == 0 || abs(x) == 2 && abs(y) == 2) continue;
                    float c = texelFetch(_Moments, clamp(px + ivec2(x, y), ivec2(0), size - 1), 0).w;
                    sum += c;
                    squares += c * c;
                    n += 1.0;
                }
            }
            float m1 = sum / n;
            bool noisy = sqrt(max(squares / n - m1 * m1, 0.0)) > _Spread.y;
            filtering = (_Spread.z >= 8.0 || noisy) && _Spread.z > 1.0 && (stdDev / max(moments.x, 0.1) > 0.5 || noisy);
        }
        bool filterVis = disocclusion > 0.01 || vis > 0.02 && vis < 0.98;
        if (_Spatial.x <= 0.0 || !filtering && !filterVis) return;

        vec3 p = surface(px), n = normal_at(px, p);
        float centreLum = luminance(centre.rgb);
        vec3 sum = centre.rgb;
        float weightSum = 1.0, visSum = vis, visWeight = 1.0;
        uvec2 seed = cg_rand_pcg16(ivec3(px % 2, CG_FRAME_INDEX)).xy;
        uint count = uint(mix(_Spatial.y, 2.0 * _Spatial.y, disocclusion) + 0.5);
        for (uint k = 0u; k < count; k++) {
            vec2 offset = cg_disk_concentric(cg_hammersley16(k, count, seed)) * _Spatial.x;
            ivec2 q = ivec2(vec2(px) + offset + 0.5);
            if (any(lessThan(q, ivec2(0))) || any(greaterThanEqual(q, size))) continue;
            vec4 neighbour = texelFetch(_Lighting, q, 0);
            if (neighbour.a <= 0.0) continue;
            vec3 s = surface(q);
            if (s.z >= 0.0) continue;
            float relative = abs(dot(s - p, n)) / -p.z;
            float depthWeight = exp2(-_Spatial.z * relative * relative);
            float normalWeight = 1.0 - clamp(acos(clamp(dot(n, normal_at(q, s)), 0.0, 1.0)), 0.0, 1.0);
            float geometry = depthWeight * normalWeight;
            if (filtering) {
                float w = geometry * exp2(-abs(centreLum - luminance(neighbour.rgb)) / max(stdDev, 0.001));
                sum += neighbour.rgb * w;
                weightSum += w;
            }
            if (filterVis) {
                visSum += texelFetch(_Vis, q, 0).x * geometry;
                visWeight += geometry;
            }
        }
        if (filtering) o.lighting = vec4(sum / weightSum, 1.0);
        if (filterVis) o.visibility = vec4(visSum / visWeight, 0.0, 0.0, 1.0);
    }
}
