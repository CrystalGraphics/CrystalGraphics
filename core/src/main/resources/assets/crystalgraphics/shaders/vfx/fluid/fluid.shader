// A fluid (CgVfxFluid) marched as NVIDIA Flow marches its blocks (BSD-3-Clause, flow/include/nvflowext/shaders/
// NvFlowRayMarch.hlsli, NvFlowRayMarchBlocks): steps of three quarters of a cell on a lattice fixed along the ray from
// the eye, offset by a noise each pixel; at each, the colour map's colour and opacity by temperature, the opacity times
// the smoke (held to 1) and times 1 - exp(-attenuation * step), the colour times the light the cell is lit by under
// Flow's shadowFactor; summed front to back, the depth weighted by what each step took. Flow's light is the shadow it
// baked; here it is the engine's: the sun through the fluid (the atlas's bake, Flow's ShadowUpdateCS) under a two-lobe
// phase, and the sky. The smoke's detail below the cells is wavelet noise (Mantaflow's) read at its two UV grids,
// carried with the flow and blended as Mantaflow weighs them.
// CG_OBJECT_TO_WORLD: the box, a unit cube scaled. CG_OBJECT_CUSTOM0: the fluid's place in the atlas, its cell in blocks,
// its first UV grid's weight, the layer's size; CG_OBJECT_CUSTOM1: its grid's low corner from the box's middle.
#type spatial
#pragma cg_use rows
#include "crystalgraphics:shaders/lib/color.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_depth.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_draw.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_scatter.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_sparse.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_wavelet.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_fluid.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "ColorSpace" = "Linear" }
Queue = "Transparent"

Properties {
    _FluidShade  ("The fluid atlas's shade: smoke, temperature, sun, sky (CgVfxFluidAtlas)", sampler3D) = "black"
    _FluidRestA  ("Its first UV grid: cells moved since its reset",   sampler3D) = "black"
    _FluidRestB  ("Its second",                                       sampler3D) = "black"
    _FluidPages  ("Each fluid's page table",                          sampler3D) = "black"
    _Colormap    ("Colour and opacity by temperature (CgVfxColormap)", sampler2D) = "white"
    _Wavelet     ("Wavelet noise",                                    sampler3D) = "cg_wavelet_noise"
    _Attenuation ("Opacity a block through whole smoke",              float)     = 5.0
    _ShadowFactor ("How much its light colours it, 0 to 1",           float)     = 1.0
    _ColormapMin ("The temperature at the colour map's start",        float)     = 0.0
    _ColormapMax ("And at its end",                                   float)     = 1.0
    _Scattering  ("Forward scattering, 0 to 0.9",                     float)     = 0.6
    _Detail      ("Detail below the cells, 0 to 1",                   float)     = 0.6
    _DetailSize  ("Its size, blocks",                                 float)     = 0.5
    _Sun         ("The sun's strength in it",                         float)     = 1.0
    _Ambient     ("The sky's strength in it",                         float)     = 0.6
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
        int fluid = int(CG_OBJECT_CUSTOM0.x + 0.5);
        float cell = CG_OBJECT_CUSTOM0.y, weightA = CG_OBJECT_CUSTOM0.z;
        vec3 centre = CG_OBJECT_TO_WORLD[3].xyz;
        vec3 size = vec3(length(CG_OBJECT_TO_WORLD[0].xyz), length(CG_OBJECT_TO_WORLD[1].xyz),
                length(CG_OBJECT_TO_WORLD[2].xyz));
        vec3 lo = centre - 0.5 * size, grid = centre + CG_OBJECT_CUSTOM1.xyz;
        vec2 t = fx_scatter_box(eye, ray, lo, lo + size);
        t.x = max(t.x, 0.0);
        t.y = min(t.y, FX_SCENE_DISTANCE(ray));
        if (t.y <= t.x) discard;
        float dt = FX_FLUID_STEP_CELLS * cell, tile = float(FX_SPARSE_TILE) * cell;
        // Flow's rayNoise: each pixel's lattice offset, here interleaved gradient noise (Jimenez 2014), moved each
        // frame by the golden ratio for the resolve to average.
        float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
#ifdef CG_VOLUME_TARGET
        noise = fract(noise + 0.618034 * float(CG_FRAME_INDEX % 64));
#endif
        float cosine = dot(ray, CG_SUN_DIRECTION);
        float phase = fx_scatter_phase(cosine, clamp(_Scattering, 0.0, 0.9));
        vec3 sun = mix(vec3(0.05, 0.06, 0.1), vec3(1.0, 0.95, 0.88), CG_DAYLIGHT) * _Sun;
        vec3 sky = srgb_to_linear(CG_LIGHTMAP(vec2(0.0, 15.0))) * vec3(0.78, 0.87, 1.0) * _Ambient;
        float range = max(_ColormapMax - _ColormapMin, 1.0e-4);
        vec4 sum = vec4(0.0, 0.0, 0.0, 1.0);
        vec3 glow = vec3(0.0);
        float nominal = 0.0;
        // Flow's cellMinT: the first lattice point past where the ray enters, each step standing for the half step
        // either side of it, clipped to where the ray is inside.
        float at = (ceil(t.x / dt + noise - 0.5) - noise) * dt;
        for (int k = 0; k < 512 && at - 0.5 * dt < t.y; k++) {
            float from = max(at - 0.5 * dt, t.x), to = min(at + 0.5 * dt, t.y), span = to - from;
            vec3 p = eye + ray * (0.5 * (from + to));
            vec3 c = (p - grid) / cell;
            ivec3 held = fx_sparse_tile(c);
            int slot = fx_sparse_slot_of(_FluidPages, fluid, held);
            if (slot < 0) {
                // Still air: on to the first lattice point past where the ray leaves this tile.
                vec3 tileLo = grid + vec3(held) * tile;
                at += max(ceil((fx_scatter_box(eye, ray, tileLo, tileLo + tile).y - at) / dt), 1.0) * dt;
                continue;
            }
            vec3 uvw = fx_sparse_pool_uvw(_FluidShade, slot, c);
            if (textureLod(_FluidShade, uvw, 0.0).x < 1.0e-4) {
                at += dt;
                continue;
            }
            vec4 s = fx_sparse_smooth(_FluidShade, slot, c);
            float smoke = max(s.x, 0.0);
            if (_Detail > 0.0) {
                vec3 restA = textureLod(_FluidRestA, uvw, 0.0).xyz, restB = textureLod(_FluidRestB, uvw, 0.0).xyz;
                float n = mix(fx_fluid_detail(_Wavelet, c - restB, cell, _DetailSize),
                        fx_fluid_detail(_Wavelet, c - restA, cell, _DetailSize), weightA);
                smoke *= clamp(1.0 + 2.0 * _Detail * n, 0.0, 2.0);
            }
            vec4 color = textureLod(_Colormap, vec2((s.y - _ColormapMin) / range, 0.5), 0.0);
            // Flow's colour is radiance, scaled by its shadow alone. Here up to 1 it is what the fluid scatters, lit by
            // the sun and sky; past 1, what it gives off: never dimmed by the sun, and all that blooms.
            vec3 albedo = min(color.rgb, vec3(1.0)), emit = color.rgb - albedo;
            vec3 light = sun * (phase * s.z) + sky * s.w;
            color.rgb = albedo * mix(vec3(1.0), light, _ShadowFactor) + emit;
            color.a = min(1.0, max(color.a, 0.0) * smoke) * (1.0 - exp(-_Attenuation * span));
            nominal += sum.a * color.a * at;
            sum.rgb += sum.a * color.a * color.rgb;
            glow += sum.a * color.a * emit;
            sum.a *= 1.0 - color.a;
            if (sum.a < 0.00005) break;
            at += dt;
        }
        float alpha = 1.0 - sum.a;
        // Where its light came from, weighed by what each step took: what the resolve reprojects last frame by.
        CG_VOLUME_DEPTH(alpha > 0.01 ? FX_EYE_DEPTH(ray, nominal / alpha) : 0.0);
        vec3 light = sum.rgb * FX_PRESENCE;
        alpha *= FX_PRESENCE;
        if (alpha < 1.0e-4) discard;
        if (CG_LINEAR_SCENE) {
            fragColor = vec4(light, alpha);
        } else {
            vec3 c = light / max(alpha, 1.0e-4);
            fragColor = vec4((linear_to_srgb(min(c, vec3(1.0))) + max(c - 1.0, 0.0)) * alpha, alpha);
        }
        CG_GLOW(glow * FX_PRESENCE);
    }
}

// Codeless: what glows past white blooms, in the same draw.
Pass { Tags { "LightMode" = "Emissive" } }
