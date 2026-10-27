// CgBonePalettes' shader environment: the skinned vertex factory, Unreal's GPU skin vertex factory.
//
// INJECTED in a material's CG_SKINNED variant, after the buffer's declaration; never declared by hand. The compiler
// then reads cg_Position and cg_Normal as the skinned vertex, so a material skins with no code of its own.
#pragma once

// Where the draw's palette starts, in bones: its object record's spare x (CgWorldRenderer's .bones(base)).
#define CG_BONE_BASE int(CG_OBJECT_SPARE.x + 0.5)

// Bone b of the draw's palette, the three rows of its 3x4 skinning transform.
#define CG_BONE_ROW(b, r) (BONES_DATA((CG_BONE_BASE + int(b)) * 3 + (r)).row)

// The weighted sum of the vertex's four bones' transforms: Unreal's ComputeBoneMatrixWithLimitedInfluences.
void cg_BoneBlend(uvec4 bones, vec4 weights, out vec4 r0, out vec4 r1, out vec4 r2) {
    r0 = weights.x * CG_BONE_ROW(bones.x, 0) + weights.y * CG_BONE_ROW(bones.y, 0)
       + weights.z * CG_BONE_ROW(bones.z, 0) + weights.w * CG_BONE_ROW(bones.w, 0);
    r1 = weights.x * CG_BONE_ROW(bones.x, 1) + weights.y * CG_BONE_ROW(bones.y, 1)
       + weights.z * CG_BONE_ROW(bones.z, 1) + weights.w * CG_BONE_ROW(bones.w, 1);
    r2 = weights.x * CG_BONE_ROW(bones.x, 2) + weights.y * CG_BONE_ROW(bones.y, 2)
       + weights.z * CG_BONE_ROW(bones.z, 2) + weights.w * CG_BONE_ROW(bones.w, 2);
}
