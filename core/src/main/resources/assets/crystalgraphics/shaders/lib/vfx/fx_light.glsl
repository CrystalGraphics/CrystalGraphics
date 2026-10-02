// Light an effect casts on the opaque scene: the surface behind each pixel rebuilt from the depth snapshot, its normal
// from the surface's own screen-space derivatives, lit by point lights with a soft falloff. Additive, so it brightens
// what is there: the ground under a beam, the wall beside a blast. Include it only in a shader that reads depth.
//
//     vec3 surface, normal;
//     fx_scene_surface(eye, ray, FX_SCENE_DISTANCE(ray), surface, normal);
//     float light = fx_point_light(surface, normal, lightPosition, lightSize, reach);
#pragma once

#include "crystalgraphics:shaders/lib/vfx/fx_depth.glsl"

#ifndef CG_VERTEX_STAGE
// The scene's surface behind this pixel, sceneDistance along the ray, in camera-relative space, and its normal facing the
// eye. Fragment stage only: the normal comes from the surface's derivatives.
void fx_scene_surface(vec3 eye, vec3 ray, float sceneDistance, out vec3 point, out vec3 normal) {
    point = eye + ray * sceneDistance;
    vec3 n = cross(dFdx(point), dFdy(point));
    normal = dot(n, n) > 1.0e-12 ? normalize(n) : vec3(0.0, 1.0, 0.0);
    if (dot(normal, ray) > 0.0) normal = -normal;
}
#endif

// How much light a source of the given size at light reaches surface point p: an inverse-square falloff softened by the
// source's size, gone by reach. n is taken for a facing term later; it is unused now, because a normal rebuilt from
// depth flickers row to row and stripes the light.
float fx_point_light(vec3 p, vec3 n, vec3 light, float size, float reach) {
    vec3 l = light - p;
    float d2 = dot(l, l);
    float falloff = size * size / (d2 + size * size);
    return falloff * (1.0 - smoothstep(reach * 0.6, reach, sqrt(d2)));
}
