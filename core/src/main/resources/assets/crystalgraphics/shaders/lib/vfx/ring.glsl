// A shock front running out along the ground, as a short wall round its centre: CgMeshShapes' sphere bent into a
// cylinder, its longitude round the wall and its latitude up it. Pure: the caller passes the sphere's texture coordinate.
//
//     vec3 outward; float rise;
//     vec3 world = fx_ring_wall(centre, radius, 1.5, 3.0, 0.6, cg_TexCoord0, outward, rise);
#pragma once

// A point of the wall: below blocks under centre to height over it, leaning out lean blocks a block it rises over the
// centre. outward is the horizontal direction from the centre; rise 0 at its foot to 1 at its top.
vec3 fx_ring_wall(vec3 centre, float radius, float below, float height, float lean, vec2 uv, out vec3 outward,
        out float rise) {
    float angle = uv.x * 6.28318531;
    outward = vec3(cos(angle), 0.0, sin(angle));
    rise = 1.0 - uv.y;
    float y = mix(-below, height, rise);
    return centre + outward * (radius + lean * max(y, 0.0)) + vec3(0.0, y, 0.0);
}
