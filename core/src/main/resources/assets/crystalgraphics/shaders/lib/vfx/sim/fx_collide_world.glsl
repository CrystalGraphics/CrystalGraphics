// CgVfxModule.CollideWorld, after the solver: the voxel window's solid octants, with fx_contact_respond. A particle
// inside one leaves by the octant's face the distance field's gradient points through most. With no level, the
// instance's fixed ground. m0: bounce, friction, rest, then its reach, which the instance's near.x decides on the CPU:
// 0 while the camera is past it; m1 to m4 its Cascade half (FxCollision), whose lead tests the contact that share of the
// particle's size ahead of it along its motion, as Cascade's trace reaches past it (ParticleModules_Collision.cpp:360).
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_world_at.glsl"
#include "crystalgraphics:shaders/lib/vfx/sim/fx_contact.glsl"

void fx_collide_world(inout FxParticle p, FxStep s, vec4 m0, vec4 m1, vec4 m2, vec4 m3, vec4 m4, vec4 near, FxWorld world) {
    FxCollision c = FxCollision(m1, m2, m3, m4);
    if (fx_collision_skips(p, c) || near.x == 0.0) return;
    if (!world.live) {
        if (isnan(world.floorY) || p.position.y >= world.floorY) return;
        fx_contact_respond(p, vec3(0.0, 1.0, 0.0), world.floorY - p.position.y + FX_CONTACT_EPSILON, m0.xyz, c,
                FX_HIT_GROUND);
        return;
    }
    float speed = length(p.velocity);
    vec3 probe = c.most.z > 0.0 && speed > 0.0 ? p.position + p.velocity / speed * (c.most.z * p.size) : p.position;
    if (!fx_world_solid(world, probe)) return;
    vec3 g;
    fx_world_sdf(world, probe, g);
    if (dot(g, g) == 0.0) g = vec3(0.0, 1.0, 0.0);
    vec3 a = abs(g);
    int axis = a.x >= a.y && a.x >= a.z ? 0 : a.y >= a.z ? 1 : 2;
    vec3 n = vec3(0.0);
    n[axis] = g[axis] > 0.0 ? 1.0 : -1.0;
    float halves = (world.originFrac[axis] + probe[axis]) * 2.0;
    float cell = floor(halves);
    float depth = (n[axis] > 0.0 ? cell + 1.0 - halves : halves - cell) * 0.5 + FX_CONTACT_EPSILON;
    fx_contact_respond(p, n, depth, m0.xyz, c, FX_HIT_BLOCK);
}
