// The particle spawn hash: the k-th draw of the n-th particle an emitter instance spawned, fixed by its seed. The same
// bits as CgVfxEmitterInstance.rand on every tier: integer operations only, and the top 24 bits as a float are exact.
//
//     float life = mix(lifeMin, lifeMax, fx_rand(seedBits, k, 4u));   // seedBits: floatBitsToUint of the instance's seed
//     if (fx_rand(seedBits, k, 10u) >= share) return;                // thinned by density, by spawn index
#pragma once

float fx_rand(uint seed, uint n, uint k) {
    uint h = seed * 0x9E3779B1u ^ n * 0x85EBCA77u ^ k * 0xC2B2AE3Du;
    h ^= h >> 15u;
    h *= 0x2C1B3C6Du;
    h ^= h >> 12u;
    h *= 0x297A2D39u;
    h ^= h >> 15u;
    return float(h >> 8u) * (1.0 / 16777216.0);
}
