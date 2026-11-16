# presentation — what produces looks

> Plan: `plan/crystalgraphics/renderer/steps/R15.md`.

The renderer draws scene components and runs a view's post chain (`render/post`, `render/scene`). This package is what
puts looks into a scene from authored content: a sequence's post and colour-region spawnables, impact sequences, and a
stage file. Nothing under `render` names it.

| Package | Holds |
|---|---|
| `post` | `CgPostVolumeSpawner` (a sequence's `post` spawnable: a `CgPostVolume` whose settings carry the block's post materials as weighted blendables), `CgPostVolumePreparer` (a sequence's post materials and looks built before `READY`), `CgColourRegionSpawner` (a `colour_region` spawnable: a `CgColourRegion` component placed each update) |
| `impact` | `CgImpactSequence`: an impact frame's beats as a volume's settings over time |
| `stage` | `CgStageAssets`: the `stage` kind's compiler, a stage file to a `CgPrefab` (anchor root, `volume<i>` post nodes, a `fog` node). Spawned like any prefab; `CgPrefabPostPreparer` readies it |

## Rules

- **Produce components, never draw.** A producer spawns volumes, regions and prefabs into a scene and removes them when
  its play ends; the chain draws what the scene holds.
- **Settings types live in `render/post/settings`**, not here: prefabs read post blocks, and `render` never imports
  `presentation`.
