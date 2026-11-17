# Springs, hair and cloth

**What you will have:** a ponytail that lags and settles as the head turns, a tail and a sash that swing with the
body's run and turn, and hair that stays out of the shoulders.

## In plain words

Hair, tails and capes should not be stiff: they should swing, lag behind the body and settle. A **spring bone** is a
bone hung on a spring, like a bobblehead's head. **AnimDynamics** goes further: it treats a chain of bones as little
boxes joined together and lets gravity and the body's movement swing them, like a rope tied to your belt as you run.

A **spring bone** lets one bone lag behind on a spring and settle:

```java
CgSpringBoneNode.of(pose, "ponytail_03").spring(50f, 4f).limit(0.2f);
```

**AnimDynamics** hangs a chain of boxes from the animated pose and simulates them: hair, a tail, a sash, a pouch. Give
each box the size of its bone and set its centre half a bone along, so each joint sits on a bone:

```java
CgAnimDynamicsNode.BodyDefinition strand = CgAnimDynamicsNode.BodyDefinition.DEFAULT
        .withBox(0.08f, 0.02f, 0.02f)            // along the bone's X, as Unreal authors bones
        .withJointOffset(0.04f, 0f, 0f)
        .withConstraint(CgAnimDynamicsNode.ConstraintSetup.DEFAULT.withAngularLimits(-30f, 30f));

CgAnimDynamicsNode.of(pose, "hair_01").chain("hair_04").body(strand)
        .space(CgAnimDynamicsNode.SimulationSpace.WORLD);        // the body's motion drags it
```

```yaml
nodes:
  hair:
    kind: anim_dynamics
    source: pose
    bone: hair_01
    chain_end: hair_04
    simulation_space: world
    bodies:
      - box_extents: [0.08, 0.02, 0.02]
        local_joint_offset: [0.04, 0, 0]
        constraint: { angular_min: [-30, -30, -30], angular_max: [30, 30, 30] }
```

- **The default joint is rigid**: linear limits at nothing, angular limits at nothing. Open the angles you want.
- **Component space** (the default) moves with the character, so only gravity moves the bodies; set
  `component_motion` to feel its movement too. **World space** feels every move and turn.
- **Keep them out of the body** with `spherical_limits` (a sphere on a bone) and `planar_limits` (a plane).
- An angular spring constant is Unreal's divided by 10,000 (the boxes are in metres).
- No wind yet: a `wind` key fails at load.

## What goes wrong

- **Nothing moves**: the default joint is rigid. Open its angular limits.
- **Hair through the body**: add `spherical_limits` or `planar_limits`.
- **It runs before the feet are planted**: chain it after foot placement and look-at, so it feels the final pose.
- **A `wind` key fails at load**: there is no wind yet.

## Where next

- Bone controllers in general: [`ik-and-look-at.md`](ik-and-look-at.md).
