# Springs, hair and cloth

**What you will have:** a ponytail that lags behind and settles as the head turns, a tail and a sash that swing with
the body's run and turn, and hair that stays out of the shoulders, all without animating them by hand.

## In plain words

Hair, tails and capes should not be stiff: they should swing, lag behind the body, then settle. A **spring bone** is
a bone hung on a spring, like a bobblehead's head. **AnimDynamics** goes further: it treats a chain of bones as little
boxes joined together and lets gravity and the body's movement swing them, like a rope tied to your belt as you run.

## The code: a ponytail on a spring

```java
CgSpringBoneNode.of(pose, "ponytail_03").spring(50f, 4f).limit(0.2f);    // stiffness, damping; at most 0.2 m off
```

```yaml
tail: { kind: spring_bone, source: pose, bone: ponytail_03, stiffness: 50, damping: 4, max_displacement: 0.2 }
```

Stiffer springs snap back faster; more damping settles with less wobble.

## Variations

### A swinging chain: AnimDynamics

Give each box the size of its bone and set its centre half a bone along, so each joint sits on a bone:

```java
CgAnimDynamicsNode.BodyDefinition strand = CgAnimDynamicsNode.BodyDefinition.DEFAULT
        .withBox(0.08f, 0.02f, 0.02f)            // along the bone's X
        .withJointOffset(0.04f, 0f, 0f)
        .withConstraint(CgAnimDynamicsNode.ConstraintSetup.DEFAULT.withAngularLimits(-30f, 30f));

CgAnimDynamicsNode.of(pose, "hair_01").chain("hair_04").body(strand)
        .space(CgAnimDynamicsNode.SimulationSpace.WORLD);        // the body's movement drags it
```

```yaml
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

### Which movement it feels

| `simulation_space` | Feels |
|---|---|
| `component` (default) | only gravity: it moves with the character as if glued, unless `component_motion` adds the character's movement |
| `world` | every step and turn the character makes |

### Keeping it out of the body

`spherical_limits` (a sphere around a bone, the head) and `planar_limits` (a plane, the back) push the boxes out.

Every field: [`../reference/nodes.md`](../reference/nodes.md) § `anim_dynamics`, `spring_bone`.

## What goes wrong

- **Nothing moves**: the default joint is rigid, its limits all zero. Open the angles you want.
- **Hair through the shoulders**: add spherical or planar limits.
- **It swings against the final pose**: chain it after foot placement and look-at, so it feels where the body ended up.
- **An angular spring from another engine's file is far too strong**: here its constant is that value divided by
  10,000 (the boxes are in metres).
- **A `wind` key fails at load**: there is no wind yet.

## Where next

- Bones placed by the game: [`ik-and-look-at.md`](ik-and-look-at.md).
