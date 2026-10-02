# render/world — the world, drawn under the host's camera

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../AGENTS.md) § *CgWorldRenderer*

| Type | Role |
|------|------|
| `CgWorldRenderer` | Registered on `WORLD_OPAQUE` and `WORLD_TRANSPARENT` at `ORDER` (1000). Holds the frame's draws flat (absolute positions in doubles, local transforms, customs, queue, priority); at each stage culls them against the stage's view, picks a `CgMeshLods` draw's level by its screen height, sorts them, and records the depth snapshot callback, the prepass, and the opaque or transparent pass onto the host's target. `onFrame` listeners run once a frame, before the first world stage records |
| `CgDepthSnapshot` | The scene's depth for `cg_DepthBuffer`: blitted from the host's main framebuffer by a callback pass, its format matched to the source's. It is itself the `CgTexture` each world pass binds, so a format change never leaves a pass holding a deleted texture |
| `CgSortKey` | Filament's key layout with a log-quantised distance: slot, priority, material, distance, mesh. No far plane |

## Rules

- **Camera-relative.** A model matrix is the draw's local transform plus `position - view position`, the subtraction
  in doubles. The pass constants come from `CgStageFrame.constants()`: the host's view and projection, the eye at the
  view's origin, `cg_WorldOrigin` at the host's absolute position.
- **A draw lives one ring frame** (`CgFrameRing.frame()`); a stage drops the previous frame's draws before it records.
  A host advancing no ring frame (a test) keeps them.
- **The snapshot is taken once a frame**, at the first world stage whose draws include a material reading
  `cg_DepthBuffer` (`CgMaterial.readsSceneDepth`).
- **Material chains** (`setNextPass`) are drawn as further draws on the same instances, in the forward passes only.
- The prepass takes a material's depth pass when it has one, else its forward pipeline with colour writes off
  (`CgRenderState.withColorMask`), cached per render state.
