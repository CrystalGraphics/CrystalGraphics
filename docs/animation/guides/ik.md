# IK and dynamics: bones placed by the game

A clip says where every bone goes. A **bone controller** changes that afterwards: a foot moved onto a step, a head
turned toward a target, a ponytail swinging after the head. Each is a node in the graph
([graphs.md](graphs.md)), taking a pose in and giving one out, as Unreal's Skeletal Control nodes do.

Distances are the skeleton's units, metres. Every node can be written in Java or in an `anim_graph` file; both forms
are shown.

## Every control node has an alpha

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

## Targets

A target (`CgIkTarget`) is a point in one of four spaces: `WORLD`, `COMPONENT` (the mesh's), `PARENT_BONE` or `BONE`
(a bone's own). Each coordinate is a number or a float variable.

```java
CgIkTarget.of(CgBoneSpace.COMPONENT, null, 0.3f, 1.0f, 0.2f);   // a fixed point by the mesh
CgIkTarget.of(CgBoneSpace.BONE, "hand_l", 0f, 0.1f, 0f);        // 10 cm off the left hand
CgIkTarget.world(targetX, targetY, targetZ);                    // the world, from three variables
```

A world target needs the instance to know where it stands. A skeletal mesh component tells its own instance; an
instance of your own is told with `anim.placedBy(out -> out.set(myWorldMatrix))`.

## Moving a bone by hand: modify bone

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

## Reaching: two-bone IK, FABRIK and CCD

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

## Looking: look-at

```java
CgLookAtNode.of(pose, "head", CgIkTarget.world(x, y, z)).clamp(70f);   // never turns more than 70 degrees
```

## Copying and constraining

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

## Feet on the ground

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

**A skeleton with no IK foot bones** (a character exported without them) gains them as virtual bones (Unreal's): each
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

- The IK root's up is whichever of its axes points up at rest, so an import that keeps Unreal's bone frames under a
  turned root plants the same as one that converts them.
- Make the IK root follow the bone that stands on the floor at rest (`root`), not the pelvis: the feet keep their
  height above it.

## Things that swing

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

## The kinds

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

## Order matters

Nodes run in the order they are chained. Plant the feet before simulating hair, so the hair feels where the body
ended up; put a look-at after the locomotion it turns.
