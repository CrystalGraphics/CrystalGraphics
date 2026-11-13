// CgVfxModule.ScaleVelocity: Cascade's VelocityOverLifetime (ParticleModules_Velocity.cpp:266-330), which scales the
// velocity each update over a base velocity reset each tick, so it scales the motion and never compounds; absolute
// moves it at the vector itself. m0.xyz the vector, m0.w 1 for absolute, m1.x 1 to take the owner's scale
// (bApplyOwnerScale, ParticleModules_Velocity.cpp:254-273).
#pragma once
#include "crystalgraphics:shaders/vfx/sim/types.glsl"

void fx_scale_velocity(inout FxParticle p, inout FxForces f, FxStep s, vec4 m0, vec4 m1) {
    vec3 v = m0.xyz * (m1.x > 0.5 ? s.ownerScale : vec3(1.0));
    if (m0.w > 0.5) {
        f.motionScale = vec3(0.0);
        f.motionSet = v;
    } else {
        f.motionScale *= v;
    }
}
