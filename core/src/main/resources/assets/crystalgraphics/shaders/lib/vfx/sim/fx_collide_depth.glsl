// CgVfxModule.CollideDepth, after the solver: the scene's depth (Niagara's and Unity's depth buffer collision), with
// Godot's rigid response from where the particle was. m0: bounce, friction, rest, kill (fx_contact_respond); m1.x: how
// thick a surface is, in blocks of eye depth.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_depth_at.glsl"
#include "crystalgraphics:shaders/lib/vfx/sim/fx_contact.glsl"

void fx_collide_depth(inout FxParticle p, FxStep s, vec4 m0, vec4 m1, FxDepth depth) {
    vec3 n;
    if (p.resting || !fx_depth_behind(depth, p.position, m1.x, n)) return;
    p.position = p.previous;
    fx_contact_respond(p, n, 0.0, m0);
}
