// CgVfxModule.ScaleVelocity: Cascade's VelocityOverLifetime (ParticleModules_Velocity.cpp:266-330), which scales the
// velocity each update over a base velocity reset each tick, so it scales the motion and never compounds; absolute
// moves it at the vector itself. m.xyz the vector, m.w 1 for absolute.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_types.glsl"

void fx_scale_velocity(inout FxParticle p, inout FxForces f, FxStep s, vec4 m) {
    if (m.w > 0.5) {
        f.motionScale = vec3(0.0);
        f.motionSet = m.xyz;
    } else {
        f.motionScale *= m.xyz;
    }
}
