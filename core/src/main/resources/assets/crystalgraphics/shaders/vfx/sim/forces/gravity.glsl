// CgVfxModule.Gravity: a constant acceleration. m.xyz: blocks a second squared.
#pragma once
#include "crystalgraphics:shaders/vfx/sim/types.glsl"

void fx_gravity(inout FxParticle p, inout FxForces f, FxStep s, vec4 m) {
    f.accel += m.xyz;
}
