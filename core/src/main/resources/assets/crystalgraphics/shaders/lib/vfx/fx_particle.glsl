// What a particle shader needs to place a CgVfxQuads quad or a CgVfxRibbons stroke from a particle record. Pure
// functions: nothing here names cg_* or CG_*, so the record itself is read by the shader through CG_PARTICLE_*.
//
//     int n = fx_particle_index(FX_QUAD_INDEX, CG_OBJECT_CUSTOM0.x, CG_OBJECT_CUSTOM0.y);   // -1 past the draw's count
//     vec3 world = fx_particle_corner(centre, corner, vec2(length, width), angle, right, up);
#pragma once

// The record a vertex's quad or stroke reads: base + its index, or -1 when the draw has fewer particles than slots.
int fx_particle_index(float slot, float base, float count) {
    return slot < count ? int(base + slot + 0.5) : -1;
}

// A corner of a camera-facing quad: corner -1..1 on each axis, scaled by size (half-length along x, half-width along
// y), turned by angle in the eye's plane.
vec3 fx_particle_corner(vec3 centre, vec2 corner, vec2 size, float angle, vec3 right, vec3 up) {
    float c = cos(angle), s = sin(angle);
    vec2 scaled = corner * size;
    return centre + right * (c * scaled.x - s * scaled.y) + up * (s * scaled.x + c * scaled.y);
}

// The angle in the eye's plane a velocity points at: what a streak lines up with.
float fx_particle_heading(vec3 velocity, vec3 right, vec3 up) {
    return atan(dot(velocity, up), dot(velocity, right) + 1.0e-6);
}
