// CgParticleBuffer's shader environment: the frame's particle records, read by index.
//
// INJECTED BY `#pragma cg_use particle`, immediately after the buffer's own declaration -- see CgEngineBufferRegistry,
// which holds the path. A shader that does not declare the pragma never sees any of this.
//
// One record per particle, written once a frame by whatever simulates particles (the vfx engine's emitters). A draw
// reads its own range of records, [base, base + count), naming base and count in its own per-draw data.
//
//     vec3 p = CG_PARTICLE_POSITION(base + index);
//     float size = CG_PARTICLE_SIZE(base + index), life = CG_PARTICLE_PROGRESS(base + index);
#pragma once

// Where it is (in the writer's space; the vfx engine writes positions relative to an effect's origin) and its size.
#define CG_PARTICLE_POSITION(n) (PARTICLE_DATA(n).place.xyz)
#define CG_PARTICLE_SIZE(n) (PARTICLE_DATA(n).place.w)
// Its velocity, in the same space a second, and how far through its life it is, 0..1.
#define CG_PARTICLE_VELOCITY(n) (PARTICLE_DATA(n).motion.xyz)
#define CG_PARTICLE_PROGRESS(n) (PARTICLE_DATA(n).motion.w)
// A 0..1 seed, its spin angle in radians, its heat 0..1, and its opacity 0..1.
#define CG_PARTICLE_SEED(n) (PARTICLE_DATA(n).state.x)
#define CG_PARTICLE_SPIN(n) (PARTICLE_DATA(n).state.y)
#define CG_PARTICLE_HEAT(n) (PARTICLE_DATA(n).state.z)
#define CG_PARTICLE_OPACITY(n) (PARTICLE_DATA(n).state.w)
// The world's block and sky light where it is, 0 to 15 each: what a lit particle sets cg_Light to.
#define CG_PARTICLE_LIGHT(n) (PARTICLE_DATA(n).light.xy)
// Its height over the floor under it, and 1; (0, 0) for none: written for kinds that land on a floor (Ground).
#define CG_PARTICLE_FLOOR(n) (PARTICLE_DATA(n).light.zw)
