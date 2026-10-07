// CgVfxModule.LimitSpeed, after the solver: Godot's velocity limit, m.x blocks a second, and the step's move redone at
// the limited speed. Lists before any other kind after the solver.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_types.glsl"

void fx_limit_speed(inout FxParticle p, FxStep s, vec4 m) {
    float v = length(p.velocity);
    if (v <= m.x) return;
    p.velocity *= m.x / v;
    p.position = p.previous + p.velocity * s.dt;
}
