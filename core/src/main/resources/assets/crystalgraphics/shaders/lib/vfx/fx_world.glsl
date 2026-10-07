// The voxel window (CgVfxVoxelWindow): the host world around the camera, a block a texel of an RGBA8 volume addressed
// toroidally, and a texel a section saying whether that section is filled. Arguments only: a kernel declares the
// window's properties and passes them (CgVfxVoxelWindow.bind and CgVfxVoxelWindow.PROPERTIES).
//
//   world, sections   _World, _WorldSections
//   distance          _WorldDistance: the distance field, read by fx_world_distance and fx_world_field
//   base              the window's lowest block, absolute: ivec3(_WorldBaseX, _WorldBaseY, _WorldBaseZ)
//   wrap              base's texel: ivec3(_WorldWrapX, _WorldWrapY, _WorldWrapZ)
//
// A block is absolute: an instance's origin as whole blocks plus a fraction, then the particle's position from it,
// so a point far from 0 keeps its precision (fx_world_block). GLSL leaves / and % of a negative int undefined, so
// every index here is taken from b - base, which is never negative inside the window.
#pragma once

const ivec3 FX_WORLD_SIZE = ivec3(128, 96, 128);
const int FX_WORLD_SECTION = 16;
/** Blocks fx_world_floor looks down before it gives up: a particle further above its floor keeps falling. */
const int FX_WORLD_SCAN = 32;
/** The distance field's reach in blocks: anything further from solid reads as this. */
const float FX_WORLD_FAR = 8.0;

// The absolute block of a point p from an origin at originBlock + originFrac.
ivec3 fx_world_block(ivec3 originBlock, vec3 originFrac, vec3 p) {
    return originBlock + ivec3(floor(originFrac + p));
}

bool fx_world_inside(ivec3 base, ivec3 b) {
    ivec3 rel = b - base;
    return all(greaterThanEqual(rel, ivec3(0))) && all(lessThan(rel, FX_WORLD_SIZE));
}

// Block b's texel, for b inside the window.
ivec3 fx_world_texel(ivec3 base, ivec3 wrap, ivec3 b) {
    ivec3 t = wrap + (b - base);
    return t - FX_WORLD_SIZE * ivec3(greaterThanEqual(t, FX_WORLD_SIZE));
}

// Whether block b is inside the window and its section filled: what every other reader asks first.
bool fx_world_known(sampler3D sections, ivec3 base, ivec3 wrap, ivec3 b) {
    if (!fx_world_inside(base, b)) return false;
    return texelFetch(sections, fx_world_texel(base, wrap, b) / FX_WORLD_SECTION, 0).r > 0.5;
}

// Block b's four bytes, 0 to 255: its solid octants (bit ox | oy << 1 | oz << 2), block light times 17, sky light
// times 17, and its fluid's kind times 64 plus its height in 63rds. For a known block.
uvec4 fx_world_bytes(sampler3D world, ivec3 base, ivec3 wrap, ivec3 b) {
    return uvec4(texelFetch(world, fx_world_texel(base, wrap, b), 0) * 255.0 + 0.5);
}

// Block and sky light, 0 to 15, at block b; fallback where it is not known.
vec2 fx_world_light(sampler3D world, sampler3D sections, ivec3 base, ivec3 wrap, ivec3 b, vec2 fallback) {
    if (!fx_world_known(sections, base, wrap, b)) return fallback;
    uvec4 v = fx_world_bytes(world, base, wrap, b);
    return vec2(float(v.y / 17u), float(v.z / 17u));
}

// The fluid at block b: its kind (CgWorldQuery.FLUID_*, 0 for none) and its height within the block; 0 where unknown.
vec2 fx_world_fluid(sampler3D world, sampler3D sections, ivec3 base, ivec3 wrap, ivec3 b) {
    if (!fx_world_known(sections, base, wrap, b)) return vec2(0.0);
    uint f = fx_world_bytes(world, base, wrap, b).w;
    return vec2(float(f >> 6u), float(f & 63u) / 63.0);
}

// Blocks from block b's centre to the nearest solid octant (_WorldDistance), up to FX_WORLD_FAR; FX_WORLD_FAR where
// not known.
float fx_world_distance(sampler3D distance, sampler3D sections, ivec3 base, ivec3 wrap, ivec3 b) {
    if (!fx_world_known(sections, base, wrap, b)) return FX_WORLD_FAR;
    return texelFetch(distance, fx_world_texel(base, wrap, b), 0).r * FX_WORLD_FAR;
}

// The distance at p from an origin at originBlock + originFrac, trilinear between block centres, and its gradient,
// pointing away from solid: xyz the gradient, w the distance. A surface's normal is the gradient normalised; near
// none it is 0.
vec4 fx_world_field(sampler3D distance, sampler3D sections, ivec3 base, ivec3 wrap, ivec3 originBlock, vec3 originFrac,
                    vec3 p) {
    vec3 q = originFrac + p - 0.5, whole = floor(q), f = q - whole;
    ivec3 b = originBlock + ivec3(whole);
    float d000 = fx_world_distance(distance, sections, base, wrap, b);
    float d100 = fx_world_distance(distance, sections, base, wrap, b + ivec3(1, 0, 0));
    float d010 = fx_world_distance(distance, sections, base, wrap, b + ivec3(0, 1, 0));
    float d110 = fx_world_distance(distance, sections, base, wrap, b + ivec3(1, 1, 0));
    float d001 = fx_world_distance(distance, sections, base, wrap, b + ivec3(0, 0, 1));
    float d101 = fx_world_distance(distance, sections, base, wrap, b + ivec3(1, 0, 1));
    float d011 = fx_world_distance(distance, sections, base, wrap, b + ivec3(0, 1, 1));
    float d111 = fx_world_distance(distance, sections, base, wrap, b + ivec3(1, 1, 1));
    vec2 x0 = mix(vec2(d000, d001), vec2(d100, d101), f.x), x1 = mix(vec2(d010, d011), vec2(d110, d111), f.x);
    vec2 y = mix(x0, x1, f.y);
    vec3 gradient = vec3(mix(mix(d100 - d000, d110 - d010, f.y), mix(d101 - d001, d111 - d011, f.y), f.z),
                         mix(x1.x - x0.x, x1.y - x0.y, f.z), y.y - y.x);
    return vec4(gradient, mix(y.x, y.y, f.z));
}

// CgVfxGround.floor on the window, at half a block: the highest surface under a particle at p from its origin that
// was at previousY a step ago, relative to the origin, not above max(y, previousY) + 0.01 so it cannot tunnel. A
// surface is the top of a solid half block with air over it, so a particle inside solid lands under that span. NaN
// for none within FX_WORLD_SCAN blocks, or once the column reaches a block not known.
float fx_world_floor(sampler3D world, sampler3D sections, ivec3 base, ivec3 wrap, ivec3 originBlock, vec3 originFrac,
                     vec3 p, float previousY) {
    float none = uintBitsToFloat(0x7fc00000u);
    vec3 at = originFrac + vec3(p.x, max(p.y, previousY) + 0.01, p.z);
    ivec3 halves = ivec3(floor(at * 2.0)) + originBlock * 2;
    ivec3 b = halves >> 1;   // arithmetic: floor for negatives
    ivec3 o = halves & 1;
    uint lower = 1u << uint(o.x | (o.z << 2)), upper = lower << 2u;
    float originY = float(originBlock.y) + originFrac.y;
    bool air = false;
    for (int n = 0; n < FX_WORLD_SCAN; n++, b.y--) {
        if (!fx_world_known(sections, base, wrap, b)) return none;
        uint bits = fx_world_bytes(world, base, wrap, b).x;
        if (n > 0 || o.y == 1) {
            bool solid = (bits & upper) != 0u;
            if (solid && air) return float(b.y) + 1.0 - originY;
            air = air || !solid;
        }
        bool solid = (bits & lower) != 0u;
        if (solid && air) return float(b.y) + 0.5 - originY;
        air = air || !solid;
    }
    return none;
}
