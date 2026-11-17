# IK and look-at: bones placed by the game

**What you will have:** feet that stand on Minecraft's stairs and slabs, a head that turns toward a target, a hand
that reaches a lever, a weapon that follows the hand, all on top of whatever the clips play.

## In plain words

A clip is recorded on flat ground, but Minecraft has stairs, slabs and hills. **IK** (inverse kinematics) works
backwards from where a hand or foot must end up: you say "the foot goes on this step", and it bends the knee and hip so
the foot gets there, the way you reach for a cup without thinking about each joint. **Look-at** turns the head toward a
point. Both change the pose after the clip plays, as the game runs.

A clip says where every bone goes. A **bone controller** changes that afterwards. Each is a node in the graph
([`graphs-and-variables.md`](graphs-and-variables.md)), taking a pose in and giving one out. Distances are the
skeleton's units, metres. Every node can be written in Java or in an `anim_graph` file.

## The code: feet on the ground

**Foot placement** sweeps each foot down onto the ground, plants it while it is slow, aligns it to the slope and
lowers the pelvis so every leg reaches; **leg IK** after it bends the legs to the placed feet. It needs IK foot bones
(`ik_foot_l`, `ik_foot_r` under `ik_foot_root`), as Unreal's does.

```java
CgAnimNode placed = CgFootPlacementNode.of(locomotion, "pelvis", "ik_foot_root")
        .leg("foot_l", "ik_foot_l", "ball_l").leg("foot_r", "ik_foot_r", "ball_r");
CgAnimNode legs = CgLegIkNode.of(placed).leg("ik_foot_l", "foot_l").leg("ik_foot_r", "foot_r");

// what the feet stand on: Minecraft's blocks under the component
anim.ground(CgCollisionGround.of(() -> CgCollisionWorld.client(partialTick), component::worldTransform));
```

```yaml
nodes:
  placed:
    kind: foot_placement
    source: locomotion
    pelvis_bone: pelvis
    ik_foot_root_bone: ik_foot_root
    legs:
      - { fk_foot_bone: foot_l, ik_foot_bone: ik_foot_l, ball_bone: ball_l }
      - { fk_foot_bone: foot_r, ik_foot_bone: ik_foot_r, ball_bone: ball_r }
  legs:
    kind: leg_ik
    source: placed
    legs:
      - { ik_foot_bone: ik_foot_l, fk_foot_bone: foot_l }
      - { ik_foot_bone: ik_foot_r, fk_foot_bone: foot_r }
```

**A skeleton with no IK foot bones** (a character exported without them) gains them as virtual bones: each
follows its foot in every clip, under an IK root that follows the root, and the graph above runs unchanged. Nothing
in the mesh or the clips changes. The animation demo's IK station plants Goku this way.

```java
CgSkeletalMesh goku = mesh.withVirtualBones(List.of(
        new CgVirtualBone("ik_foot_root", "root", "root"),
        new CgVirtualBone("ik_foot_l", "ik_foot_root", "foot_l"),
        new CgVirtualBone("ik_foot_r", "ik_foot_root", "foot_r")));
// build the graph on goku.skeleton(): it has the IK bones now
```

```yaml
# or in the skeleton asset, which every mesh built on it takes
virtual_bones:
  - { name: ik_foot_root, source: root, target: root }
  - { name: ik_foot_l, source: ik_foot_root, target: foot_l }
  - { name: ik_foot_r, source: ik_foot_root, target: foot_r }
```

- The IK root's up is whichever of its axes points up at rest, so an import that keeps another engine's bone frames under a
  turned root plants the same as one that converts them.
- Make the IK root follow the bone that stands on the floor at rest (`root`), not the pelvis: the feet keep their
  height above it. A GeckoLib model's bone 0 is a root at its origin for this.
- **Leg IK keeps the bend a limb has in its clips**: it cannot choose one for a limb its clips hold
  straight (a Minecraft-style model's legs), and that limb folds onto itself. Give such a limb two-bone IK with a pole:

```java
CgTwoBoneIkNode.of(placed, "left_foot_locator", CgIkTarget.of(CgBoneSpace.BONE, "ik_foot_l", 0f, 0f, 0f),
        CgIkTarget.of(CgBoneSpace.BONE, "left_leg_2", 0f, 0f, -0.4f))   // the knee bends toward its own -z: forward
        .rotationFromEffector();
```

## Variations

### Every control node has an alpha

The alpha blends the node's result over its input: 1 is all of it, 0 none.

```java
CgAnimVariable<Boolean> aiming = CgAnimVariable.bool("aiming", false);
CgAnimVariable<Float> weight = CgAnimVariable.floating("weight", 1f);

node.alpha(CgControlAlpha.of(0.5f));                          // half
node.alpha(CgControlAlpha.of(weight));                        // a variable
node.alpha(CgControlAlpha.bool(aiming, 0.2f, 0.3f));          // blends in over 0.2 s while true, out over 0.3 s
```

```yaml
alpha: 0.5
alpha: weight
alpha: { bool: aiming, blend_in: 0.2, blend_out: 0.3 }
```

### Targets

A target (`CgIkTarget`) is a point in one of four spaces: `WORLD`, `COMPONENT` (the mesh's), `PARENT_BONE` or `BONE`
(a bone's own). Each coordinate is a number or a float variable.

```java
CgIkTarget.of(CgBoneSpace.COMPONENT, null, 0.3f, 1.0f, 0.2f);   // a fixed point by the mesh
CgIkTarget.of(CgBoneSpace.BONE, "hand_l", 0f, 0.1f, 0f);        // 10 cm off the left hand
CgIkTarget.world(targetX, targetY, targetZ);                    // the world, from three variables
```

A world target needs the instance to know where it stands. A skeletal mesh component tells its own instance; an
instance of your own is told with `anim.placedBy(out -> out.set(myWorldMatrix))`.

### Moving a bone by hand: modify bone

```java
// turn the head 30 degrees about its parent's Z, on top of the clip
CgModifyBoneNode.of(locomotion, "head")
        .rotation(CgModifyBoneNode.Mode.ADD, CgBoneSpace.PARENT_BONE, 0f, 0f, 30f);

// lower the pelvis by a variable, in the mesh's space
CgModifyBoneNode.of(locomotion, "pelvis")
        .translation(CgModifyBoneNode.Mode.ADD, CgBoneSpace.COMPONENT, null, pelvisDrop, null);
```

```yaml
nodes:
  head_turn: { kind: modify_bone, source: locomotion, bone: head, rotation: { mode: add, space: parent_bone, value: [0, 0, 30] } }
  pelvis_drop: { kind: modify_bone, source: head_turn, bone: pelvis, translation: { mode: add, space: component, value: [0, pelvis_drop, 0] } }
```

A value is a number or a declared float variable's name.

### Reaching: two-bone IK, FABRIK and CCD

**Two-bone IK** bends an arm or a leg so its end lands on a target, the middle joint bending toward a second one.

```java
CgTwoBoneIkNode.of(pose, "hand_r",
        CgIkTarget.of(CgBoneSpace.COMPONENT, null, 0.4f, 1.2f, 0.3f),    // where the hand goes
        CgIkTarget.of(CgBoneSpace.BONE, "lowerarm_r", 0f, 0f, -0.3f));   // which way the elbow points
```

**FABRIK** and **CCD** reach with a chain of any length, a tail or a tentacle:

```java
CgFabrikNode.of(pose, "tail_01", "tail_08", CgIkTarget.world(x, y, z));
CgCcdIkNode.of(pose, "tail_01", "tail_08", CgIkTarget.world(x, y, z)).rotationLimits(true, 20f, 20f, 20f);
```

### Looking: look-at

```java
CgLookAtNode.of(pose, "head", CgIkTarget.world(x, y, z)).clamp(70f);   // never turns more than 70 degrees
```

### Copying and constraining

```java
CgCopyBoneNode.of(pose, "hand_r", "weapon").copy(true, true, false);            // the weapon follows the hand
CgConstraintNode.of(pose, "shoulder_pad").constraint("upperarm_l", CgConstraintNode.Type.ROTATION, true, 0.5f);
CgApplyLimitsNode.of(pose).limit("neck_01", -40f, -60f, -30f, 40f, 60f, 30f);   // degrees, per axis
```

A **bone-driven controller** maps one bone's motion onto another's:

```java
// the knee pad turns with the knee
CgBoneDrivenControllerNode.of(pose, "calf_l", CgBoneDrivenControllerNode.Source.ROTATION_Y, "kneepad_l")
        .affects(CgBoneDrivenControllerNode.Part.ROTATION_Y).multiplier(0.5f);
```


### The kinds

| Kind | Fields (besides `source` and `alpha`) |
|---|---|
| `modify_bone` | `bone`, `translation`, `rotation`, `scale`: each `{ mode: ignore\|replace\|add, space, value }` |
| `two_bone_ik` | `bone`, `effector`, `joint_target`, `stretch`, `rotation_from_effector`, `keep_relative_rotation`, `no_twist` |
| `look_at` | `bone`, `target`, `look_at_axis`, `look_up_axis`, `clamp`, `interpolation`, `interpolation_time`, `interpolation_trigger` |
| `copy_bone` | `source_bone`, `target_bone`, `translation`, `rotation`, `scale`, `space` |
| `fabrik`, `ccd_ik` | `root_bone`, `tip_bone`, `effector`, `precision`, `max_iterations`; FABRIK's `tip_rotation`; CCD's `start_from_tail`, `enable_rotation_limit`, `rotation_limits` |
| `constraint` | `bone`, `constraints: [{ target_bone, type, maintain_offset, axes, weight }]` |
| `apply_limits` | `limits: [{ bone, min, max }]`, degrees |
| `bone_driven_controller` | `source_bone`, `source_component`, `target_bone`, `affects`, `multiplier` or `range`, `mode` |
| `leg_ik`, `foot_placement` | as above |
| `spring_bone` | `bone`, `stiffness`, `damping`, `error_reset`, `max_displacement`, `translate`, `rotate`, `owner_velocity` |
| `anim_dynamics` | `bone`, `chain_end`, `simulation_space`, `bodies`, gravity, damping, springs, iterations, limits |

The schema generated from the engine's declarations has every field and default.

## What goes wrong

- **Order matters.** Nodes run in the order they are chained: plant the feet before simulating hair, so the hair
  feels where the body ended up; put a look-at after the locomotion it turns.
- **A world target needs the instance to know where it stands**: a component tells its own instance; an instance of
  your own is told with `anim.placedBy(...)`.
- **A limb its clips hold straight** cannot be bent by leg IK: give it two-bone IK with a pole, as above.
- **A bone a node names that the skeleton lacks** fails when an instance runs the graph.

## Where next

- Hair, tails and cloth that swing: [`springs-hair-and-cloth.md`](springs-hair-and-cloth.md).
- Feet planted at every speed: [`locomotion.md`](locomotion.md).
