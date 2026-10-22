// CgVfxModule.SpriteRotation: Cascade's RotationOverLifetime, each update. m.x: radians; m.y: 1 to scale, else add.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_types.glsl"

void fx_sprite_rotation(inout FxParticle p, inout FxForces f, FxStep s, vec4 m) {
    p.spin = m.y > 0.5 ? p.spin * m.x : p.spin + m.x;
}
