#pragma once

// Hash functions (Dave Hoskins, shadertoy.com/view/4djSRW).
// Sin-free — cross-platform bit-stable, no GPU sin() precision issues.

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec2 hash22(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}

float hash13(vec3 p) {
    vec3 p3 = fract(p * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// Value noise — Hermite-smooth bilinear interpolation of hashed corners
float value_noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);

    return mix(
        mix(hash12(i + vec2(0.0, 0.0)), hash12(i + vec2(1.0, 0.0)), u.x),
        mix(hash12(i + vec2(0.0, 1.0)), hash12(i + vec2(1.0, 1.0)), u.x),
        u.y
    );
}

// FBM — Fractal Brownian Motion (Inigo Quilez, iquilezles.org/articles/fbm).
// Rotation matrix encodes ~36.87° rotation + x2 scale to break axis-aligned artifacts.

// 4-octave unrolled. Output normalized to [0, 1].
float fbm4(vec2 p) {
    const mat2 m = mat2(0.80, 0.60, -0.60, 0.80);
    float f = 0.0;
    f += 0.5000 * value_noise(p); p = m * p * 2.02;
    f += 0.2500 * value_noise(p); p = m * p * 2.03;
    f += 0.1250 * value_noise(p); p = m * p * 2.01;
    f += 0.0625 * value_noise(p);
    return f / 0.9375;
}

// 6-octave unrolled variant
float fbm6(vec2 p) {
    const mat2 m = mat2(0.80, 0.60, -0.60, 0.80);
    float f = 0.0;
    f += 0.500000 * value_noise(p); p = m * p * 2.02;
    f += 0.250000 * value_noise(p); p = m * p * 2.03;
    f += 0.125000 * value_noise(p); p = m * p * 2.01;
    f += 0.062500 * value_noise(p); p = m * p * 2.04;
    f += 0.031250 * value_noise(p); p = m * p * 2.01;
    f += 0.015625 * value_noise(p);
    return f / 0.984375;
}

// Flexible octave count
float fbm(vec2 p, int octaves) {
    const mat2 m = mat2(1.6, 1.2, -1.2, 1.6);
    float f = 0.0, amp = 0.5, total = 0.0;
    for (int i = 0; i < octaves; i++) {
        f += amp * value_noise(p);
        total += amp;
        p = m * p;
        amp *= 0.5;
    }
    return f / total;
}

// Ridged FBM — sharp ridges (terrain edges, lightning, veins)
float fbm_ridged(vec2 p, int octaves) {
    const mat2 m = mat2(1.6, 1.2, -1.2, 1.6);
    float f = 0.0, amp = 0.5, total = 0.0;
    for (int i = 0; i < octaves; i++) {
        float n = value_noise(p);
        f += amp * (1.0 - abs(2.0 * n - 1.0));
        total += amp;
        p = m * p;
        amp *= 0.5;
    }
    return f / total;
}
