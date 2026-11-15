# animation.sync — asset players ticked together

> Using it: `docs/animation/guides/graphs.md` § *Sync groups*. The graph that records players is `animation.graph`.

| Type | Role |
|---|---|
| `CgAnimSync` | `FAnimSync`: this frame's players by group, double-buffered; `tick` runs each group's leader, then its followers, then the ungrouped (AnimSync.cpp:79-411), `Prepare`'s sort and shared markers, `Finalize`'s reset; `addMontage` scores a montage 3, above every player |
| `CgAnimAssetPlayer` | `FAnimNode_AssetPlayerBase` and its relevancy interface: what the sync ticks and a state machine's rules read |
| `CgAnimTickContext` | `FAnimAssetTickContext` with its marker context: leader or follower, ratios, start and end positions, markers passed; `advanceTime` |
| `CgMarkerSync` | `UAnimSequence`'s marker sync: tick by markers as leader or follower, marker indices for a time or a position |
| `CgSyncGroup`, `CgAnimGroupRole` | `FAnimationGroupReference`, `EAnimGroupRole` |
| `CgMarkerTickRecord`, `CgMarkerSyncPosition` | `FMarkerTickRecord`, `FMarkerSyncAnimPosition` |

## Rules

- **Beneath the graph**: this package names no graph type. Players record themselves in their update; the instance
  ticks the sync after the walk and before evaluation, Unreal's update, tick, evaluate.
- **Unreal decides**: each function cites its `.cpp` lines; a behaviour Unreal has is ported, never approximated.
  Mirrored marker names wait for mirroring players.
- **0 B a frame once warm**: records, groups and the context's arrays are pooled and grow only to their largest.
- Gate: `animation.blend.CgBlendSpaceTest` (feet in phase in a group, length-ratio sync).
