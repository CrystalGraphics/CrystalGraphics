// CgVfxModule.Vortex: bevy_hanabi's TangentAccelModifier and RadialAccelModifier (MIT/Apache-2.0, (c) 2021
// Jerome Humbert), together. m0: the unit axis, then the tangential pull; m1.x: the radial (negative pulls
// in). centre: its centre.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_contact.glsl"

void fx_vortex(inout FxParticle p, inout FxForces f, FxStep s, vec4 m0, vec4 m1, vec4 centre) {
    vec3 away = fx_safe_normalize(p.position - centre.xyz);
    f.accel += fx_safe_normalize(cross(m0.xyz, away)) * m0.w + away * m1.x;
}
