# Retargeting — one character's clips on another

A game's character comes with hundreds of clips; your mod's model has a handful. Retargeting plays the first one's
clips on the second: a Goku punch on a Minecraft player model, at the player's size, its feet on the floor and its hands
where Goku's are. You name what each skeleton is (a **rig**), say how one plays on the other (a **retargeter**), and
the engine bakes each clip onto your model once, when it loads. Nothing retargets while it plays.

| Word | What it is | Class |
|---|---|---|
| **Rig** | How a skeleton is read as a body: its root, its pelvis, its named chains (a spine, each arm, each leg), and the IK goal at the end of a limb | `CgRig` |
| **Adapter** | What a model library says of its models: which way they face, which bones only rotate | `CgRigAdapter`, `CgRigAdapters` |
| **Default rig** | A rig found from bone names, so a common shape needs no rig file: the standard humanoid's names, GeckoLib's humanoid | `CgRigs` |
| **Retargeter** | One rig's animation on another: which chain follows which, the poses that line the two up, the steps | `CgRetargeter` |
| **Retarget pose** | Each skeleton's stance the two are compared in: an arms-down model turned to meet a source standing arms out | `CgRetargetPoses` |
| **Processor** | Runs a retargeter on poses: a source pose in, the target's out | `CgRetargetProcessor` |
| **Bake** | A source clip made into a clip of the target's own bones, once, cached | `CgRetargetBake` |
| **Resolver** | Which clip a character plays for a name: its own, else the source's baked onto it, else an error naming it | `CgClipResolver` |

## Ten lines: a Goku clip on a GeckoLib model

```java
CgSkeleton goku = gokuMesh.skeleton(), player = playerMesh.skeleton();
CgRig from = CgRigs.detect(goku, CgRigAdapters.CRYSTALGRAPHICS);     // the standard humanoid names
CgRig to = CgRigs.detect(player, CgRigAdapters.GECKOLIB);            // GeckoLib's humanoid
CgRetargeter retargeter = CgRetargeter.auto(goku, from, player, to); // chains by name, poses lined up
CgRetargetProcessor p = new CgRetargetProcessor(retargeter, goku, player);

CgAnimSequence punch = CgRetargetBake.cached(p, gokuPunch);          // baked once, shared after
playerInstance.play(punch, false);                                   // plays like any clip of its own
```

`detect` returns null for a skeleton of neither shape: give it a rig of its own (below).

## What each step does

A retargeter runs its steps in order, each on the pose the last left:

| Step | Does | Without it |
|---|---|---|
| `scale_source` | scales the source's places before anything reads them | — |
| `pelvis_motion` | moves the target's hips as the source's move, scaled by the height ratio (the target's hip height over the source's), and turns the pelvis as the source's turns | the body stays where it stood while the source jumps |
| `fk_chains` | turns each target chain's bones as its source chain's turn, sampled along the chain so chains of different bone counts match | the target stays in its retarget pose |
| `ik_chains` | puts each limb's IK goal where the source's hand or foot is, relative to the shoulder or hip and scaled to the target's own limb, then bends the limb to it | hands and feet drift: right turns on a differently built body land elsewhere |
| `floor_constraint` | holds a foot near the floor at the source's height above it, plus the target's own foot height | feet sink or float on a body whose legs differ |

```java
// the default stack, spelled out; tune one value or drop a step
CgRetargeter tuned = retargeter.toBuilder()
        .ops(CgRetargeter.Op.scaleSource(1f), CgRetargeter.Op.pelvisMotion(0.8f, 1f),   // 80% of the source's travel
             CgRetargeter.Op.FK_CHAINS, CgRetargeter.Op.IK_CHAINS, CgRetargeter.Op.floor(1f))
        .build();

// a chain told how to follow
CgRetargeter oneToOne = retargeter.toBuilder()
        .chain(CgRetargeter.ChainMap.of("spine", "spine").fk(CgRetargeter.Rotation.ONE_TO_ONE, CgRetargeter.Translation.NONE))
        .build();
```

## A rig of your own

For a model whose bones have other names, or that is built another way:

```java
CgRig golem = CgRig.builder("mymod:golem", CgRigAdapters.GECKOLIB).root("root").pelvis("torso")
        .chain("spine", "torso", "chest")
        .chain("head", "head", "head").rides("head", "chest")
        .chain("arm_l", "arm_l_upper", "arm_l_hand", "ik_hand_l").bend("arm_l", 0f, 0f, 1f).rides("arm_l", "chest")
        .chain("leg_l", "leg_l_upper", "leg_l_foot", "ik_foot_l").bend("leg_l", 0f, 0f, -1f)
        .build();
```

```yaml
# rigs/mymod/golem.yaml: the same as data
model: mymod:geo/golem.geo.json
adapter: geckolib
root: root
pelvis: torso
chains:
  - { id: c1, name: spine, start: torso, end: chest }
  - { id: c2, name: head, start: head, end: head, rides: chest }
  - { id: c3, name: arm_l, start: arm_l_upper, end: arm_l_hand, goal: ik_hand_l, bend: [0, 0, 1], rides: chest }
  - { id: c4, name: leg_l, start: leg_l_upper, end: leg_l_foot, goal: ik_foot_l, bend: [0, 0, -1] }
```

| Chain setting | For |
|---|---|
| `goal` | the IK bone a limb's end is placed by; `rig.ikBones(skeleton)` makes it a virtual bone where the skeleton has none |
| `bend` | which way the middle joint bends, in the model's space, for a limb its clips hold straight (GeckoLib's) |
| `rides` | the bone a chain's start moves with as if hung from it: GeckoLib's humanoid hangs its arms and head from its pelvis, not its chest, so they ride `body` or they stay put as the chest leans |
| `pivot` | where a chain turns, in the model's space at rest, for a bone pivoted elsewhere: GeckoLib's humanoid pivots its torso at the neck, so it turns about its hips |

Chains map by name (`arm_l` to `arm_l`), else by the closest name: name yours as the source's are and `auto` maps them.

## A retargeter as data

```yaml
# retargeters/mymod/goku_to_player.yaml
source: kk:goku
target: mymod:player
poses:
  target: { rotations: { left_arm: [0, 0, -1.2], right_arm: [0, 0, 1.2] } }   # XYZ angles; an Unreal kit's are rotators
chains:
  - { id: m1, target: arm_l, source: arm_l, fk: { rotation: interpolated }, ik: { enabled: true } }
ops:
  - { id: o1, op: scale_source, factor: 1 }
  - { id: o2, op: pelvis_motion, scale_horizontal: 1, scale_vertical: 1 }
  - { id: o3, op: fk_chains }
  - { id: o4, op: ik_chains }
  - { id: o5, op: floor_constraint, weight: 1 }
```

`retargeter.toTree()` writes one; the `retargeter` kind's compiler reads it, its rigs as hard references.

## Baking and which clip plays

```java
CgClipResolver clips = new CgClipResolver(playerClips::get, gokuClips::get, processor);
clips.bakeAll(List.of("punch", "kick", "kamehameha"));   // at load, across workers
playerInstance.play(clips.resolve("punch"), false);      // its own "punch" if it has one, else Goku's baked onto it
```

- **Its own clip wins**: give your model a GeckoLib animation named as one of the source's and it plays instead.
- **A clip neither has fails**, naming it.
- **A clip not named to `bakeAll` bakes on first use**, on the calling thread, and counts `retarget.late` on the
  `crystalgraphics.world` trace channel: name it to `bakeAll`.
- Bakes are cached by the clip, the retargeter and the bake's version; `CgRetargetBake.clear()` after a reload.

## Things to know

- **A processor is one thread's.** `bakeAll` makes one per worker.
- **What is not built fails by name** when the processor is made or the file loads: the `match_chain`,
  `match_scaled_chain` and `copy_local` rotations, the `stretch_*` and `orient_and_scale` translations, the `pin_bone`
  and `curve_remap` steps, a pose's `root_offset`, an IK chain's `static_rotation_offset` and `scale_vertical`.
- **IK solves limbs of three bones** (shoulder, elbow, hand): a longer limb with a goal is refused, naming it.
- **A one-bone chain standing for a longer one** (GeckoLib's single torso for a three-bone spine) takes the source
  chain's whole lean, not its last bone's turn: a rigid torso cannot curl as a spine does.
- **Different builds land differently.** A target whose torso is long for its legs puts its hands where its own
  shoulders reach: retargeting keeps each hand's direction from its shoulder and the target's own reach, not the
  source's place in the world.

## See it

The animation demo (`anim-demo` in the harness, `anim` in the game's demo) has two stations for it:

- **Rig IK**: every character with a rig in a ring, reaching for an orb and watching it, each set up from its rig alone.
- **Retarget**: Goku beside every RPG-Core body playing his clips, with each step switchable, the rigs drawn over the
  bodies and trails behind the hands.

## Where it came from

The design is Unreal's IK Retargeter: rigs of named chains, retarget poses, chain mapping by name, and the op stack of
scale source, pelvis motion, FK chains, IK chains and floor constraint. Facing frames from adapters, hips-based scaling,
and `rides` and `pivot` are ours, for model libraries built unlike Unreal's skeletons.
