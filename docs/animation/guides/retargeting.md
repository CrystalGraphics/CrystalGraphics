# Retargeting: one character's clips on another

**What you will have:** a pack of clips made for one character (punches, rolls, a victory dance) playing on your own
model at its own size, its feet on the floor and its hands where they should be, baked once as the game loads. Your
model's own clips still win where it has them.

## In plain words

Every character has its own skeleton: different heights, bone names and arm lengths. **Retargeting** copies a
movement from one skeleton to another, the way one puppeteer can work two puppets of different sizes with the same
hand moves. A tall source raising its arm above its head makes a short target raise its own arm above its own head,
not reach for the same spot in the air.

| Word | In plain words | Class |
|---|---|---|
| **Rig** | which bones of a skeleton are its hips, spine, arms and legs | `CgRig` |
| **Retargeter** | how one rig's movement maps onto another's | `CgRetargeter` |
| **Bake** | a source clip converted, once, into a clip of the target's own bones | `CgRetargetBake` |
| **Resolver** | which clip plays for a name: the target's own, else the source's baked onto it | `CgClipResolver` |

## The code: a clip pack on your model

```java
CgSkeletalMesh pack = CgMeshLoader.skeletal("mymod:models/fighter_pack.glb");   // the source: its skeleton and its clips
CgSkeletalMesh hero = CgMeshLoader.skeletal("mymod:models/hero.glb");           // your model

CgRig from = CgRigs.detect(pack.skeleton(), CgRigAdapters.CRYSTALGRAPHICS);     // found from the bone names
CgRig to = CgRigs.detect(hero.skeleton(), CgRigAdapters.CRYSTALGRAPHICS);
CgRetargetProcessor toHero = new CgRetargetProcessor(
        CgRetargeter.auto(pack.skeleton(), from, hero.skeleton(), to), pack.skeleton(), hero.skeleton());

CgClipResolver clips = new CgClipResolver(hero::sequence, pack::sequence, toHero);
clips.bakeAll(List.of("punch", "kick", "roll", "victory"));      // at load, across workers

body.anim().play(clips.resolve("roll"), false);                   // the hero's own "roll" if it has one, else the pack's
```

- **`detect`** reads a skeleton named the standard way ([`../authoring/naming-conventions.md`](../authoring/naming-conventions.md)).
  `CgRigAdapters.GECKOLIB` reads GeckoLib's humanoid, and Minecraft's own model has its rig
  ([`vanilla-models.md`](vanilla-models.md)). It answers null for a skeleton of none of these shapes: write a rig.
- **`auto`** maps chains by name and lines the two rest poses up.
- **A graph** takes the resolver as its clip source:
  `GRAPH.get().graph(hero.skeleton(), id -> clips.resolve(id.substring(id.lastIndexOf('/') + 1)))`.

## Variations

### A rig of your own

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
# assets/mymod/rigs/golem.yaml
_v: 1
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
| `goal` | the IK bone a limb's end is placed by; `rig.ikBones(skeleton)` adds it as a virtual bone where the skeleton has none |
| `bend` | which way the middle joint bends, in the model's space, for a limb its clips hold straight |
| `rides` | the bone a chain moves with as if hung from it: GeckoLib's humanoid hangs its arms and head from its pelvis, so they ride `body` or they stay put as the chest leans |
| `pivot` | where a chain turns, in the model's space at rest, for a bone pivoted elsewhere: GeckoLib's humanoid pivots its torso at the neck, so it turns about its hips |

Chains map by name (`arm_l` to `arm_l`), else by the closest name: name yours as the source's are and `auto` maps them.

### A retargeter as a file

```yaml
# assets/mymod/retargeters/pack_to_golem.yaml
_v: 1
source: mymod:fighter_pack
target: mymod:golem
poses:
  target: { rotations: { left_arm: [0, 0, -1.2], right_arm: [0, 0, 1.2] } }   # the target's arms lowered to meet the source's stance
chains:
  - { id: m1, target: arm_l, source: arm_l, fk: { rotation: interpolated }, ik: { enabled: true } }
ops:
  - { id: o1, op: scale_source, factor: 1 }
  - { id: o2, op: pelvis_motion, scale_horizontal: 1, scale_vertical: 1 }
  - { id: o3, op: fk_chains }
  - { id: o4, op: ik_chains }
  - { id: o5, op: floor_constraint, weight: 1 }
```

`retargeter.toTree()` writes one built in Java.

### What each step does

A retargeter runs its steps in order, each on the pose the last left:

| Step | Does | Without it |
|---|---|---|
| `scale_source` | scales the source before anything reads it | — |
| `pelvis_motion` | moves the target's hips as the source's move, scaled by the ratio of their hip heights, and turns the pelvis with it | the body stays put while the source jumps |
| `fk_chains` | turns each target chain's bones as its source chain's turn, sampled along the chain so chains of different bone counts match | the target stays in its rest stance |
| `ik_chains` | puts each hand and foot where the source's is, relative to its shoulder or hip and scaled to the target's own limb, then bends the limb to it, or points a rigid part at it (Minecraft's one-cube arm) | hands and feet drift on a body built differently |
| `floor_constraint` | keeps a foot at the source's height above the floor, plus the target's own foot height | feet sink or float on a body whose legs differ |

```java
CgRetargeter tuned = retargeter.toBuilder()
        .ops(CgRetargeter.Op.scaleSource(1f), CgRetargeter.Op.pelvisMotion(0.8f, 1f),   // 80% of the source's travel
             CgRetargeter.Op.FK_CHAINS, CgRetargeter.Op.IK_CHAINS, CgRetargeter.Op.floor(1f))
        .build();
CgRetargeter oneToOne = retargeter.toBuilder()
        .chain(CgRetargeter.ChainMap.of("spine", "spine").fk(CgRetargeter.Rotation.ONE_TO_ONE, CgRetargeter.Translation.NONE))
        .build();
```

### Sockets and effect bones

A move may name sockets and bones of its own character: a hand socket an effect attaches to, a keyed dummy bone a
trail follows. On your model each must still be somewhere:

```java
CgRig source = CgRigs.detect(pack.skeleton(), CgRigAdapters.CRYSTALGRAPHICS).toBuilder()
        .carried("attach_r").carried("attach_l")             // keyed dummies: keep their motion on the target
        .build();
CgRetargetProcessor p = new CgRetargetProcessor(CgRetargeter.auto(pack.skeleton(), source, hero.skeleton(), to),
        pack.skeleton(), hero.skeleton());
CgSkeletalMesh placed = p.mesh(hero, pack.sockets());      // the carried bones added, every source socket placed
```

- **A socket sits where it sat on the source**, relative to the nearest bone a chain maps, scaled by the height
  ratio. Every source bone the target lacks becomes such a socket, so anything a move names resolves.
- **A carried bone is added to the target** and keeps its keyed motion: a dummy swinging round the source's hand
  swings round your model's hand.
- **Your model's own sockets win**, then the retargeter's `sockets:`, then the placed ones.

### Baking ahead

```java
assets.preparer(MyKinds.FIGHTER, (definition, plan) -> clips.plan(plan, ((Fighter) definition).moveClips()));
```

`clips.plan` in a preparer bakes on workers while the asset loads, so nothing bakes in play. A sequence's animation
section asks its role for a clip: answer with `clips.find(name)`.

## What goes wrong

- **`detect` answers null**: the skeleton matches no known shape. Write a rig.
- **Arms through the body**: the two rest stances differ (arms down against arms out). Set the retargeter's target
  pose to meet the source's.
- **A hitch the first time a move plays**: its clip was not named to `bakeAll`, so it baked on first use. It counts
  `retarget.late` on the `crystalgraphics.world` trace channel.
- **A clip neither model has** fails, naming it.
- **Not built, and failing by name** when the processor is made or the file loads: the `match_chain`,
  `match_scaled_chain` and `copy_local` rotations, the `stretch_*` and `orient_and_scale` translations, the `pin_bone`
  and `curve_remap` steps, a pose's `root_offset`, an IK chain's `static_rotation_offset` and `scale_vertical`.
- **IK solves limbs of three bones** (shoulder, elbow, hand) and points a rigid part of two at its goal; a longer limb
  with a goal is refused, naming it.
- **A one-bone torso standing for a three-bone spine** takes the whole lean, not a curl: a rigid torso cannot bend.
- **Hands land where the target's own arms reach**, not where the source's hands were in the world: each hand keeps
  its direction from its shoulder at the target's own reach.
- **A processor is one thread's.** `bakeAll` makes one per worker. After a reload, `CgRetargetBake.clear()`.

## Where next

- A vanilla zombie or player playing the pack: [`vanilla-models.md`](vanilla-models.md).
- Your model on an entity: [`actors.md`](actors.md).
- The full walkthrough: [`../start/6-someone-elses-animations-on-your-model.md`](../start/6-someone-elses-animations-on-your-model.md).
