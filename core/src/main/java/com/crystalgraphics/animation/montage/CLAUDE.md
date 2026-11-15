# animation.montage — montages on a character: weight, slot groups, root motion

> Using it: `docs/animation/guides/graphs.md` § *Montages*. What drives a montage is a sequence's `animation` track
> (`sequence.animation.CgSequenceAnimationEvaluator`); this package is the character's side.

| Type | Role |
|---|---|
| `CgMontage` | `UAnimMontage`'s properties its instance reads: slot group, blend in and out with their curves, `BlendOutTriggerTime`, `bEnableAutoBlendOut`, root motion, sync group. Withers off `DEFAULT` |
| `CgMontageInstance` | `FAnimMontageInstance`: `Play`, `Stop`, `UpdateWeight` once a frame, `Terminate`; its slot entries by pass (the last pass of a frame wins); played on alone once `release`d (interrupted, blending out); root motion and markers from its first entry; `UAnimMontage::TickAssetPlayer` as its sync group's leader while blending out |
| `CgMontages` | a character's montages (`anim.montages()`): `play` stops its group's others over its blend in and the root-motion one where it moves the character; `preUpdate` is `UpdateMontage` (weights, released montages advanced, the blended out terminated, entries onto slots, sync records); listeners for blended in, blending out, ended; `consumeRootMotion` (the mode and the params are `graph`'s `CgRootMotionMode` and `CgRootMotionParams`; under `FROM_EVERYTHING` the graph's players' motion joins the montages' after the pose, `accumulateRootMotion`) |

- The root-motion montage's step is bent by its clip's warp windows before it accumulates (`animation.warping.CgMotionWarping`, `CgMontages.motionWarping()`).

## Rules

- **Unreal's order**: a montage's weight updates before its driver reads its end (`UpdateMontage` then `Advance`), so a
  stop by its own end moves the weight from the next frame, and a stop by gameplay the same frame.
- **Beneath the sequencer**: this package names no sequence type. Its driver is an `Object`; what drives it calls
  `updateWeight`, `beginPass`, `entry`, `stop`, `release`.
- **0 B a frame once warm**: entries and events are arrays that only grow; listeners are called over an index loop.
- Gates: `sequence.animation.CgMontageTest` (blends, interrupts, groups, a loop, root motion, sync, 0 B a frame) and
  `CgMontageNetTest` (a predicted combo branch over latency and a late joiner), both headless.
