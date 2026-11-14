# animation.graph — Unreal's anim graph: nodes, their runtime, the data form

> Using it: `docs/animation/guides/graphs.md`. The instance that runs a graph is `animation.CgAnimInstance`.

| Type | Role |
|---|---|
| `CgAnimGraph` | The compiled graph, immutable and shared (an anim blueprint's generated class): variables, the output node, cached poses by name, the slots found; its build checks cycles, nodes read twice, unsaved uses, undeclared or mistyped variables, duplicate slots. `single(skeleton)` is the single node graph |
| `CgAnimGraphInstance` | One character's run of a graph (the proxy): variables and their worker copy, each node's runtime, slots, a frame's ticks and samples (for the curves), the scratch pose pool |
| `CgAnimNode` | A node: `instantiate` its runtime, `children`, `reads`, `write` its data form. `Runtime`: `initialize`, `update(weight)`, `evaluate(out)` |
| `CgAnimVariable` | A typed variable (float, bool, int) by name, with a default; a handle by name and type works on any graph declaring it |
| `CgSequencePlayerNode`, `CgRefPoseNode`, `CgTwoWayBlendNode`, `CgBlendListNode`, `CgSlotNode`, `CgLayeredBlendNode`, `CgApplyAdditiveNode`, `CgCachedPoseNode` | Unreal's nodes, each citing its `AnimNode_*.cpp` |
| `CgAnimSlot`, `CgBlendMask` | A slot's entries this frame (montage weights, a stop blending them out); a layer's per-bone weights from branches and bone entries |
| `CgAnimGraphCodec`, `CgAnimGraphDefinition`, `CgAnimNodeWriter` | The `anim_graph` kind's compiler and writer; the compiled file, bound to a skeleton and its clips by id; where a node writes itself |

## Rules

- **Unreal decides behaviour**: a node ports its `AnimNode_*.cpp` (weights, relevance, `ZERO_WEIGHT`, blend list
  easing, a slot's source at `GetSmallestRelevantWeight`), cited in its javadoc.
- **The frame**: `preUpdate` (game thread), then `update` (the walk, each cached pose's source at its heaviest use, then
  every recorded player's tick, `TickAssetPlayerInstances`), then `evaluate`, on one worker. A node reads variables
  only through `get`/`is`, the worker's copy.
- **0 B a frame**: runtimes are made at instantiate; frame loops run over arrays; scratch poses are borrowed from the
  instance's pool and given back last first (`evaluate` checks the balance).
- **A node kind with a data form** writes itself through `CgAnimNodeWriter` and is read in `CgAnimGraphCodec`; the
  declaration is `asset.kind.CgBodyKinds.ANIM_NODES`, and the schema is generated from it. A field equal to its
  declared default is not written.
- **A file fails at load, naming its node** (the codec binds it against a stand-in skeleton and clip at compile); a
  clip the mesh lacks fails at bind.
- Gates: `CgAnimGraphTest` (headless: blends, cached poses, additives, the round trip, load failures, 0 B a frame),
  `CgLayeredBlendTest` (a face layer's bones and curves on a component's materials).
