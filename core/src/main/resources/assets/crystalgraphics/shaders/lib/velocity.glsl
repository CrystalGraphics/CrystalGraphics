// Velocity: where a pixel's surface was last frame, as an offset in normalized device coordinates (this frame's less
// last frame's, -2 to 2). The velocity pass writes it for what moved or deformed (CgEnginePasses.VELOCITY) into an
// RG16 unsigned normalized target cleared to 0, which means "nothing wrote here": Unreal's EncodeVelocityToTexture biases every written
// value away from 0. Where nothing wrote, the surface stood still and the camera's own motion is the pixel's, derived
// from its depth and last frame's view, as Unreal's temporal AA derives it rather than drawing it.
//     vec2 v = cg_Velocity(_Velocity, uv, textureLod(cg_DepthBuffer, uv, 0.0).r);   // temporal AA, motion blur
#pragma once

// A velocity as the target holds it: never 0 for a written one.
vec2 cg_EncodeVelocity(vec2 v) {
    return v * (0.499 * 0.5) + 32767.0 / 65535.0;
}

vec2 cg_DecodeVelocity(vec2 encoded) {
    return (encoded - 32767.0 / 65535.0) / (0.499 * 0.5);
}

// Whether the velocity pass wrote at texel at or beside it: with every engine solid writing
// (CgSceneTextures.requestVelocityWithStill), whether the engine drew there, its edges' outer texels included.
bool cg_EngineDrew(sampler2D velocity, ivec2 at) {
    ivec2 last = textureSize(velocity, 0) - 1;
    for (int k = 0; k < 9; k++) {   // LOOP-BOUND: the 3x3 round the texel
        if (texelFetch(velocity, clamp(at + ivec2(k % 3 - 1, k / 3 - 1), ivec2(0), last), 0).x > 0.0) return true;
    }
    return false;
}

// Fragment stage: they read the scene's depth as a world point (cg_DepthToWorld)
#if !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE)
// The camera's own motion at uv (0 to 1, bottom left) for window depth windowDepth: the point reconstructed in this
// pass's camera-relative world, projected by last frame's view (cg_PrevViewProj). 0 where there is no history.
vec2 cg_CameraVelocity(vec2 uv, float windowDepth) {
    if (!CG_HISTORY_VALID) return vec2(0.0);
    vec4 previous = cg_PreviousClip(cg_DepthToWorld(uv, windowDepth));
    return uv * 2.0 - 1.0 - previous.xy / previous.w;
}

// The pixel's velocity: what the velocity pass wrote there, else the camera's motion over its depth.
vec2 cg_Velocity(sampler2D velocity, vec2 uv, float windowDepth) {
    vec2 written = textureLod(velocity, uv, 0.0).xy;
    return written.x > 0.0 ? cg_DecodeVelocity(written) : cg_CameraVelocity(uv, windowDepth);
}
#endif
