// The Sequence factory's: a clip's node on a SEQUENCE particle at the particle's age, from the baked clips
// (CgVfxClipAtlas). The factory places the part; a material declares the clips and reads the node's values:
//
//     Material { VertexFactory VfxMesh, Sequence }
//     Buffers { FX_CLIPS ("Baked clips", vec4, readonly) }
//     float opacity = FX_SEQUENCE_OPACITY;                                                   // under FX_ON_SEQUENCE
//     vec2 offset = vec2(FX_SEQ_SCALAR(0, _UV0Window.x), FX_SEQ_SCALAR(1, _UV0Window.y));   // the clip's, else its own
//
// CG_OBJECT_CUSTOM0.w is the particle's age, CUSTOM2 the node (first vec4, row length, frames a second of age, step
// mask): either stage.
#pragma once

#include "crystalgraphics:shaders/lib/vfx/clip.glsl"

// Whether row r steps rather than interpolates, from node constants c.
bool fx_sequence_steps(int r, vec4 c) {
    return ((uint(c.w) >> uint(r)) & 1u) != 0u;
}

// The vec4 of row r to read at age for frame a (k 0) or the one after (k 1).
int fx_sequence_index(int r, int k, float age, vec4 c) {
    int a, b;
    float t;
    fx_clip_frames(age, c.z, c.y - 1.0, fx_sequence_steps(r, c), a, b, t);
    return int(c.x) + r * int(c.y) + (k == 0 ? a : b);
}

// Row r's two frames mixed at age.
vec4 fx_sequence_mix(int r, vec4 first, vec4 next, float age, vec4 c) {
    int a, b;
    float t;
    fx_clip_frames(age, c.z, c.y - 1.0, fx_sequence_steps(r, c), a, b, t);
    return mix(first, next, t);
}

// A node's matrix from its three rows, for GLSL's column-major mat4.
mat4 fx_sequence_matrix(vec4 r0, vec4 r1, vec4 r2) {
    return transpose(mat4(r0, r1, r2, vec4(0.0, 0.0, 0.0, 1.0)));
}

// Row r of the draw's node at its particle's age (CgVfxClipAtlas: 0 to 2 its matrix, 3 its opacity and keyed scalars,
// 4 to 7 its scalars).
#define FX_SEQUENCE_ROW(r) fx_sequence_mix((r), FX_CLIPS(fx_sequence_index((r), 0, CG_OBJECT_CUSTOM0.w, CG_OBJECT_CUSTOM2)), FX_CLIPS(fx_sequence_index((r), 1, CG_OBJECT_CUSTOM0.w, CG_OBJECT_CUSTOM2)), CG_OBJECT_CUSTOM0.w, CG_OBJECT_CUSTOM2)

// The part's model matrix: its particle's, then its node's at the particle's age.
#define FX_SEQUENCE_TO_WORLD (CG_OBJECT_TO_WORLD * fx_sequence_matrix(FX_SEQUENCE_ROW(0), FX_SEQUENCE_ROW(1), FX_SEQUENCE_ROW(2)))

// The node's own opacity at the particle's age.
#define FX_SEQUENCE_OPACITY (FX_SEQUENCE_ROW(3).x)

// Material scalar i (CgVfxNodeClip.MATERIAL_SCALARS' order) at the particle's age where the clip gives it, else `own`:
// the material's value.
#define FX_SEQ_SCALAR(i, own) (((uint(FX_SEQUENCE_ROW(3).y) >> uint(i)) & 1u) != 0u ? FX_SEQUENCE_ROW(4 + (i) / 4)[(i) % 4] : (own))
