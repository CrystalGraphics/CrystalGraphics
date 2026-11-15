# animation.blend — blend spaces, aim offsets, blend curves and inertialization

> Using it: `docs/animation/guides/graphs.md` § *Blend spaces*, § *Inertialization*.

| Type | Role |
|---|---|
| `CgBlendSpace` | `UBlendSpace` (1D, 2D, aim offset): axes, samples, weight speed and ease, the 4.21 switches (`gridInterpolation`, `legacyLength`), `scaleAxis`, a cooked grid; its lookup baked at build (grid elements, else segments or triangles) and `length` both ways. Builder `oneD`/`twoD` |
| `CgBlendSpaceDefinition`, `CgBlendSpaceCodec` | The `blend_space` kind compiled (clips by id, bound per mesh) and written back (`toTree`) |
| `CgBlendSpacePlayerNode` | `FAnimNode_BlendSpacePlayer` and `UBlendSpace::TickAssetPlayer`: inputs filtered, samples found and their weights interpolated, normalised time advanced, marker samples leading the rest; an asset player of `animation.sync`; the `blend_space_player` kind's reader (`KIND`) |
| `CgAimOffsetNode` | `FAnimNode_RotationOffsetBlendSpace`: a mesh-space additive blend space over a base at an alpha; the `aim_offset` kind's reader |
| `CgBlendFilter` | `FFIRFilterTimeBased`: an axis's input smoothing (windowed, exponential, spring damper) |
| `CgDelaunay` | Godot's `Delaunay2D` (MIT), what a 2D blend space bakes from |
| `CgAlphaBlend` | `EAlphaBlendOption`: each option over `easing.CgEasings` (AlphaBlend.cpp:239-270, checked against UnrealMathUtility.h), `inverse` as `CalculateInverseAlpha`; `of(id)` reads a file's lowercase name |
| `CgInertializationNode` | `FAnimNode_Inertialization`: requests from below merged and shortened by the deficit, each bone's translation, rotation and scale offset and speed from the last two poses, decayed by `CalcInertialFloat`; the `inertialization` kind's reader (`KIND`) |

## Rules

- **Both Unreals.** Release's behaviour is the default and 4.21's is kept behind a switch an importer sets, never
  dropped: the grid lookup, constant-rate weights, summed lengths with the move scaled by target over blended length.
- **The cooked grid wins**: a file's `grid` is played as given; the engine bakes only without one, and a 2D bake's
  diagonal on a square is Godot's choice, not Unreal's `PreferredTriangulationDirection` (not ported).
- **0 B a frame**: a player's sample lists are fixed arrays sized from its blend space; per-sample time and markers are
  kept by sample index, reset when a sample enters the list, as Unreal's fresh entry is.
- **Not played, failing at load**: `sync: true` (matching sync phases). Per-bone blend profiles, single-frame and
  mirrored samples have no key yet; a blend space's notifies and root motion are not extracted yet.
- **Offsets are local** (each bone's parent space): the instance has no component transform, so no world-space or
  teleport handling; curves are not inertialized. Both are recorded departures, not oversights.
- **A requester finds the node through the graph instance** (`pushInertializer`, Unreal's graph message); a machine
  checks at instantiate that one is above it (`inertializerAbove`).
- Gates: `CgBlendSpaceTest` (StandMoveLP's weights at 5/s, both lengths, feet in phase in a blend and a group, the aim
  offset, the round trip, 0 B a frame); `CgAnimStateMachineTest`'s inertialization cases.
