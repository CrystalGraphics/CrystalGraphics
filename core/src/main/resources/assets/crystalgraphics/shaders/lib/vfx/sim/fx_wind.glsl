// CgVfxModule.Wind: the system's wind pulling a particle up to its speed; none for one already faster along it.
// m.x: resistance, a second.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_types.glsl"

void fx_wind(inout FxParticle p, inout FxForces f, FxStep s, vec4 m) {
    float speed = length(s.wind);
    if (speed < 1.0e-5) return;
    vec3 along = s.wind / speed;
    float moving = dot(p.velocity, along);
    if (moving >= speed) return;
    f.accel += along * ((speed - moving) * m.x);
}
