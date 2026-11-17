# IK and look-at: bones placed by the game

**What you will have:** feet that stand on Minecraft's stairs and slabs instead of floating or sinking, a head that
turns toward whatever the character watches, a hand that reaches a lever, and a weapon that follows the hand, all on
top of whatever the clips play.

## In plain words

A clip is recorded on flat ground, but Minecraft has stairs, slabs and hills. **IK** (inverse kinematics) works
backwards from where a hand or foot must end up: you say "the foot goes on this step", and it bends the knee and hip
so the foot gets there, the way you reach for a cup without thinking about each joint. **Look-at** turns the head
toward a point. Both change the pose after the clip plays, every frame, as nodes in the graph.

## The code: feet on the blocks

**Foot placement** sweeps each foot down onto what is under it, plants it while it moves slowly, tilts it to the
slope, and lowers the hips so every leg still reaches. **Leg IK** after it bends the legs to the placed feet.

```yaml
# in the graph, after the locomotion
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

```java
CgAnimNode placed = CgFootPlacementNode.of(locomotion, "pelvis", "ik_foot_root")
        .leg("foot_l", "ik_foot_l", "ball_l").leg("foot_r", "ik_foot_r", "ball_r");
CgAnimNode legs = CgLegIkNode.of(placed).leg("ik_foot_l", "foot_l").leg("ik_foot_r", "foot_r");
```

What the feet stand on is Minecraft's blocks, read from the world under the character:

```java
anim.ground(CgCollisionGround.of(() -> CgCollisionWorld.client(partialTick), component::worldTransform));
```

### IK foot bones

Foot placement moves **IK foot bones** (`ik_foot_l`, `ik_foot_r` under `ik_foot_root`). A model exported without them
gains them as virtual bones, each following its foot in every clip, with nothing in the mesh or clips changed:

```yaml
# assets/mymod/skeletons/hero.yaml, which every mesh built on the skeleton takes
_v: 1
model: mymod:models/hero.glb
sockets: []
virtual_bones:
  - { name: ik_foot_root, source: root, target: root }
  - { name: ik_foot_l, source: ik_foot_root, target: foot_l }
  - { name: ik_foot_r, source: ik_foot_root, target: foot_r }
```

```java
CgSkeletalMesh hero = mesh.withVirtualBones(List.of(
        new CgVirtualBone("ik_foot_root", "root", "root"),
        new CgVirtualBone("ik_foot_l", "ik_foot_root", "foot_l"),
        new CgVirtualBone("ik_foot_r", "ik_foot_root", "foot_r")));
```

Make the IK root follow the bone on the floor at rest (`root`), not the pelvis, so the feet keep their height above
it. A GeckoLib model's bone 0 is such a root.

## Variations

### Turn the head: look-at

```java
CgLookAtNode.of(pose, "head", CgIkTarget.world(lookX, lookY, lookZ)).clamp(70f);   // never more than 70°
```

On an entity, the humanoid template already turns `head` by `entity.head_yaw` and `entity.head_pitch`
([`vanilla-models.md`](vanilla-models.md)).

### Reach a point: two-bone IK, FABRIK, CCD

**Two-bone IK** bends an arm or leg so its end lands on a target, the elbow or knee bending toward a second point.

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

### Targets

A target (`CgIkTarget`) is a point in one of four spaces, each coordinate a number or a float variable:

| Space | Means |
|---|---|
| `WORLD` | the world: a lever, an enemy |
| `COMPONENT` | the model's own space |
| `PARENT_BONE` | a bone's parent's space |
| `BONE` | a bone's own space: 10 cm off the left hand |

```java
CgIkTarget.world(targetX, targetY, targetZ);                    // three float variables
CgIkTarget.of(CgBoneSpace.BONE, "hand_l", 0f, 0.1f, 0f);
```

### Move a bone by hand: modify bone

```java
CgModifyBoneNode.of(locomotion, "head")
        .rotation(CgModifyBoneNode.Mode.ADD, CgBoneSpace.PARENT_BONE, 0f, 0f, 30f);        // 30° on top of the clip
CgModifyBoneNode.of(locomotion, "pelvis")
        .translation(CgModifyBoneNode.Mode.ADD, CgBoneSpace.COMPONENT, null, pelvisDrop, null);   // a variable
```

```yaml
head_turn: { kind: modify_bone, source: locomotion, bone: head, rotation: { mode: add, space: parent_bone, value: [0, 0, 30] } }
```

### Follow or limit another bone

```java
CgCopyBoneNode.of(pose, "hand_r", "weapon").copy(true, true, false);            // the weapon follows the hand
CgConstraintNode.of(pose, "shoulder_pad").constraint("upperarm_l", CgConstraintNode.Type.ROTATION, true, 0.5f);
CgApplyLimitsNode.of(pose).limit("neck_01", -40f, -60f, -30f, 40f, 60f, 30f);   // degrees, per axis
CgBoneDrivenControllerNode.of(pose, "calf_l", CgBoneDrivenControllerNode.Source.ROTATION_Y, "kneepad_l")
        .affects(CgBoneDrivenControllerNode.Part.ROTATION_Y).multiplier(0.5f);  // the knee pad turns with the knee
```

### Fade a node in and out: alpha

Every one of these nodes has an alpha: 1 applies it fully, 0 not at all.

```java
node.alpha(CgControlAlpha.of(0.5f));                          // half
node.alpha(CgControlAlpha.of(WEIGHT));                        // a variable
node.alpha(CgControlAlpha.bool(AIMING, 0.2f, 0.3f));          // in over 0.2 s while true, out over 0.3 s
```

```yaml
alpha: { bool: aiming, blend_in: 0.2, blend_out: 0.3 }
```

Every node's file fields: [`../reference/nodes.md`](../reference/nodes.md).

## What goes wrong

- **Order matters.** Nodes run in the order they are chained: plant the feet, then turn the head, then swing the hair
  ([`springs-hair-and-cloth.md`](springs-hair-and-cloth.md)), so each sees where the last left the body.
- **Feet float or sink**: no ground was given (`anim.ground(...)`), or the IK root follows the pelvis instead of the
  floor bone.
- **A knee bends backwards or folds**: leg IK keeps the bend a limb has in its clips, and a limb its clips hold
  straight (a Minecraft-style leg) has none. Give it two-bone IK with a pole:

  ```java
  CgTwoBoneIkNode.of(placed, "left_foot_locator", CgIkTarget.of(CgBoneSpace.BONE, "ik_foot_l", 0f, 0f, 0f),
          CgIkTarget.of(CgBoneSpace.BONE, "left_leg_2", 0f, 0f, -0.4f))   // the knee bends toward its own -z: forward
          .rotationFromEffector();
  ```
- **A world target lands at the origin**: the instance does not know where it stands. A component tells its own
  instance; one of your own needs `anim.placedBy(...)`.
- **A bone a node names that the skeleton lacks** fails when an instance runs the graph, naming it.

## Where next

- Hair, tails and capes that swing: [`springs-hair-and-cloth.md`](springs-hair-and-cloth.md).
- Feet that fit any speed: [`locomotion.md`](locomotion.md).
