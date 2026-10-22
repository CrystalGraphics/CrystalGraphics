// The world's shadowed lights shaded at every pixel from their samples, as Unreal's MegaLights shades them
// (MegaLightsShading.usf's ShadeLightSamplesCS): each pixel takes one of the four half-resolution pixels round it,
// chosen at random by its bilinear weight, how near its plane is and how alike its normal (Unreal's stochastic bilinear
// upsample), then each light among that pixel's visible samples lights it exactly, times the sum of those samples'
// weights (1 / pdf, at most MaxShadingWeight) over the samples taken. What it writes is demodulated, as Unreal's
// resolved lighting is: what the scene is multiplied by, the denoisers' input; alpha 1 where it holds lighting, 0 where
// no half-resolution pixel near stands for this one.
#type none
#include "crystalgraphics:shaders/lib/math.glsl"
#include "crystalgraphics:shaders/lib/color.glsl"
#include "crystalgraphics:shaders/lib/lights.glsl"
#include "crystalgraphics:shaders/lib/light_shadow.glsl"

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Linear" }
Queue = "Overlay"

Properties {
    _Rays    ("Unit-depth view rays at the target's edges: left, bottom, right, top", vec4) = (-1, -1, 1, 1)
    _ToView0 ("Eye-relative world to the bobbed view, the first row, its shift in w", vec4) = (1, 0, 0, 0)
    _ToView1 ("The second row",                                    vec4) = (0, 1, 0, 0)
    _ToView2 ("The third row",                                     vec4) = (0, 0, 1, 0)
    _Depth   ("The perspective's m22, m32, m23 and m33: window depth to eye depth", vec4) = (1, 0, -1, 0)
    _DayFade ("A light's strength under a full day's open sky; 1 for never fading", float) = 0.1
    _Screen  ("The full target's width and height",                vec4) = (1, 1, 0, 0)
    _Samples ("The shadowed lights' samples, four a half-resolution pixel", sampler2D) = "black"
    _MaxWeight ("Unreal's r.MegaLights.MaxShadingWeight",          float) = 20.0
}

struct CgLight { vec4 positionRadius; vec4 colorExponent; vec4 spotCos; vec4 segmentCone; vec4 ambient; };

Buffers {
    LIGHTS ("The firing's lights", CgLight, readonly)
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

    vec3 to_view_dir(vec3 w) { return vec3(dot(_ToView0.xyz, w), dot(_ToView1.xyz, w), dot(_ToView2.xyz, w)); }
    vec3 to_world_dir(vec3 v) {
        return vec3(dot(vec3(_ToView0.x, _ToView1.x, _ToView2.x), v), dot(vec3(_ToView0.y, _ToView1.y, _ToView2.y), v),
                dot(vec3(_ToView0.z, _ToView1.z, _ToView2.z), v));
    }

    float eye_depth(ivec2 px) {
        px = clamp(px, ivec2(0), ivec2(_Screen.xy) - 1);
        float window = texelFetch(cg_DepthBuffer, px, 0).r;
        if (cg_DepthParams.x > 0.5 ? window <= 0.0 : window >= 1.0) return 0.0;
        float ndc = cg_DepthParams.y > 0.5 ? window : window * 2.0 - 1.0;
        return _Depth.z == 0.0 ? (_Depth.y - ndc) / _Depth.x
                : (_Depth.y - _Depth.x / _Depth.z * _Depth.w) / (ndc - _Depth.x / _Depth.z);
    }

    vec3 surface(ivec2 px) {
        float depth = eye_depth(px);
        vec2 uv = (vec2(clamp(px, ivec2(0), ivec2(_Screen.xy) - 1)) + 0.5) / _Screen.xy;
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

    ivec2 jitter(ivec2 d) {
        int k = (CG_FRAME_INDEX + d.x * 3 + d.y * 5) & 3;
        return ivec2(k & 1, k >> 1);
    }

    // world_lights.shader's lighting of p facing n by one light, times the scene.
    vec3 lighting(CgLight light, vec3 p, vec3 n) {
        vec3 v = to_view_dir(light.positionRadius.xyz) + vec3(_ToView0.w, _ToView1.w, _ToView2.w);
        vec4 spot = vec4(to_view_dir(light.spotCos.xyz), light.spotCos.w);
        vec4 segment = vec4(to_view_dir(light.segmentCone.xyz), light.segmentCone.w);
        float lit = cg_light_surface(v, light.positionRadius.w, light.colorExponent.w, spot, segment, light.ambient.z, p, n);
        if (lit <= 0.0) return vec3(0.0);
        vec3 ambient = vec3(1.0);
        float adapt = 1.0;
        if (light.ambient.x >= 0.0) {
            vec3 lightmap = CG_LIGHTMAP(light.ambient.xy);
            adapt = mix(1.0, _DayFade, clamp(CG_DAYLIGHT, 0.0, 1.0) * light.ambient.y / 15.0);
            ambient = max(CG_LINEAR_SCENE ? srgb_to_linear(lightmap) : lightmap, vec3(CG_LINEAR_SCENE ? 0.02 : 0.15));
        }
        return light.colorExponent.rgb * (lit * adapt / CG_PI) / ambient;
    }

    void vertex(out v2f o) {
        vec2 p = vec2(float((CG_VERTEX_ID & 1) << 2) - 1.0, float((CG_VERTEX_ID & 2) << 1) - 1.0);
        o.uv = p * 0.5 + 0.5;
        gl_Position = vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        ivec2 px = ivec2(gl_FragCoord.xy);
        fragColor = vec4(0.0);
        vec3 p = surface(px);
        if (p.z >= 0.0) return;
        vec3 n = normal_at(px, p);

        // The stochastic bilinear upsample: the four half-resolution pixels round this one, each weighed by its
        // bilinear weight, its plane's distance (MegaLights' spatial depth weight) and its normal (GetNormalWeight).
        ivec2 halfSize = ivec2(textureSize(_Samples, 0)) >> 1;
        vec2 at = (vec2(px) + 0.5) * 0.5 - 0.5;
        ivec2 d00 = ivec2(floor(at));
        vec2 f = at - vec2(d00);
        vec4 weights = vec4((1.0 - f.x) * (1.0 - f.y), f.x * (1.0 - f.y), (1.0 - f.x) * f.y, f.x * f.y);
        for (int k = 0; k < 4; k++) {
            ivec2 d = clamp(d00 + ivec2(k & 1, k >> 1), ivec2(0), halfSize - 1);
            ivec2 rep = d * 2 + jitter(d);
            vec3 q = surface(rep);
            if (q.z >= 0.0) {
                weights[k] = 0.0;
                continue;
            }
            float relative = abs(dot(q - p, n)) / -p.z;
            float normalWeight = 1.0 - clamp(acos(clamp(dot(normal_at(rep, q), n), -1.0, 1.0)), 0.0, 1.0);
            weights[k] *= exp2(-10000.0 * relative * relative) * normalWeight * normalWeight;
        }
        float total = dot(weights, vec4(1.0));
        if (total <= 1.0e-4) return;
        float r = cg_shadow_noise(vec2(px), CG_FRAME_INDEX) * total;
        int chosen = 3;
        for (int k = 0; k < 3; k++) {
            if (r < weights[k]) {
                chosen = k;
                break;
            }
            r -= weights[k];
        }
        ivec2 d = clamp(d00 + ivec2(chosen & 1, chosen >> 1), ivec2(0), halfSize - 1);

        // AccumulateLightSample: each light once, its visible samples' weights summed, merged samples counted in.
        vec2 samples[4];
        for (int s = 0; s < 4; s++) samples[s] = texelFetch(_Samples, d * 2 + ivec2(s & 1, s >> 1), 0).xy;
        vec3 sum = vec3(0.0);
        for (int s = 0; s < 4; s++) {
            float index = samples[s].x;
            if (index < 0.0) continue;
            bool first = true;
            for (int e = 0; e < s; e++) first = first && samples[e].x != index;
            if (!first) continue;
            float weight = 0.0, merged = 1.0;
            for (int e = s; e < 4; e++) {
                if (samples[e].x != index) continue;
                if (samples[e].y == 0.0) {
                    merged += 1.0;
                } else if (samples[e].y > 0.0) {
                    weight += min(samples[e].y, _MaxWeight) * merged;
                    merged = 1.0;
                }
            }
            if (weight > 0.0) sum += lighting(LIGHTS(int(index)), p, n) * (weight * 0.25);
        }
        fragColor = vec4(sum, 1.0);
    }
}
