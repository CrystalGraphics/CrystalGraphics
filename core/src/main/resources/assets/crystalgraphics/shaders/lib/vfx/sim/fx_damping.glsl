// CgVfxModule.Damping: Godot's damping, a constant deceleration of m.x blocks a second squared down to a stop.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_types.glsl"

void fx_damping(inout FxParticle p, inout FxForces f, FxStep s, vec4 m) {
    float v = length(p.velocity);
    if (v <= 0.0) return;
    float left = v - m.x * s.dt;
    p.velocity = left < 0.0 ? vec3(0.0) : p.velocity / v * left;
}
