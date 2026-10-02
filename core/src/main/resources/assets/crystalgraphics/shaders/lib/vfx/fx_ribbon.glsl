// What a CgVfxRibbons shader needs to place stateless particles: stable hashes of a ribbon's index, a uniform direction,
// and a vertex pushed sideways to face the eye. Nothing here names cg_* or CG_*.
//
//     float index = cg_Normal.x, along = cg_TexCoord0.x, side = cg_TexCoord0.y * 2.0 - 1.0;
//     vec4 h = fx_hash41(index + seed * 101.0);
//     vec3 world = (CG_OBJECT_TO_WORLD * vec4(pointOnPath, 1.0)).xyz;
//     world = fx_ribbon_vertex(world, tangentInWorld, FX_CAMERA, halfWidth, side);
#pragma once

// Four 0..1 numbers from one, stable for the same input (Dave Hoskins' hash without sine).
vec4 fx_hash41(float p) {
    vec4 p4 = fract(vec4(p) * vec4(0.1031, 0.1030, 0.0973, 0.1099));
    p4 += dot(p4, p4.wzxy + 33.33);
    return fract((p4.xxyz + p4.yzzw) * p4.zywx);
}

// A unit vector, uniform over the sphere, from two 0..1 numbers.
vec3 fx_sphere_dir(vec2 u) {
    float z = u.x * 2.0 - 1.0;
    float a = u.y * 6.28318531;
    float r = sqrt(max(1.0 - z * z, 0.0));
    return vec3(r * cos(a), r * sin(a), z);
}

// v turned by angle about +z.
vec3 fx_rotate_z(vec3 v, float angle) {
    float c = cos(angle), s = sin(angle);
    return vec3(c * v.x - s * v.y, s * v.x + c * v.y, v.z);
}

// A ribbon vertex: the path's point moved halfWidth sideways, across the path and facing the eye; side is -1 or 1.
vec3 fx_ribbon_vertex(vec3 world, vec3 tangent, vec3 eye, float halfWidth, float side) {
    vec3 across = cross(tangent, eye - world);
    float l = length(across);
    across = l > 1.0e-6 ? across / l : vec3(0.0, 1.0, 0.0);
    return world + across * (halfWidth * side);
}
