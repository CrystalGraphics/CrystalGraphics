# animation.ik — Unreal's bone controllers: IK, constraints and dynamics as graph nodes

> Using it: `docs/animation/guides/ik-and-look-at.md`, `springs-hair-and-cloth.md`. The graph these nodes sit in is `animation.graph`; the solver AnimDynamics
> runs on is `animation.dynamics.CgAnimPhys`.

| Type | Role |
|---|---|
| `CgSkeletalControlNode` | `FAnimNode_SkeletalControlBase`: an input pose, an alpha, and a `Control` per instance that answers component-space transforms for its bones (ascending), blended in at the alpha (`CgAnimPose.blendComponent`, Unreal's `LocalBlendCSBoneTransforms`). Its static readers (`source`, `alpha`, `number`, `vector`, `choice`, `flags`) read a kind's data form |
| `CgControlAlpha` | `FInputAlphaBoolBlend` and `FInputScaleBias`/`FInputScaleBiasClamp`: a number, a variable, a bool blended in and out, or a scaled, mapped, clamped, interpolated float |
| `CgIkTarget`, `CgBoneSpace` | Unreal's `FBoneSocketTarget` and `EBoneControlSpace`: a point in world, component, parent bone or bone space, its components numbers or variables. World reads the instance's placement |
| `CgIkMath` | `AnimationCore`'s solvers: two-bone, aim, swing-twist, find-between, ZYX Euler |
| `CgModifyBoneNode`, `CgTwoBoneIkNode`, `CgLookAtNode`, `CgCopyBoneNode`, `CgFabrikNode`, `CgCcdIkNode`, `CgConstraintNode`, `CgApplyLimitsNode`, `CgBoneDrivenControllerNode` | Each its `AnimNode_*.cpp`, cited in its javadoc |
| `CgLegIkNode`, `CgFootPlacementNode` | Leg IK (two-bone or FABRIK per leg) and Foot Placement (the AnimationWarping plugin's): feet swept onto the ground on the game thread (`preUpdate`, through the instance's `CgAnimGround`), planted, aligned, the pelvis lowered |
| `CgStrideWarpingNode`, `CgOrientationWarpingNode`, `CgOffsetRootBoneNode` | Locomotion warping: strides scaled to the real speed (the pelvis pulled down, thighs turned, a vector RK4 spring), legs turned toward the way travelled with the spine turned back, the root held off the entity and eased back (turn in place). `GRAPH` mode (`CgWarpingMode`) reads and replaces the graph's root motion from everything (`CgAnimGraphInstance.rootMotion`); `CgWarpInput` is a number or a float variable. Gates: `CgLocomotionWarpingKakarotTest` (Goku's loops from a crawl to twice his sprint, and strafing), `locomotion.CgLocomotionWarpingTest` (headless) |
| `CgSpringBoneNode`, `CgAnimDynamicsNode` | The spring controller (120 Hz fixed steps) and AnimDynamics: boxes on joints over `CgAnimPhys`, in component, world, root or bone space |

## Rules

- **Unreal decides behaviour**, each node from its source, cited by file; departures are in its javadoc and the step
  notes (no wind, no scaled bodies, no retargeting in AnimDynamics; Foot Placement's `Graph` speed mode alone).
- **Units are metres**: Unreal's centimetre defaults divided by 100; an angular spring constant by 10,000.
- **0 B a frame**: a `Control` makes its scratch at instantiate. Gate: `CgSkeletalControlTest.aWarmFrameAllocatesNothing`.
- **What needs the world runs on the game thread**: a sweep or a placement read happens in `preUpdate` (`hasPreUpdate`),
  never on the worker.
- **A construct with no port fails at load, by name** (a twist offset curve, a driving curve, wind): never plays as
  something else.
- **A new kind** is a `KIND` reader, a `write`, a declaration in `asset.kind.CgBodyKinds` (`controlNode(...)`) and a
  `registerKind` line in `asset.load.CgBuiltInCompilers`; add it to `CgSkeletalControlTest.everyKind`, which round
  trips and plays every kind, and ask the format's designer to add it to the spec.
- Gates (headless): `CgSkeletalControlTest`, `CgFootPlacementTest`, `animation.CgPoseFeaturesTest`.
