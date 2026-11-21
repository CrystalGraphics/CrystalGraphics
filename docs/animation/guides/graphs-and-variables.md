# Graphs and variables: what a character plays

**What you will have:** a character that idles, runs and falls on its own, chosen each frame by variables your code
(or its entity) sets: one graph, shared by every character it animates, in Java or as a file.

## In plain words

A character has many clips: standing, walking, running, falling. Something has to decide, every moment, which one to
show, and how to mix two while it changes from one to the other. That decider is the **graph**: a little flowchart of
"if fast, show the run; if in the air, show the fall". The **variables** are the facts it looks at, like how fast the
character goes, the way you look at the weather to decide whether to take a coat.

A character's **anim instance** (`CgAnimInstance`) decides its pose each frame. It plays one clip, or runs an **anim
graph** (`CgAnimGraph`): nodes that play clips and mix them by **variables**. A skeletal mesh component has one:
`component.anim()`.

## The code

One clip first:

```java
CgAnimInstance anim = knight.anim();             // knight: CgScene.client().skeletal(mesh)...spawn()
anim.play(mesh.sequence("idle"));                // looping, from its start
anim.play(mesh.sequence("roar"), false);         // once: it holds its last frame
anim.position(0.5f);                             // its time, seconds
anim.rate(0.5f);                                 // half speed; playing(false) holds it
```

A graph: idle and run blended by speed, a fall while off the ground.

```java
static final CgAnimVariable<Float> SPEED = CgAnimVariable.floating("speed");
static final CgAnimVariable<Boolean> GROUNDED = CgAnimVariable.bool("grounded", true);

static CgAnimGraph hero(CgSkeletalMesh mesh) {
    return CgAnimGraph.builder("mymod:hero", mesh.skeleton())
            .variables(SPEED, GROUNDED)
            .output(CgSlotNode.of(CgAnimGraph.DEFAULT_SLOT,                        // where montages play
                    CgBlendListNode.byBool(GROUNDED,
                                    CgTwoWayBlendNode.of(
                                            CgSequencePlayerNode.of(mesh.sequence("idle")).loop(),
                                            CgSequencePlayerNode.of(mesh.sequence("run")).loop(), SPEED),
                                    CgSequencePlayerNode.of(mesh.sequence("fall")).loop())
                            .blendTimes(0.2f, 0.1f)))                              // into each pose, seconds
            .build();
}

anim.graph(hero(mesh))
    .onUpdate((a, dt) -> a.set(SPEED, speed / 4f).set(GROUNDED, onGround));
```

The same graph as `assets/mymod/anim_graphs/hero.yaml`:

```yaml
_v: 1
variables:
  speed: { type: float }
  grounded: { type: bool, default: true }
output: body
nodes:
  body: { kind: slot, source: locomotion }
  locomotion: { kind: blend_list_by_bool, selector: grounded, when_true: ground, when_false: fall, blend_times: [0.2, 0.1] }
  ground: { kind: two_way_blend, a: idle, b: run, alpha: speed }
  idle: { kind: sequence_player, clip: mymod:hero/idle, loop: true }
  run: { kind: sequence_player, clip: mymod:hero/run, loop: true }
  fall: { kind: sequence_player, clip: mymod:hero/fall, loop: true }
```

```java
static final CgAssetRef<CgAnimGraphDefinition> HERO = CgVfxAssets.ref(CgBodyKinds.ANIM_GRAPH, "mymod:hero");

CgAnimGraph graph = HERO.get().graph(mesh.skeleton(), id -> mesh.sequence(id.substring(id.lastIndexOf('/') + 1)));
anim.graph(graph);                                // bind once per mesh and keep it
```

A graph on an entity's actor needs neither: the actor binds it to its body and feeds it from the entity
([`entity-variables.md`](entity-variables.md)).

## How it runs

- **`onUpdate` runs on the game thread every frame**, before the graph. Read the game there and set variables. The
  graph then runs on a worker from a copy taken after it, so a variable set later in the frame is read next frame.
- **Variables are typed**: `floating`, `bool`, `integer`, each with an optional default. `set` boxes nothing, so set
  them every frame freely.
- **A graph is a tree**: a node is read by one parent. A pose wanted twice is cached
  ([`layers-and-masks.md`](layers-and-masks.md)).
- **A node updates only while it shows**: a player under a blend at weight 0 does not advance. `resetOnActivate()`
  restarts a pose each time it is chosen.
- **Blend lists ease linearly** over each pose's blend time, scaled by how far it has to go; the first choice is
  instant.

## The nodes

| Java | File `kind` | Does |
|---|---|---|
| `CgSequencePlayerNode.of(clip)` `.loop()` `.playRate(r or variable)` `.startPosition(s)` `.sync(group)` | `sequence_player`: `clip`, `loop`, `play_rate`, `start_position`, `sync_group`, `group_role` | plays a clip on its own clock, or in step with its sync group |
| `CgSequenceEvaluatorNode.of(clip).explicitTime(v)` | `sequence_evaluator`: `clip`, `time`, `normalized`, `mode`, `distance` | a clip held at a time the graph sets ([`reactions-and-locks.md`](reactions-and-locks.md)) |
| `CgBlendSpacePlayerNode.of(space, x[, y])` | `blend_space_player`: `blend_space`, `x`, `y`, `play_rate`, `loop`, `sync_group` | clips blended by where an input falls ([`blend-spaces.md`](blend-spaces.md)) |
| `CgAimOffsetNode.of(base, space, yaw, pitch)` `.alpha(...)` | `aim_offset`: `base`, `blend_space`, `x`, `y`, `alpha` | an additive blend space turned onto `base` ([`additive-and-aim-offsets.md`](additive-and-aim-offsets.md)) |
| `CgRefPoseNode.create()` | `ref_pose` | the skeleton's rest pose |
| `CgTwoWayBlendNode.of(a, b, alpha)` | `two_way_blend`: `a`, `b`, `alpha` | `a` and `b` by an alpha, a constant or a float variable |
| `CgBlendListNode.byBool(v, t, f)`, `.byInt(v, poses...)` `.blendTimes(...)` `.resetOnActivate()` | `blend_list_by_bool`, `blend_list_by_int` | one pose by a variable, easing into each |
| `CgSlotNode.of(name, source)` | `slot`: `slot_name`, `source` | where montages play over `source` ([`montages-and-slots.md`](montages-and-slots.md)) |
| `CgLayeredBlendNode.of(base).layer(pose, mask[, weight])` | `layered_blend` | a pose over part of the skeleton ([`layers-and-masks.md`](layers-and-masks.md)) |
| `CgApplyAdditiveNode.of(base, additive, alpha)` | `apply_additive`: `base`, `additive`, `alpha` | an additive pose added over `base` |
| `graph.save(name, source)`, `CgCachedPoseNode.use(name)` | `save_cached_pose`, `use_cached_pose` | one pose read in several places, evaluated once |
| `CgAnimStateMachineNode.of(machine)` | `state_machine`: `machine` | states and transitions ([`state-machines.md`](state-machines.md)) |
| `CgInertializationNode.of(source)` | `inertialization`: `source` | smooths a switch below it ([`transitions-and-blending.md`](transitions-and-blending.md)) |

In a file, nodes are a map by name and a node reads others by name. An `alpha` or `weight` is a number or a declared
float variable's name. IK and procedural nodes are [`ik-and-look-at.md`](ik-and-look-at.md).

## Variations

### One graph, many characters

A graph from a file names its clips by id and is bound to each mesh, so each character plays its own clips:

```java
CgAnimGraphDefinition hero = HERO.get();
knight.anim().graph(hero.graph(knight.mesh().skeleton(), id -> knightClips.get(id)));
rogue.anim().graph(hero.graph(rogue.mesh().skeleton(), id -> rogueClips.get(id)));
```

### Child graphs

A character whose graph differs only in some clips is a **child graph**: `parent:` and the nodes it changes, each
merged field by field, so replacing a node's `clip` keeps its `loop`, its inputs and its sync group.

```yaml
# assets/mymod/anim_graphs/villain.yaml: the hero's graph with the villain's run
_v: 1
parent: mymod:hero
nodes:
  run: { clip: mymod:villain/run }
```

The engine ships one parent for every humanoid: [`vanilla-models.md`](vanilla-models.md).

### Writing a Java graph as a file

```java
Map<String, Object> tree = CgAnimGraphCodec.toTree(graph);    // read back and bound, it is the same graph
```

## What goes wrong

- **Building checks it**, and throws with the graph's id: a cycle, a node read twice, a cached pose never saved, an
  undeclared variable.
- **A file fails at load**, naming the file and the node: an unknown kind, a name no node has, a node read twice or by
  none, an undeclared or mistyped variable.
- **A clip the mesh lacks** fails when the graph is bound to that mesh, naming it.
- **A variable set after `onUpdate`** is read next frame, not this one.
- **Nothing allocates per frame** once a graph has run a few frames; a hand-written `onUpdate` that allocates breaks
  that.

## Where next

- Choosing between states: [`state-machines.md`](state-machines.md).
- Fed by an entity, with no code: [`entity-variables.md`](entity-variables.md).
- A punch over the running legs: [`montages-and-slots.md`](montages-and-slots.md).
- What happens inside a frame: [`../concepts/the-frame.md`](../concepts/the-frame.md).
