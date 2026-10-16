// Detail finer than a volume's cells, carried along its flow, and the erosion it wears an edge by (the grid layers').
// Every value is an argument; the volumes are passed in, as noise_volume.glsl's are.
//
//     float d = fx_detail_carried(_Noise, _Voronoi, p / 1.2, velocity / 1.2, age, 0.4);
//     float density = fx_erode(s.x, d, 0.5);
#pragma once
#include "crystalgraphics:shaders/lib/noise_volume.glsl"

// Detail at p (in the noise's units), 0 to 1, high where the volume keeps its body: billowing cells over three octaves
// of grain, the finest a quarter of a unit (Schneider's Perlin-Worley). The grain is gradient noise ("cg_noise"), never
// value noise, whose lattice shows through as squares.
float fx_detail(sampler3D noise, sampler3D voronoi, vec3 p) {
    float cells = 1.0 - clamp(cg_voronoi3(voronoi, p * 0.7).x, 0.0, 1.0);
    float fine = 0.5 + 0.5 * (cg_noise4(noise, p).x * 0.5 + cg_noise4(noise, p * 2.03 + vec3(3.1, 7.7, 1.3)).y * 0.3
            + cg_noise4(noise, p * 4.11 + vec3(8.3, 2.9, 5.6)).z * 0.2);
    return clamp(cells * 0.6 + fine * 0.55 - 0.1, 0.0, 1.0);
}

// The detail at p (noise units), dragged a short way along the velocity (noise units a second) and evolving in time: it
// rides the flow, never more than reach units off. A flow map's whole-period drift streaks wherever the flow shears.
float fx_detail_carried(sampler3D noise, sampler3D voronoi, vec3 p, vec3 velocity, float time, float reach) {
    vec3 drag = velocity * 0.15;
    float len = length(drag);
    if (len > reach) drag *= reach / len;
    return fx_detail(noise, voronoi, p - drag + cg_noise_time(time * 0.3));
}

// A density worn away by detail (Schneider's erosion): its coverage less amount times what the detail lacks, eased back
// to 0 to 1 rather than cut, so an edge thins into wisps; a body from about half a unit keeps nearly all of itself.
float fx_erode(float density, float detail, float amount) {
    float c = 1.0 - exp(-6.0 * density);
    float e = amount * (1.0 - detail);
    return density * smoothstep(e, 1.0, c) / max(c, 1.0e-4);
}
