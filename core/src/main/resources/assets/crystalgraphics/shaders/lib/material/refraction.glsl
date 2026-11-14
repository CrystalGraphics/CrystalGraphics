// Unreal's refraction, as a refracting material's distortion pass works it out (DistortAccumulatePS.usf:52-100,
// DistortionRendering.cpp:365-369): the view-space normal's xy times (refraction - 1), scaled by the screen's size, the
// projection's focal lengths and Unreal's 0.00023 offset factor, then faded in by how far the scene lies behind the
// pixel: nothing where the scene is no farther than the bias, all of it where it is a range (half the bias, 1 to 50)
// farther.
//
//     #include "crystalgraphics:shaders/lib/material/refraction.glsl"
//     s.refraction = cg_refraction(worldNormal, _Refraction, _RefractionBias, 100.0);   // 100: Unreal's units a block
//
// Fragment only. `refraction` is Unreal's Refraction input (1 bends nothing), `refractionBias` its bias in Unreal's
// units, `unitsPerBlock` how many of those a block holds (Unreal's centimetres: 100). A bend too small to see (Unreal
// clips it) is (0, 0). Meant for `s.refraction`, which the engine's distortion pass applies.
#pragma once

#if !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE)
vec2 cg_refraction(vec3 worldNormal, float refraction, float refractionBias, float unitsPerBlock) {
    vec3 viewNormal = normalize(mat3(cg_ViewMatrix) * worldNormal);
    vec2 raw = viewNormal.xy * refraction;
    if (dot(raw, raw) < 1e-5) return vec2(0.0);
    vec2 screen = gl_FragCoord.xy / CG_RESOLUTION;
    vec2 bend = raw * CG_RESOLUTION * 0.00023 * vec2(cg_ProjMatrix[0][0], cg_ProjMatrix[1][1]);
    float pixel = cg_LinearEyeDepth(gl_FragCoord.z);
    float behind = (CG_SCENE_EYE_DEPTH(screen + bend) - pixel) * unitsPerBlock;
    float range = max(1.0, min(50.0, abs(0.5 * refractionBias)));
    return bend * clamp(behind / range - refractionBias, 0.0, 1.0);
}
#endif
