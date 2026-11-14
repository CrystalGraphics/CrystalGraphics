# Graphs: what a character plays

A character's **anim instance** (`CgAnimInstance`, Unreal's `UAnimInstance`) decides its pose each frame. It plays one
clip, or runs an **anim graph** (`CgAnimGraph`, Unreal's anim blueprint): nodes that play clips and mix them by
**variables** your code sets from the game. A skeletal mesh component has one: `component.anim()`.

## One clip

```java
CgAnimInstance anim = goku.anim();               // goku: CgScene.client().skeletal(mesh)...spawn()
anim.play(mesh.sequence("idle"));                // looping, from its start: Unreal's PlayAnimation
anim.play(mesh.sequence("roar"), false);         // once: it holds its last frame
anim.position(0.5f);                             // its time, seconds
anim.rate(0.5f);                                 // half speed; playing(false) holds it
```

## A graph

A graph is built once and shared by every character it animates; each instance runs it with state of its own (clip
times, blend weights).

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
    .onUpdate((a, dt) -> a.set(SPEED, entity.speed() / 4f).set(GROUNDED, entity.onGround()));
```

- **`onUpdate` runs on the game thread every frame**, before the graph: Unreal's `NativeUpdateAnimation`. Read the game
  there and set variables. The graph then runs on a worker from a copy taken after it, so a variable set later in the
  frame is read next frame.
- **Variables are typed**: `floating`, `bool`, `integer`, each with an optional default. `set` boxes nothing, so set them
  every frame freely. A variable the graph does not declare throws.
- **A graph is a tree**: a node is read by one parent. A pose wanted twice is cached (below).
- **Building checks it**: a cycle, a node read twice, a cached pose never saved, an undeclared variable, each throws
  with the graph's id.

### The nodes

| Node | Unreal | Does |
|---|---|---|
| `CgSequencePlayerNode.of(clip)` `.loop()` `.playRate(r)` `.startPosition(s)` | Sequence Player | plays a clip on its own clock; an additive clip gives its differences |
| `CgRefPoseNode.create()` | Reference Pose | the skeleton's rest pose |
| `CgTwoWayBlendNode.of(a, b, alpha)` | Blend | `a` and `b` by an alpha, a constant or a float variable |
| `CgBlendListNode.byBool(v, t, f)`, `.byInt(v, poses...)` `.blendTimes(...)` `.resetOnActivate()` | Blend Poses by bool / int | one pose by a variable, easing into each over its blend time |
| `CgSlotNode.of(name, source)` | Slot | where montages and sequences play over `source` |
| `CgLayeredBlendNode.of(base).layer(pose, mask[, weight])` `.curvesFromBase()` | Layered blend per bone | a pose over part of the skeleton |
| `CgApplyAdditiveNode.of(base, additive, alpha)` | Apply Additive | an additive pose added over `base` |
| `graph.save(name, source)`, `CgCachedPoseNode.use(name)` | Save / Use Cached Pose | one pose read in several places, evaluated once a frame |

- **A node updates only while it shows**: a player under a blend at weight 0 does not advance, as in Unreal. Use
  `resetOnActivate()` to restart a pose each time it is chosen.
- **Blend lists ease linearly** over each pose's blend time, scaled by how far it has to go, as Unreal's
  `FAnimNode_BlendListBase` does; the first choice is instant.

## Slots: montages over the graph

A **slot** is where a montage or a sequence's `animation` track plays this frame. A sequence plays on the slot its
section names, `DefaultSlot` where it names none; a slot the graph lacks plays nothing.

```java
CgAnimSlot slot = anim.slot("DefaultSlot");      // null where the graph has none
slot.play(roar, 0.4f, 1f);                       // this frame: roar at 0.4 s over what the graph plays
slot.play(roar, 0.4f, 0.25f);                    // a quarter roar over three quarters of the graph
anim.slot("FacialSlot").stop(1f);                // its entries blended out: the graph shows through
```

- **Entries last one frame**: set them every frame they should hold (`onUpdate` is the place).
- Past a total weight of 1 the entries are normalised and the source is gone (Unreal's `GetSlotWeight`).
- An entry's clip is sampled over the rest pose, as a montage's track is: limit a slot to the bones its clips key with
  a layered blend.

## Layers and cached poses

Kakarot's face is a slot over the face's bones, on top of a body pose read twice:

```java
CgAnimGraph.builder("mymod:hero", skeleton)
        .save("body", CgSlotNode.of(CgAnimGraph.DEFAULT_SLOT, locomotion))      // evaluated once a frame
        .output(CgLayeredBlendNode.of(CgCachedPoseNode.use("body"))
                .layer(CgSlotNode.of("FacialSlot", CgCachedPoseNode.use("body")), CgBlendMask.branch("facialbase", 1)))
        .build();
```

```java
CgBlendMask face = CgBlendMask.branch("facialbase", 1);                       // the branch, all at once
CgBlendMask arms = CgBlendMask.branch("upperarm_l", 0).andBranch("upperarm_r", 0);
CgBlendMask spine = CgBlendMask.branch("spine_01", 3);                        // ramping in over three levels
CgBlendMask lean = CgBlendMask.bone("spine_02", 0.5f).andBone("head", 0.25f); // a weight per bone
CgLayeredBlendNode.of(run).layer(aim, spine, AIM_WEIGHT);                     // the layer's weight a variable
```

- A branch weighs a bone `d` levels below its root `(d + 1) / depth`, at most 1; depth 0 takes the whole branch.
  Branches add, at most 1; a bone weight replaces what the branches gave it.
- A mask naming a bone the skeleton lacks throws when an instance runs the graph.
- A layer's material curves replace the base's (`MaxWeight`); `curvesFromBase()` keeps the base's (`UseBaseOnly`).

## From a file

The same graph, as `anim_graphs/mymod/hero.yaml`:

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

| Kind | Fields |
|---|---|
| `sequence_player` | `clip` (an id the mesh resolves), `loop`, `play_rate`, `start_position` |
| `ref_pose` | — |
| `slot` | `slot_name` (`DefaultSlot`), `source` |
| `two_way_blend` | `a`, `b`, `alpha` |
| `blend_list_by_bool` | `selector`, `when_true`, `when_false`, `blend_times`, `reset_child_on_activate` |
| `blend_list_by_int` | `selector`, `poses`, `blend_times`, `reset_child_on_activate` |
| `layered_blend` | `base`, `layers: [{ pose, mask: { branches: [{bone, depth}], bones: [{bone, weight}] }, weight, curves }]` |
| `apply_additive` | `base`, `additive`, `alpha` |
| `save_cached_pose` | `pose`: its own name is the cache's |
| `use_cached_pose` | `save` |

- Nodes are a map by name, and a node reads others by name. An `alpha` or `weight` is a number or a declared float
  variable's name.
- **It fails at load**, naming the file and the node: an unknown kind, a name no node has, a node read twice or by
  none, an undeclared or mistyped variable. A clip the mesh lacks fails when the graph is bound to it.
- **A child graph** names its `parent:` and overrides nodes by name, as any asset overrides its parent's keys:

```yaml
# anim_graphs/mymod/villain.yaml: the hero's graph with the villain's run
_v: 1
parent: mymod:hero
nodes:
  run: { clip: mymod:villain/run }
```
- `CgAnimGraphCodec.toTree(graph)` writes a graph built in Java; read back and bound, it is the same graph.

## The frame

`CgAnimation` runs every instance of a world each frame, as Unreal does:

1. **The game thread**: each instance's `onUpdate`, then its variables copied for the worker.
2. **A worker** (split across workers past `crystalgraphics.animation.parallelAt` instances): the graph's update
   (weights, which nodes show, every clip's tick), its evaluation into the pose, bone writes, the skinning matrices.
3. **The game thread**: the frame's material curves onto the component's materials.

- **Nothing allocates per frame** once a graph has run a few frames.
- **A graph keeps state between frames**, as Unreal's does, so it is not sought. A sequence is: it sets slot entries
  at the time it is sought to.
- **While the game is paused** each instance moves 0 s and is still posed.
- By hand, for a tool or a test: `anim.tick(seconds)` runs one frame on the calling thread.

## On a dedicated server

```java
CgAnimInstance body = CgAnimation.server().add(new CgAnimInstance(mesh).graph(hero(mesh)));
Matrix4f hand = body.socket(body.socket("hand_r"), new Matrix4f());   // this tick's pose, in the mesh's space
```

The server runs instances each game tick for their bones and sockets, and draws nothing.
