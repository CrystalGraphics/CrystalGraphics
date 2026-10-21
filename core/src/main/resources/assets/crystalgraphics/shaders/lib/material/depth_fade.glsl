// Unreal's DepthFade (UMaterialExpressionDepthFade): opacity faded in linearly from where the pixel meets the scene
// behind it, 0, to fadeDistance in front of it, 1. Depths are eye depths in blocks.
#pragma once

float cg_depth_fade(float opacity, float fadeDistance, float sceneDepth, float pixelDepth) {
    return opacity * clamp((sceneDepth - pixelDepth) / max(fadeDistance, 0.00001), 0.0, 1.0);
}

// In a material's fragment stage: the scene's depth at screenUv against this fragment's. A macro, so it expands where it
// is called: a material's includes come before cg_env.glsl, whose depth reads a function would not see.
#if !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE)
#define CG_DEPTH_FADE(opacity, fadeDistance, screenUv) cg_depth_fade(opacity, fadeDistance, CG_SCENE_EYE_DEPTH(screenUv), cg_LinearEyeDepth(gl_FragCoord.z))
#endif
