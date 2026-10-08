// CgVfxModule.CollideWorld, after the solver: the voxel window's solid octants, with Godot's rigid response. A
// particle inside one leaves by the octant's face the distance field's gradient points through most. With no level,
// the instance's fixed ground, as the CPU path has. m: bounce, friction, rest, kill (fx_contact_respond).
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_world_at.glsl"
#include "crystalgraphics:shaders/lib/vfx/sim/fx_contact.glsl"

void fx_collide_world(inout FxParticle p, FxStep s, vec4 m, FxWorld world) {
    if (p.resting) return;
    if (!world.live) {
        if (isnan(world.floorY) || p.position.y >= world.floorY) return;
        fx_contact_respond(p, vec3(0.0, 1.0, 0.0), world.floorY - p.position.y + FX_CONTACT_EPSILON, m);
        return;
    }
    if (!fx_world_solid(world, p.position)) return;
    vec3 g;
    fx_world_sdf(world, p.position, g);
    if (dot(g, g) == 0.0) g = vec3(0.0, 1.0, 0.0);
    vec3 a = abs(g);
    int axis = a.x >= a.y && a.x >= a.z ? 0 : a.y >= a.z ? 1 : 2;
    vec3 n = vec3(0.0);
    n[axis] = g[axis] > 0.0 ? 1.0 : -1.0;
    float halves = (world.originFrac[axis] + p.position[axis]) * 2.0;
    float cell = floor(halves);
    float depth = (n[axis] > 0.0 ? cell + 1.0 - halves : halves - cell) * 0.5 + FX_CONTACT_EPSILON;
    fx_contact_respond(p, n, depth, m);
}
