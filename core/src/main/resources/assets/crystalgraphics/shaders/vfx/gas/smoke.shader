// A gas (CgVfxGas) marched through the box round its tiles (CgVfxFrame.volume), a tile it has not skipped in one step.
// Each step reads the solver's shade (density, flame, the transmittance toward the sun it cached, the sky light the gas
// above leaves) and flow (velocity, temperature) through the gas's page table; wears the density's thin edges with noise finer than the cells, carried along the flow (Schneider's
// erosion over Vlachos's flow maps); and adds the light scattered toward the eye by Hillaire's energy-conserving step
// (Frostbite, 2015): the sun through a two-lobe phase with Wrenninge's octaves standing in for light scattered more
// than once (2013) and Horizon's powder at thin edges, the sky cooler than the sun, hot smoke's blackbody glow by
// Kirchhoff's law (it emits as it absorbs), and its flames' own light, which needs no smoke to shine. The march stops at
// the scene's depth, and draws from inside the box as from outside: back faces, its own depth test.
// CG_OBJECT_TO_WORLD: the box, a unit cube scaled. CG_OBJECT_CUSTOM0: the gas's place in the atlas, its cell in blocks,
// 0, the layer's size (a density scale); CG_OBJECT_CUSTOM1: its grid's low corner from the box's middle, its extinction a
// block. _Smoke is its colour in full light, _Fire a tint on what burns.
#type spatial
#pragma cg_use rows
#include "crystalgraphics:shaders/lib/color.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_depth.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_draw.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_gas.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "ColorSpace" = "Linear" }
Queue = "Transparent"

Properties {
    _GasShade   ("The gas atlas's shade: density, flame, sun, sky (CgVfxGasAtlas)", sampler3D) = "black"
    _GasFlow    ("Its flow: velocity, temperature",                 sampler3D) = "black"
    _GasPages   ("Each gas's page table",                           sampler3D) = "black"
    _ValueNoise ("Value noise",                                     sampler3D) = "cg_value_noise"
    _Voronoi    ("Voronoi",                                         sampler3D) = "cg_voronoi"
    _Smoke      ("The smoke in full light",                         color)     = (0.7, 0.7, 0.68, 1)
    _Fire       ("A tint on what burns in it",                      color)     = (1, 1, 1, 1)
    _Steps      ("Steps across the box, at most; never under half a cell", float) = 160.0
    _Scattering ("Forward scattering, 0 to 0.9",                    float)     = 0.6
    _Sun        ("The sun's strength in it",                        float)     = 3.0
    _Ambient    ("The sky's strength in it",                        float)     = 1.0
    _Detail     ("Its edges worn by finer noise, 0 to 1",           float)     = 0.5
    _DetailSize ("That noise's size, blocks",                       float)     = 1.2
    _Kelvin     ("Kelvin a unit of temperature, over the air's 300", float)    = 600.0
    _Soot       ("Hot smoke's glow",                                float)     = 0.35
    _Glow       ("Its fire's brightness",                           float)     = 1.0
}

struct v2f { vec3 world; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE_MINUS_SRC_ALPHA
        DepthTest ALWAYS
        DepthWrite OFF
        Cull FRONT
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.world = world.xyz;
        gl_Position = cg_ProjMatrix * (cg_ViewMatrix * world);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 eye = CG_CAMERA_WORLD_POS, ray = FX_PIXEL_RAY;
        int gas = int(CG_OBJECT_CUSTOM0.x + 0.5);
        float cell = CG_OBJECT_CUSTOM0.y, extinction = CG_OBJECT_CUSTOM1.w * CG_OBJECT_CUSTOM0.w;
        vec3 centre = CG_OBJECT_TO_WORLD[3].xyz;
        vec3 size = vec3(length(CG_OBJECT_TO_WORLD[0].xyz), length(CG_OBJECT_TO_WORLD[1].xyz),
                length(CG_OBJECT_TO_WORLD[2].xyz));
        vec3 lo = centre - 0.5 * size, grid = centre + CG_OBJECT_CUSTOM1.xyz;
        vec2 t = fx_gas_box(eye, ray, lo, lo + size);
        t.x = max(t.x, 0.0);
        t.y = min(t.y, FX_SCENE_DISTANCE(ray));
        if (t.y <= t.x) discard;
        float dt = max((t.y - t.x) / _Steps, 0.5 * cell), tile = float(FX_GAS_TILE) * cell;
        // Interleaved gradient noise (Jimenez, 2014): each pixel's steps start apart, so banding becomes grain.
        float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
        float cosine = dot(ray, CG_SUN_DIRECTION);
        // Each octave a dimmer copy of the light scattered once, less shadowed and less forward.
        float g = clamp(_Scattering, 0.0, 0.9);
        float p0 = fx_gas_phase(cosine, g), p1 = fx_gas_phase(cosine, g * 0.5), p2 = fx_gas_phase(cosine, g * 0.25);
        // The sun by day, the moon's dim blue by night: CG_SUN_DIRECTION is whichever is up. The sky bluer than either.
        vec3 sun = mix(vec3(0.05, 0.06, 0.1), vec3(1.0, 0.95, 0.88), CG_DAYLIGHT) * _Sun;
        vec3 sky = srgb_to_linear(CG_LIGHTMAP(vec2(0.0, 15.0))) * vec3(0.78, 0.87, 1.0) * _Ambient;
        vec3 albedo = srgb_to_linear(_Smoke.rgb), fireTint = _Fire.rgb * _Glow;
        float transmit = 1.0;
        vec3 light = vec3(0.0), emit = vec3(0.0), glow = vec3(0.0);
        float at = t.x + jitter * dt;
        for (int k = 0; k < 512 && at < t.y; k++) {
            vec3 p = eye + ray * at;
            vec3 c = (p - grid) / cell;
            ivec3 held = fx_gas_tile(c);
            int slot = fx_gas_slot_of(_GasPages, gas, held);
            if (slot < 0) {
                // Still air: on to the first step past where the ray leaves this tile, keeping the steps' phase.
                vec3 from = grid + vec3(held) * tile;
                at += max(ceil((fx_gas_box(eye, ray, from, from + tile).y - at) / dt), 1.0) * dt;
                continue;
            }
            vec3 uvw = fx_gas_pool_uvw(_GasShade, slot, c);
            vec4 s = textureLod(_GasShade, uvw, 0.0);
            if (s.x < 1.0e-4 && s.y < 1.0e-3) {
                at += dt;
                continue;
            }
            vec4 f = textureLod(_GasFlow, uvw, 0.0);
            float density = s.x;
            // Only an edge thin enough to see through is worn: a body's inside is hidden anyway.
            if (_Detail > 0.0 && density < 2.0) {
                vec3 q = (p - grid) / max(_DetailSize, 0.05);
                density = fx_gas_erode(density, fx_gas_flowing_detail(_ValueNoise, _Voronoi, q,
                        f.xyz / max(_DetailSize, 0.05), FX_AGE, 0.6), _Detail);
            }
            float sigma = density * extinction;
            vec3 fire = fx_gas_emission(300.0 + _Kelvin * f.w) * fireTint;
            float through = exp(-sigma * dt);
            if (sigma > 1.0e-4) {
                float lit = (p0 * s.z + 0.5 * p1 * sqrt(s.z) + 0.25 * p2 * sqrt(sqrt(s.z))) * fx_gas_powder(sigma, cosine);
                // What a step scatters and its soot emits toward the eye, integrated against its own extinction.
                float taken = transmit * (1.0 - through);
                vec3 soot = fire * _Soot;
                light += (albedo * (sun * lit + sky * s.w) + soot) * taken;
                glow += soot * taken;
            }
            // A flame's own light along the step, dimmed by the smoke in it.
            vec3 flame = fire * s.y * (sigma > 1.0e-4 ? (1.0 - through) / sigma : dt) * transmit;
            emit += flame;
            glow += flame;
            transmit *= through;
            if (transmit < 0.01) break;
            at += dt;
        }
        float alpha = (1.0 - transmit) * FX_PRESENCE;
        light *= FX_PRESENCE;
        emit *= FX_PRESENCE;
        if (alpha < 1.0e-4 && max(emit.r, max(emit.g, emit.b)) < 1.0e-4) discard;
        if (CG_LINEAR_SCENE) {
            fragColor = vec4(light + emit, alpha);
        } else {
            // Into the encoded picture, its excess past white kept as the scene's decode keeps it: the smoke over what
            // is behind it, its flames added.
            vec3 c = light / max(alpha, 1.0e-4);
            vec3 smoke = (linear_to_srgb(min(c, vec3(1.0))) + max(c - 1.0, 0.0)) * alpha;
            fragColor = vec4(smoke + linear_to_srgb(min(emit, vec3(1.0))) + max(emit - 1.0, 0.0), alpha);
        }
        CG_GLOW(glow * FX_PRESENCE);
    }
}

// Codeless: what burns blooms, in the same draw.
Pass { Tags { "LightMode" = "Emissive" } }
