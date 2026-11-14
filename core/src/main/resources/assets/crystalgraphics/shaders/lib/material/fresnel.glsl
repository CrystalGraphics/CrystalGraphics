// Unreal's Fresnel (UMaterialExpressionFresnel, MaterialExpressions.cpp:8668-8685):
// pow(abs(1 - max(0, N.V)), exponent) * (1 - baseReflectFraction) + baseReflectFraction, the power clamped positive
// as PositiveClampedPow is (Common.ush: pow(max(x, 0), y)). N.V is the normal against the camera vector, toward the eye.
//
//     #include "crystalgraphics:shaders/lib/material/fresnel.glsl"
//     s.emissive = _Rim.rgb * cg_fresnel(i.normal, _Exponent, 0.04);     // the pixel's own camera vector
//
// Unreal's defaults: exponent 3, baseReflectFraction 0.04. `normal` is world space; pass it normalized.
#pragma once

// Any stage: both vectors given. `cameraVector` points from the surface toward the eye.
float cg_fresnel(vec3 normal, vec3 cameraVector, float exponent, float baseReflectFraction) {
    float rim = pow(max(abs(1.0 - max(0.0, dot(normal, cameraVector))), 0.0), exponent);
    return rim * (1.0 - baseReflectFraction) + baseReflectFraction;
}

#if !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE)
// In a fragment: against this pixel's camera vector.
float cg_fresnel(vec3 normal, float exponent, float baseReflectFraction) {
    return cg_fresnel(normal, -cg_PixelRay(), exponent, baseReflectFraction);
}
#endif
