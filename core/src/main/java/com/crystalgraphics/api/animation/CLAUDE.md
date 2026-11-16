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
| `CgAnimSequence` | Unreal's `UAnimSequence`: a frame rate, `frameCount` keys, a track per animated bone **named, not indexed**, each channel one key or every key, and float curves (morph weights). `builder(name, rate, keys).track(...).curve(...)`; `build()` applies Unreal's trivial-key filter. **Keyed** (`keyed(name, duration).track(bone, nine CgRichCurves)`): each channel a curve with keys at their own times, the rotation Euler `Rz·Ry·Rx`, sampled exactly at any time: GeckoLib's clips. No frames, never cooked; per-frame reads throw |
| `CgClipModifiers` | Data baked into a clip at load from its own motion: `rootMotionFromFeet` (an in-place clip's root moved as its planted feet say, a step's sweep at an even pace, a loop's seam one step), `footSyncMarkers` (a marker where a foot passes the root forward), `distanceCurve` (root motion distance to its stop, negative before), `speed`, `encodeRootBone` (a clip's travel moved from its pelvis onto its root, the pose unchanged). `CgAnimSequence.withTrack` writes a track |
| `CgClipSettings` | What a clip carries beside its keys (`UAnimSequence`'s settings): `CgSyncMarker`s, `CgRootMotion`, `CgAdditive`, its notify sequence's id. On every sequence (`settings()`, `with(settings)`, the builders' `settings`); `NONE` by default. `markersPassed(from, to, moved, sink)` is `CollectMarkersInRange`; `sample(pose, t, extractRootMotion)` locks the root (`FRootMotionReset`); `extractRootMotion(skeleton, from, dt, looping, out)` is `ExtractRootMotion`; `sampleAdditive(out, base, t)` is `GetBonePose_Additive`. Using them: `docs/animation/guides/clips.md` |
| `CgWarpWindow` | A span of a clip whose root motion is bent so the move ends on a named target (`CgClipSettings.warpWindows`, `warp_windows` in a clip file); played by `animation.warping.CgMotionWarping` |
| `CgClipDefinition` | A `clip` asset compiled (`clips/<clip id>.yaml`); `settings(clips)` resolves its additive base clip. A mesh-space additive is an aim offset's sample. Gate: `CgClipDataTest` (headless) |
| `CgAnimPose` | One skeleton's pose, mutable and reused: local TRS a bone, `sample` writes it, `blend` is Unreal's `BlendPosesTogether`, `convertToAdditive`/`accumulateAdditive` its additive pair (`ConvertPoseToAdditive`, `AccumulateLocalSpaceAdditivePoseInternal`), `accumulateMeshRotationAdditive` an aim offset's (with `toMeshRotation`/`fromMeshRotation`), `resolve()` composes component space, `skinning(mesh, out)` multiplies the inverse binds. Allocates nothing after construction |
| `CgMirrorTable` | Unreal's `UMirrorDataTable`: bone pairs and an axis; `byName(skeleton, axis)` pairs by Unreal's default find-and-replace (`r_`/`l_`, `_l`/`_r`, `right`/`left`, infixes, centre bones onto themselves), `builder(...).pair(a, b)`, `self(bone)`. `CgAnimPose.mirror(table)` is `MirrorPose`; a bone outside the table is left as it is |
| `CgSocket` | Unreal's `USkeletalMeshSocket`: a name, a bone, a relative transform; on `CgSkeletalMesh` (`Builder.socket`, `withSockets`), names compared ignoring case |
| `CgSkeleton.withBone`, `extendsSkeleton` | A real bone appended under a parent (a retarget's carried effect bone, keyed by name like any); whether a skeleton starts with another's bones |
| `CgVirtualBone` | Unreal's `FVirtualBone`: a bone added under a source that follows a target. `CgSkeleton.withVirtualBones` (and the mesh's) appends them; every clip sample derives them after its tracks (`CgAnimPose.deriveVirtualBones`), where Unreal bakes them at cook; a clip's own track for one wins. Gate: `CgFootPlacementTest.anImportedSkeletonPlantsOnItsVirtualIkBones` |
| `CgSkeletonDefinition` | A `skeleton` asset compiled (`CgBuiltInCompilers`): its `virtual_bones`, then its `from: bone` sockets in the host's units, a bone's own space converted as CUE4Parse and Unreal's exporter convert bones (`CgUnits.UNREAL`); `compile(id, tree, units)` for a file read by hand. Gate: `CgSkeletonDefinitionTest` (`-Pkits=kakarot_kit=<dir>` for Goku's) |
| `CgMorphTargets` | per-vertex CSR of (target, position delta, normal delta); `builder(vertexCount).target(...).delta(...)`; `NONE` |
| `CgCookedAnimation` | `.cganim`: sequences as columns, written and read |

## Rules

- **Immutable and shared**; builders copy what they are handed.
- **A rotation is normalised once**: `unitLength` leaves a unit quaternion's bits alone, so a value reads back equal
  from its own cook. Never divide by a length again elsewhere.
- **Tracks bind by bone name, ignoring case** (Unreal's `FName`): `CgAnimSequence.sample` maps them once per
  skeleton and caches the last binding on the sequence.
- **Sampling and blending follow Unreal and cite it at the method** (`TimeToIndex`, `FTransform::Blend`,
  `BlendPosesTogether`). The runtime that plays them is `com.crystalgraphics.animation`; a section's time on a
  timeline is `sequence.animation.CgAnimSection`'s.
- **What follows Unreal is cited at its method**: the thresholds (`UAnimCompress::FilterTrivialKeys`), the rate
  choice and bake (`CgGltfLoader`). A change to either reads the source first.
- Gate: `CgSkeletalMeshTest` (`-PskinnedMeshes=<folder>,...` loads and cooks every `.glb` there).
