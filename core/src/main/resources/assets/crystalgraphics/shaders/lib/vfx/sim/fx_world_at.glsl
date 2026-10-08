// The voxel window as a module kind after the solver reads it (CgVfxWorldInput.WORLD, WORLD_DISTANCE): every point
// relative to the particle's instance origin, as FxParticle's positions are. The Step kernel includes this when its
// shape reads the world, after the window's properties; a kind taking an FxWorld includes it too.
//
//   void fx_bounce(inout FxParticle p, FxStep s, vec4 m, FxWorld world) {
//       if (!fx_world_solid(world, p.position)) return;
//       vec3 g;
//       fx_world_sdf(world, p.position, g);                      // WORLD_DISTANCE: the field is kept current
//       vec3 n = length(g) > 0.0 ? normalize(g) : vec3(0.0, 1.0, 0.0);
//       fx_hit(p, n);                                            // a COLLISION event, if the definition lists one
//       p.position = p.previous;
//       p.velocity = reflect(p.velocity, n) * m.x;
//   }
//
// With no level (world.live false) every reader answers "nothing there": not solid, no fluid, the fallback light,
// FX_WORLD_FAR. A block outside the window or in a section not yet filled reads the same.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/fx_world.glsl"

// Where the instance's origin is: whole blocks plus a fraction, so a point far from 0 keeps its precision. floorY is
// the instance's fixed ground (CgVfxEmitterInstance.ground), NaN for none: what a collider stands on with no level.
struct FxWorld {
    ivec3 originBlock;
    vec3 originFrac;
    bool live;
    float floorY;
};

ivec3 fx_world_base() {
    return ivec3(_WorldBaseX, _WorldBaseY, _WorldBaseZ);
}

ivec3 fx_world_wrap() {
    return ivec3(_WorldWrapX, _WorldWrapY, _WorldWrapZ);
}

// Whether the half-block octant holding p is solid.
bool fx_world_solid(FxWorld w, vec3 p) {
    if (!w.live) return false;
    ivec3 halves = ivec3(floor((w.originFrac + p) * 2.0)) + w.originBlock * 2;
    ivec3 b = halves >> 1;   // arithmetic: floor for negatives
    ivec3 o = halves & 1;
    if (!fx_world_known(_WorldSections, fx_world_base(), fx_world_wrap(), b)) return false;
    uint bits = fx_world_bytes(_World, fx_world_base(), fx_world_wrap(), b).x;
    return (bits & (1u << uint(o.x | (o.y << 1) | (o.z << 2)))) != 0u;
}

// The fluid in p's block: its kind (CgWorldQuery.FLUID_*, 0 for none) and its height within the block, 0 to 1.
vec2 fx_world_fluid(FxWorld w, vec3 p) {
    if (!w.live) return vec2(0.0);
    return fx_world_fluid(_World, _WorldSections, fx_world_base(), fx_world_wrap(),
            fx_world_block(w.originBlock, w.originFrac, p));
}

// Whether p is under the surface of a fluid of any kind.
bool fx_world_in_fluid(FxWorld w, vec3 p) {
    vec2 f = fx_world_fluid(w, p);
    vec3 at = w.originFrac + p;
    return f.x > 0.0 && at.y - floor(at.y) < f.y;
}

// Block and sky light, 0 to 15, in p's block.
vec2 fx_world_light(FxWorld w, vec3 p, vec2 fallback) {
    if (!w.live) return fallback;
    return fx_world_light(_World, _WorldSections, fx_world_base(), fx_world_wrap(),
            fx_world_block(w.originBlock, w.originFrac, p), fallback);
}

// Blocks from p to the nearest solid octant, trilinear, up to FX_WORLD_FAR, and the gradient pointing away from solid
// (0 near none): a kind declaring WORLD_DISTANCE, which keeps the field current while its shape plays.
float fx_world_sdf(FxWorld w, vec3 p, out vec3 gradient) {
    if (!w.live) {
        gradient = vec3(0.0);
        return FX_WORLD_FAR;
    }
    vec4 f = fx_world_field(_WorldDistance, _WorldSections, fx_world_base(), fx_world_wrap(), w.originBlock,
            w.originFrac, p);
    gradient = f.xyz;
    return f.w;
}
