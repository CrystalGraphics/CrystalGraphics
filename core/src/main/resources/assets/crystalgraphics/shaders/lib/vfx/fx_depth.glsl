// How far along a view ray the opaque scene is, from the depth snapshot. Include it only in a shader that reads depth:
// any mention of the depth buffer in a shader's includes makes the world renderer take a snapshot for it.
#pragma once

#ifndef CG_VERTEX_STAGE
// Fragment stage only; a macro, since the frame block is declared after any included file.
#define FX_SCENE_DISTANCE(ray) (CG_SCENE_EYE_DEPTH(gl_FragCoord.xy / CG_RESOLUTION) / max(dot(ray, -vec3(cg_ViewMatrix[0][2], cg_ViewMatrix[1][2], cg_ViewMatrix[2][2])), 1.0e-4))
#endif
