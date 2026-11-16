# animation — what a skeletal mesh plays, posed here every frame

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../AGENTS.md) · using it:
> `docs/animation/guides/graphs.md`. The data it plays is `api/animation`; the graph runtime is `graph/`, with its own
> guide; montages are `montage/`; where a mesh stands and what draws it is `render/scene/component/CgSkeletalMeshComponent`.

| Type | Role |
|---|---|
| `CgAnimInstance` | Unreal's `UAnimInstance` with its proxy: runs a `graph.CgAnimGraph` (one clip by default, `play(clip[, loop])`, `position`), `linkLayers`/`unlinkLayers`/`linkGraph` (Unreal's linked layers and graphs), `montages()` (`montage.CgMontages`, updated first in `preUpdate`, as `UpdateMontage` precedes `NativeUpdateAnimation`), `onUpdate` (the game thread's `NativeUpdateAnimation`, handed the frame's seconds), typed `set`, `slot(name)`, `rate`/`playing`, `placedBy` and `ground` (what world-space nodes read), `preUpdate` then `evaluate` (`tick` both), `skinning()`, `socket(name)` and `socket(handle, out)` in the mesh's space, `curveTarget`. Places nothing and draws nothing; headless on a server |
| `CgAnimCurveTarget` | Where an instance's material curves are set each frame: a skeletal mesh component's materials |
| `CgMaterialCurves` | A frame's material curves: the graph's base clips summed by weight, each curve layer's over them (`MaxWeight`) |
| `CgAnimation` | The world subsystem: in `POSE` (running while paused, moving 0 s then; its seconds scaled by the world's `timeDilation`, as Unreal's world delta is) every instance's `preUpdate` on the schedule's thread, then `evaluate` (split by `CgJob.forEach` from `PARALLEL_AT`), then their curves on the schedule's thread; drops its instances when the level changes. `server()`: the same in `GAMEPLAY` on a dedicated server's world |
| `CgSkinBounds` | A posed mesh's bounds from its bones: each bone's reach of skin, found once a mesh, around the posed bones (Unreal's physics-asset bounds) |
| `CgCpuSkin`, `CgPosedVertexSink` | Unreal's CPU skinning, quads into a sink, for a host's own vertex path under a shader pack (`CgSkeletalMeshComponent.cpuSkin`; gate `CgCpuSkinTest`) |
| `ik/` | Unreal's bone controllers as graph nodes: modify bone, two-bone IK, look-at, FABRIK, CCD, constraints, leg IK, foot placement, spring bone, AnimDynamics. Its own guide |
| `dynamics/` | `CgAnimPhys`, the rigid-body solver AnimDynamics runs on (Stan Melax's Sandbox, as Unreal adapted it) |
| `rig/` | `CgRig` (a skeleton read as a body: root, pelvis, named chains with IK goals, `bend`, `rides`, `pivot`, carried effect bones; `toBuilder`; the `rig` kind's compiler), `CgRigAdapter`/`CgRigAdapters` (a library's facing and rotation-only bones: the engine's, GeckoLib's, Minecraft's), `CgRigs` (default rigs from bone names: the standard humanoid, GeckoLib's humanoid, Minecraft's humanoid, `mc.model.CgVanillaHumanoid`) |
| `retarget/` | Unreal's IK Retargeter: `CgRetargeter` (chain mapping, retarget poses, the op stack; `auto`, the `retargeter` kind's compiler), `CgRetargetPoses` (auto-align), `CgRetargetProcessor` (a source pose in, the target's out, 0 B a run; the source's carried bones added to `targetSkeleton()`; `sockets`/`mesh` place every source socket and bone name on the target; a rigid two-bone limb points at its goal), `CgRetargetBake` (a clip baked onto the target, cached), `CgClipResolver` (own, retargeted, fail; `plan` bakes as an asset is prepared, before READY). Gates: `CgRetargetTest` (headless) and `CgRetargetKakarotTest` (Goku's battle clips on RPG-Core's humanoid and Minecraft's, his 433 bone and socket names, a carried dummy, an own clip winning). Guide: `docs/animation/guides/retargeting.md` |
| `locomotion/` | Locomotion's sums and the evaluator: `CgSequenceEvaluatorNode` (a clip at a time set each frame: a variable's, advanced by distance covered, or matched to the distance to go), `CgDistanceMatching`, `CgMovementPrediction` (stop and pivot points). Its own guide; the warping nodes are `ik/`'s. Guide: `docs/animation/guides/locomotion.md` |
| `warping/` | Motion warping: `CgMotionWarping` (a character's named targets; the root-motion montage's step bent inside its clip's `CgWarpWindow`s so the move ends on its target, a skew warp; placement read live through the instance's `placedBy`). `anim.motionWarping()`. Guide: `docs/animation/guides/motion-warping.md` |
| `debug/` | `CgAnimDebug`: an instance's state as text (variables, tags, state machines, the frame's players, montages), Unreal's `showdebug animation`; shown in game over the entity looked at by `world.actor.CgAnimDebugOverlay` (`/cgc anim debug`) |
| `CgCollisionGround` | A `graph.CgAnimGround` over a `CgCollisionWorld`: foot placement's sweeps against Minecraft's blocks |
| `CgBonePalettes` | A frame's bone matrices on the GPU, written by the scene in `ATTACH` |

## Rules

- **Unreal's frame**: the game thread sets variables and slot entries (`onUpdate`), `preUpdate` copies the variables;
  a worker updates the graph (weights, relevance, every player's tick) and evaluates it, then resolve and skinning; the game thread applies the curves. Nothing of the graph is touched off its worker in between.
- **A graph keeps state between frames** (clip times, blend weights), as Unreal's: a pose is not a function of one
  clock. A sequence stays seekable because it sets slot entries at the time it is sought to (`CgAnimationTest`, every
  Kakarot clip played and sought bit for bit, `-PskinnedClips`).
- **0 B a frame once warm**, gated by `graph.CgAnimGraphTest`. Variables are set through typed overloads: a generic
  setter would box every float.
- **A pose reads its placement only as handed over in `preUpdate`** (`placedBy`, `ground`), so instances evaluate in
  any order, in parallel. Attachment is the scene's, in `ATTACH` after `POSE` (`CgSkeletalMeshTest`). Hidden bones collapse in
  the component's copy in `ATTACH`, never here.
- **Sockets are read after `ATTACH`**: an effect following a component's socket is placed at the top of `EFFECTS` and
  again in `LATE_ATTACH` (`CgVfxSystem.lateAttach`), so it never lags. The gates are `CgSocketTest` (the mesh's space)
  and `CgSkeletalMeshTest` (the world's).
- Modify bone, mirroring and the server's posing are headless: `CgPoseFeaturesTest` (`headlessTest`).
- `PARALLEL_AT` (8): warm, `forEach` pays from 2 Kurama bodies (16 against 11 µs); the margin is for workers waking
  cold at a frame's start.
