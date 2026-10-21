// Unreal's DepthFade (UMaterialExpressionDepthFade): opacity faded in linearly from where the pixel meets the scene
// behind it, 0, to fadeDistance in front of it, 1. Depths are eye depths in blocks.
#pragma once

float cg_depth_fade(float opacity, float fadeDistance, float sceneDepth, float pixelDepth) {
    return opacity * clamp((sceneDepth - pixelDepth) / max(fadeDistance, 0.00001), 0.0, 1.0);
}

// A material's fragment stage: the scene's depth at screenUv against this fragment's.
#if defined(CG_SCENE_EYE_DEPTH) && !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE)
float cg_depth_fade(float opacity, float fadeDistance, vec2 screenUv) {
    return cg_depth_fade(opacity, fadeDistance, CG_SCENE_EYE_DEPTH(screenUv), cg_LinearEyeDepth(gl_FragCoord.z));
}
#endif
