// What a strip's looks read of a ribbon draw's vertex (particle_factory.glsl's ribbon factory), and the ki streak's
// look they share. A strip material has no vertex data and takes the particle factory:
//
//     CgMaterialVertex v = cg_material_vertex();
//     CgMaterialParticle p = cg_material_particle();
//     vec3 trail = fx_strip(v.texCoords[0], v.texCoords[1], p.relativeTime);
//
// Its renderer tiles UV1 once a block (a Cascade trail's tiling_distance 1, a Niagara ribbon's uv1 tiled over its
// length at tiling_length 1), so UV1's U is blocks along it.
#pragma once

#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"

// A strip vertex's trail: (its particles' life 0 newest to 1 oldest, -1 to 1 across, blocks along).
vec3 fx_strip(vec2 uv0, vec2 uv1, float life) {
    return vec3(life, uv0.y * 2.0 - 1.0, uv1.x);
}

// The ki streak's look (trail/glow.shader): white-hot down its middle to its colour at soft edges, energy flowing back
// along it in streaks and eaten away toward its tail as it fades.
struct FxGlow {
    vec3 color;       // its energy, times its strength
    vec3 core;        // its white-hot middle
    float coreWidth;  // the middle's share of the width
    float softness;   // higher thins its edges
    float fade;       // higher fades it sooner along its life
    vec2 tiling;      // noise cells a block along it, and across it
    float flow;       // how fast its energy flows back, blocks a second
    float erode;      // how much its tail is eaten away, 0..1
};

// trail as fx_strip answers; noise the engine's gradient noise volume. Answers its light, added, and puts its hot part
// in glow, for CG_GLOW.
vec3 fx_strip_glow(FxGlow g, sampler3D noise, vec3 trail, float time, float seed, out vec3 glow) {
    glow = vec3(0.0);
    float u = trail.x, r = abs(trail.y);
    if (r >= 1.0) return vec3(0.0);
    float core = exp(-r * r / (g.coreWidth * g.coreWidth) * 2.0);
    float body = pow(1.0 - r, g.softness);
    vec3 q = vec3(trail.z * g.tiling.x - time * g.flow * g.tiling.x, trail.y * g.tiling.y, seed * 9.0);
    float n = 0.5 + 0.5 * cg_fbm3(noise, q, 3);
    float streak = pow(cg_ridged3(noise, vec3(q.x * 0.6, q.y * 2.2, seed * 5.0 + time * 0.7), 3), 4.0);
    float e = body * (0.55 + 0.45 * n) + streak * 0.45 * (1.0 - r) - u * u * g.erode;
    float strength = smoothstep(0.0, 0.3, e) * pow(1.0 - u, g.fade) * fx_flicker_volume(noise, time, seed);
    float hot = clamp(core * 1.15 + streak * 0.5 - u * 0.6, 0.0, 1.0);
    glow = (g.core * hot + g.color * body * 0.35) * strength;
    return mix(g.color * (0.65 + 0.55 * n), g.core, hot) * strength;
}
