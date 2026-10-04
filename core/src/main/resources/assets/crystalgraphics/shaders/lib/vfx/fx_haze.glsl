// What a haze that must read at a distance bends with: a bend set in screen height and held to a share of the haze's
// own size on screen, a direction as it runs on screen, and the scene sampled through the bend with a colour split.
#pragma once

#if !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE)
// The share of a bend of strength (a share of the screen's height) kept for a haze radius blocks across at dist blocks:
// all of it until it would pass hold of the haze's height on screen. focal is the projection's [1][1].
float fx_haze_hold(float strength, float radius, float dist, float focal, float hold) {
    float onScreen = radius * focal / max(2.0 * dist, 1.0e-3);
    return min(1.0, hold * onScreen / max(strength, 1.0e-5));
}

// A view-space direction as it runs on screen: unit length, shortening to nothing as it turns toward the view.
vec2 fx_haze_screen_dir(vec3 view) {
    return view.xy / max(length(view.xy), 0.3);
}

// Macros, since the frame block and cg_SceneColor are declared after any included file.
#define FX_HAZE_HOLD(strength, radius, dist, hold) fx_haze_hold(strength, radius, dist, cg_ProjMatrix[1][1], hold)
#define FX_HAZE_SCREEN_DIR(world) fx_haze_screen_dir(mat3(cg_ViewMatrix) * (world))
// The scene at uv bent by offset, its red bent fringe more and its blue as much less.
#define FX_HAZE_SCENE(uv, offset, fringe) vec3(CG_SCENE_COLOR((uv) + (offset) * (1.0 + (fringe))).r, CG_SCENE_COLOR((uv) + (offset)).g, CG_SCENE_COLOR((uv) + (offset) * (1.0 - (fringe))).b)
#endif
