// Value and cellular noise computed rather than read from the volumes, for what a volume cannot carry: a normal taken
// by finite differences of the noise, or a feature thinner than a texel, such as the crack between two cells. A volume
// holds four texels a lattice cell, filtered linearly, so such a normal shows each texel as a facet and its filter
// steps as contour lines, and cell borders follow the texel grid. Several times the cost of a volume read: use it for
// those, and the volumes for the rest. The pre-volume fx_common's, unchanged.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"

// Rotates each octave so their lattices never line up.
const mat3 FX_EXACT_OCTAVE = mat3(0.00, 0.80, 0.60, -0.80, 0.36, -0.48, -0.60, -0.48, 0.64);

// 0..1, smooth to the second derivative.
float fx_exact_value_noise(vec3 p) {
    vec3 i = floor(p), f = fract(p);
    vec3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float a = fx_hash31(i);
    float b = fx_hash31(i + vec3(1.0, 0.0, 0.0));
    float c = fx_hash31(i + vec3(0.0, 1.0, 0.0));
    float d = fx_hash31(i + vec3(1.0, 1.0, 0.0));
    float e = fx_hash31(i + vec3(0.0, 0.0, 1.0));
    float g = fx_hash31(i + vec3(1.0, 0.0, 1.0));
    float h = fx_hash31(i + vec3(0.0, 1.0, 1.0));
    float k = fx_hash31(i + vec3(1.0, 1.0, 1.0));
    return mix(mix(mix(a, b, u.x), mix(c, d, u.x), u.y), mix(mix(e, g, u.x), mix(h, k, u.x), u.y), u.z);
}

float fx_exact_value_fbm(vec3 p, int octaves) {
    float sum = 0.0, amplitude = 0.5, norm = 0.0;
    for (int k = 0; k < octaves; k++) {
        sum += amplitude * fx_exact_value_noise(p);
        norm += amplitude;
        p = FX_EXACT_OCTAVE * p * 2.03;
        amplitude *= 0.5;
    }
    return sum / norm;
}

// 0..1, sharp crests where the noise crosses its middle.
float fx_exact_value_ridged(vec3 p, int octaves) {
    float sum = 0.0, amplitude = 0.5, norm = 0.0;
    for (int k = 0; k < octaves; k++) {
        float n = 1.0 - abs(fx_exact_value_noise(p) * 2.0 - 1.0);
        sum += amplitude * n * n;
        norm += amplitude;
        p = FX_EXACT_OCTAVE * p * 2.11;
        amplitude *= 0.5;
    }
    return sum / norm;
}

// The nearest and second-nearest feature distances, and the nearest cell's id in [0, 1).
vec3 fx_exact_voronoi(vec3 p) {
    vec3 cell = floor(p), f = fract(p);
    float f1 = 8.0, f2 = 8.0, id = 0.0;
    for (int z = -1; z <= 1; z++) {
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                vec3 offset = vec3(float(x), float(y), float(z));
                float d = length(offset + fx_hash33(cell + offset) - f);
                if (d < f1) {
                    f2 = f1;
                    f1 = d;
                    id = fx_hash31(cell + offset);
                } else if (d < f2) {
                    f2 = d;
                }
            }
        }
    }
    return vec3(f1, f2, id);
}
