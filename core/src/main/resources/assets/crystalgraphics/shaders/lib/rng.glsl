#pragma once

// Random numbers without state: four words hashed from (seed, element, step, stream), so any element draws its own in
// any order, on any tier, and a kernel and its Java body draw the same ones. PCG4D, from Jarzynski and Olano, "Hash
// Functions for GPU Rendering" (JCGT 2020). Integer operations only: com.crystalgraphics.compute.ops.CgRng gives the
// same bits.
//
//     uvec4 r = cg_rng4(_Seed, PARTICLES(i).id, _Step, 0u);   // the element's id, never its slot
//     float u = cg_rng_unit(r.x);                            // [0, 1), exactly as Java's
//     vec3  d = cg_rng_direction(r.yz);                      // float math from there on: within an ulp, not exact

uvec4 cg_rng4(uint seed, uint element, uint step, uint stream) {
    uvec4 v = uvec4(seed, element, step, stream) * 1664525u + 1013904223u;
    v.x += v.y * v.w; v.y += v.z * v.x; v.z += v.x * v.y; v.w += v.y * v.z;
    v ^= v >> 16u;
    v.x += v.y * v.w; v.y += v.z * v.x; v.z += v.x * v.y; v.w += v.y * v.z;
    return v;
}

uint cg_rng(uint seed, uint element, uint step, uint stream) {
    return cg_rng4(seed, element, step, stream).x;
}

// The top 24 bits as a float in [0, 1): every value exact.
float cg_rng_unit(uint bits) {
    return float(bits >> 8u) * (1.0 / 16777216.0);
}

vec4 cg_rng_unit4(uvec4 bits) {
    return vec4(bits >> 8u) * (1.0 / 16777216.0);
}

// A direction uniform over the sphere, from two words.
vec3 cg_rng_direction(uvec2 bits) {
    float z = 1.0 - 2.0 * cg_rng_unit(bits.x);
    float a = 6.28318530718 * cg_rng_unit(bits.y);
    float r = sqrt(max(0.0, 1.0 - z * z));
    return vec3(r * cos(a), r * sin(a), z);
}
