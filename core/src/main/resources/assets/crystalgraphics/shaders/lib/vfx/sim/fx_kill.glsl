// CgVfxModule.Kill, after the solver: bevy_hanabi's KillSphereModifier and KillAabbModifier (MIT/Apache-2.0, (c) 2021
// Jerome Humbert), and a plane. m0: the volume; m1.x: 1 kills inside it, 0 outside. centre: its centre, and a plane's
// radius round it.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_contact.glsl"

void fx_kill(inout FxParticle p, FxStep s, vec4 m0, vec4 m1, vec4 centre) {
    vec3 r = p.position - centre.xyz;
    if ((fx_volume_inside(m0, r) && fx_volume_over(m0, centre, r)) == (m1.x > 0.0)) p.life = p.age;
}
