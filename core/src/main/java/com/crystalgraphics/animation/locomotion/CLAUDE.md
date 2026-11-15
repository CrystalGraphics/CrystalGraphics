# animation.locomotion — playing locomotion by distance

> Using it: `docs/animation/guides/locomotion.md`. The warping nodes (stride, orientation, offset root) are
> `animation.ik`'s; the clip data they read (root motion from feet, foot markers, distance curves) is
> `api.animation.CgClipModifiers`.

| Type | Role |
|---|---|
| `CgSequenceEvaluatorNode` | A clip at a time set each frame: `explicitTime` (a variable's seconds), `advanceByDistance` (metres covered this frame along its distance curve, the rate clamped), `matchToTarget` (metres still to go). Records itself as a player, so its curves land and its root motion is extracted; joins the codec as `sequence_evaluator` (`registerKind`) |
| `CgDistanceMatching` | The sums: `timeAtDistance`, `timeAfterDistanceTravelled` (1/30 s steps, the last split), `playRateToMatchSpeed` |
| `CgMovementPrediction` | Where braking stops a character, where accelerating against its velocity turns it round: x and z, y up |

## Rules

- **A clip without its distance curve fails when the graph binds**, naming it, never played by time instead.
- **0 B a frame**: the evaluator and the sums allocate nothing; `playRateToMatchSpeed` is for setup.
- Gate: `CgLocomotionWarpingTest` (headless).
