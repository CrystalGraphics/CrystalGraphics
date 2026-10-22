// CgParticleBuffer's shader environment: the frame's particle records, read by index.
//
// INJECTED BY `#pragma cg_use particle`, immediately after the buffer's own declaration -- see CgEngineBufferRegistry,
// which holds the path. A shader that does not declare the pragma never sees any of this.
//
// A GPU pool's records, written by its Step once a frame, read through PARTICLE_ORDER: a draw's particle n is the
// record Range's culled, sorted order names at n. A draw reads its own range, [base, base + count), naming base and
// count in its own per-draw data.
//
//     vec3 p = CG_PARTICLE_POSITION(base + index);
//     float size = CG_PARTICLE_SIZE(base + index), life = CG_PARTICLE_PROGRESS(base + index);
#pragma once

// Particle n of the draws' order: its record.
#define CG_PARTICLE(n) PARTICLE_DATA(int(PARTICLE_ORDER_DATA(n).index))

// Where it is (in the writer's space; the vfx engine writes positions relative to an effect's origin) and its size.
#define CG_PARTICLE_POSITION(n) (CG_PARTICLE(n).place.xyz)
#define CG_PARTICLE_SIZE(n) (CG_PARTICLE(n).place.w)
// Its velocity, in the same space a second, and how far through its life it is, 0..1.
#define CG_PARTICLE_VELOCITY(n) (CG_PARTICLE(n).motion.xyz)
#define CG_PARTICLE_PROGRESS(n) (CG_PARTICLE(n).motion.w)
// A 0..1 seed, its spin angle in radians, its heat 0..1, and its opacity 0..1.
#define CG_PARTICLE_SEED(n) (CG_PARTICLE(n).state.x)
#define CG_PARTICLE_SPIN(n) (CG_PARTICLE(n).state.y)
#define CG_PARTICLE_HEAT(n) (CG_PARTICLE(n).state.z)
#define CG_PARTICLE_OPACITY(n) (CG_PARTICLE(n).state.w)
// The world's block and sky light where it is, 0 to 15 each: what a lit particle sets cg_Light to. Stored as one
// word, block + 16 sky.
#define CG_PARTICLE_LIGHT(n) (vec2(mod(CG_PARTICLE(n).light.x, 16.0), floor(CG_PARTICLE(n).light.x / 16.0)))
// Its spawn index in its emitter instance, its low 24 bits: what orders a ribbon and finds a particle's trail.
#define CG_PARTICLE_ID(n) (CG_PARTICLE(n).light.y)
// Its height over the floor under it, and 1; (0, 0) for none: written for kinds that land on a floor (Ground).
#define CG_PARTICLE_FLOOR(n) (CG_PARTICLE(n).light.zw)
// Its colour, multiplying a look's own (rgb past 1 where it glows), and its opacity: white unless its emitter says.
#define CG_PARTICLE_COLOR(n) (CG_PARTICLE(n).color)
// Its scale on each axis, times its size: a quad is size * scale.x across and size * scale.y up. 1 unless its emitter says.
#define CG_PARTICLE_SCALE(n) (CG_PARTICLE(n).scale.xyz)
// Its age in seconds: what a flipbook's loop and random frames run on (progress alone is a fraction of its life).
#define CG_PARTICLE_AGE(n) (CG_PARTICLE(n).scale.w)

// Its drawn slots (CgVfxDrawnChannel), at the element after its drawn part where its pool writes any (a renderer's
// _cg_Drawn says which): place to light are dynamic parameters 0 to 3, color.xy its sub-image index and camera offset.
#define CG_PARTICLE_DRAWN(n) PARTICLE_DATA(int(PARTICLE_ORDER_DATA(n).index) + 1)

// The vertex factory a kit's material program reads: cg_material_vertex(), cg_material_particle().
#include "crystalgraphics:shaders/env/buffer/particle_factory.glsl"
