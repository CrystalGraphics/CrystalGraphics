// The world's lights' shadow rays (CgWorldLights, plan render-shadows), as Unreal's MegaLights traces them
// (MegaLightsRayTracing.usf): a ray from a surface to a point on the light, a screen trace first, then a world trace
// from where it stopped. The world trace here is the voxel window's blocks, sphere-traced on its distance field where
// Unreal's is its global distance field, and the boxes of the entities round the camera as Unreal's capsule shadows
// stand them in for a character. Arguments only: a shader passes what it declares.
#pragma once

#include "crystalgraphics:shaders/lib/vfx/fx_world.glsl"

// Interleaved gradient noise (Jimenez 2014), as Unreal's InterleavedGradientNoise: a value in [0, 1) a pixel, its
// pattern moved each frame.
float cg_shadow_noise(vec2 pixel, int frame) {
    pixel += 5.588238 * float(frame & 63);
    return fract(52.9829189 * fract(0.06711056 * pixel.x + 0.00583715 * pixel.y));
}

// Lumen's YCoCg (LumenRGBToYCoCg), what the temporal pass clamps its history in.
vec3 cg_rgb_to_ycocg(vec3 c) {
    return vec3(dot(c, vec3(0.25, 0.5, 0.25)), dot(c, vec3(0.5, 0.0, -0.5)), dot(c, vec3(-0.25, 0.5, -0.25)));
}

vec3 cg_ycocg_to_rgb(vec3 c) {
    return vec3(c.x + c.y - c.z, c.x + c.z, c.x - c.y - c.z);
}

// Unreal's Rand3DPCG16 (Random.ush): three 16-bit randoms from three integers.
uvec3 cg_rand_pcg16(ivec3 p) {
    uvec3 v = uvec3(p) * 1664525u + 1013904223u;
    v.x += v.y * v.z;
    v.y += v.z * v.x;
    v.z += v.x * v.y;
    v.x += v.y * v.z;
    v.y += v.z * v.x;
    v.z += v.x * v.y;
    return v >> 16u;
}

// Unreal's Hammersley16: sample index of count in [0, 1)^2, scrambled by a 16-bit random pair. The bit reversal is by
// hand, for GL 3.3.
vec2 cg_hammersley16(uint index, uint count, uvec2 random) {
    float e1 = fract(float(index) / float(count) + float(random.x & 0xFFFFu) / 65536.0);
    uint r = index & 0xFFFFu;
    r = (r << 8u | r >> 8u) & 0xFFFFu;
    r = ((r & 0x0F0Fu) << 4u) | ((r & 0xF0F0u) >> 4u);
    r = ((r & 0x3333u) << 2u) | ((r & 0xCCCCu) >> 2u);
    r = ((r & 0x5555u) << 1u) | ((r & 0xAAAAu) >> 1u);
    return vec2(e1, float(r ^ random.y) / 65536.0);
}

// Unreal's UniformSampleDiskConcentric (Shirley and Chiu): the unit square onto the unit disk, areas kept.
vec2 cg_disk_concentric(vec2 e) {
    vec2 p = 2.0 * e - 1.0;
    if (p.x == 0.0 && p.y == 0.0) return vec2(0.0);
    float radius, phi;
    if (abs(p.x) > abs(p.y)) {
        radius = p.x;
        phi = 0.78539816 * (p.y / p.x);
    } else {
        radius = p.y;
        phi = 1.57079633 - 0.78539816 * (p.x / p.y);
    }
    return radius * vec2(cos(phi), sin(phi));
}

// A direction toward a sphere of the given radius at toCentre from the eye of the ray, sampled uniformly over the solid
// angle it covers (Unreal's GenerateSphereLightOcclusionRayWithSolidAngleSampling), u two randoms in [0, 1); w the
// distance to the sphere's near side along it, where the ray stops.
vec4 cg_shadow_sphere_ray(vec3 toCentre, float radius, vec2 u) {
    float d2 = dot(toCentre, toCentre), d = sqrt(d2);
    vec3 axis = toCentre / max(d, 1.0e-6);
    if (radius <= 0.0 || d <= radius) return vec4(axis, d);
    float sinMax2 = radius * radius / d2, cosMax = sqrt(1.0 - sinMax2);
    float cosTheta = 1.0 - u.x * (1.0 - cosMax), sinTheta = sqrt(max(1.0 - cosTheta * cosTheta, 0.0));
    float phi = 6.28318531 * u.y;
    vec3 side = abs(axis.y) < 0.999 ? normalize(cross(axis, vec3(0.0, 1.0, 0.0))) : vec3(1.0, 0.0, 0.0);
    vec3 up = cross(side, axis);
    vec3 dir = axis * cosTheta + (side * cos(phi) + up * sin(phi)) * sinTheta;
    // The near root of |t dir - toCentre| = radius.
    float b = dot(dir, toCentre), c = d2 - radius * radius;
    return vec4(dir, b - sqrt(max(b * b - c, 0.0)));
}

// Whether a solid octant of the voxel window lies on the ray from a, along unit dir, between t0 and t1 blocks: points
// relative to the eye, at block eyeBlock + eyeFrac. Sphere-traced on the window's distance field (a block's centre is
// at least d from solid, so any point of it at least d - sqrt(3) / 2), then octant by octant where solid is near.
// Clear outside the window and where a section is not filled.
bool cg_shadow_window(sampler3D world, sampler3D sections, sampler3D distance, ivec3 base, ivec3 wrap, ivec3 eyeBlock,
                      vec3 eyeFrac, vec3 a, vec3 dir, float t0, float t1) {
    float t = t0;
    for (int i = 0; i < 128; i++) {
        if (t >= t1) return false;
        vec3 q = eyeFrac + a + dir * t, whole = floor(q);
        ivec3 b = eyeBlock + ivec3(whole);
        if (!fx_world_known(sections, base, wrap, b)) {
            t += 1.0;
            continue;
        }
        float d = fx_world_distance(distance, sections, base, wrap, b);
        if (d > 1.5) {
            t += d - 0.866;
            continue;
        }
        uint solid = fx_world_bytes(world, base, wrap, b).x;
        vec3 f = q - whole;
        uint octant = uint(f.x >= 0.5) | (uint(f.y >= 0.5) << 1) | (uint(f.z >= 0.5) << 2);
        if ((solid & (1u << octant)) != 0u) return true;
        t += 0.125;
    }
    return false;
}

// Whether the segment from a to b passes within radius of the segment from c0 to c1: a ray against a capsule.
bool cg_shadow_capsule(vec3 a, vec3 b, vec3 c0, vec3 c1, float radius) {
    vec3 d1 = b - a, d2 = c1 - c0, r = a - c0;
    float aa = dot(d1, d1), ee = dot(d2, d2), f = dot(d2, r), c = dot(d1, r), bb = dot(d1, d2);
    float denom = aa * ee - bb * bb;
    float s = denom > 1.0e-8 ? clamp((bb * f - c * ee) / denom, 0.0, 1.0) : 0.0;
    float t = clamp((bb * s + f) / max(ee, 1.0e-8), 0.0, 1.0);
    s = clamp((bb * t - c) / max(aa, 1.0e-8), 0.0, 1.0);
    vec3 gap = a + d1 * s - (c0 + d2 * t);
    return dot(gap, gap) < radius * radius;
}
