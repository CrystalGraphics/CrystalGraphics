# render/world — the world, drawn under the host's camera

> Root guide: [`CrystalGraphics/docs/ENGINE_API.md`](../../../../../../../../docs/ENGINE_API.md) § *CgWorldRenderer*

| Type | Role |
|------|------|
| `CgWorldRenderer` | Registered on `WORLD_OPAQUE` and `WORLD_TRANSPARENT` at `ORDER` (1000). Holds the frame's draws flat (absolute positions in doubles, local transforms, customs, queue, sort layer, order, group); at each stage culls them against the stage's view, picks a `CgMeshLods` draw's level by its screen height, sorts them, and records the prepass and the opaque or transparent pass onto the host's target, each declaring `sceneDepth`/`sceneColor` so its readers sample the target as it stands. `onFrame` listeners run once a frame, before the first world stage records |
| `CgSortKey` | Filament's key layout with a log-quantised distance: slot, sort layer, then opaque order, material, distance, mesh, or transparent group distance, order, distance. No far plane |
| `CgSortLayer` | Named sort layers, Unity's: a later one draws after an earlier whatever the distance. Built in `BACKGROUND`, `DEFAULT`, `EFFECTS`, `OVERLAY`; a mod defines its own `before`/`after` one in a static field |

## Rules

- **Camera-relative.** A model matrix is the draw's local transform plus `position - view position`, the subtraction
  in doubles. The pass constants come from `CgStageFrame.constants()`: the host's view and projection, the eye at the
  view's origin, `cg_WorldOrigin` at the host's absolute position.
- **A draw lives one ring frame** (`CgFrameRing.frame()`); a stage drops the previous frame's draws before it records.
  A host advancing no ring frame (a test) keeps them.
- **`cg_DepthBuffer` and `cg_SceneColor` are the graph's copies of the target** (`CgRasterPass.sceneDepth`,
  `sceneColor`): a reader sees every draw sorted before it except readers in a row with it, so a heat haze bends what is sorted
  under it and nothing above. `render/graph/AGENTS.md` § *Reading the target*.
- **Material chains** (`setNextPass`) are drawn as further draws on the same instances, in the forward passes only.
- **An indirect draw** (`.indirect(count, offset, mode, factor)`) carries its count into each pass's chunk. Its
  culling is by the bounds it states: what the count will be is unknown when it is culled. A count written in the same
  stage comes from a renderer registered below `ORDER` recording a compute pass into the stage's frame.
- The prepass takes a material's depth pass when it has one, else its forward pipeline with colour writes off
  (`CgRenderState.withColorMask`), cached per render state.
- **Emission** (`recordEmission`, after the transparent pass): every visible draw whose chain has an Emissive pass
  draws that pass into a transient R11G11B10F target, the tier's share of the target's size (Low 0.25, Ultra 1, else 0.5;
  `emissionScale` overrides it), reading the stage
  target's depth through `sceneDepth(unit, from)`, and publishes it as `CgFrameKeys.EMISSION`. **It produces and stops**:
  blurring and compositing are the post stack's (`render/post`), and with bloom off nothing reads the target, so the
  graph culls the pass. Its gate is `--mode=bloom-occlusion`: a ball behind a wall changes no pixel, at emission scales
  1 and 0.5, on gl and vulkan.
- **The emissive state is ONE ONE, never `CgBlendState.ADDITIVE`** (SRC_ALPHA ONE): emission is written with an
  alpha of 0, which ADDITIVE multiplies away.
