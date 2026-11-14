# animation — what a skeletal mesh plays, posed here every frame

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../AGENTS.md) · using it: `docs/vfx/prefabs.md`
> § *Playing clips*. The data it plays is `api/animation`; where it stands and what draws it is
> `render/scene/component/CgSkeletalMeshComponent`.

| Type | Role |
|---|---|
| `CgAnimInstance` | Unreal's `UAnimInstance`: sections on its own timeline, `seek`/`rate`/`playing`, `evaluate()` (sample each live section, normalise their weights as Sequencer's `MultiWayBlend`, blend under this frame's `slot` entries as a montage slot, layers, bone writes, resolve, skin), `skinning()`, `socket(name)` and `socket(handle, out)` in the mesh's space, `curveTarget` (where its material curves land: its component). Places nothing and draws nothing; headless on a server |
| `CgAnimCurveTarget` | Where an instance's material curves are set each frame: a skeletal mesh component's materials |
| `CgAnimLayer` | A slot over part of the skeleton, Unreal's layered blend per bone: `branch(bone, depth)` and `bone(name, w)` make its mask, `weight(w)` scales it; sections, `slot` entries and `stop` as the instance's, each optionally mirrored |
| `CgBoneWrite` | Unreal's modify bone: `instance.modify(bone)`, then translation, rotation and scale each `IGNORE`/`REPLACE`/`ADD` in `COMPONENT`, `PARENT` or `BONE` space, with an alpha; applied in order after the layers |
| `CgAnimation` | The world subsystem: in `POSE` (running while paused, advancing nothing then) advances every playing instance, evaluates them (split by `CgJob.forEach` from `PARALLEL_AT`), then applies their curves on the schedule's thread; drops its instances when the level changes. `server()`: the same in `GAMEPLAY` on a dedicated server's world |
| `CgSkinBounds` | A posed mesh's bounds from its bones: each bone's reach of skin, found once a mesh, around the posed bones (Unreal's physics-asset bounds) |
| `CgCpuSkin`, `CgPosedVertexSink` | Unreal's CPU skinning, quads into a sink, for a host's own vertex path under a shader pack (`CgSkeletalMeshComponent.cpuSkin`; gate `CgCpuSkinTest`) |
| `CgBonePalettes` | A frame's bone matrices on the GPU, written by the scene in `ATTACH` |

## Rules

- **A pose is a pure function of the instance's time**: nothing carried between frames, so seek and replay are exact.
  The gate is `CgAnimationTest` (`-PskinnedMeshes`, `-PskinnedClips`).
- **A frame's order**: sections and the slot, each layer, each bone write, resolve, skinning. Hidden bones collapse in
  the component's copy in `ATTACH`, never here. Layers, writes, mirroring and the server's posing are headless:
  `CgPoseFeaturesTest` (`headlessTest`).
- **A pose depends on no placement**: bone writes reach component space at most, so instances evaluate in any order,
  in parallel. Attachment is the scene's, in `ATTACH` after `POSE` (`CgSkeletalMeshTest`).
- **Instances change on the world's thread outside `POSE`**; inside it, each is evaluated by one worker alone.
- **Sockets are read after `ATTACH`**: an effect following a component's socket is placed at the top of `EFFECTS` and
  again in `LATE_ATTACH` (`CgVfxSystem.lateAttach`), so it never lags. The gates are `CgSocketTest` (the mesh's space)
  and `CgSkeletalMeshTest` (the world's).
- `PARALLEL_AT` (8): warm, `forEach` pays from 2 Kurama bodies (16 against 11 µs); the margin is for workers waking
  cold at a frame's start.
