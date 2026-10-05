// Curl noise: CgVfxCurlNoise on the GPU, the turbulence force's field. Each component is roughly -2..2.
//
// Where it is sampled arrives split per octave into a lattice cell and a fraction, so world coordinates keep their
// fraction in float. The CPU splits the instance's origin (in doubles) for both octaves: the second samples at
// x * 2.03 + (5.2, 1.3, 7.9), whose cell is no integer multiple of the first's. The caller adds the particle's own
// offset to each fraction, which may then leave 0..1:
//
//     vec3 v = fx_curl(cell0.xyz, frac0.xyz + p.position * freq, cell1.xyz, frac1.xyz + p.position * (freq * 2.03));
//     f.accel += v * strength * 0.5;
//
// Same lattice, hash and gradients as the Java, so within float rounding of it (the Java casts the summed position to
// float first, so at world coordinates this is the more precise of the two).
#pragma once

// Perlin's improved-noise gradients: the twelve cube edges, four of them twice to fill sixteen.
const vec3 FX_CURL_G[16] = vec3[16](
        vec3(1.0, 1.0, 0.0), vec3(-1.0, 1.0, 0.0), vec3(1.0, -1.0, 0.0), vec3(-1.0, -1.0, 0.0),
        vec3(1.0, 0.0, 1.0), vec3(-1.0, 0.0, 1.0), vec3(1.0, 0.0, -1.0), vec3(-1.0, 0.0, -1.0),
        vec3(0.0, 1.0, 1.0), vec3(0.0, -1.0, 1.0), vec3(0.0, 1.0, -1.0), vec3(0.0, -1.0, -1.0),
        vec3(1.0, 1.0, 0.0), vec3(-1.0, 1.0, 0.0), vec3(0.0, -1.0, 1.0), vec3(0.0, -1.0, -1.0));

uint fx_curl_hash(uvec3 c) {
    uint h = c.x * 0x8DA6B343u ^ c.y * 0xD8163841u ^ c.z * 0xCB1AB31Fu;
    h ^= h >> 15u;
    h *= 0x2C1B3C6Du;
    h ^= h >> 12u;
    h *= 0x297A2D39u;
    return h;
}

// One octave's gradients of the three potentials, scaled by k: a = (dA/dy, dA/dz), b = (dB/dx, dB/dz),
// c = (dC/dx, dC/dy). The three share the cell, its weights and one hash a corner.
void fx_curl_octave(ivec3 cell, vec3 frac, float k, inout vec2 a, inout vec2 b, inout vec2 c) {
    vec3 whole = floor(frac);
    uvec3 base = uvec3(cell + ivec3(whole));
    vec3 f = frac - whole;
    vec3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    vec3 du = k * (30.0 * f * f * (f * (f - 2.0) + 1.0));
    for (int n = 0; n < 8; n++) {
        ivec3 o = ivec3(n & 1, (n >> 1) & 1, (n >> 2) & 1);
        bvec3 far = equal(o, ivec3(1));
        uint h = fx_curl_hash(base + uvec3(o));
        vec3 r = f - vec3(o);
        vec3 wt = mix(1.0 - u, u, far);
        vec3 s = mix(-du, du, far);
        // gradient of the weighted corner (w * dot): w * g + dot * grad(w), the octave's scale folded into both
        float w = k * wt.x * wt.y * wt.z;
        vec3 wd = vec3(s.x * wt.y * wt.z, wt.x * s.y * wt.z, wt.x * wt.y * s.z);
        vec3 g = FX_CURL_G[int(h >> 28u)];
        float d = dot(g, r);
        a += vec2(w * g.y + wd.y * d, w * g.z + wd.z * d);
        g = FX_CURL_G[int((h >> 24u) & 15u)];
        d = dot(g, r);
        b += vec2(w * g.x + wd.x * d, w * g.z + wd.z * d);
        g = FX_CURL_G[int((h >> 20u) & 15u)];
        d = dot(g, r);
        c += vec2(w * g.x + wd.x * d, w * g.y + wd.y * d);
    }
}

vec3 fx_curl(ivec3 cell0, vec3 frac0, ivec3 cell1, vec3 frac1) {
    vec2 a = vec2(0.0), b = vec2(0.0), c = vec2(0.0);
    fx_curl_octave(cell0, frac0, 1.0, a, b, c);
    fx_curl_octave(cell1, frac1, 0.5 * 2.03, a, b, c);
    // curl of (A, B, C): (dC/dy - dB/dz, dA/dz - dC/dx, dB/dx - dA/dy)
    return vec3(c.y - b.y, a.y - c.x, b.x - a.x);
}
