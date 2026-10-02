// What the vfx shaders share: 3D gradient noise and fbm, erf, a filmic tonemap, and the eye in camera-relative space.
// Prefixed fx_ so it never collides with the showcase's vfx_common.glsl.
#pragma once

// The eye, in the camera-relative space shaders work in: not always the origin, since a host may fold a translation into
// the view. A macro, since the frame block is declared after any included file.
#define FX_CAMERA (-(transpose(mat3(cg_ViewMatrix)) * cg_ViewMatrix[3].xyz))

vec3 fx_hash33(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return -1.0 + 2.0 * fract((p.xxy + p.yxx) * p.zyx);
}

// Gradient noise, about -1..1, smooth to the second derivative.
float fx_noise(vec3 p) {
    vec3 i = floor(p), f = fract(p);
    vec3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float n000 = dot(fx_hash33(i), f);
    float n100 = dot(fx_hash33(i + vec3(1.0, 0.0, 0.0)), f - vec3(1.0, 0.0, 0.0));
    float n010 = dot(fx_hash33(i + vec3(0.0, 1.0, 0.0)), f - vec3(0.0, 1.0, 0.0));
    float n110 = dot(fx_hash33(i + vec3(1.0, 1.0, 0.0)), f - vec3(1.0, 1.0, 0.0));
    float n001 = dot(fx_hash33(i + vec3(0.0, 0.0, 1.0)), f - vec3(0.0, 0.0, 1.0));
    float n101 = dot(fx_hash33(i + vec3(1.0, 0.0, 1.0)), f - vec3(1.0, 0.0, 1.0));
    float n011 = dot(fx_hash33(i + vec3(0.0, 1.0, 1.0)), f - vec3(0.0, 1.0, 1.0));
    float n111 = dot(fx_hash33(i + vec3(1.0)), f - vec3(1.0));
    return 1.6 * mix(mix(mix(n000, n100, u.x), mix(n010, n110, u.x), u.y),
                     mix(mix(n001, n101, u.x), mix(n011, n111, u.x), u.y), u.z);
}

// Rotates each octave so their lattices never line up.
const mat3 FX_OCTAVE = mat3(0.00, 0.80, 0.60, -0.80, 0.36, -0.48, -0.60, -0.48, 0.64);

// Fractal sum of fx_noise, about -1..1.
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

// erf, to 0.0004 (Vedder's tanh form).
float fx_erf(float x) {
    x = clamp(x, -4.0, 4.0);
    return tanh(x * (1.1283792 + 0.1009 * x * x));
}

// Narkowicz's ACES fit.
vec3 fx_aces(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}
