// The world's lights' samples, as Unreal's MegaLights picks and traces them (MegaLightsSampling.usf's
// GenerateLightSamplesCS, MegaLightsRayTracing.usf). The target is twice the size of a half-resolution grid: each
// half-resolution pixel's four samples are its 2x2 block, as Unreal's NumSamplesPerPixel 2x2 lays them. Every fragment
// runs the pixel's weighted reservoir over its light-grid cell's sampled lights (CG_LIGHT_SAMPLED), stratified four
// ways, keeps its own pick, and for a light that casts shadows traces a ray to a point on it (one that casts none
// reaches it unasked): across the screen's depth first, then through the voxel window's
// blocks from where that stopped, and against the boxes of the entities round the camera. The sun is not one of its
// lights (Unreal's r.MegaLights.DirectionalLights 0): our objects shadow it through its cascaded shadow map, read with
// Unreal's Manual3x3PCF, and on our objects' pixels a ray toward it is traced through the blocks and the entities.
//
// What casts depends on the receiver: on our objects' pixels (nearer than the host's depth before our opaque draws)
// everything does; on Minecraft's, only our objects, its own blocks keeping the light it baked. Our objects cast a
// light's shadow through their meshes' distance fields (_CasterRows, _MeshFields), on screen or off, as Unreal's
// distance field shadows trace them (DistanceFieldShadowingShared.ush's ShadowRayTraceThroughCulledObjects).
//
// Minecraft's light-giving blocks are lights of their own (CG_LIGHT_MINECRAFT): each sample picks one in proportion to
// the block light Minecraft's flood gives the pixel from it, level less the blocks between, and traces it as it traces
// a light of ours. Their mean is the share of the pixel's block light still reaching it.
//
// Written: x the light's index, -1 for none; y its shading weight (WeightSum / weight, 1 / pdf), 0 where a sample of
// the same light after it took its ray (Unreal's MergeRays), negative where the ray was blocked; z the sun's
// visibility, 1 with no sun; w the block light's visibility, 1 with no Minecraft light.
#type none
#include "crystalgraphics:shaders/lib/math.glsl"
#include "crystalgraphics:shaders/lib/color.glsl"
#include "crystalgraphics:shaders/lib/lights.glsl"
#include "crystalgraphics:shaders/lib/light_shadow.glsl"
#include "crystalgraphics:shaders/lib/world_light_trace.glsl"

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
    _Screen  ("The full target's width and height, then the downsample factor", vec4) = (1, 1, 2, 2)
    _Trace   ("Screen trace: blocks, steps, normal bias in blocks, relative depth thickness", vec4) = (8, 24, 0.02, 0.005)
    _Sun     ("The sun: 1 for its shadows, 1 where its map was drawn", vec4) = (0, 0, 0, 0)
    _Own     ("1 where the host's depth before our opaque draws was taken this frame", float) = 0.0
    _SampleGrid ("Unreal's NumSamplesPerPixel as a block of the samples texture: its width, height and count", vec4) = (2, 2, 4, 0)
    _Skip    ("Parts left out, to measure each: 1 sun, 2 block light, 4 lights, 8 meshes, 16 screen, 32 blocks, 64 entities", int) = 0
    _EyeX    ("The eye's block: x",                                int) = 0
    _EyeY    ("y",                                                 int) = 0
    _EyeZ    ("z",                                                 int) = 0
    _EyeFrac ("The eye within its block",                          vec4) = (0, 0, 0, 0)
    _World         ("The voxel window's blocks",                   sampler3D) = "black"
    _WorldSections ("Which of its sections are filled",            sampler3D) = "black"
    _WorldDistance ("Its distance to solid over FX_WORLD_FAR",      sampler3D) = "white"
    _HostDepth     ("The host's window depth before our opaque draws", sampler2D) = "white"
    _CasterRows    ("Our meshes casting shadows, five texels each: eye-relative world to field voxels (three rows), the field's size and first float, its scales; from SUN_ROWS_AT the sun's cascades, four each: to uv, to depth, view depths and depth bias", sampler2D) = "black"
    _MeshFields    ("Every mesh's distance field in its own units, MESH_FIELD_ROW floats a row", sampler2D) = "black"
    _Normals       ("Our surfaces' normals, world, n * 0.5 + 0.5, alpha 1 where drawn", sampler2D) = "black"
    _EntityTiles   ("Each 16x16 tile's entities a ray from it can meet: 16 bits a channel (world_lights_tiles.shader)", sampler2D) = "black"
    _SunMap        ("The sun's cascades, a quadrant each, nearest bottom left: our casters' depth from the light, blocks", sampler2D) = "white"
    _WorldBaseX    ("Its lowest block, absolute: x",               int) = 0
    _WorldBaseY    ("y",                                           int) = 0
    _WorldBaseZ    ("z",                                           int) = 0
    _WorldWrapX    ("That block's texel: x",                       int) = 0
    _WorldWrapY    ("y",                                           int) = 0
    _WorldWrapZ    ("z",                                           int) = 0
    _WorldLive     ("1 with a level, else 0: read nothing",        int) = 0
    _Entities      ("Entity boxes in ENTITIES",                    int) = 0
    _Casters       ("Our meshes casting shadows in _CasterRows",   int) = 0
}

struct CgLight { vec4 positionRadius; vec4 colorExponent; vec4 spotCos; vec4 segmentCone; vec4 ambient; vec4 air; };

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

    // A window depth's eye depth under the bob-free perspective; 0 for none (the sky).
    float eye_depth_of(float window) {
        if (cg_DepthParams.x > 0.5 ? window <= 0.0 : window >= 1.0) return 0.0;
        float ndc = cg_DepthParams.y > 0.5 ? window : window * 2.0 - 1.0;
        return _Depth.z == 0.0 ? (_Depth.y - ndc) / _Depth.x
                : (_Depth.y - _Depth.x / _Depth.z * _Depth.w) / (ndc - _Depth.x / _Depth.z);
    }

    // The scene's eye depth at a pixel of the full target; 0 for none.
    float eye_depth(ivec2 px) {
        return eye_depth_of(texelFetch(cg_DepthBuffer, clamp(px, ivec2(0), ivec2(_Screen.xy) - 1), 0).r);
    }

    // Whether pixel px is one of our objects': nearer than the host's depth was before our opaque draws.
    bool own(ivec2 px, float depth) {
        if (_Own < 0.5 || depth <= 0.0) return false;
        float host = eye_depth_of(texelFetch(_HostDepth, clamp(px, ivec2(0), ivec2(_Screen.xy) - 1), 0).r);
        return host <= 0.0 || depth < host - max(0.01, 0.002 * depth);
    }

    vec3 surface(ivec2 px) {
        float depth = eye_depth(px);
        vec2 uv = (vec2(clamp(px, ivec2(0), ivec2(_Screen.xy) - 1)) + 0.5) / _Screen.xy;
        return vec3(mix(_Rays.xy, _Rays.zw, uv) * depth, -depth);
    }

    // world_lights.shader's normal: ours as drawn; Minecraft's from the nearer neighbour each way, held to a world axis
    // within 10 degrees, as its blocks are.
    // Our surfaces' own normal where the opaque pass wrote one (Unreal's G-buffer normal); else rebuilt from depth.
    vec3 drawn_normal(ivec2 px, vec3 p, vec2 size, out bool given) {
        vec4 t = texelFetch(_Normals, clamp(px, ivec2(0), ivec2(size) - 1), 0);
        given = t.a > 0.5;
        if (!given) return vec3(0.0);
        vec3 n = to_view_dir(normalize(t.xyz * 2.0 - 1.0));
        return dot(n, p) > 0.0 ? -n : n;
    }

    vec3 normal_at(ivec2 px, vec3 p) {
        bool given;
        vec3 drawn = drawn_normal(px, p, _Screen.xy, given);
        if (given) return drawn;
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

    // Unreal's r.MegaLights.DownsampleMode as a factor: 1x1, 2x1 (checkerboard) or 2x2 (half resolution).
    ivec2 down() { return ivec2(_Screen.zw); }

    // Unreal's GetDownsampleJitter2x2: which pixel of a downsampled pixel's block it stands for this frame.
    ivec2 jitter(ivec2 d) {
        int k = (CG_FRAME_INDEX + d.x * 3 + d.y * 5) & 3;
        return ivec2(k & 1, k >> 1) % down();
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

    // The screen trace (TraceScreen) from a along dir for len: 1 a hit within the depth thickness, -1 uncertain, the ray
    // behind the scene or off screen, with reached where it got to; 0 clear.
    int screen_trace(vec3 a, vec3 dir, float len, int steps, float jit, out float reached) {
        reached = len;
        if ((_Skip & 16) != 0) {
            reached = 0.0;
            return -1;
        }
        float last = 0.0;
        for (int k = 0; k < steps; k++) {
            float t = (float(k) + jit) / float(steps) * len;
            vec3 q = a + dir * t;
            float rayDepth = -q.z;
            vec2 uv = (q.xy / max(rayDepth, 1.0e-4) - _Rays.xy) / (_Rays.zw - _Rays.xy);
            if (rayDepth <= 0.05 || any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {
                reached = last;
                return -1;
            }
            ivec2 at = ivec2(uv * _Screen.xy);
            float scene = eye_depth(at);
            float behind = rayDepth - scene;
            if (scene > 0.0 && behind > 0.0) {
                // Unreal's RelativeDepthThickness alone: a ray further behind passed behind the surface, not into it.
                if (behind < _Trace.w * scene) return 1;
                reached = last;
                return -1;
            }
            last = t;
        }
        return 0;
    }

    // This pixel's tile's entities, a bit each: the only capsules its rays test.
    uvec4 entityTiles;

    // Whether the window's blocks or an entity's capsule lie on the eye-relative world segment a to a + dir * t1, from
    // t0: the world trace past the screen.
    bool world_trace(vec3 aw, vec3 dir, float t0, float t1) {
        if ((_Skip & 32) == 0 && _WorldLive != 0 && t0 < t1 && cg_shadow_window(_World, _WorldSections, _WorldDistance,
                ivec3(_WorldBaseX, _WorldBaseY, _WorldBaseZ), ivec3(_WorldWrapX, _WorldWrapY, _WorldWrapZ),
                ivec3(_EyeX, _EyeY, _EyeZ), _EyeFrac.xyz, aw, dir, max(t0, 0.05), t1)) {
            return true;
        }
        // The entities round the camera, each a vertical capsule in its box (Unreal's capsule shadows' stand-in for a
        // character), skipped where the ray starts or ends inside it: the surface or the light is that entity's own.
        vec3 bw = aw + dir * t1;
        for (int k = 0; k < ((_Skip & 64) != 0 ? 0 : _Entities); k++) {
            if (((entityTiles[k >> 4] >> uint(k & 15)) & 1u) == 0u) continue;
            vec3 lo = ENTITIES(k * 2).xyz - _EyeFrac.xyz, hi = ENTITIES(k * 2 + 1).xyz - _EyeFrac.xyz;
            if (all(greaterThan(aw, lo - 0.1)) && all(lessThan(aw, hi + 0.1))) continue;
            if (all(greaterThan(bw, lo)) && all(lessThan(bw, hi))) continue;
            vec3 c = (lo + hi) * 0.5;
            float r = 0.45 * min(hi.x - lo.x, hi.z - lo.z);
            vec3 c0 = vec3(c.x, lo.y + r, c.z), c1 = vec3(c.x, max(hi.y - r, lo.y + r), c.z);
            if (cg_shadow_capsule(aw, bw, c0, c1, r)) return true;
        }
        return false;
    }

    // Whether one of our meshes lies on the segment: lib/world_light_trace.glsl's casters, less what _Skip leaves out.
    bool caster_trace(vec3 aw, vec3 dir, float t0, float t1) {
        return cg_trace_casters(_CasterRows, _MeshFields, (_Skip & 8) != 0 ? 0 : _Casters, aw, dir, t0, t1);
    }


    // CgWorldLights.SUN_ROWS_AT: where the sun's cascade rows start in _CasterRows.
    const int SUN_ROWS_AT = 1280;

    vec4 sun_row(int k, int r) { return texelFetch(_CasterRows, ivec2(SUN_ROWS_AT + k * 4 + r, 0), 0); }

    // Cascade k's visibility at eye-relative w, n.l the receiver's toward the sun: Unreal's Manual3x3PCF
    // (ShadowFilteringCommon.ush:245-262), a 4x4 texel footprint weighted to a 3x3 tent by the fraction, each texel's
    // soft comparison saturate((caster - receiver) * scale + 1) over the caster bias, its scale lerp(0.9, 1, n.l) of it
    // (ShadowProjectionPixelShader.usf:249, r.Shadow.CSMReceiverBias). Past the last cascade, lit.
    float sun_cascade(int k, vec3 w, float nol) {
        if (k > 3) return 1.0;
        vec4 r0 = sun_row(k, 0), r1 = sun_row(k, 1), r2 = sun_row(k, 2), range = sun_row(k, 3);
        vec2 uv = vec2(dot(r0.xyz, w) + r0.w, dot(r1.xyz, w) + r1.w);
        float depth = dot(r2.xyz, w) + r2.w;
        float scale = mix(0.9, 1.0, nol) / range.w;
        int size = textureSize(_SunMap, 0).x / 2;
        ivec2 origin = ivec2(k & 1, k >> 1) * size;
        vec2 texel = uv * float(size) - 0.5;
        vec2 f = fract(texel);
        ivec2 corner = ivec2(floor(texel)) - 1;
        float lit = 0.0;
        for (int y = 0; y < 4; y++) {
            float wy = y == 0 ? 1.0 - f.y : y == 3 ? f.y : 1.0;
            for (int x = 0; x < 4; x++) {
                float wx = x == 0 ? 1.0 - f.x : x == 3 ? f.x : 1.0;
                ivec2 at = origin + clamp(corner + ivec2(x, y), ivec2(0), ivec2(size - 1));
                lit += wx * wy * clamp((texelFetch(_SunMap, at, 0).r - depth) * scale + 1.0, 0.0, 1.0);
            }
        }
        return lit / 9.0;
    }

    // Our casters' shadow of the sun at view depth z: the cascade z falls in, blended into the next over its fade
    // region, the last fading to lit (Unreal's SplitFarFadeRegion).
    float sun_map(float z, vec3 w, float nol) {
        if (_Sun.y < 0.5) return 1.0;
        for (int k = 0; k < 4; k++) {
            vec4 range = sun_row(k, 3);
            if (z >= range.z) continue;
            float v = sun_cascade(k, w, nol);
            if (z <= range.y) return v;
            return mix(v, sun_cascade(k + 1, w, nol), (z - range.y) / max(range.z - range.y, 1.0e-4));
        }
        return 1.0;
    }

    // The sun's visibility at p facing n: 1 lit, 0 shadowed. Our casters through its map; on our objects' pixels the
    // blocks and entities too, a ray toward it through the world. On our objects it fades out over the last few degrees
    // before a face turns from it, so a face edge-on to it (Minecraft's sun crosses east to west: a north face is exactly
    // edge-on) holds still rather than flipping with a normal's last bit; on Minecraft's a face turned from it is left as
    // Minecraft lit it.
    float sun_visibility(vec3 p, vec3 n, bool ownPixel) {
        if ((_Skip & 1) != 0 || _Sun.x < 0.5 || CG_DAYLIGHT <= 0.0 || CG_SUN_DIRECTION.y <= 0.0) return 1.0;
        vec3 toSun = normalize(to_view_dir(CG_SUN_DIRECTION));
        float facing = ownPixel ? smoothstep(0.0, 0.2, dot(n, toSun)) : 1.0;
        if (dot(n, toSun) <= 0.0) return ownPixel ? 0.0 : 1.0;
        float v = sun_map(-p.z, to_world(p), clamp(dot(n, toSun), 0.0, 1.0));
        if (!ownPixel || v <= 0.0) return v;
        vec3 a = to_world(p + n * (_Trace.z + 0.002 * -p.z));
        return world_trace(a, normalize(CG_SUN_DIRECTION), 0.0, 96.0) ? 0.0 : v * facing;
    }

    // The block a surface's light is read at: the one its face looks into, from the eye's block.
    vec3 block_of(vec3 w) { return floor(w + _EyeFrac.xyz); }

    // Whether Minecraft's block light from the light-giving block at eye-relative c reaches p facing n: 1 or 0. One
    // block, picked from the cell's Minecraft lights by the light Minecraft's flood gives the pixel from each.
    float block_visibility(vec3 p, vec3 n, bool ownPixel, ivec2 sampleCoord) {
        if ((_Skip & 2) != 0) return 1.0;
        ivec3 cell = cg_light_cell(p, _ZParams.xyz, _Rays.xy, _Rays.zw, _Tile.xy, ivec2(_Grid.xy));
        int e = (cell.z * int(_Grid.y) + cell.y) * int(_Grid.x) + cell.x;
        int count = int(CELLS(e * 2)), from = int(CELLS(e * 2 + 1));
        vec3 at = block_of(to_world(p + n * 0.5));
        float pick = cg_shadow_noise(vec2(sampleCoord) + 113.0, CG_FRAME_INDEX), weightSum = 0.0;
        int chosen = -1;
        for (int k = 0; k < count; k++) {
            int index = int(INDICES(from + k));
            CgLight light = LIGHTS(index);
            if (!CG_LIGHT_MINECRAFT(light)) continue;
            vec3 steps = abs(block_of(light.positionRadius.xyz) - at);
            float weight = light.positionRadius.w - 0.5 - (steps.x + steps.y + steps.z);
            if (weight <= 0.0) continue;
            float tau = weightSum / (weightSum + weight);
            weightSum += weight;
            if (pick < tau) {
                pick /= tau;
            } else {
                pick = (pick - tau) / (1.0 - tau);
                chosen = index;
            }
            pick = clamp(pick, 0.0, 0.9999);
        }
        if (chosen < 0) return 1.0;
        vec3 centre = to_view_dir(LIGHTS(chosen).positionRadius.xyz) + vec3(_ToView0.w, _ToView1.w, _ToView2.w);
        vec3 toLight = centre - p;
        if (dot(n, toLight) <= 0.0) return ownPixel ? 0.0 : 1.0;
        vec2 u = vec2(cg_shadow_noise(vec2(sampleCoord) + 131.0, CG_FRAME_INDEX), cg_shadow_noise(vec2(sampleCoord) + 151.0, CG_FRAME_INDEX));
        vec3 a = p + n * (_Trace.z + 0.002 * -p.z);
        vec4 ray = cg_shadow_sphere_ray(centre - a, 0.3, u);
        // Stopped short of the block it comes from, which may be solid (glowstone).
        float rayEnd = max(ray.w - 0.9, 0.0);
        float jit = cg_shadow_noise(vec2(sampleCoord) + 173.0, CG_FRAME_INDEX), reached;
        // Minecraft's world keeps its own blocks' shadows: only our objects take its light from it.
        if (caster_trace(to_world(a), to_world_dir(ray.xyz), 0.0, rayEnd)) return 0.0;
        if (!ownPixel) return 1.0;
        int screen = screen_trace(a, ray.xyz, min(rayEnd, _Trace.x), int(_Trace.y), jit, reached);
        if (screen == 1) return 0.0;
        if (screen == 0) reached = min(rayEnd, _Trace.x);
        return reached < rayEnd && world_trace(to_world(a), to_world_dir(ray.xyz), reached, rayEnd) ? 0.0 : 1.0;
    }

    // This sample's light: x its index, -1 for none; y its weight, 0 merged, -1 blocked.
    vec2 light_sample(vec3 p, vec3 n, ivec2 d, ivec2 sampleCoord, int mine) {
        if ((_Skip & 4) != 0) return vec2(-1.0, 0.0);
        ivec3 cell = cg_light_cell(p, _ZParams.xyz, _Rays.xy, _Rays.zw, _Tile.xy, ivec2(_Grid.xy));
        int e = (cell.z * int(_Grid.y) + cell.y) * int(_Grid.x) + cell.x;
        int count = int(CELLS(e * 2)), from = int(CELLS(e * 2 + 1));

        // InitLightSamplerStratified and AddLightSample (MegaLightsSampling.ush): four stratified picks in one pass.
        float rnd = cg_shadow_noise(vec2(d), CG_FRAME_INDEX);
        vec4 pick = (vec4(0.0, 1.0, 2.0, 3.0) + rnd) / _SampleGrid.z;
        ivec4 chosen = ivec4(-1);
        vec4 chosenWeight = vec4(0.0);
        float weightSum = 0.0;
        for (int k = 0; k < count; k++) {
            int index = int(INDICES(from + k));
            if (!CG_LIGHT_SAMPLED(LIGHTS(index))) continue;
            CgLight light = LIGHTS(index);
            vec3 v = to_view_dir(light.positionRadius.xyz) + vec3(_ToView0.w, _ToView1.w, _ToView2.w);
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
        if (index < 0) return vec2(-1.0, 0.0);
        CgLight light = LIGHTS(index);
        bool area = light.ambient.z > 0.001 || dot(light.segmentCone.xyz, light.segmentCone.xyz) > 1.0e-4;
        // MergeRays: the next sample of the same light takes this one's ray, unless the light has an area to sample.
        if (!area && mine < int(_SampleGrid.z) - 1 && chosen[mine + 1] == index) return vec2(float(index), 0.0);
        float weight = weightSum / chosenWeight[mine];
        if (!CG_LIGHT_SHADOWED(light)) return vec2(float(index), weight);

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

        // The screen trace, then the world trace from where it left off: a lamp of ours is shadowed by everything.
        float jit = cg_shadow_noise(vec2(sampleCoord) + 31.0, CG_FRAME_INDEX), reached;
        if (caster_trace(to_world(a), to_world_dir(ray.xyz), 0.0, rayEnd)) return vec2(float(index), -1.0);
        int screen = screen_trace(a, ray.xyz, min(rayEnd, _Trace.x), int(_Trace.y), jit, reached);
        if (screen == 1) return vec2(float(index), -1.0);
        if (screen == 0) reached = min(rayEnd, _Trace.x);
        if (reached < rayEnd && world_trace(to_world(a), to_world_dir(ray.xyz), reached, rayEnd)) return vec2(float(index), -1.0);
        return vec2(float(index), weight);
    }

    void vertex(out v2f o) {
        vec2 p = vec2(float((CG_VERTEX_ID & 1) << 2) - 1.0, float((CG_VERTEX_ID & 2) << 1) - 1.0);
        o.uv = p * 0.5 + 0.5;
        gl_Position = vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        ivec2 grid = ivec2(_SampleGrid.xy), sampleCoord = ivec2(gl_FragCoord.xy), d = sampleCoord / grid;
        int mine = sampleCoord.x % grid.x + grid.x * (sampleCoord.y % grid.y);
        fragColor = vec4(-1.0, 0.0, 1.0, 1.0);
        ivec2 px = d * down() + jitter(d);
        if (px.x >= int(_Screen.x) || px.y >= int(_Screen.y)) return;
        vec3 p = surface(px);
        if (p.z >= 0.0) return;
        vec3 n = normal_at(px, p);
        bool ownPixel = own(px, -p.z);
        entityTiles = uvec4(texelFetch(_EntityTiles, min(px / 16, textureSize(_EntityTiles, 0) - 1), 0));
        // The sun and Minecraft's block light take one ray a pixel, in its first sample: MegaLights spends a pixel's
        // four on every light together, and the frames average the rest.
        bool first = mine == 0;
        fragColor = vec4(light_sample(p, n, d, sampleCoord, mine), first ? sun_visibility(p, n, ownPixel) : 1.0,
                first ? block_visibility(p, n, ownPixel, sampleCoord) : 1.0);
    }
}
