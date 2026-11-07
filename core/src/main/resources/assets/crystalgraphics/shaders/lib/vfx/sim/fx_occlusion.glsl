// CgVfxModule.Occlusion from the scene's depth, after the solver: Niagara's Occlusion data interface
// (NiagaraDataInterfaceOcclusion, QueryOcclusionFactorWithCircle and WithRectangle). Each sample of its window is the
// scene's depth there against the particle's own; the visible share of the samples on screen is its visibility, which
// Look multiplies its drawn alpha by. Set each step, never multiplied into itself. Lanes: fx_occlusion_window.glsl.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_depth_at.glsl"
#include "crystalgraphics:shaders/lib/vfx/sim/fx_occlusion_window.glsl"

void fx_occlusion(inout FxParticle p, FxStep s, vec4 m0, vec4 m1, vec4 m2, FxDepth depth) {
    ivec2 pixel;
    float own = fx_depth_project(depth, p.position, pixel);
    // No depth, or off screen: nothing hides it here, as every depth reader answers.
    if (own == FX_DEPTH_NONE) {
        p.visibility = 1.0;
        return;
    }
    // A block across at its depth, in pixels: the projection's scale over the eye depth (fx_depth_surface's inverse).
    vec2 blocksToPixels = _DepthLens.xy / own * 0.5 * _DepthSize.xy;
    vec2 centre = vec2(pixel) + 0.5;
    float onScreen = 0.0, visible = 0.0;
    int samples = fx_occlusion_samples(m0, m1, m2);
    for (int i = 0; i < samples; i++) {   // LOOP-BOUND: fx_occlusion_samples, 256 at most
        vec2 at = centre + fx_occlusion_offset(m0, m1, m2, i) * blocksToPixels;
        if (any(lessThan(at, vec2(0.0))) || any(greaterThanEqual(at, _DepthSize.xy))) continue;
        onScreen += 1.0;
        if (fx_depth_at(ivec2(at)) >= own) visible += 1.0;
    }
    // Niagara's VisibilityFraction: the visible share of the samples on screen, 0 with none on screen.
    p.visibility = onScreen > 0.0 ? visible / onScreen : 0.0;
}
