// CgVfxModule.PhysicsField: Niagara's PhysicsField module, the fields of its group applied for its target. m.x: the
// group; m.y: the target (fx_field.glsl's FX_FIELD_*); m.z: its scale, which a form rolls per particle.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_field.glsl"

void fx_physics_field(inout FxParticle p, inout FxForces f, FxStep s, vec4 m, FxFields fields) {
    fx_field_apply(fields, int(m.x + 0.5), int(m.y + 0.5), m.z, p, f, s);
}
