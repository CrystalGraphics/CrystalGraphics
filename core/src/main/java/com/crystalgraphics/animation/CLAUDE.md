# animation — what a skeletal mesh plays, posed here every frame

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../AGENTS.md) · using it:
> `docs/animation/guides/graphs.md`. The data it plays is `api/animation`; the graph runtime is `graph/`, with its own
> guide; where a mesh stands and what draws it is `render/scene/component/CgSkeletalMeshComponent`.

| Type | Role |
|---|---|
| `CgAnimInstance` | Unreal's `UAnimInstance` with its proxy: runs a `graph.CgAnimGraph` (one clip by default, `play(clip[, loop])`, `position`), `onUpdate` (the game thread's `NativeUpdateAnimation`, handed the frame's seconds), typed `set`, `slot(name)`, `rate`/`playing`, bone writes, `preUpdate` then `evaluate` (`tick` both), `skinning()`, `socket(name)` and `socket(handle, out)` in the mesh's space, `curveTarget`. Places nothing and draws nothing; headless on a server |
| `CgAnimCurveTarget` | Where an instance's material curves are set each frame: a skeletal mesh component's materials |
| `CgMaterialCurves` | A frame's material curves: the graph's base clips summed by weight, each curve layer's over them (`MaxWeight`) |
| `CgBoneWrite` | Unreal's modify bone: `instance.modify(bone)`, then translation, rotation and scale each `IGNORE`/`REPLACE`/`ADD` in `COMPONENT`, `PARENT` or `BONE` space, with an alpha; applied in order after the graph. Kept until the modify-bone node replaces it; no new callers |
| `CgAnimation` | The world subsystem: in `POSE` (running while paused, moving 0 s then) every instance's `preUpdate` on the schedule's thread, then `evaluate` (split by `CgJob.forEach` from `PARALLEL_AT`), then their curves on the schedule's thread; drops its instances when the level changes. `server()`: the same in `GAMEPLAY` on a dedicated server's world |
| `CgSkinBounds` | A posed mesh's bounds from its bones: each bone's reach of skin, found once a mesh, around the posed bones (Unreal's physics-asset bounds) |
| `CgCpuSkin`, `CgPosedVertexSink` | Unreal's CPU skinning, quads into a sink, for a host's own vertex path under a shader pack (`CgSkeletalMeshComponent.cpuSkin`; gate `CgCpuSkinTest`) |
| `CgBonePalettes` | A frame's bone matrices on the GPU, written by the scene in `ATTACH` |

## Rules

- **Unreal's frame**: the game thread sets variables and slot entries (`onUpdate`), `preUpdate` copies the variables;
  a worker updates the graph (weights, relevance, every player's tick) and evaluates it, then bone writes, resolve and
  skinning; the game thread applies the curves. Nothing of the graph is touched off its worker in between.
- **A graph keeps state between frames** (clip times, blend weights), as Unreal's: a pose is not a function of one
  clock. A sequence stays seekable because it sets slot entries at the time it is sought to (`CgAnimationTest`, every
  Kakarot clip played and sought bit for bit, `-PskinnedClips`).
- **0 B a frame once warm**, gated by `graph.CgAnimGraphTest`. Variables are set through typed overloads: a generic
  setter would box every float.
- **A pose depends on no placement**: bone writes reach component space at most, so instances evaluate in any order,
  in parallel. Attachment is the scene's, in `ATTACH` after `POSE` (`CgSkeletalMeshTest`). Hidden bones collapse in
  the component's copy in `ATTACH`, never here.
- **Sockets are read after `ATTACH`**: an effect following a component's socket is placed at the top of `EFFECTS` and
  again in `LATE_ATTACH` (`CgVfxSystem.lateAttach`), so it never lags. The gates are `CgSocketTest` (the mesh's space)
  and `CgSkeletalMeshTest` (the world's).
- Bone writes, mirroring and the server's posing are headless: `CgPoseFeaturesTest` (`headlessTest`).
- `PARALLEL_AT` (8): warm, `forEach` pays from 2 Kurama bodies (16 against 11 µs); the margin is for workers waking
  cold at a frame's start.
