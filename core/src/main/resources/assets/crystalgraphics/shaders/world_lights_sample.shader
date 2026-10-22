// The world's shadowed lights' samples, as Unreal's MegaLights picks and traces them (MegaLightsSampling.usf's
// GenerateLightSamplesCS, MegaLightsRayTracing.usf). The target is twice the size of a half-resolution grid: each
// half-resolution pixel's four samples are its 2x2 block, as Unreal's NumSamplesPerPixel 2x2 lays them. Every fragment
// runs the pixel's weighted reservoir over its light-grid cell's shadowed lights, stratified four ways, keeps its own
// pick, and traces a ray to a point on that light: across the screen's depth first, then through the voxel window's
// blocks from where that stopped, and against the boxes of the entities round the camera.
//
// Written: x the light's index, -1 for none; y its shading weight (WeightSum / weight, 1 / pdf), 0 where a sample of
// the same light after it took its ray (Unreal's MergeRays), negative where the ray was blocked.
#type none
#include "crystalgraphics:shaders/lib/math.glsl"
#include "crystalgraphics:shaders/lib/color.glsl"
#include "crystalgraphics:shaders/lib/lights.glsl"
#include "crystalgraphics:shaders/lib/light_shadow.glsl"

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Linear" }
Queue = "Overlay"

Properties {
    _Grid    ("Cells across, up and deep",                         vec4) = (1, 1, 32, 0)
    _Tile    ("A tile's share of the target across and up",        vec4) = (1, 1, 0, 0)
    _ZParams ("The slices' B, O and S",                            vec4) = (1, 1, 1, 0)
    _Rays    ("Unit-depth view rays at the target's edges: left, bottom, right, top", vec4) = (-1, -1, 1, 1)
    _ToView0 ("Eye-relative world to the bobbed view, the first row, its shift in w", vec4) = (1, 0, 0, 0)
    _ToView1 ("The second row",                                    vec4) = (0, 1, 0, 0)
    _ToView2 ("The third row",                                     vec4) = (0, 0, 1, 0)
    _Depth   ("The perspective's m22, m32, m23 and m33: window depth to eye depth", vec4) = (1, 0, -1, 0)
    _DayFade ("A light's strength under a full day's open sky; 1 for never fading", float) = 0.1
    _Screen  ("The full target's width and height",                vec4) = (1, 1, 0, 0)
    _Trace   ("Screen trace: blocks, steps, normal bias in blocks, relative depth thickness", vec4) = (8, 24, 0.02, 0.005)
    _EyeX    ("The eye's block: x",                                int) = 0
    _EyeY    ("y",                                                 int) = 0
    _EyeZ    ("z",                                                 int) = 0
    _EyeFrac ("The eye within its block",                          vec4) = (0, 0, 0, 0)
    _World         ("The voxel window's blocks",                   sampler3D) = "black"
    _WorldSections ("Which of its sections are filled",            sampler3D) = "black"
    _WorldDistance ("Its distance to solid over FX_WORLD_FAR",      sampler3D) = "white"
    _WorldBaseX    ("Its lowest block, absolute: x",               int) = 0
    _WorldBaseY    ("y",                                           int) = 0
    _WorldBaseZ    ("z",                                           int) = 0
    _WorldWrapX    ("That block's texel: x",                       int) = 0
    _WorldWrapY    ("y",                                           int) = 0
    _WorldWrapZ    ("z",                                           int) = 0
    _WorldLive     ("1 with a level, else 0: read nothing",        int) = 0
    _Entities      ("Entity boxes in ENTITIES",                    int) = 0
}

struct CgLight { vec4 positionRadius; vec4 colorExponent; vec4 spotCos; vec4 segmentCone; vec4 ambient; };

Buffers {
    LIGHTS   ("The firing's lights",                               CgLight, readonly)
    CELLS    ("Each cell's light count and start",                 uint,    readonly)
    INDICES  ("The cells' light indices",                          uint,    readonly)
    ENTITIES ("Entity boxes, two vec4s each: min and max from the eye's block", vec4, readonly)
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
    vec3 to_world(vec3 v) { return to_world_dir(v - vec3(_ToView0.w, _ToView1.w, _ToView2.w)); }

    // The scene's eye depth at a pixel of the full target, under the bob-free perspective; 0 for none (the sky).
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

    // world_lights.shader's normal: from the nearer neighbour each way, held to a world axis within 10 degrees.
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

    // Unreal's GetDownsampleJitter2x2: which pixel of a half-resolution pixel's 2x2 block it stands for this frame.
    ivec2 jitter(ivec2 d) {
        int k = (CG_FRAME_INDEX + d.x * 3 + d.y * 5) & 3;
        return ivec2(k & 1, k >> 1);
    }

    // A light's lighting at p facing n, times the scene: world_lights.shader's, before its colour.
    float light_scale(CgLight light) {
        float adapt = 1.0;
        vec3 ambient = vec3(1.0);
        if (light.ambient.x >= 0.0) {
            vec3 lightmap = CG_LIGHTMAP(light.ambient.xy);
            adapt = mix(1.0, _DayFade, clamp(CG_DAYLIGHT, 0.0, 1.0) * light.ambient.y / 15.0);
            ambient = max(CG_LINEAR_SCENE ? srgb_to_linear(lightmap) : lightmap, vec3(CG_LINEAR_SCENE ? 0.02 : 0.15));
        }
        return adapt / (CG_PI * luminance(ambient));
    }

    void vertex(out v2f o) {
        vec2 p = vec2(float((CG_VERTEX_ID & 1) << 2) - 1.0, float((CG_VERTEX_ID & 2) << 1) - 1.0);
        o.uv = p * 0.5 + 0.5;
        gl_Position = vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        ivec2 sampleCoord = ivec2(gl_FragCoord.xy), d = sampleCoord >> 1;
        int mine = (sampleCoord.x & 1) + 2 * (sampleCoord.y & 1);
        fragColor = vec4(-1.0, 0.0, 0.0, 0.0);
        ivec2 px = d * 2 + jitter(d);
        if (px.x >= int(_Screen.x) || px.y >= int(_Screen.y)) return;
        vec3 p = surface(px);
        if (p.z >= 0.0) return;
        vec3 n = normal_at(px, p);
        ivec3 cell = cg_light_cell(p, _ZParams.xyz, _Rays.xy, _Rays.zw, _Tile.xy, ivec2(_Grid.xy));
        int e = (cell.z * int(_Grid.y) + cell.y) * int(_Grid.x) + cell.x;
        int count = int(CELLS(e * 2)), from = int(CELLS(e * 2 + 1));

        // InitLightSamplerStratified and AddLightSample (MegaLightsSampling.ush): four stratified picks in one pass.
        float rnd = cg_shadow_noise(vec2(d), CG_FRAME_INDEX);
        vec4 pick = (vec4(0.0, 1.0, 2.0, 3.0) + rnd) * 0.25;
        ivec4 chosen = ivec4(-1);
        vec4 chosenWeight = vec4(0.0);
        float weightSum = 0.0;
        for (int k = 0; k < count; k++) {
            int index = int(INDICES(from + k));
            CgLight light = LIGHTS(index);
            if (!CG_LIGHT_SHADOWED(light)) continue;
            vec3 at = light.positionRadius.xyz;
            vec3 v = to_view_dir(at) + vec3(_ToView0.w, _ToView1.w, _ToView2.w);
            vec4 spot = vec4(to_view_dir(light.spotCos.xyz), light.spotCos.w);
            vec4 segment = vec4(to_view_dir(light.segmentCone.xyz), light.segmentCone.w);
            float lit = cg_light_surface(v, light.positionRadius.w, light.colorExponent.w, spot, segment, light.ambient.z, p, n);
            // GetLocalLightTargetPDF: log2 of the lighting's luminance, smoothly zero below MinSampleWeight.
            float lum = luminance(light.colorExponent.rgb) * lit * light_scale(light);
            if (lum <= 0.001) continue;
            lum *= clamp((lum - 0.001) / 0.001, 0.0, 1.0);
            float weight = log2(lum + 1.0);
            if (weight <= 0.0) continue;
            float tau = weightSum / (weightSum + weight);
            weightSum += weight;
            for (int s = 0; s < 4; s++) {
                if (pick[s] < tau) {
                    pick[s] /= tau;
                } else {
                    pick[s] = (pick[s] - tau) / (1.0 - tau);
                    chosen[s] = index;
                    chosenWeight[s] = weight;
                }
                pick[s] = clamp(pick[s], 0.0, 0.9999);
            }
        }
        int index = chosen[mine];
        if (index < 0) return;
        CgLight light = LIGHTS(index);
        bool area = light.ambient.z > 0.001 || dot(light.segmentCone.xyz, light.segmentCone.xyz) > 1.0e-4;
        // MergeRays: the next sample of the same light takes this one's ray, unless the light has an area to sample.
        if (!area && mine < 3 && chosen[mine + 1] == index) {
            fragColor = vec4(float(index), 0.0, 0.0, 0.0);
            return;
        }
        float weight = weightSum / chosenWeight[mine];

        // GetLightSampleTrace: toward a point of the light sampled over the solid angle its sphere covers, along its
        // line for a tube.
        vec2 u = vec2(cg_shadow_noise(vec2(sampleCoord), CG_FRAME_INDEX), cg_shadow_noise(vec2(sampleCoord) + 17.0, CG_FRAME_INDEX));
        vec3 a = p + n * (_Trace.z + 0.002 * -p.z);
        vec3 centre = to_view_dir(light.positionRadius.xyz) + vec3(_ToView0.w, _ToView1.w, _ToView2.w);
        vec3 segment = to_view_dir(light.segmentCone.xyz);
        centre += segment * (u.x - 0.5);
        vec4 ray = cg_shadow_sphere_ray(centre - a, light.ambient.z, u);
        // Unreal's ray end bias: a centimetre short of the light.
        float rayEnd = max(ray.w - 0.01, 0.0);

        // The screen trace (TraceScreen): marched over the scene's depth; a hit within the depth thickness is final,
        // a point further behind the scene is uncertain and leaves the rest to the world trace, as does the screen's edge.
        float screenEnd = min(rayEnd, _Trace.x), reached = screenEnd;
        int steps = int(_Trace.y);
        float jit = cg_shadow_noise(vec2(sampleCoord) + 31.0, CG_FRAME_INDEX), last = 0.0;
        for (int k = 0; k < steps; k++) {
            float t = (float(k) + jit) / float(steps) * screenEnd;
            vec3 q = a + ray.xyz * t;
            float rayDepth = -q.z;
            vec2 uv = (q.xy / max(rayDepth, 1.0e-4) - _Rays.xy) / (_Rays.zw - _Rays.xy);
            if (rayDepth <= 0.05 || any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {
                reached = last;
                break;
            }
            float scene = eye_depth(ivec2(uv * _Screen.xy));
            float behind = rayDepth - scene, stepDepth = abs(ray.z) * screenEnd / float(steps);
            if (scene > 0.0 && behind > 0.0) {
                if (behind < max(_Trace.w * scene, stepDepth)) {
                    fragColor = vec4(float(index), -1.0, 0.0, 0.0);
                    return;
                }
                reached = last;
                break;
            }
            last = t;
        }

        // The world trace, from where the screen's left off, in eye-relative world space.
        vec3 aw = to_world(a), dir = to_world_dir(ray.xyz);
        if (_WorldLive != 0 && reached < rayEnd && cg_shadow_window(_World, _WorldSections, _WorldDistance,
                ivec3(_WorldBaseX, _WorldBaseY, _WorldBaseZ), ivec3(_WorldWrapX, _WorldWrapY, _WorldWrapZ),
                ivec3(_EyeX, _EyeY, _EyeZ), _EyeFrac.xyz, aw, dir, max(reached, 0.05), rayEnd)) {
            fragColor = vec4(float(index), -1.0, 0.0, 0.0);
            return;
        }
        // The entities round the camera, each a vertical capsule in its box (Unreal's capsule shadows' stand-in for a
        // character), skipped where the ray starts or ends inside it: the surface or the light is that entity's own.
        vec3 bw = aw + dir * rayEnd;
        for (int k = 0; k < _Entities; k++) {
            vec3 lo = ENTITIES(k * 2).xyz - _EyeFrac.xyz, hi = ENTITIES(k * 2 + 1).xyz - _EyeFrac.xyz;
            if (all(greaterThan(aw, lo - 0.1)) && all(lessThan(aw, hi + 0.1))) continue;
            if (all(greaterThan(bw, lo)) && all(lessThan(bw, hi))) continue;
            vec3 c = (lo + hi) * 0.5;
            float r = 0.45 * min(hi.x - lo.x, hi.z - lo.z);
            vec3 c0 = vec3(c.x, lo.y + r, c.z), c1 = vec3(c.x, max(hi.y - r, lo.y + r), c.z);
            if (cg_shadow_capsule(aw, bw, c0, c1, r)) {
                fragColor = vec4(float(index), -1.0, 0.0, 0.0);
                return;
            }
        }
        fragColor = vec4(float(index), weight, 0.0, 0.0);
    }
}
