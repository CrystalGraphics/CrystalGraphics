// CgVfxModule.Spin: the spin rate decays. m.x: drag, a second.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_types.glsl"

void fx_spin(inout FxParticle p, inout FxForces f, FxStep s, vec4 m) {
    p.spinRate *= exp(-m.x * s.dt);
}
