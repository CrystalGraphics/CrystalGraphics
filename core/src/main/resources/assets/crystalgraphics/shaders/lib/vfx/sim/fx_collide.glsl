// CgVfxModule.Collide, after the solver: Godot's sphere and box colliders, and a plane, with fx_contact_respond. m0:
// the volume; m1: bounce, friction, rest, then the share of a particle's size that collides; m2.x 1 for a container;
// m3 to m6 its Cascade half (FxCollision). centre: the volume's centre, and a plane's radius round it.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_contact.glsl"

void fx_collide(inout FxParticle p, FxStep s, vec4 m0, vec4 m1, vec4 m2, vec4 m3, vec4 m4, vec4 m5, vec4 m6, vec4 centre) {
    FxCollision c = FxCollision(m3, m4, m5, m6);
    if (fx_collision_skips(p, c)) return;
    vec3 r = p.position - centre.xyz;
    if (!fx_volume_over(m0, centre, r)) return;
    vec3 n;
    float depth;
    if (fx_volume_contact(m0, m2.x > 0.0, r, m1.w * p.size, n, depth)) {
        fx_contact_respond(p, n, depth, m1.xyz, c, FX_HIT_SHAPE);
    }
}
