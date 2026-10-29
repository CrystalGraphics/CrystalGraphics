// CgVfxModule.VectorField: Godot's vector field attractor over a CgVfxField. m0: the box's half extents, then 1 for a
// field that tiles; m1: strength, attenuation, tightness; m2: the box's centre from the grid's origin, all in the grid's
// own space. centre: that origin in the world; a0-a2: rows taking a world offset into the grid's space; b0-b2: rows
// turning its vectors into the world (CgVfxFieldTurn.frame). Filtered by hand as CgVfxField.sample filters, so both
// paths agree to rounding.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_types.glsl"

vec3 fx_field_texel(sampler3D field, ivec3 at, ivec3 size, bvec3 tiles) {
    ivec3 wrapped = at - size * ivec3(floor(vec3(at) / vec3(size))), held = clamp(at, ivec3(0), size - 1);
    ivec3 i = ivec3(tiles.x ? wrapped.x : held.x, tiles.y ? wrapped.y : held.y, tiles.z ? wrapped.z : held.z);
    return texelFetch(field, i, 0).xyz;
}

vec3 fx_field_sample(sampler3D field, vec3 uvw, bvec3 tiles) {
    ivec3 size = textureSize(field, 0);
    vec3 at = uvw * vec3(size) - 0.5;
    vec3 low = floor(at);
    vec3 t = at - low;
    ivec3 a = ivec3(low), b = a + 1;
    vec3 c00 = mix(fx_field_texel(field, a, size, tiles), fx_field_texel(field, ivec3(b.x, a.y, a.z), size, tiles), t.x);
    vec3 c10 = mix(fx_field_texel(field, ivec3(a.x, b.y, a.z), size, tiles),
            fx_field_texel(field, ivec3(b.x, b.y, a.z), size, tiles), t.x);
    vec3 c01 = mix(fx_field_texel(field, ivec3(a.x, a.y, b.z), size, tiles),
            fx_field_texel(field, ivec3(b.x, a.y, b.z), size, tiles), t.x);
    vec3 c11 = mix(fx_field_texel(field, ivec3(a.x, b.y, b.z), size, tiles), fx_field_texel(field, b, size, tiles), t.x);
    return mix(mix(c00, c10, t.y), mix(c01, c11, t.y), t.z);
}

void fx_vector_field(inout FxParticle p, inout FxForces f, FxStep s, vec4 m0, vec4 m1, vec4 m2, vec4 centre, vec4 a0,
                     vec4 a1, vec4 a2, vec4 b0, vec4 b1, vec4 b2, sampler3D field) {
    vec3 d = p.position - centre.xyz;
    vec3 local = vec3(dot(a0.xyz, d), dot(a1.xyz, d), dot(a2.xyz, d)) - m2.xyz;
    vec3 uvw = (local / m0.xyz + 1.0) * 0.5;
    // m0.w: the axes it tiles on, 1 x, 2 y, 4 z (CgVfxField.tileAxes); past its box on another it pushes nothing.
    int axes = int(m0.w + 0.5);
    bvec3 tiles = bvec3((axes & 1) != 0, (axes & 2) != 0, (axes & 4) != 0);
    vec3 past = max(-uvw, uvw - 1.0);
    if ((past.x > 0.0 && !tiles.x) || (past.y > 0.0 && !tiles.y) || (past.z > 0.0 && !tiles.z)) return;
    vec3 raw = fx_field_sample(field, uvw, tiles);
    vec3 v = vec3(dot(b0.xyz, raw), dot(b1.xyz, raw), dot(b2.xyz, raw));
    // Cascade's tightness: the velocity drawn toward the field's own, its value times its intensity, by tightness each
    // of its fixed 1/30 s steps (ParticleSimulationShader.usf:236-245, 584-585; r.GPUParticle.FixDeltaSeconds), so as
    // often a second whatever our step.
    if (m1.z > 0.0) {
        float pull = 1.0 - pow(1.0 - min(m1.z, 1.0), s.dt * 30.0);
        p.velocity = mix(p.velocity, v * m1.x * (m1.z / (m1.z + 0.001)), pull);
    }
    float l = length(v);
    if (l <= 0.0) return;
    f.accel += v / l * (pow(l, m1.y) * m1.x);
}
