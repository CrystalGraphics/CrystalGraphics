// CgVfxModule.Force: a constant acceleration, blocks a second squared. m.xyz.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_types.glsl"

void fx_force(inout FxParticle p, inout FxForces f, FxStep s, vec4 m) {
    f.accel += m.xyz;
}
