// A prefab part a clip plays on a SEQUENCE particle (CgVfxSlot.Kind.SEQUENCE): its node's rows at the particle's age,
// from the baked clips (CgVfxClipAtlas). A shader drawing on that kind declares FX_ON_SEQUENCE, includes this, declares
// the baked clips in its Buffers { } and places its vertices by the node:
//
//     #pragma cg_feature FX_ON_SEQUENCE
//     #include "crystalgraphics:shaders/lib/vfx/fx_sequence.glsl"
//     Buffers { FX_CLIPS ("Baked clips", vec4, readonly) }
//     ...
//     vec4 world = FX_SEQUENCE_TO_WORLD * vec4(cg_Position, 1.0);     // CG_OBJECT_TO_WORLD on any other kind
//     float opacity = FX_SEQUENCE_OPACITY;                             // the node's own
//     vec2 offset = vec2(FX_SEQ_SCALAR(0, _UV0Window.x), FX_SEQ_SCALAR(1, _UV0Window.y));   // the clip's, else the material's
//
// Its macros name CG_OBJECT_CUSTOM0 (w: the particle's age) and CUSTOM2 (the node's first vec4, its row's length, the
// clip's frames a second of age, its step mask), so they read in either stage.
#pragma once

#include "crystalgraphics:shaders/lib/vfx/fx_clip.glsl"

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
