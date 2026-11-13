// CgVfxModule.Force: a constant acceleration, blocks a second squared. m.xyz; m.w 1 to take the owner's scale
// (Cascade's bApplyOwnerScale, ParticleModules.cpp:2873-2877).
#pragma once
#include "crystalgraphics:shaders/vfx/sim/types.glsl"

void fx_force(inout FxParticle p, inout FxForces f, FxStep s, vec4 m) {
    f.accel += m.xyz * (m.w > 0.5 ? s.ownerScale : vec3(1.0));
}
