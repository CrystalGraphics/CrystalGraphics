// CgVfxModule.CollideEntities, after the solver: the entities round the camera, with Godot's rigid response at the face
// it entered. m0: bounce, friction, rest, kill (fx_contact_respond); m1.x: the share of a particle's size that collides.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_entities_at.glsl"
#include "crystalgraphics:shaders/lib/vfx/sim/fx_contact.glsl"

void fx_collide_entities(inout FxParticle p, FxStep s, vec4 m0, vec4 m1, FxEntities entities) {
    if (p.resting || entities.count == 0) return;
    vec3 at, n;
    uint entity;
    if (!fx_entities_enter(entities, p.previous, p.position, m1.x * p.size, at, n, entity)) return;
    p.hitEntity = entity;
    p.position = at;
    fx_contact_respond(p, n, FX_CONTACT_EPSILON, m0, FX_HIT_ENTITY);
}
