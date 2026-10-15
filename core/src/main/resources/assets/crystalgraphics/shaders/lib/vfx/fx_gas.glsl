// Reading and marching a sparse gas (CgVfxGas, CgVfxGasAtlas): which pool slot holds a cell, where a cell is read in
// the atlas's pools, the span of a ray through a box, how smoke scatters light, the light of what burns in it, and the
// detail finer than its cells. Every value is an argument; the volumes are passed in, as noise_volume.glsl's are.
//
//     vec3 c = (p - gridLo) / cell;                                             // the gas's grid cells
//     int slot = fx_gas_slot(_GasPages, gas, c);                                // -1: no tile, still air
//     vec3 uvw = fx_gas_pool_uvw(_GasShade, slot, c);
//     vec4 s = fx_gas_smooth(_GasShade, slot, c);                               // density, flame, sun, sky
//     vec4 f = textureLod(_GasFlow, uvw, 0.0);                                  // velocity, temperature
//     float lit = fx_gas_phase(dot(ray, sun), 0.6) * s.z;
//     float d = fx_gas_carried_detail(_Noise, _Voronoi, p / 1.2, f.xyz / 1.2, age, 0.4);
//     float density = fx_gas_erode(s.x, d, 0.5);
//     vec3 fire = fx_gas_emission(300.0 + 600.0 * f.w);
#pragma once
#include "crystalgraphics:shaders/lib/noise_volume.glsl"

// CgVfxGasAtlas's layout: cells a tile side, tiles a page table side, cells a grid side, pool tiles a layer side, a
// tile's side in the apron'd pools, and the apron.
#define FX_GAS_TILE 16
#define FX_GAS_PAGES 32
#define FX_GAS_SPAN 512
#define FX_GAS_LAYER 8
#define FX_GAS_SHADE_TILE 20
#define FX_GAS_APRON 2

// The tile holding cell c (the gas's grid cells), in tiles.
ivec3 fx_gas_tile(vec3 c) {
    return ivec3(floor(c / float(FX_GAS_TILE)));
}

// The pool slot of gas g's tile t: -1 where it has no tile, and past its grid.
int fx_gas_slot_of(sampler3D pages, int g, ivec3 t) {
    if (any(lessThan(t, ivec3(0))) || any(greaterThanEqual(t, ivec3(FX_GAS_PAGES)))) return -1;
    return int(texelFetch(pages, ivec3(t.x, t.y, t.z + FX_GAS_PAGES * g), 0).x + 0.5) - 1;
}

int fx_gas_slot(sampler3D pages, int g, vec3 c) {
    return fx_gas_slot_of(pages, g, fx_gas_tile(c));
}

// Where cell c of slot s's tile is in an apron'd pool (shade, flow), in texels.
vec3 fx_gas_pool_texel(int s, vec3 c) {
    ivec3 p = ivec3(s % FX_GAS_LAYER, (s / FX_GAS_LAYER) % FX_GAS_LAYER, s / (FX_GAS_LAYER * FX_GAS_LAYER));
    return vec3(p * FX_GAS_SHADE_TILE + FX_GAS_APRON) + c - vec3(fx_gas_tile(c) * FX_GAS_TILE);
}

// Where cell c of slot s's tile is read, filtered, in an apron'd pool: never its neighbour's texels.
vec3 fx_gas_pool_uvw(sampler3D pool, int s, vec3 c) {
    return fx_gas_pool_texel(s, c) / vec3(textureSize(pool, 0));
}

// The pool's value at cell c of slot s's tile under a cubic B-spline, from eight filtered reads (Sigg and Hadwiger,
// GPU Gems 2 ch. 20): smooth across cell faces, where a trilinear read bends and shows the grid. Its reach is the
// two cells of apron.
vec4 fx_gas_smooth(sampler3D pool, int s, vec3 c) {
    vec3 size = vec3(textureSize(pool, 0));
    vec3 x = fx_gas_pool_texel(s, c) - 0.5;
    vec3 i = floor(x), f = x - i, f2 = f * f, f3 = f2 * f;
    vec3 w0 = (1.0 - 3.0 * f + 3.0 * f2 - f3) / 6.0;
    vec3 w1 = (4.0 - 6.0 * f2 + 3.0 * f3) / 6.0;
    vec3 w3 = f3 / 6.0;
    vec3 g1 = 1.0 - w0 - w1;
    vec3 h0 = (i - 0.5 + w1 / (w0 + w1)) / size, h1 = (i + 1.5 + w3 / max(g1, 1.0e-6)) / size;
    vec4 a = mix(textureLod(pool, h0, 0.0), textureLod(pool, vec3(h1.x, h0.y, h0.z), 0.0), g1.x);
    vec4 b = mix(textureLod(pool, vec3(h0.x, h1.y, h0.z), 0.0), textureLod(pool, vec3(h1.x, h1.y, h0.z), 0.0), g1.x);
    vec4 d = mix(textureLod(pool, vec3(h0.x, h0.y, h1.z), 0.0), textureLod(pool, vec3(h1.x, h0.y, h1.z), 0.0), g1.x);
    vec4 e = mix(textureLod(pool, vec3(h0.x, h1.y, h1.z), 0.0), textureLod(pool, h1, 0.0), g1.x);
    return mix(mix(a, b, g1.y), mix(d, e, g1.y), g1.z);
}

// A pool's value at cell c of gas g, filtered; 0 where it has no tile.
vec4 fx_gas_read(sampler3D pool, sampler3D pages, int g, vec3 c) {
    int s = fx_gas_slot(pages, g, c);
    return s < 0 ? vec4(0.0) : textureLod(pool, fx_gas_pool_uvw(pool, s, c), 0.0);
}

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
