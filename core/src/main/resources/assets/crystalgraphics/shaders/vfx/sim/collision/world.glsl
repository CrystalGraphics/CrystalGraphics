// CgVfxModule.CollideWorld, after the solver: the voxel window's solid octants, with fx_contact_respond. A particle
// inside one leaves by the octant's face the distance field's gradient points through most. With no level, the
// instance's fixed ground. m0: bounce, friction, rest, then its reach, which the instance's near.x decides on the CPU:
// 0 while the camera is past it; m1 to m5 its Cascade half (FxCollision), whose lead tests the contact that share of the
// particle's size ahead of it along its motion, as Cascade's trace reaches past it (ParticleModules_Collision.cpp:360).
#pragma once
#include "crystalgraphics:shaders/vfx/sim/data/world.glsl"
#include "crystalgraphics:shaders/vfx/sim/collision/contact.glsl"

void fx_collide_world(inout FxParticle p, FxStep s, vec4 m0, vec4 m1, vec4 m2, vec4 m3, vec4 m4, vec4 m5, vec4 near,
                      FxWorld world) {
    FxCollision c = FxCollision(m1, m2, m3, m4, m5);
    if (fx_collision_skips(p, c) || near.x == 0.0) return;
    // One fx_contact_respond for both contacts: the driver inlines a copy per call, and it is most of the kernel.
    vec3 n;
    float depth, what;
    if (!world.live) {
        if (isnan(world.floorY) || p.position.y >= world.floorY) return;
        n = vec3(0.0, 1.0, 0.0);
        depth = world.floorY - p.position.y + FX_CONTACT_EPSILON;
        what = FX_HIT_GROUND;
    } else {
        float speed = length(p.velocity);
        vec3 probe = c.most.z > 0.0 && speed > 0.0 ? p.position + p.velocity / speed * (c.most.z * p.size) : p.position;
        if (!fx_world_solid(world, probe)) return;
        vec3 g;
        fx_world_sdf(world, probe, g);
        if (dot(g, g) == 0.0) g = vec3(0.0, 1.0, 0.0);
        vec3 a = abs(g);
        // The axis as a mask, not an index: a vector indexed at run time is costly to compile.
        vec3 axis = a.x >= a.y && a.x >= a.z ? vec3(1.0, 0.0, 0.0) : a.y >= a.z ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0);
        float sg = dot(g, axis) > 0.0 ? 1.0 : -1.0;
        n = axis * sg;
        float halves = dot(world.originFrac + probe, axis) * 2.0;
        float cell = floor(halves);
        depth = (sg > 0.0 ? cell + 1.0 - halves : halves - cell) * 0.5 + FX_CONTACT_EPSILON;
        what = FX_HIT_BLOCK;
    }
    fx_contact_respond(p, n, depth, m0.xyz, c, what);
}
