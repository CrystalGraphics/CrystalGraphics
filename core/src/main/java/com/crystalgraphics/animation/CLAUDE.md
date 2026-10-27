# animation — skinned meshes posed here, every frame

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../AGENTS.md) · using it: `docs/vfx/prefabs.md`
> § *Playing clips*. The data it plays is `api/animation`.

| Type | Role |
|---|---|
| `CgAnimInstance` | A skeletal mesh posed by the engine: sections on its own timeline, `seek`/`advance`/`rate`/`playing`, `evaluate()` (sample each live section, normalise their weights as Sequencer's `MultiWayBlend`, blend under this frame's `slot` entries as a montage slot, resolve, skin), `palette()` for the draw (this ring frame's only), `place` (component-to-world), `socket(name)` and `socket(handle, point, turn)` as Unreal's `GetSocketTransform`, `material(slot, m)` (Unreal's `OverrideMaterials`) and `boundsScale`. Drawn by `CgWorldRenderer.draw(instance)`, each slot in its material, or `draw(instance, material)`; followed by `CgVfxEffect.follow(instance, socket)` |
| `CgAnimation` | The world subsystem: in `POSE` advances every playing instance, evaluates them (split by `CgJob.forEach` from `PARALLEL_AT`), then writes each palette on the schedule's thread |

## Rules

- **A pose is a pure function of the instance's time**: nothing carried between frames, so seek and replay are exact.
  The gate is `CgAnimationTest` (`-PskinnedMeshes`, `-PskinnedClips`).
- **Draws that name a palette are submitted after `POSE`** (in `SUBMIT`): a base is its ring frame's.
- **Instances change on the client world's thread outside `POSE`**; inside it, each is evaluated by one worker alone.
- **Sockets are read after `POSE`**: an effect following one is placed at the top of `EFFECTS` and again in
  `LATE_ATTACH` (`CgVfxSystem.lateAttach`), so it never lags. The gate is `CgSocketTest`.
- `PARALLEL_AT` (8): warm, `forEach` pays from 2 Kurama bodies (16 against 11 µs); the margin is for workers waking
  cold at a frame's start.
