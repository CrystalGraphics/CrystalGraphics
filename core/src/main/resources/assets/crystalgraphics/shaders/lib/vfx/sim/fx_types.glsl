#pragma once
// What the emitter compiler's Step kernel hands a module kind (vfx-gpu §13.2). A kind is one function,
// fx_<kind> in shaders/lib/vfx/sim/fx_<kind>.glsl, including this file:
//
//   before the solver:  void fx_<kind>(inout FxParticle p, inout FxForces f, FxStep s, <params>, <lanes>)
//   after the solver:   void fx_<kind>(inout FxParticle p, FxStep s, <params>, <lanes>, <world inputs>)
//
// <params> are its numbers, vec4 m (or m0, m1, ...); <lanes> its per-instance values; <world inputs> what the kernel
// fetched for it (float floorY, FxWorld world: fx_world_at.glsl). Each is declared by the kind's Java side
// (CgVfxGpuModule).

// A particle, unpacked from its pool record. Positions are relative to its instance's origin, in blocks; times in
// seconds. previous is where it was a step ago, what a frame interpolates from. collisions counts its impacts so far
// (16 bits, saturating); hit is this step's, set by fx_hit and never stored. A kind kills a particle by setting life
// to its age: it dies, and its death events fire, at the end of this step.
struct FxParticle {
    vec3 position, previous, velocity;
    float age, life, size, seed, spin, spinRate, heat;
    bool resting;
    uint id, slot, collisions;
    vec4 hit;
};

// An impact this step against a surface whose normal is n: what a COLLISION event fires on, and what collisions
// counts. Unity VFX Graph's punctual contact: call it when the speed into the surface reaches the kind's bounce
// threshold, never for a particle resting or sliding along it, or a resting particle fires every step. The last call
// in a step gives the normal; each call counts.
void fx_hit(inout FxParticle p, vec3 n) {
    p.hit = vec4(n, 1.0);
    p.collisions = min(p.collisions + 1u, 65535u);
}

// What modules before the solver add to: acceleration in blocks a second squared, linear drag a second, quadratic
// drag a block. Zeroed each step, never stored.
struct FxForces {
    vec3 accel;
    float drag, dragQuad;
};

// The step: its length in seconds, and the system's wind in blocks a second.
struct FxStep {
    float dt;
    vec3 wind;
};
