# animation.warping — moves bent to land on their targets

> Using it: `docs/animation/guides/motion-warping.md`. The window is data on the clip (`api.animation.CgWarpWindow`);
> the montage that plays the clip calls this package.

| Type | Role |
|---|---|
| `CgMotionWarping` | A character's warp targets by name (`target`, `standOff`: stop short of it toward the character, `removeTarget`), and `process`: the root-motion montage's step, inside an open window of its clip, bent toward its target. Translation by a skew warp (the step scaled along the window's remaining motion by the target's reach over the motion's, sheared sideways and up by the angles between); a window with no motion of its own pulls in a straight line from where it opened. Rotation by slerp, clamped slerp, constant rate or the clip's own turn scaled. One per `CgMontages`, `anim.motionWarping()` |

## Rules

- **Names only `api.animation`**: `montage` calls it, never the reverse.
- **Placement is read as each step is bent**, through the instance's `placedBy`: a sequence's montage moves inside the
  sequencer's evaluation, before the instance's own update, so a copy taken there would be a frame late.
- A window opens when play enters it and closes once play passes its end or the clip changes; a loop's wrap is never
  bent.
- **0 B a step once warm** (`CgMotionWarpingTest.warmItAllocatesNothing`).
- Gates: `CgMotionWarpingTest` (headless) and `CgMotionWarpingKakarotTest` (Goku's back attack, and his lunging strike at arm's length facing his opponent, `core/src/test`).
