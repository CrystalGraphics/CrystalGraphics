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
| `CgSequencePlayerNode.of(clip)` `.loop()` `.playRate(r or variable)` `.startPosition(s)` `.sync(group)` | Sequence Player | plays a clip on its own clock, or in step with its sync group; an additive clip gives its differences |
| `CgBlendSpacePlayerNode.of(space, x[, y])` `.playRate(...)` `.sync(group)` `.once()` | Blend Space Player | clips blended by where an input falls among them ([below](#blend-spaces)) |
| `CgAimOffsetNode.of(base, space, yaw, pitch)` `.alpha(...)` | Aim Offset | a mesh-space additive blend space turned onto `base` |
| `CgRefPoseNode.create()` | Reference Pose | the skeleton's rest pose |
| `CgTwoWayBlendNode.of(a, b, alpha)` | Blend | `a` and `b` by an alpha, a constant or a float variable |
| `CgBlendListNode.byBool(v, t, f)`, `.byInt(v, poses...)` `.blendTimes(...)` `.resetOnActivate()` | Blend Poses by bool / int | one pose by a variable, easing into each over its blend time |
| `CgSlotNode.of(name, source)` | Slot | where montages and sequences play over `source` |
| `CgLayeredBlendNode.of(base).layer(pose, mask[, weight])` `.curvesFromBase()` | Layered blend per bone | a pose over part of the skeleton |
| `CgApplyAdditiveNode.of(base, additive, alpha)` | Apply Additive | an additive pose added over `base` |
| `graph.save(name, source)`, `CgCachedPoseNode.use(name)` | Save / Use Cached Pose | one pose read in several places, evaluated once a frame |
| `CgAnimStateMachineNode.of(machine)` | State Machine | states and the transitions between them ([below](#state-machines)) |
| `CgInertializationNode.of(source)` | Inertialization | smooths a switch below it away instead of crossfading |

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

## State machines

A **state machine** (`CgAnimStateMachine`, Unreal's) plays one state at a time and moves between states when a
transition's rule holds, crossfading as it goes. States are poses (any node, another machine included); a **conduit** is
a branch point with a rule of its own.

```java
static final CgAnimVariable<Float> SPEED = CgAnimVariable.floating("speed");
static final CgAnimVariable<Boolean> GROUNDED = CgAnimVariable.bool("grounded", true);

static final CgAnimStateMachine LOCOMOTION = CgAnimStateMachine.builder("locomotion")
        .state(CgAnimState.of("idle", CgSequencePlayerNode.of(IDLE).loop()))
        .state(CgAnimState.of("run", CgSequencePlayerNode.of(RUN).loop()))
        .state(CgAnimState.of("fall", CgSequencePlayerNode.of(FALL).loop()))
        .state(CgAnimState.of("land", CgSequencePlayerNode.of(LAND)).alwaysResetOnEntry())
        .transition(CgAnimTransition.of("idle", "run", CgAnimRule.variable(SPEED, CgAnimRule.Op.GREATER, 0.1f)))
        .transition(CgAnimTransition.of("run", "idle", CgAnimRule.variable(SPEED, CgAnimRule.Op.LESS_EQUAL, 0.1f)).crossfade(0.3f))
        .transition(CgAnimTransition.of("idle", "fall", CgAnimRule.not(CgAnimRule.isTrue(GROUNDED))).priority(0))
        .transition(CgAnimTransition.of("run", "fall", CgAnimRule.not(CgAnimRule.isTrue(GROUNDED))).priority(0))
        .transition(CgAnimTransition.of("fall", "land", CgAnimRule.isTrue(GROUNDED)).crossfade(0.05f))
        .transition(CgAnimTransition.automatic("land", "idle").crossfade(0.15f))      // when the landing clip runs out
        .build();

anim.graph(CgAnimGraph.builder("mymod:hero", mesh.skeleton()).variables(SPEED, GROUNDED)
        .output(CgSlotNode.of(CgAnimGraph.DEFAULT_SLOT, CgAnimStateMachineNode.of(LOCOMOTION)))
        .build());
```

| Rule | Holds when |
|---|---|
| `CgAnimRule.variable(v, op, value)`, `isTrue(bool)` | a variable compares so (`<`, `<=`, `>`, `>=`, `==`, `!=`) |
| `timeRemaining(op, s)`, `timeRemainingFraction(op, f)` | the state's clip has so long left: its highest weighted clip |
| `stateTime(op, s)` | the machine has been in the state so long |
| `event(name)` | the game requested the event: `anim.requestTransitionEvent("jump", 0.25)` |
| `all(...)`, `any(...)`, `not(r)`, `always()` | combinations; `always()` is an empty `all` |

- **A state's exits are tried by priority**, lower first (Unreal's default is 1), then in the order made. The first
  whose rule holds is taken; up to three a frame (`maxTransitionsPerFrame`), each from the state the last entered.
- **A crossfade** runs its `crossfade` seconds (0.2 by default) along its `blend` curve (`HERMITE_CUBIC` by default, any
  of Unreal's `EAlphaBlendOption`s). One taken while another runs blends over that one's result. Back into a state that
  still shows, it is shortened by how much of it shows.
- **A state restarts** when it is entered from no weight, or on every entry with `alwaysResetOnEntry()`.
- **`automatic`** is Unreal's rule based on the state's clip: taken when the clip has `triggerTime` left (the crossfade
  by default); the crossfade is then shortened by how far past that the frame went, unless
  `overshootCorrection(false)` (Unreal 4's behaviour).
- **On its first frame** a machine enters at once whatever its transitions reach, with no crossfade.
- `minReentry(s)` keeps a state from being entered again within `s` seconds.
- **Events** are queued for a time to live on the instance's clock; the first transition to take one consumes it, or
  each transition once with `requestTransitionEvent(name, ttl, true, Replace.APPEND)`.

For a tool or a test, `machineNode.in(anim.graph())` reads a machine's `currentState()`, `elapsed()`,
`stateWeight(name)` and `activeTransitions()`.

### Inertialization

A transition marked `inertialize()` switches at once and hands its time to the nearest `CgInertializationNode` above
the machine. That node carries each bone's offset and velocity from the old pose into the new one and decays it to zero
along a curve that never overshoots (Unreal's inertialization). Only the target state runs, so it costs one pose where a
crossfade costs two: the cheap choice for sudden moves such as a hit, a dodge or a cancel.

```java
CgAnimStateMachine moves = CgAnimStateMachine.builder("moves")
        .state(CgAnimState.of("guard", guard)).state(CgAnimState.of("hit", hit).alwaysResetOnEntry())
        .transition(CgAnimTransition.of("guard", "hit", CgAnimRule.event("hit")).crossfade(0.2f).inertialize())
        .transition(CgAnimTransition.automatic("hit", "guard").inertialize())
        .build();
CgAnimGraph.builder("mymod:fighter", skeleton)
        .output(CgInertializationNode.of(CgSlotNode.of(CgAnimGraph.DEFAULT_SLOT, CgAnimStateMachineNode.of(moves))))
        .build();
```

- **A machine that inertializes with no inertialization node above it** fails when an instance runs the graph. A
  machine reached through a cached pose cannot reach one.
- **Offsets are in each bone's parent space**: a jump of the whole character is not smoothed, and material curves switch
  at once.

## Blend spaces

A **blend space** (`CgBlendSpace`, Unreal's) places clips on one or two axes and blends the ones nearest an input. One
axis of speed turns an idle, a jog and a sprint into one locomotion; speed and direction make strafing.

```java
static final CgBlendSpace RUN = CgBlendSpace.oneD("mymod:hero_run", "speed", 0, 8)
        .sample(IDLE, 0).sample(JOG, 4).sample(SPRINT, 8)
        .weightSpeed(5)                                       // weights move toward their targets 5 a second
        .build();

static final CgBlendSpace STRAFE = CgBlendSpace.twoD("mymod:hero_strafe", "direction", -180, 180, "speed", 0, 6)
        .sample(IDLE, 0, 0).sample(FORWARD, 0, 6).sample(LEFT, -90, 6).sample(RIGHT, 90, 6)
        .sample(BACK, -180, 6).sample(BACK, 180, 6)
        .wrap(0)                                              // -180 and 180 are one direction
        .smoothing(1, 0.2f, CgBlendFilter.Type.SPRING_DAMPER) // the speed eases over 0.2 s
        .build();

CgBlendSpacePlayerNode.of(RUN, SPEED);                        // in a graph, by a variable
CgBlendSpacePlayerNode.of(STRAFE, DIRECTION, SPEED);
```

- **Its time is shared**: every sample sits at the same fraction of its own clip, so a jog and a sprint of different
  lengths stay in step. Where its clips carry sync markers (`foot_l`, `foot_r`), the heaviest marked clip leads and the
  others follow its markers instead.
- **Weights ease** toward each frame's targets at `weightSpeed` a second, eased by default; `weightEase(false)` moves them
  at a constant speed (Unreal 4's).
- **The lookup** is release Unreal's: between the nearest samples on one axis, by triangles on two.
  `gridInterpolation(true)` uses Unreal 4's grid instead; `legacyLength(true)` its way of timing a blend. An importer of
  an Unreal 4 game sets both.
- **Past an axis's end** the input is clamped; `scaleAxis(X)` plays the clips faster instead, so a sprint past the
  fastest sample does not slide its feet.
- A sample outside its axes, or clips of mixed additive kinds, fails when the blend space is built.

### Aim offsets

An **aim offset** is a blend space of mesh-space additive clips (each the character aimed up, down, left, right, made
with `CgAdditive.meshSpace`): the one at the look's angle is added over whatever plays, in the mesh's space, so the spine,
arms and head turn together whatever the legs do.

```java
static final CgBlendSpace AIM = CgBlendSpace.twoD("mymod:hero_aim", "yaw", -90, 90, "pitch", -90, 90)
        .sample(AIM_CENTRE, 0, 0).sample(AIM_LEFT, -90, 0).sample(AIM_RIGHT, 90, 0).sample(AIM_UP, 0, 90).sample(AIM_DOWN, 0, -90)
        .build();

CgAimOffsetNode.of(locomotion, AIM, YAW, PITCH).alpha(AIMING);    // only while aiming
```

### Sync groups

Players in one **sync group** move together: the heaviest leads, the others follow its sync markers, or its fraction of
its clip where they have none. A walk and a run blended by speed keep their feet in step.

```java
CgTwoWayBlendNode.of(
        CgSequencePlayerNode.of(WALK).loop().sync(CgSyncGroup.of("feet")),
        CgSequencePlayerNode.of(RUN).loop().sync(CgSyncGroup.of("feet")), SPEED);

CgSequencePlayerNode.of(LIMP).loop().sync(CgSyncGroup.of("feet", CgAnimGroupRole.ALWAYS_FOLLOWER));
```

- A group's markers are those its leader and every marked member share; the rest are stepped over.
- `TRANSITION_LEADER` and `TRANSITION_FOLLOWER` join only once fully blended in, as Unreal's.
- Play rate by speed: `playRate(STRIDE)` reads a float variable each frame.

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
| `sequence_player` | `clip` (an id the mesh resolves), `loop`, `play_rate`, `start_position`, `sync_group`, `group_role` |
| `blend_space_player` | `blend_space` (an id), `x`, `y`, `play_rate`, `loop` (true), `start_position`, `reset_on_change`, `sync_group`, `group_role` |
| `aim_offset` | `base`, `blend_space`, `x`, `y`, `alpha` |
| `ref_pose` | — |
| `slot` | `slot_name` (`DefaultSlot`), `source` |
| `two_way_blend` | `a`, `b`, `alpha` |
| `blend_list_by_bool` | `selector`, `when_true`, `when_false`, `blend_times`, `reset_child_on_activate` |
| `blend_list_by_int` | `selector`, `poses`, `blend_times`, `reset_child_on_activate` |
| `layered_blend` | `base`, `layers: [{ pose, mask: { branches: [{bone, depth}], bones: [{bone, weight}] }, weight, curves }]` |
| `apply_additive` | `base`, `additive`, `alpha` |
| `save_cached_pose` | `pose`: its own name is the cache's |
| `use_cached_pose` | `save` |
| `state_machine` | `machine`: a name under the graph's `machines` |
| `inertialization` | `source` |

- Nodes are a map by name, and a node reads others by name. An `alpha` or `weight` is a number or a declared float
  variable's name.
- **It fails at load**, naming the file and the node: an unknown kind, a name no node has, a node read twice or by
  none, an undeclared or mistyped variable. A clip the mesh lacks fails when the graph is bound to it.
- **Machines** sit beside the nodes, by name, their states' poses naming nodes:

```yaml
output: out
nodes:
  out: { kind: inertialization, source: body }   # what the inertialized transition needs above its machine
  body: { kind: slot, source: loco }
  loco: { kind: state_machine, machine: locomotion }
  idle: { kind: sequence_player, clip: mymod:hero/idle, loop: true }
  run: { kind: sequence_player, clip: mymod:hero/run, loop: true }
  land: { kind: sequence_player, clip: mymod:hero/land }
machines:
  locomotion:
    states:
      idle: { pose: idle }
      run: { pose: run }
      land: { pose: land, always_reset_on_entry: true }
      moving: { entry_rule: { variable: grounded, op: '==', value: true } }   # a conduit
    transitions:
      - { from: idle, to: moving, rule: { variable: speed, op: '>', value: 0.1 } }
      - { from: moving, to: run, rule: { all: [] }, crossfade: 0.25, blend: cubic_in_out }
      - { from: run, to: idle, rule: { variable: speed, op: '<=', value: 0.1 }, crossfade: 0.3 }
      - { from: land, to: idle, automatic: true, crossfade: 0.15 }
      - { from: run, to: land, rule: { event: land }, logic: inertialization, priority: 0 }
```

  A rule is `all`, `any`, `not`, `{ variable, op, value }`, `{ time_remaining | time_remaining_fraction | state_time:
  <op>, value }` or `{ event }`. A transition's other keys, each with Unreal's default: `crossfade` 0.2, `blend`
  hermite_cubic, `logic` standard, `priority` 1, `trigger_time` (the crossfade), `overshoot_correction` true,
  `min_reentry` (none), `inertialize_self` false. A machine's: `initial` (its first state),
  `max_transitions_per_frame` 3, `skip_first_update_transition` true, `reinitialize_on_becoming_relevant` true. Blend
  profiles, custom curves and custom transition graphs fail at load.
- **A child graph** names its `parent:` and overrides nodes by name, as any asset overrides its parent's keys:

```yaml
# anim_graphs/mymod/villain.yaml: the hero's graph with the villain's run
_v: 1
parent: mymod:hero
nodes:
  run: { clip: mymod:villain/run }
```
- `CgAnimGraphCodec.toTree(graph)` writes a graph built in Java; read back and bound, it is the same graph.
- **A blend space** is its own asset, `blend_spaces/mymod/hero_run.yaml`, which a graph's node names by id; its clips
  bind with the graph's:

```yaml
_v: 1
axes: [ { name: speed, min: 0, max: 8 } ]       # each: min, max, grid (4), smoothing: { time, type, damping, max_speed }, wrap
samples:
  - { clip: mymod:hero/idle, at: [0] }
  - { clip: mymod:hero/jog, at: [4] }
  - { clip: mymod:hero/sprint, at: [8], rate_scale: 1.1 }
weight_speed: 5
```

  Its other keys, each with release Unreal's default: `weight_ease` true, `grid_interpolation` false, `legacy_length`
  false, `scale_axis` none, and `grid`, the editor's cooked grid (per grid point `y·(nx+1)+x`, its `[sample, weight]`
  pairs), played as given. `CgBlendSpaceCodec.toTree(space)` writes one built in Java.

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
