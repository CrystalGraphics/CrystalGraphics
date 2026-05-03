#pragma once

#ifndef CG_PI
#define CG_PI      3.14159265358979323846
#define CG_TWO_PI  6.28318530717958647692
#define CG_HALF_PI 1.57079632679489661923
#define CG_INV_PI  0.31830988618379067154
#define CG_SQRT2   1.41421356237309504880
#endif

#ifndef CG_FLT_EPS
#define CG_FLT_EPS 5.960464478e-8
#define CG_FLT_MIN 1.175494351e-38
#define CG_FLT_MAX 3.402823466e+38
#endif

float  saturate(float  x) { return clamp(x, 0.0, 1.0); }
vec2   saturate(vec2   x) { return clamp(x, vec2(0.0), vec2(1.0)); }
vec3   saturate(vec3   x) { return clamp(x, vec3(0.0), vec3(1.0)); }
vec4   saturate(vec4   x) { return clamp(x, vec4(0.0), vec4(1.0)); }

float  remap(float  x, float  in_min, float  in_max, float  out_min, float  out_max) {
    return out_min + (out_max - out_min) * ((x - in_min) / (in_max - in_min));
}
vec2   remap(vec2 x, vec2 in_min, vec2 in_max, vec2 out_min, vec2 out_max) {
    return out_min + (out_max - out_min) * ((x - in_min) / (in_max - in_min));
}
vec3   remap(vec3 x, vec3 in_min, vec3 in_max, vec3 out_min, vec3 out_max) {
    return out_min + (out_max - out_min) * ((x - in_min) / (in_max - in_min));
}
vec4   remap(vec4 x, vec4 in_min, vec4 in_max, vec4 out_min, vec4 out_max) {
    return out_min + (out_max - out_min) * ((x - in_min) / (in_max - in_min));
}

float  remap01(float  x, float  in_min, float  in_max) { return saturate((x - in_min) / (in_max - in_min)); }
vec2   remap01(vec2   x, vec2   in_min, vec2   in_max) { return saturate((x - in_min) / (in_max - in_min)); }
vec3   remap01(vec3   x, vec3   in_min, vec3   in_max) { return saturate((x - in_min) / (in_max - in_min)); }
vec4   remap01(vec4   x, vec4   in_min, vec4   in_max) { return saturate((x - in_min) / (in_max - in_min)); }

float  sq(float  x) { return x * x; }
vec2   sq(vec2   x) { return x * x; }
vec3   sq(vec3   x) { return x * x; }
vec4   sq(vec4   x) { return x * x; }

float  cb(float  x) { return x * x * x; }
vec2   cb(vec2   x) { return x * x * x; }
vec3   cb(vec3   x) { return x * x * x; }
vec4   cb(vec4   x) { return x * x * x; }

// pow(|base|, power) — avoids NaN on negative base. Matches Unity URP PositivePow.
float  positive_pow(float  b, float  p) { return pow(abs(b), p); }
vec2   positive_pow(vec2   b, vec2   p) { return pow(abs(b), p); }
vec3   positive_pow(vec3   b, vec3   p) { return pow(abs(b), p); }
vec4   positive_pow(vec4   b, vec4   p) { return pow(abs(b), p); }

// Clamps base to FLT_EPS so pow(0,0) -> 1. Matches Unity URP SafePositivePow.
float  safe_pow(float  b, float  p) { return pow(max(abs(b), CG_FLT_EPS), p); }
vec2   safe_pow(vec2   b, vec2   p) { return pow(max(abs(b), vec2(CG_FLT_EPS)), p); }
vec3   safe_pow(vec3   b, vec3   p) { return pow(max(abs(b), vec3(CG_FLT_EPS)), p); }
vec4   safe_pow(vec4   b, vec4   p) { return pow(max(abs(b), vec4(CG_FLT_EPS)), p); }

// sign-preserving pow; useful for color-grading curves
float  sign_pow(float  x, float p) { return sign(x) * pow(abs(x), p); }
vec2   sign_pow(vec2   x, float p) { return sign(x) * pow(abs(x), vec2(p)); }
vec3   sign_pow(vec3   x, float p) { return sign(x) * pow(abs(x), vec3(p)); }
vec4   sign_pow(vec4   x, float p) { return sign(x) * pow(abs(x), vec4(p)); }

// Perlin quintic (C² continuous). No derivative discontinuity vs smoothstep.
float smootherstep(float a, float b, float x) {
    float t = saturate((x - a) / (b - a));
    return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
}

float deg_to_rad(float d) { return d * (CG_PI / 180.0); }
float rad_to_deg(float r) { return r * (180.0 / CG_PI); }
