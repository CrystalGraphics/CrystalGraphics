// Noise read from the engine's noise volumes (CgNoiseVolumes): one texel where computed noise hashes eight corners. The
// engine's only noise. Each function takes the volume, which the material declares as a sampler property whose default
// names it, so this file names no uniform and compiles into every stage, kernels included. A library function calling
// it takes the volume as a parameter too: a material's #includes come before its properties are declared.
//
//   Properties {
//       _Noise      ("Noise",       sampler3D) = "cg_noise"         // gradient, about -1..1
//       _ValueNoise ("Value noise", sampler3D) = "cg_value_noise"   // value, 0..1
//       _Voronoi    ("Voronoi",     sampler3D) = "cg_voronoi"       // F1, F2, the cell's hash
//       _VoronoiNearest ("Cells",   sampler3D) = "cg_voronoi_nearest" // F1's gradient, F1
//       _Curl       ("Curl",        sampler3D) = "cg_curl"          // a divergence-free flow
//       _ValueGradient ("Value slope", sampler3D) = "cg_value_gradient" // value noise's gradient, its value
//   }
//   float n = cg_fbm3(_Noise, p, 3);
//
// Each volume's four channels are the same noise with four seeds, so cg_noise4 gives four independent values in one
// fetch, and an fbm's octaves read different channels. Read at level 0, as the procedural noise is: safe in a vertex
// stage, a kernel and a loop that exits early, and no blurrier up close.
#pragma once

// Lattice cells across a volume (CgNoiseVolumes.PERIOD): the volumes repeat every this many units of p.
#define CG_NOISE_PERIOD 16.0

// Rotates each octave so their lattices never line up (fx_common's FX_OCTAVE).
const mat3 CG_NOISE_OCTAVE = mat3(0.00, 0.80, 0.60, -0.80, 0.36, -0.48, -0.60, -0.48, 0.64);

vec4 cg_noise4(sampler3D volume, vec3 p) {
    return textureLod(volume, p / CG_NOISE_PERIOD, 0.0);
}

float cg_noise3(sampler3D volume, vec3 p) {
    return cg_noise4(volume, p).x;
}

// Where time t sits in the volume, for noise driven by time: a unit step a second along a direction of irrational slope
// in y and z, so the line never closes on the tiling volume and the noise never loops. Add it to the other coordinates:
//   cg_noise3(volume, vec3(seed, 0.0, 0.0) + cg_noise_time(t * 20.0))
vec3 cg_noise_time(float t) {
    return t * vec3(0.0, 0.8506508, 0.5257311);
}

// Octave k reads channel k mod 4.
float cg_fbm3(sampler3D volume, vec3 p, int octaves) {
    float sum = 0.0, amplitude = 0.5, norm = 0.0;
    for (int k = 0; k < octaves; k++) {
        sum += amplitude * cg_noise4(volume, p)[k & 3];
        norm += amplitude;
        p = CG_NOISE_OCTAVE * p * 2.03;
        amplitude *= 0.5;
    }
    return sum / norm;
}

// Value noise's fbm with its slope, from "cg_value_gradient": xyz the gradient in p's units, w the value, 0..1. A normal
// for a surface displaced by it: the gradient interpolates smoothly, where differences of a filtered value crease at
// every texel. One seed, the octaves turned and scaled as cg_fbm3's.
vec4 cg_value_fbm_grad3(sampler3D volume, vec3 p, int octaves) {
    vec4 sum = vec4(0.0);
    float amplitude = 0.5, norm = 0.0;
    mat3 chain = mat3(1.0);   // d(octave p) / dp
    for (int k = 0; k < octaves; k++) {
        vec4 s = cg_noise4(volume, p);
        sum += amplitude * vec4(transpose(chain) * s.xyz, s.w);
        norm += amplitude;
        p = CG_NOISE_OCTAVE * p * 2.03;
        chain = CG_NOISE_OCTAVE * chain * 2.03;
        amplitude *= 0.5;
    }
    return sum / norm;
}

// 0..1, sharp crests where the noise crosses zero; gradient noise.
float cg_ridged3(sampler3D volume, vec3 p, int octaves) {
    float sum = 0.0, amplitude = 0.5, norm = 0.0;
    for (int k = 0; k < octaves; k++) {
        float n = 1.0 - abs(cg_noise4(volume, p)[k & 3]);
        sum += amplitude * n * n;
        norm += amplitude;
        p = CG_NOISE_OCTAVE * p * 2.03;
        amplitude *= 0.5;
    }
    return sum / norm;
}

// 0..1, sharp crests; value noise, its octaves 2.11 apart.
float cg_value_ridged3(sampler3D volume, vec3 p, int octaves) {
    float sum = 0.0, amplitude = 0.5, norm = 0.0;
    for (int k = 0; k < octaves; k++) {
        float n = 1.0 - abs(cg_noise4(volume, p)[k & 3] * 2.0 - 1.0);
        sum += amplitude * n * n;
        norm += amplitude;
        p = CG_NOISE_OCTAVE * p * 2.11;
        amplitude *= 0.5;
    }
    return sum / norm;
}

// An fbm whose domain a three-channel fbm bends first.
float cg_warped3(sampler3D volume, vec3 p, float strength) {
    vec3 warp = vec3(0.0);
    float amplitude = 0.5, norm = 0.0;
    vec3 q = p;
    for (int k = 0; k < 3; k++) {
        warp += amplitude * cg_noise4(volume, q).yzw;
        norm += amplitude;
        q = CG_NOISE_OCTAVE * q * 2.03;
        amplitude *= 0.5;
    }
    return cg_fbm3(volume, p + warp / norm * strength, 4);
}

// x the distance to the nearest feature point, y to the second, both in cells; z the nearest cell's hash, 0..1, blended
// across a cell's edge for a texel.
vec3 cg_voronoi3(sampler3D volume, vec3 p) {
    return cg_noise4(volume, p).xyz;
}

// xyz the unit direction from the nearest feature point, the gradient of w, the distance to it in cells: a normal from
// cellular noise without differencing a filtered distance, which steps at every texel. "cg_voronoi_nearest".
vec4 cg_voronoi_nearest4(sampler3D volume, vec3 p) {
    return cg_noise4(volume, p);
}

// The flow at p, divergence-free, about -2..2 a component.
vec3 cg_curl3(sampler3D volume, vec3 p) {
    return cg_noise4(volume, p).xyz;
}
