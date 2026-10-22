// CgVfxModule.CollideDepth, after the solver: the scene's depth (Niagara's and Unity's depth buffer collision), with
// fx_contact_respond from where the particle was. m0: bounce, friction, rest, then how thick a surface is, in blocks of
// eye depth; m1.x its reach, which the instance's near.x decides on the CPU; m2 to m6 its Cascade half (FxCollision).
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_depth_at.glsl"
#include "crystalgraphics:shaders/lib/vfx/sim/fx_contact.glsl"

void fx_collide_depth(inout FxParticle p, FxStep s, vec4 m0, vec4 m1, vec4 m2, vec4 m3, vec4 m4, vec4 m5, vec4 m6,
                      vec4 near, FxDepth depth) {
    FxCollision c = FxCollision(m2, m3, m4, m5, m6);
    vec3 n;
    if (fx_collision_skips(p, c) || near.x == 0.0 || !fx_depth_behind(depth, p.position, m0.w, n)) return;
    p.position = p.previous;
    fx_contact_respond(p, n, 0.0, m0.xyz, c, FX_HIT_SCENE);
}
