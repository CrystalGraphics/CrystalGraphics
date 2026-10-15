// Marching a sparse gas (CgVfxGas, CgVfxGasAtlas), read through fx_sparse.glsl: the span of a ray through a box, how smoke scatters light, the light of what burns in it, and the
// detail finer than its cells. Every value is an argument; the volumes are passed in, as noise_volume.glsl's are.
//
//     vec3 c = (p - gridLo) / cell;                                             // the gas's grid cells
//     int slot = fx_sparse_slot(_GasPages, gas, c);                                // -1: no tile, still air
//     vec3 uvw = fx_sparse_pool_uvw(_GasShade, slot, c);
//     vec4 s = fx_sparse_smooth(_GasShade, slot, c);                               // density, flame, sun, sky
//     vec4 f = textureLod(_GasFlow, uvw, 0.0);                                  // velocity, temperature
//     float lit = fx_gas_phase(dot(ray, sun), 0.6) * s.z;
//     float d = fx_gas_carried_detail(_Noise, _Voronoi, p / 1.2, f.xyz / 1.2, age, 0.4);
//     float density = fx_gas_erode(s.x, d, 0.5);
//     vec3 fire = fx_gas_emission(300.0 + 600.0 * f.w);
#pragma once
#include "crystalgraphics:shaders/lib/noise_volume.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_sparse.glsl"

// Where a ray from o along unit d enters and leaves the box lo to hi; y below x for a miss.
vec2 fx_gas_box(vec3 o, vec3 d, vec3 lo, vec3 hi) {
    vec3 inv = 1.0 / d;
    vec3 a = (lo - o) * inv, b = (hi - o) * inv;
    vec3 near = min(a, b), far = max(a, b);
    return vec2(max(max(near.x, near.y), near.z), min(min(far.x, far.y), far.z));
}

// Henyey-Greenstein's phase for the cosine between the ray and the light: g above 0 forward, below back.
float fx_hg(float cosine, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (12.5663706 * pow(max(1.0 + g2 - 2.0 * g * cosine, 1.0e-4), 1.5));
}

// Smoke's phase, a strong forward lobe and a weak back one (Frostbite's and Horizon's two lobes), times 4 pi so light
// scattered evenly is 1: the silver edge toward the sun, and a face lit from behind.
float fx_gas_phase(float cosine, float g) {
    return mix(fx_hg(cosine, g), fx_hg(cosine, -0.3), 0.25) * 12.5663706;
}

// Horizon Zero Dawn's powder (Schneider 2015): a thin edge scatters less light toward the eye than a deep one, except
// looking toward the sun, where its silver lining is. sigma is the extinction a block where the step is.
float fx_gas_powder(float sigma, float cosine) {
    return mix(1.0 - exp(-2.0 * sigma), 1.0, 0.5 + 0.5 * cosine);
}

// A blackbody's colour at kelvin, linear, its brightest channel 1: Planck's law at 610, 550 and 465 nm.
vec3 fx_blackbody(float kelvin) {
    vec3 l = vec3(0.61, 0.55, 0.465);
    vec3 b = 1.0 / (l * l * l * l * l * (exp(14388.0 / (l * max(kelvin, 500.0))) - 1.0));
    return b / max(b.r, max(b.g, b.b));
}

// The light of what burns at kelvin, linear: its colour, as bright as Stefan-Boltzmann's fourth power from 1800 K
// (so 1 there), held at 3500 K, and nothing below a dull red.
vec3 fx_gas_emission(float kelvin) {
    float k = min(kelvin, 3500.0);
    float b = k / 1800.0;
    return fx_blackbody(k) * (b * b * b * b) * smoothstep(750.0, 1100.0, k);
}

// Detail at p (in the noise's units), 0 to 1, high where the gas keeps its body: billowing cells over three octaves of
// grain, the finest a quarter of a unit, so it reaches under the cells (Schneider's Perlin-Worley). The grain is
// gradient noise ("cg_noise"), never value noise, whose lattice shows through as squares.
float fx_gas_detail(sampler3D noise, sampler3D voronoi, vec3 p) {
    float cells = 1.0 - clamp(cg_voronoi3(voronoi, p * 0.7).x, 0.0, 1.0);
    float fine = 0.5 + 0.5 * (cg_noise4(noise, p).x * 0.5 + cg_noise4(noise, p * 2.03 + vec3(3.1, 7.7, 1.3)).y * 0.3
            + cg_noise4(noise, p * 4.11 + vec3(8.3, 2.9, 5.6)).z * 0.2);
    return clamp(cells * 0.6 + fine * 0.55 - 0.1, 0.0, 1.0);
}

// The detail at p (noise units), dragged a short way along the gas's velocity (noise units a second) and evolving in
// time: it rides the flow, never more than reach units off. A flow map's drift (Vlachos 2010) of a whole period
// stretched it into streaks wherever the flow shears, as up a fast plume.
float fx_gas_carried_detail(sampler3D noise, sampler3D voronoi, vec3 p, vec3 velocity, float time, float reach) {
    vec3 drag = velocity * 0.15;
    float len = length(drag);
    if (len > reach) drag *= reach / len;
    return fx_gas_detail(noise, voronoi, p - drag + cg_noise_time(time * 0.3));
}

// A density worn away by detail (Schneider's erosion): its coverage less amount times what the detail lacks, eased back
// to 0 to 1 rather than cut, so an edge thins into wisps without hard fragments; a body from about half a unit keeps
// nearly all of itself.
float fx_gas_erode(float density, float detail, float amount) {
    float c = 1.0 - exp(-6.0 * density);
    float e = amount * (1.0 - detail);
    return density * smoothstep(e, 1.0, c) / max(c, 1.0e-4);
}
