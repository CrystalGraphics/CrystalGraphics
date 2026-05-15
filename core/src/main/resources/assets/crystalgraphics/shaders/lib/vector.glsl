#pragma once
#include "crystalgraphics:shaders/lib/math.glsl"

float length2(vec2 v) { return dot(v, v); }
float length2(vec3 v) { return dot(v, v); }
float length2(vec4 v) { return dot(v, v); }

// Returns zero-vector instead of NaN when input is zero. Matches Unity URP SafeNormalize.
vec2 safe_normalize(vec2 v) { return v * inversesqrt(max(dot(v, v), CG_FLT_MIN)); }
vec3 safe_normalize(vec3 v) { return v * inversesqrt(max(dot(v, v), CG_FLT_MIN)); }
vec4 safe_normalize(vec4 v) { return v * inversesqrt(max(dot(v, v), CG_FLT_MIN)); }

// Gram-Schmidt: projects tangent onto normal's plane. Assumes normal is already normalized.
vec3 orthonormalize(vec3 tangent, vec3 normal) {
    return normalize(tangent - dot(tangent, normal) * normal);
}

// Schlick Fresnel approximation. N and V must be normalized. power >= 1.
float fresnel(vec3 N, vec3 V, float power) {
    return positive_pow(1.0 - saturate(dot(N, V)), power);
}

// Rodrigues' rotation formula. Rotates v around axis (normalized) by angleRad.
// Equivalent to Unity Shader Graph "Rotate About Axis" node.
vec3 rotate_axis(vec3 v, vec3 axis, float angleRad) {
    float c = cos(angleRad);
    float s = sin(angleRad);
    return v * c + cross(axis, v) * s + axis * dot(axis, v) * (1.0 - c);
}

vec3 project_onto(vec3 v, vec3 onto) { return onto * (dot(v, onto) / dot(onto, onto)); }
vec3 reject_from(vec3 v, vec3 from) { return v - project_onto(v, from); }

float angle_between(vec3 a, vec3 b) {
    return acos(clamp(dot(normalize(a), normalize(b)), -1.0, 1.0));
}
