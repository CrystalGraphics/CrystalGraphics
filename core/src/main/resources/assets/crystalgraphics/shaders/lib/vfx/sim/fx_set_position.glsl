// CgVfxModule.SetPosition: Cascade's LocationDirect (ParticleModules_Location.cpp:353-385), each step aiming the
// particle at its position plus offset: scale.x of the way by the velocity it is given, the rest moved at once.
// m0.xyz the position, m1.xyz the offset, m2.x the share moved by velocity.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_types.glsl"

void fx_set_position(inout FxParticle p, inout FxForces f, FxStep s, vec4 m0, vec4 m1, vec4 m2) {
    vec3 diff = m0.xyz + m1.xyz - p.position;
    p.velocity = s.dt > 0.0 ? diff * m2.x / s.dt : vec3(0.0);
    p.position += diff * (1.0 - m2.x);
}
