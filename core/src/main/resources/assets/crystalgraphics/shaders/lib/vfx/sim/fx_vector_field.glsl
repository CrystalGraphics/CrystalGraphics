// CgVfxModule.VectorField: Godot's vector field attractor over a CgVfxField. m0: the box's half extents, then 1 for a
// field that tiles; m1: strength, attenuation. centre: the box's centre. Filtered by hand as CgVfxField.sample filters,
// so both paths agree to rounding.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_types.glsl"

vec3 fx_field_texel(sampler3D field, ivec3 at, ivec3 size, bool tiles) {
    ivec3 i = tiles ? at - size * ivec3(floor(vec3(at) / vec3(size))) : clamp(at, ivec3(0), size - 1);
    return texelFetch(field, i, 0).xyz;
}

vec3 fx_field_sample(sampler3D field, vec3 uvw, bool tiles) {
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

void fx_vector_field(inout FxParticle p, inout FxForces f, FxStep s, vec4 m0, vec4 m1, vec4 centre, sampler3D field) {
    vec3 uvw = ((p.position - centre.xyz) / m0.xyz + 1.0) * 0.5;
    bool tiles = m0.w > 0.0;
    if (!tiles && (any(lessThan(uvw, vec3(0.0))) || any(greaterThan(uvw, vec3(1.0))))) return;
    vec3 v = fx_field_sample(field, uvw, tiles);
    float l = length(v);
    if (l <= 0.0) return;
    f.accel += v / l * (pow(l, m1.y) * m1.x);
}
