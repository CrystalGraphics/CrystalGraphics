# animation.graph — Unreal's anim graph: nodes, their runtime, the data form

> Using it: `docs/animation/guides/graphs.md`. The instance that runs a graph is `animation.CgAnimInstance`.

| Type | Role |
|---|---|
| `CgAnimGraph` | The compiled graph, immutable and shared (an anim blueprint's generated class): variables, the output node, cached poses by name, the slots found; its build checks cycles, nodes read twice, unsaved uses, undeclared or mistyped variables, duplicate slots. `single(skeleton)` is the single node graph |
| `CgAnimGraphInstance` | One character's run of a graph (the proxy): variables and their worker copy, each node's runtime, slots, a frame's asset player records (`record`, ticked by its `CgAnimSync` after the walk; a montage leading a group, `syncMontage`, queued on the game thread; a state machine's relevant player, each with its player scope) and samples (for the curves), the scratch pose pool, its clock (`seconds`), the inertializer stack (Unreal's graph message) |
| `CgAnimNode` | A node: `instantiate` its runtime, `children` (`forEachChild` for a tool), `reads`, `write` its data form. `Runtime`: `initialize`, `update(weight)`, `evaluate(out)`, public as `FAnimNode_Base`'s so a node in another package drives its children |
| `CgAnimVariable` | A typed variable (float, bool, int) by name, with a default; a handle by name and type works on any graph declaring it |
| `CgSequencePlayerNode`, `CgRefPoseNode`, `CgTwoWayBlendNode`, `CgBlendListNode`, `CgSlotNode`, `CgLayeredBlendNode`, `CgApplyAdditiveNode`, `CgCachedPoseNode` | Unreal's nodes, each citing its `AnimNode_*.cpp` |
| `CgLinkedAnimLayerNode`, `CgLinkedAnimGraphNode`, `CgLinkedInputPoseNode` | Unreal's linked anim layer (the graph's own layer, or a linked graph's: `linkLayers`, one run per group), linked anim graph (another graph as a node, swapped by tag), linked input pose. A graph declares `layers` and `inputs`; a linked run is a `CgAnimGraphInstance` in its main's frame (sync, curves, slots, inertializers and clock its main's; variables copied by name and type) |
| `CgRootMotionMode`, `CgRootMotionParams` | Where a character's root motion comes from (none, ignored, the root-motion montage, or every player at its weight, `FROM_EVERYTHING`), and a frame's motion gathered, blended (`accumulateWithBlend`, `makeUpToFullWeight`) or composed, then consumed. The instance's `rootMotionMode` and `rootMotion()`: each recorded player's clips extracted over the tick's move (`SampleSink`'s `moved`), for warping nodes to read and replace; the montages hold the mover's |
| `CgAnimSlot`, `CgBlendMask` | A slot's entries this frame (montage weights, a root held where the montage moves the character, a stop blending them out); a layer's per-bone weights from branches and bone entries |
| `CgAnimGraphCodec`, `CgAnimGraphDefinition`, `CgAnimNodeWriter`, `CgAnimNodeKind` | The `anim_graph` kind's compiler and writer; the compiled file, bound to a skeleton and its clips by id, with the assets its nodes name (blend spaces) resolved at compile; where a node writes itself (and a graph-level section, a machine); how a kind outside this package is read (`registerKind`: `state_machine`, `inertialization`, `blend_space_player`, `aim_offset`), with clips, dependencies and sync groups through its context |

## Rules

- **Unreal decides behaviour**: a node ports its `AnimNode_*.cpp` (weights, relevance, `ZERO_WEIGHT`, blend list
  easing, a slot's source at `GetSmallestRelevantWeight`), cited in its javadoc.
- **The frame**: `preUpdate` (game thread), then `update` (the walk, each cached pose's source at its heaviest use, then
  every recorded player's tick, `TickAssetPlayerInstances`), then `evaluate`, on one worker. A node reads variables
  only through `get`/`is`, the worker's copy.
- **0 B a frame**: runtimes are made at instantiate; frame loops run over arrays; scratch poses are borrowed from the
  instance's pool and given back last first (`evaluate` checks the balance).
- **This package names only `animation.sync`**, which lies beneath it: `animation.state` and `animation.blend` join
  through `CgAnimNodeKind`, the public `Runtime` contract and the instance's hooks (player scopes, inertializers,
  records, `forEachRuntime`).
- **The order is update, tick, evaluate**: a player advances in the sync tick after the walk, never in its update, so a
  group's leader moves first and its followers after it.
- **A node kind with a data form** writes itself through `CgAnimNodeWriter` and is read in `CgAnimGraphCodec`; the
  declaration is `asset.kind.CgBodyKinds.ANIM_NODES`, and the schema is generated from it. A field equal to its
  declared default is not written.
- **A file fails at load, naming its node** (the codec binds it against a stand-in skeleton and clip at compile); a
  clip the mesh lacks fails at bind.
- Gates: `CgAnimGraphTest` (headless: blends, cached poses, additives, the round trip, load failures, 0 B a frame),
  `CgLayeredBlendTest` (a face layer's bones and curves on a component's materials).
