// A post material's draw (CgPostMaterial): one triangle over the screen, its weight in the object record.
//
//     void vertex(out v2f o) {
//         o.uv = post_fullscreen_uv(CG_VERTEX_ID);
//         gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
//     }
//     fragColor = vec4(mix(scene, mine, POST_WEIGHT), 1.0);
#pragma once

// A post material works in linear light, as Unreal's do: the scene decoded where it is stored sRGB (CG_LINEAR_SCENE
// false), and its result encoded back. The post template applies both; a material sampling the scene itself decodes.
// Macros: this file is read before the engine's environment.
#define cg_PostDecode(c) (CG_LINEAR_SCENE ? (c) : cg_SceneDecode(c))
#define cg_PostEncode(c) (CG_LINEAR_SCENE ? (c) : cg_SceneEncode(c))

// 0 to 1: how much of the material a track or volume asks for.
#define POST_WEIGHT (CG_OBJECT_CUSTOM0.x)

// Vertex `vertex` of the triangle (-1,-1), (3,-1), (-1,3), as a uv: 0 to 1 over the screen, from the bottom left.
vec2 post_fullscreen_uv(int vertex) {
    return vec2(float((vertex & 1) << 1), float(vertex & 2));
}
