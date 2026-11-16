// Depth of field's shared maths (CgDepthOfField's passes). Lengths are half-size texels; a circle of confusion is
// signed, below 0 nearer than the focus and above 0 farther.
#pragma once

// The circle of confusion's radius at eye distance d: coc.x the radius a point at infinity blurs to, coc.y the focus
// distance, clamped to the nearer layer's largest (coc.z) and the farther's (coc.w).
float dof_coc(float d, vec4 coc) {
    return clamp(coc.x * (1.0 - coc.y / max(d, 1.0e-4)), -coc.z, coc.w);
}

// How far the aperture reaches toward angle a, 1 on its circle: shape.x blades, shape.y of the way from the circle to
// their polygon, turned by shape.z radians.
float dof_edge(float a, vec4 shape) {
    float sector = 6.2831853 / shape.x;
    float t = mod(a - shape.z, sector) - 0.5 * sector;
    return mix(1.0, cos(0.5 * sector) / cos(t), shape.y);
}
