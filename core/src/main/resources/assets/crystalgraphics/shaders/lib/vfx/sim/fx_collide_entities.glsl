// CgVfxModule.CollideEntities, after the solver: the entities round the camera, with fx_contact_respond at the face it
// entered. m0: bounce, friction, rest, then the share of a particle's size that collides; m1 to m5 its Cascade half
// (FxCollision).
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_entities_at.glsl"
#include "crystalgraphics:shaders/lib/vfx/sim/fx_contact.glsl"

void fx_collide_entities(inout FxParticle p, FxStep s, vec4 m0, vec4 m1, vec4 m2, vec4 m3, vec4 m4, vec4 m5,
                         FxEntities entities) {
    FxCollision c = FxCollision(m1, m2, m3, m4, m5);
    if (fx_collision_skips(p, c) || entities.count == 0) return;
    vec3 at, n;
    uint entity;
    if (!fx_entities_enter(entities, p.previous, p.position, m0.w * p.size, at, n, entity)) return;
    p.hitEntity = entity;
    p.position = at;
    fx_contact_respond(p, n, FX_CONTACT_EPSILON, m0.xyz, c, FX_HIT_ENTITY);
}
