// CgVfxModule.Collide, after the solver: Godot's sphere and box colliders, and a plane, with its rigid response. m0:
// the volume; m1: bounce, friction, rest, kill (fx_contact_respond); m2: the share of a particle's size that collides,
// then 1 for a container. centre: the volume's centre.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_contact.glsl"

void fx_collide(inout FxParticle p, FxStep s, vec4 m0, vec4 m1, vec4 m2, vec4 centre) {
    if (p.resting) return;
    vec3 n;
    float depth;
    if (fx_volume_contact(m0, m2.y > 0.0, p.position - centre.xyz, m2.x * p.size, n, depth)) {
        fx_contact_respond(p, n, depth, m1);
    }
}
