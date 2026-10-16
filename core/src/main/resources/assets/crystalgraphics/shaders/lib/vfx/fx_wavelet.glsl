// Wavelet noise (Cook and DeRose 2005) as Mantaflow evaluates it (Apache-2.0, source/noisefield.h, WNoise): its tile
// (CgWaveletNoise, "cg_wavelet_noise") read under a quadratic B-spline. Mantaflow sums 27 lattice values; here the
// spline's three taps an axis are two filtered reads (the first two folded into one, as Sigg and Hadwiger fold a cubic),
// eight in all, the same sum. Band-limited: an octave holds detail at about two lattice units, none finer or coarser.
//
//     float n = fx_wavelet(_Wavelet, p);                    // p in lattice units; about -1..1, mean 0
#pragma once

// Its tile's side, Mantaflow's NOISE_TILE_SIZE.
#define FX_WAVELET_TILE 128.0

float fx_wavelet(sampler3D tile, vec3 p) {
    vec3 mid = ceil(p - 0.5);
    vec3 t = mid - (p - 0.5);
    vec3 below = t * t * 0.5, above = (1.0 - t) * (1.0 - t) * 0.5, centre = 1.0 - below - above;
    vec3 first = below + centre;
    // Lattice value k sits at texel k's centre, (k + 0.5) / 128; the tile repeats.
    vec3 a = (mid - 1.0 + centre / max(first, 1.0e-6) + 0.5) / FX_WAVELET_TILE;
    vec3 b = (mid + 1.0 + 0.5) / FX_WAVELET_TILE;
    float sum = 0.0;
    for (int k = 0; k < 8; k++) {
        bvec3 high = bvec3((k & 1) != 0, (k & 2) != 0, (k & 4) != 0);
        vec3 at = mix(a, b, vec3(high));
        vec3 w = mix(first, above, vec3(high));
        sum += textureLod(tile, at, 0.0).x * (w.x * w.y * w.z);
    }
    return sum;
}
