// CgVfxModule.Current, after the solver: dust in a gravity current along the ground. The air under the head lifts it
// at roll times its speed, fading out depth blocks up; over the head's upper half the return flow brakes it by roll;
// slower than slow it lofts by up to loft. m: depth (blocks), roll (a second), loft (blocks a second squared), slow
// (blocks a second). floorY: the floor under it, relative to its instance's origin; NaN for none.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_types.glsl"

void fx_current(inout FxParticle p, FxStep s, vec4 m, float floorY) {
    if (p.resting || isnan(floorY)) return;
    float h = p.position.y - floorY, speed = length(p.velocity.xz);
    float lift = m.y * speed * max(1.0 - h / m.x, 0.0) + m.z * (1.0 - smoothstep(0.0, m.w, speed));
    p.velocity.y += lift * s.dt;
    p.velocity.xz *= exp(-m.y * smoothstep(0.5 * m.x, m.x, h) * s.dt);
}
