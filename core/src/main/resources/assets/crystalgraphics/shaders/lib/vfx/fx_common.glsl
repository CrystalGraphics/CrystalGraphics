// What every effect shader shares, the vfx engine's and the showcase's alike: the eye, hashes, two kinds of 3D noise and
// their fractals, cellular noise, erf and a filmic curve. Pure functions: nothing here reads a derivative, so it
// compiles into both stages.
//
//   fx_noise / fx_fbm / fx_ridged / fx_warped   gradient noise, about -1..1: smooth, no lattice showing; flowing energy
//   fx_value_noise / _fbm / _ridged             value noise, 0..1: blotchier, cheaper; surfaces and patterns
#pragma once

// The eye, in the space shaders work in: not always the origin, since a host may fold a translation into the view. A
// macro, since a material's own #includes come before cg_env.glsl declares the frame block: it may only be expanded
// inside vertex() or fragment().
#define FX_CAMERA (-(transpose(mat3(cg_ViewMatrix)) * cg_ViewMatrix[3].xyz))

// ── Hashes (Dave Hoskins, sin-free), 0..1 ──────────────────────────────────────────────────────────────────────

float fx_hash31(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

vec3 fx_hash33(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.xxy + p.yxx) * p.zyx);
}

// Rotates each octave so their lattices never line up.
const mat3 FX_OCTAVE = mat3(0.00, 0.80, 0.60, -0.80, 0.36, -0.48, -0.60, -0.48, 0.64);

// ── Gradient noise, about -1..1 ────────────────────────────────────────────────────────────────────────────────

float fx_corner(vec3 i, vec3 f, vec3 corner) {
    return dot(fx_hash33(i + corner) * 2.0 - 1.0, f - corner);
}

// Smooth to the second derivative.
float fx_noise(vec3 p) {
    vec3 i = floor(p), f = fract(p);
    vec3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    return 1.6 * mix(mix(mix(fx_corner(i, f, vec3(0.0, 0.0, 0.0)), fx_corner(i, f, vec3(1.0, 0.0, 0.0)), u.x),
                         mix(fx_corner(i, f, vec3(0.0, 1.0, 0.0)), fx_corner(i, f, vec3(1.0, 1.0, 0.0)), u.x), u.y),
                     mix(mix(fx_corner(i, f, vec3(0.0, 0.0, 1.0)), fx_corner(i, f, vec3(1.0, 0.0, 1.0)), u.x),
                         mix(fx_corner(i, f, vec3(0.0, 1.0, 1.0)), fx_corner(i, f, vec3(1.0, 1.0, 1.0)), u.x), u.y), u.z);
}

float fx_fbm(vec3 p, int octaves) {
    float sum = 0.0, amplitude = 0.5, norm = 0.0;
    for (int k = 0; k < octaves; k++) {
        sum += amplitude * fx_noise(p);
        norm += amplitude;
        p = FX_OCTAVE * p * 2.03;
        amplitude *= 0.5;
    }
    return sum / norm;
}

// 0..1, sharp crests where the noise crosses zero: filaments, cracks, flame tongues.
float fx_ridged(vec3 p, int octaves) {
    float sum = 0.0, amplitude = 0.5, norm = 0.0;
    for (int k = 0; k < octaves; k++) {
        float n = 1.0 - abs(fx_noise(p));
        sum += amplitude * n * n;
        norm += amplitude;
        p = FX_OCTAVE * p * 2.03;
        amplitude *= 0.5;
    }
    return sum / norm;
}

// fbm of p pushed around by three more fbm fields: roiling, folding turbulence rather than soft blobs.
float fx_warped(vec3 p, float strength) {
    vec3 warp = vec3(fx_fbm(p + vec3(1.7, 9.2, 3.4), 3), fx_fbm(p + vec3(8.3, 2.8, 5.1), 3), fx_fbm(p + vec3(4.1, 6.6, 1.2), 3));
    return fx_fbm(p + warp * strength, 4);
}

// A brightness about 1, jittering many times a second and never the same twice.
float fx_flicker(float time, float seed) {
    float fast = fx_noise(vec3(time * 19.0, seed * 31.0, 0.5));
    float slow = fx_noise(vec3(time * 4.3, seed * 17.0, 7.5));
    return 1.0 + 0.22 * fast + 0.12 * slow;
}

// ── Value noise, 0..1 ──────────────────────────────────────────────────────────────────────────────────────────

float fx_value_noise(vec3 p) {
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

float fx_value_fbm(vec3 p, int octaves) {
    float sum = 0.0, amplitude = 0.5, norm = 0.0;
    for (int k = 0; k < octaves; k++) {
        sum += amplitude * fx_value_noise(p);
        norm += amplitude;
        p = FX_OCTAVE * p * 2.03;
        amplitude *= 0.5;
    }
    return sum / norm;
}

float fx_value_ridged(vec3 p, int octaves) {
    float sum = 0.0, amplitude = 0.5, norm = 0.0;
    for (int k = 0; k < octaves; k++) {
        float n = 1.0 - abs(fx_value_noise(p) * 2.0 - 1.0);
        sum += amplitude * n * n;
        norm += amplitude;
        p = FX_OCTAVE * p * 2.11;
        amplitude *= 0.5;
    }
    return sum / norm;
}

// ── Cellular noise ─────────────────────────────────────────────────────────────────────────────────────────────

// The nearest and second-nearest feature distances, and the nearest cell's id in [0, 1).
vec3 fx_voronoi(vec3 p) {
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

// fx_voronoi, also giving the offset from p to its nearest feature point: what an analytic gradient of anything built
// on the nearest distance needs, since that distance changes along -nearest / f1.
vec3 fx_voronoi(vec3 p, out vec3 nearest) {
    vec3 cell = floor(p), f = fract(p);
    float f1 = 8.0, f2 = 8.0, id = 0.0;
    nearest = vec3(0.0);
    for (int z = -1; z <= 1; z++) {
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                vec3 offset = vec3(float(x), float(y), float(z));
                vec3 to = offset + fx_hash33(cell + offset) - f;
                float d = length(to);
                if (d < f1) {
                    f2 = f1;
                    f1 = d;
                    id = fx_hash31(cell + offset);
                    nearest = to;
                } else if (d < f2) {
                    f2 = d;
                }
            }
        }
    }
    return vec3(f1, f2, id);
}

// ── Light and colour ───────────────────────────────────────────────────────────────────────────────────────────

// erf, to 0.0004 (Vedder's tanh form).
float fx_erf(float x) {
    x = clamp(x, -4.0, 4.0);
    return tanh(x * (1.1283792 + 0.1009 * x * x));
}

// Narkowicz's fit of the ACES filmic curve: HDR in, display out, highlights rolling off rather than clipping.
vec3 fx_aces(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}
