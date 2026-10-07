// CgVfxModule.Orbit, after the solver: Godot's orbit velocity, the particle turned round an axis through the centre by
// m.w turns a second, moving it without adding to its velocity. m.xyz: the unit axis. centre: its centre.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_types.glsl"

void fx_orbit(inout FxParticle p, FxStep s, vec4 m, vec4 centre) {
    if (p.resting) return;
    float a = m.w * 6.2831855 * s.dt;
    float c = cos(a), sn = sin(a);
    vec3 v = p.position - centre.xyz;
    p.position = centre.xyz + v * c + cross(m.xyz, v) * sn + m.xyz * (dot(m.xyz, v) * (1.0 - c));
}
