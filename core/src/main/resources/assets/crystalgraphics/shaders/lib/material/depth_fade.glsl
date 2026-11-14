// Unreal's DepthFade (UMaterialExpressionDepthFade): opacity faded in linearly from where the pixel meets the scene
// behind it, 0, to fadeDistance in front of it, 1. Distances are in blocks.
//
//     #include "crystalgraphics:shaders/lib/material/depth_fade.glsl"
//     s.opacity = cg_depth_fade(texture(_Mask, uv).r, _FadeDistance);
#pragma once

// From the pixel's eye depth and the scene's behind it, in blocks: any stage, any two depths.
float cg_depth_fade(float opacity, float fadeDistance, float sceneDepth, float pixelDepth) {
    return opacity * clamp((sceneDepth - pixelDepth) / max(fadeDistance, 0.00001), 0.0, 1.0);
}

#if !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE)
// In a fragment: this pixel against the scene's depth under it.
float cg_depth_fade(float opacity, float fadeDistance) {
    return cg_depth_fade(opacity, fadeDistance, CG_SCENE_EYE_DEPTH(gl_FragCoord.xy / CG_RESOLUTION),
            cg_LinearEyeDepth(gl_FragCoord.z));
}
#endif
