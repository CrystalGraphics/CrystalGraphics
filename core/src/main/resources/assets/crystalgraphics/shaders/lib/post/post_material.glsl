// A post material's draw (CgPostMaterial): one triangle over the screen, its weight in the object record.
//
//     void vertex(out v2f o) {
//         o.uv = post_fullscreen_uv(CG_VERTEX_ID);
//         gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
//     }
//     fragColor = vec4(mix(scene, mine, POST_WEIGHT), 1.0);
#pragma once

// 0 to 1: how much of the material a track or volume asks for.
#define POST_WEIGHT (CG_OBJECT_CUSTOM0.x)

// Vertex `vertex` of the triangle (-1,-1), (3,-1), (-1,3), as a uv: 0 to 1 over the screen, from the bottom left.
vec2 post_fullscreen_uv(int vertex) {
    return vec2(float((vertex & 1) << 1), float(vertex & 2));
}
