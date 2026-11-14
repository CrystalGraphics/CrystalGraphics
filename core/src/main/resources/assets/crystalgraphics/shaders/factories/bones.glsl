// CgBonePalettes, Unreal's GPU skin vertex factory: compiled into a material's CG_SKINNED variant, never declared by
// hand. cg_Position and cg_Normal then read as the skinned vertex, so a material skins with no code of its own.
#pragma once

// Where the draw's palette starts, in bones: its object record's spare x (CgWorldRenderer's .bones(base)).
#define CG_BONE_BASE int(CG_OBJECT_SPARE.x + 0.5)
// Whether its mesh is SKINNED_WIDE, 16-bit indices in a 52-byte vertex: the record's spare y (CgMeshDrawCommands).
#define CG_BONES_WIDE (CG_OBJECT_SPARE.y > 0.5)

// Where last frame's palette starts: the record's spare z, its base plus 1, or 0 for none (this frame's then).
#define CG_BONE_PREVIOUS_BASE (CG_OBJECT_SPARE.z > 0.5 ? int(CG_OBJECT_SPARE.z - 0.5) : CG_BONE_BASE)

// Bone b of the palette starting at base, the three rows of its 3x4 skinning transform.
#define CG_BONE_ROW_AT(base, b, r) (BONES_DATA(((base) + int(b)) * 3 + (r)).row)
// Bone b of the draw's palette.
#define CG_BONE_ROW(b, r) CG_BONE_ROW_AT(CG_BONE_BASE, b, r)

// The weighted sum of the vertex's four bones' transforms in the palette at base: Unreal's
// ComputeBoneMatrixWithLimitedInfluences.
void cg_BoneBlendAt(int base, uvec4 bones, vec4 weights, out vec4 r0, out vec4 r1, out vec4 r2) {
    r0 = weights.x * CG_BONE_ROW_AT(base, bones.x, 0) + weights.y * CG_BONE_ROW_AT(base, bones.y, 0)
       + weights.z * CG_BONE_ROW_AT(base, bones.z, 0) + weights.w * CG_BONE_ROW_AT(base, bones.w, 0);
    r1 = weights.x * CG_BONE_ROW_AT(base, bones.x, 1) + weights.y * CG_BONE_ROW_AT(base, bones.y, 1)
       + weights.z * CG_BONE_ROW_AT(base, bones.z, 1) + weights.w * CG_BONE_ROW_AT(base, bones.w, 1);
    r2 = weights.x * CG_BONE_ROW_AT(base, bones.x, 2) + weights.y * CG_BONE_ROW_AT(base, bones.y, 2)
       + weights.z * CG_BONE_ROW_AT(base, bones.z, 2) + weights.w * CG_BONE_ROW_AT(base, bones.w, 2);
}

// The draw's own palette's blend.
void cg_BoneBlend(uvec4 bones, vec4 weights, out vec4 r0, out vec4 r1, out vec4 r2) {
    cg_BoneBlendAt(CG_BONE_BASE, bones, weights, r0, r1, r2);
}
