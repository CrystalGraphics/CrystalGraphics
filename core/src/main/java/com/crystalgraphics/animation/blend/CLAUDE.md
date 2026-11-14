# animation.blend — blend curves and inertialization

> Using it: `docs/animation/guides/graphs.md` § *Inertialization*.

| Type | Role |
|---|---|
| `CgAlphaBlend` | `EAlphaBlendOption`: each option over `easing.CgEasings` (AlphaBlend.cpp:239-270, checked against UnrealMathUtility.h), `inverse` as `CalculateInverseAlpha`; `of(id)` reads a file's lowercase name |
| `CgInertializationNode` | `FAnimNode_Inertialization`: requests from below merged and shortened by the deficit, each bone's translation, rotation and scale offset and speed from the last two poses, decayed by `CalcInertialFloat`; the `inertialization` kind's reader (`KIND`) |

## Rules

- **Offsets are local** (each bone's parent space): the instance has no component transform, so no world-space or
  teleport handling; curves are not inertialized. Both are recorded departures, not oversights.
- **A requester finds the node through the graph instance** (`pushInertializer`, Unreal's graph message); a machine
  checks at instantiate that one is above it (`inertializerAbove`).
- Gate: `CgAnimStateMachineTest`'s inertialization cases (the quintic against a hand-worked value).
