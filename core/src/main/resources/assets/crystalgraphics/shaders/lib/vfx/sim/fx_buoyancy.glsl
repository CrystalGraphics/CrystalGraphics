// CgVfxModule.Buoyancy: lift from heat, the heat cooling. m.x: lift at full heat, blocks a second squared; m.y: the
// cooling's time constant, seconds.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_types.glsl"

void fx_buoyancy(inout FxParticle p, inout FxForces f, FxStep s, vec4 m) {
    f.accel.y += m.x * p.heat;
    p.heat *= exp(-s.dt / max(m.y, 1.0e-3));
}
