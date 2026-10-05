// CgVfxModule.Ground, after the solver: a particle at or under the floor is put on it, bounces at restitution of its
// speed, loses friction of its sliding, and rests once slower than rest. m: restitution, friction, rest (blocks a
// second). floorY: the floor under it, relative to its instance's origin; NaN for none.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_types.glsl"

void fx_ground(inout FxParticle p, FxStep s, vec4 m, float floorY) {
    if (p.resting || isnan(floorY) || p.position.y > floorY) return;
    p.position.y = floorY;
    if (p.velocity.y < 0.0) p.velocity.y = -p.velocity.y * m.x;
    p.velocity.xz *= 1.0 - m.y;
    if (length(p.velocity) < m.z) {
        p.velocity = vec3(0.0);
        p.spinRate = 0.0;
        p.resting = true;
    }
}
