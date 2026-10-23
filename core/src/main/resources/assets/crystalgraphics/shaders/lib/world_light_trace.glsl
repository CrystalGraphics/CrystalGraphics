// Our meshes as the world lights' shadow rays meet them (CgWorldLights): each caster's distance field
// (CgMeshDistanceFields), five texels a caster in rows (eye-relative world to field voxels, three rows; the field's
// size and first float; its scales), the fields in one atlas of CG_MESH_FIELD_ROW floats a row. Every point and
// direction is eye-relative world.
#pragma once

// CgMeshDistanceFields.ROW: floats a row of the atlas.
const int CG_MESH_FIELD_ROW = 1024;

// A mesh field's distance at voxel c, clamped to the field.
float cg_mesh_field_at(sampler2D fields, int first, ivec3 n, ivec3 c) {
    c = clamp(c, ivec3(0), n - 1);
    int i = first + (c.z * n.y + c.y) * n.x + c.x;
    return texelFetch(fields, ivec2(i % CG_MESH_FIELD_ROW, i / CG_MESH_FIELD_ROW), 0).r;
}

// A mesh field's distance at voxel position v, trilinear, in the mesh's units; outside the field, plus the way to it.
float cg_mesh_field_sample(sampler2D fields, int first, ivec3 n, vec3 v, float voxel) {
    vec3 c = clamp(v, vec3(0.0), vec3(n - 1));
    ivec3 i = min(ivec3(floor(c)), n - 2);
    vec3 f = c - vec3(i);
    float x00 = mix(cg_mesh_field_at(fields, first, n, i), cg_mesh_field_at(fields, first, n, i + ivec3(1, 0, 0)), f.x);
    float x10 = mix(cg_mesh_field_at(fields, first, n, i + ivec3(0, 1, 0)), cg_mesh_field_at(fields, first, n, i + ivec3(1, 1, 0)), f.x);
    float x01 = mix(cg_mesh_field_at(fields, first, n, i + ivec3(0, 0, 1)), cg_mesh_field_at(fields, first, n, i + ivec3(1, 0, 1)), f.x);
    float x11 = mix(cg_mesh_field_at(fields, first, n, i + ivec3(0, 1, 1)), cg_mesh_field_at(fields, first, n, i + ivec3(1, 1, 1)), f.x);
    return mix(mix(x00, x10, f.y), mix(x01, x11, f.y), f.z) + length(v - c) * voxel;
}

// Whether one of the casters in rows lies on the eye-relative world segment aw + dir * t, t from t0 to t1: each caster's field
// sphere-traced over the part of the segment inside it, at most 32 steps, a hit within two voxels of the ray's start
// ignored as the object's own (Unreal's self-shadow bias).
bool cg_trace_casters(sampler2D rows, sampler2D fields, int count, vec3 aw, vec3 dir, float t0, float t1) {
    for (int k = 0; k < count; k++) {
        vec4 r0 = texelFetch(rows, ivec2(k * 5, 0), 0), r1 = texelFetch(rows, ivec2(k * 5 + 1, 0), 0);
        vec4 r2 = texelFetch(rows, ivec2(k * 5 + 2, 0), 0), size = texelFetch(rows, ivec2(k * 5 + 3, 0), 0);
        vec4 scale = texelFetch(rows, ivec2(k * 5 + 4, 0), 0);
        vec3 vo = vec3(dot(r0.xyz, aw) + r0.w, dot(r1.xyz, aw) + r1.w, dot(r2.xyz, aw) + r2.w);
        vec3 vd = vec3(dot(r0.xyz, dir), dot(r1.xyz, dir), dot(r2.xyz, dir));
        ivec3 n = ivec3(size.xyz);
        vec3 safe = mix(vd, vec3(1.0e-8), lessThan(abs(vd), vec3(1.0e-8)));
        vec3 ta = -vo / safe, tb = (vec3(n - 1) - vo) / safe;
        vec3 tIn = min(ta, tb), tOut = max(ta, tb);
        float enter = max(max(tIn.x, tIn.y), max(tIn.z, t0)), leave = min(min(tOut.x, tOut.y), min(tOut.z, t1));
        if (enter >= leave) continue;
        float t = enter, selfBias = 2.0 * scale.y;
        for (int s = 0; s < 32 && t < leave; s++) {
            float d = cg_mesh_field_sample(fields, int(size.w), n, vo + vd * t, scale.z) * scale.x;
            if (d < 0.1 * scale.y && t > selfBias) return true;
            t += max(d, 0.25 * scale.y);
        }
    }
    return false;
}
