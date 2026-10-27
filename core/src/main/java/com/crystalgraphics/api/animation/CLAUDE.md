# api/animation — skeletons, clips and morph targets as data

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md) · using them: `docs/vfx/prefabs.md`
> § *Skinned meshes*

## What This Package Is

What a skinned model moves by, as immutable data: no GPU, no thread, no Minecraft. A dedicated server may hold it.
The mesh that follows the bones is `api/mesh`'s `CgSkeletalMesh`; files load through `CgMeshLoader`.

## Type Map

| Type | Role |
|------|------|
| `CgSkeleton` | Unreal's `FReferenceSkeleton`: bones by name, a parent each (lower index; -1 for bone 0, the one root), a rest pose local to the parent (translation, unit rotation, scale). `builder().bone(name, parent, t[, r, s])`; `restGlobal(out)` composes parents first |
| `CgAnimSequence` | Unreal's `UAnimSequence`: a frame rate, `frameCount` keys, a track per animated bone **named, not indexed**, each channel one key or every key, and float curves (morph weights). `builder(name, rate, keys).track(...).curve(...)`; `build()` applies Unreal's trivial-key filter |
| `CgPose` | One skeleton's pose, mutable and reused: local TRS a bone, `sample` writes it, `blend` is Unreal's `BlendPosesTogether`, `resolve()` composes component space, `skinning(mesh, out)` multiplies the inverse binds. Allocates nothing after construction |
| `CgAnimSection`, `CgAnimEasing` | A clip on a timeline, Unreal's skeletal animation section: `animationTime(t)` is `MakeTransform`'s mapping, `weight(t)` the weight times the easing. Immutable |
| `CgMorphTargets` | per-vertex CSR of (target, position delta, normal delta); `builder(vertexCount).target(...).delta(...)`; `NONE` |
| `CgCookedAnimation` | `.cganim`: sequences as columns, written and read |

## Rules

- **Immutable and shared**; builders copy what they are handed.
- **A rotation is normalised once**: `unitLength` leaves a unit quaternion's bits alone, so a value reads back equal
  from its own cook. Never divide by a length again elsewhere.
- **Tracks bind by bone name, ignoring case** (Unreal's `FName`): `CgAnimSequence.sample` maps them once per
  skeleton and caches the last binding on the sequence.
- **Sampling, blending and section time follow Unreal and cite it at the method** (`TimeToIndex`, `FTransform::Blend`,
  `BlendPosesTogether`, `MakeTransform`, `EvaluateEasing`). The runtime that plays them is `com.crystalgraphics.animation`.
- **What follows Unreal is cited at its method**: the thresholds (`UAnimCompress::FilterTrivialKeys`), the rate
  choice and bake (`CgGltfLoader`). A change to either reads the source first.
- Gate: `CgSkeletalMeshTest` (`-PskinnedMeshes=<folder>,...` loads and cooks every `.glb` there).
