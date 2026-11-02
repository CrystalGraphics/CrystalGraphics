// CgVfxModule.Occlusion, after the solver: Niagara's Occlusion data interface (NiagaraDataInterfaceOcclusion,
// QueryOcclusionFactorWithCircle and WithRectangle). Samples the scene's depth in a window round the particle, each
// compared with the particle's own eye depth, and keeps the share of the on-screen samples nothing covers as its
// visibility, which Look multiplies its drawn alpha by. Set each step, never multiplied into itself.
// m0: window (0 circle, 1 rectangle), diameter, rings, samples per ring; m1: centre sample, width, height, steps per line.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_depth_at.glsl"

// Whether the scene at window offset (dx, dy) in blocks round p's pixel is behind p; counts the samples on screen.
void fx_occlusion_sample(FxDepth d, vec2 centre, float own, vec2 blocksToPixels, vec2 offset,
                         inout float onScreen, inout float visible) {
    vec2 at = centre + offset * blocksToPixels;
    if (any(lessThan(at, vec2(0.0))) || any(greaterThanEqual(at, _DepthSize.xy))) return;
    onScreen += 1.0;
    if (fx_depth_at(ivec2(at)) >= own) visible += 1.0;
}

void fx_occlusion(inout FxParticle p, FxStep s, vec4 m0, vec4 m1, FxDepth depth) {
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
    if (m0.x < 0.5) {
        float radius = 0.5 * m0.y;
        int rings = max(int(m0.z), 0), perRing = max(int(m0.w), 0);
        if (m1.x > 0.5) fx_occlusion_sample(depth, centre, own, blocksToPixels, vec2(0.0), onScreen, visible);
        for (int r = 0; r < rings; r++) {
            float ring = radius * float(r + 1) / float(rings);
            for (int k = 0; k < perRing; k++) {
                float a = 6.28318530718 * (float(k) + 0.5 * float(r & 1)) / float(perRing);
                fx_occlusion_sample(depth, centre, own, blocksToPixels, ring * vec2(cos(a), sin(a)), onScreen, visible);
            }
        }
    } else {
        int steps = max(int(m1.w), 1);
        vec2 extent = vec2(m1.y, m1.z);
        for (int y = 0; y < steps; y++) {
            for (int x = 0; x < steps; x++) {
                vec2 t = steps == 1 ? vec2(0.5) : vec2(float(x), float(y)) / float(steps - 1);
                fx_occlusion_sample(depth, centre, own, blocksToPixels, (t - 0.5) * extent, onScreen, visible);
            }
        }
    }
    // Niagara's VisibilityFraction: the visible share of the samples on screen, 0 with none on screen.
    p.visibility = onScreen > 0.0 ? visible / onScreen : 0.0;
}
