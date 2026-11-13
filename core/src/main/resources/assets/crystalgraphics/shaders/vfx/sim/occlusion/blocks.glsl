// CgVfxModule.Occlusion from blocks alone (source: blocks), after the solver: its window's samples placed round the
// particle across the camera's view, each traced from the eye through the voxel window's distance field. A sample is
// covered where a solid octant lies between, so walls, floors and terrain hide a particle and no character does (the
// scene's depth holds the entities). Outside the window, or with no level, it is seen. Lanes:
// vfx/sim/occlusion/window.glsl. The depth supplies only the eye and the view's axes.
#pragma once
#include "crystalgraphics:shaders/vfx/sim/data/depth.glsl"
#include "crystalgraphics:shaders/vfx/sim/data/world.glsl"
#include "crystalgraphics:shaders/vfx/sim/occlusion/window.glsl"

// Whether a solid octant lies on the line from 'from' to 'to', stopping a quarter block short of 'to' so the surface a
// particle rests on is not counted. Sphere traced on the distance field, a quarter block at least a step.
bool fx_occlusion_blocked(FxWorld w, vec3 from, vec3 to) {
    vec3 d = to - from;
    float span = sqrt(dot(d, d));
    if (span < 0.25) return false;
    d /= span;
    float t = 0.0;
    for (int i = 0; i < 64 && t < span - 0.25; i++) {
        vec3 q = from + d * t;
        if (fx_world_solid(w, q)) return true;
        vec3 g;
        t += max(fx_world_sdf(w, q, g), 0.25);
    }
    return false;
}

void fx_occlusion_blocks(inout FxParticle p, FxStep s, vec4 m0, vec4 m1, vec4 m2, FxDepth depth, FxWorld world) {
    if (!depth.live || !world.live) {
        p.visibility = 1.0;
        return;
    }
    float covered = 0.0;
    int samples = fx_occlusion_samples(m0, m1, m2);
    for (int i = 0; i < samples; i++) {   // LOOP-BOUND: fx_occlusion_samples, 256 at most
        vec2 o = fx_occlusion_offset(m0, m1, m2, i);
        vec3 at = p.position + _DepthViewX.xyz * o.x + _DepthViewY.xyz * o.y;
        if (fx_occlusion_blocked(world, depth.eye, at)) covered += 1.0;
    }
    p.visibility = samples > 0 ? 1.0 - covered / float(samples) : 1.0;
}
