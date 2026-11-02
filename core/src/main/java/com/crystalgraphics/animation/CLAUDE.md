# animation — skinned meshes posed here, every frame

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../AGENTS.md) · using it: `docs/vfx/prefabs.md`
> § *Playing clips*. The data it plays is `api/animation`.

| Type | Role |
|---|---|
| `CgAnimInstance` | A skeletal mesh posed by the engine: sections on its own timeline, `seek`/`advance`/`rate`/`playing`, `evaluate()` (sample each live section, normalise their weights as Sequencer's `MultiWayBlend`, blend under this frame's `slot` entries as a montage slot, resolve, skin), `palette()` for the draw (this ring frame's only), `place` (component-to-world), `socket(name)` and `socket(handle, point, turn)` as Unreal's `GetSocketTransform`, `material(slot, m)` (Unreal's `OverrideMaterials`) and `boundsScale`. Drawn by `CgWorldRenderer.draw(instance)`, each slot in its material, or `draw(instance, material)`; followed by `CgVfxEffect.follow(instance, socket)` |
| `CgAnimLayer` | A slot over part of the skeleton, Unreal's layered blend per bone: `branch(bone, depth)` and `bone(name, w)` make its mask, `weight(w)` scales it; sections, `slot` entries and `stop` as the instance's, each optionally mirrored |
| `CgBoneWrite` | Unreal's modify bone: `instance.write(bone)`, then translation, rotation and scale each `IGNORE`/`REPLACE`/`ADD` in `COMPONENT`, `PARENT` or `BONE` space, with an alpha; applied in order after the layers |
| `CgAnimation` | The world subsystem: in `POSE` (running while paused, advancing nothing then) advances every playing instance, evaluates them (split by `CgJob.forEach` from `PARALLEL_AT`), then writes each palette, and the owner's where a bone is hidden from it, on the schedule's thread; drops its instances when the level changes. `server()`: the same in `GAMEPLAY` on a dedicated server's world, with no palette |

## Rules

- **A pose is a pure function of the instance's time**: nothing carried between frames, so seek and replay are exact.
  The gate is `CgAnimationTest` (`-PskinnedMeshes`, `-PskinnedClips`).
- **A frame's order**: sections and the slot, each layer, each bone write, resolve, skinning, then hidden bones
  collapse in the skinning matrices alone. Layers, writes, mirroring, hiding and the server's posing are headless:
  `CgPoseFeaturesTest` (`headlessTest`).
- **Draws that name a palette are submitted after `POSE`** (in `SUBMIT`): a base is its ring frame's.
- **Instances change on the client world's thread outside `POSE`**; inside it, each is evaluated by one worker alone.
- **Sockets are read after `POSE`**: an effect following one is placed at the top of `EFFECTS` and again in
  `LATE_ATTACH` (`CgVfxSystem.lateAttach`), so it never lags. The gate is `CgSocketTest`.
- `PARALLEL_AT` (8): warm, `forEach` pays from 2 Kurama bodies (16 against 11 µs); the margin is for workers waking
  cold at a frame's start.
