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

### Variables an entity fills: `entity`

A graph that animates a Minecraft entity needs no `onUpdate` for what the entity is doing: it binds its variables to
the entity's properties, and the entity's actor sets them every frame before the graph runs, on both sides. Unreal's
gameplay tag property map, over the entity's properties. An entity's properties are a networked type on entities
([`docs/NETWORKING.md`](../../NETWORKING.md) § *An entity's properties*): the engine's own, `CgEntityProperties.MOTION`,
named `entity.<field>`, and any of your mod's, named `<type>#<field>`.

```yaml
# anim_graphs/mymod/hero.yaml
variables:
  speed: { type: float }
  grounded: { type: bool, default: true }
  draw: { type: float }
  ki: { type: float }
entity:
  speed: entity.speed           # the graph's own name: the property feeding it
  grounded: entity.grounded
  draw: entity.use_time
  ki: mymod:ki#current          # your mod's own type on entities
```

```java
CgAnimGraph.builder("mymod:hero", mesh.skeleton())
        .variables(SPEED, GROUNDED, KI)
        .entity(SPEED, CgEntityProperties.SPEED)
        .entity(GROUNDED, CgEntityProperties.GROUNDED)
        .entity(KI, Ki.TYPE, Ki.CURRENT)          // or .entity(KI, Ki.CURRENT): the one type listing it
        ...;
```

| Group | `entity.` names |
|---|---|
| Motion, smooth across the frame | `speed`, `vertical_speed`, `move_direction` (degrees against the facing, 90 left), `acceleration`, `turn_rate` |
| Intent | `input` (0 to 1), `input_direction`, `input_from_player`, `sprint_held`, `jump_held` |
| Ground and air | `grounded`, `airborne`, `air_time`, `ground_distance`, `land_height` |
| Stance | `living`, `sneaking`, `sprinting`, `swimming`, `crawling`, `swim_amount`, `gliding`, `spin_attack`, `in_water`, `riding`, `vehicle` (an id, -1 none), `sleeping` |
| Life | `dead`, `dead_time`, `hurt_time` (seconds since the last hit) |
| Look | `head_yaw` (against the body), `head_pitch` |
| Hands | `left_handed`, `main_item` and `off_item` (raw ids), `main_arm` and `off_arm` (an arm pose, int), `using`, `use_hand`, `use_animation` (int), `use_time`, `use_length`, `swinging`, `swing` (0 to 1), `swing_hand` |
| Events, true for their frame | `jumped`, `landed`, `hurt`, `swing_started`, `use_started`, `use_stopped`, `mounted`, `dismounted`, `died` |

- **Each fails at load, naming it**: a name no type or field has, a field no number or bool, a type not on entities,
  a graph variable the graph does not declare, or a type that cannot take the value. A float takes any (a bool as 0
  or 1); a bool or an int only its own type.
- **What each means**: [`docs/vfx/prefabs.md`](../../vfx/prefabs.md) § *What the entity is doing*. An actor feeds
  every skeletal mesh it `add`s and every skeletal node of its prefab.
- A graph's own variable keeps its name: a state machine's rule reads `speed`, never `entity.speed`, so the same graph
  runs fed by an entity or by a hand-written `onUpdate`.
- A mod's type is declared before the graph loads. Until the entity has an object of it on a side, a field reads its
  `fallback`; a local type's object is made when first read.

**A tag the entity owns** (`CgEntityTags`, [`docs/vfx/networking.md`](../../vfx/networking.md) § *Tags an entity
owns*) feeds a variable the same way: Unreal's gameplay tag property map. A bool is whether the tag is owned, an int
its count, a float its count; `as` says otherwise for a float. A tag counts the tags under it.

```yaml
variables:
  flying: { type: bool }
  stacks: { type: int }
  stunned: { type: float }
tags:
  flying: { tag: mymod:state.flying }               # owned, mymod:state.flying.fast included
  stacks: { tag: mymod:buff.rage }                  # its count
  stunned: { tag: mymod:state.stunned, as: bool }   # 0 or 1, into a float a blend reads
```

```java
builder.variables(FLYING).tag(FLYING, CgTagRegistry.global().require("mymod:state.flying"));
```

- **Each fails at load, naming it**: a tag no one declared, an undeclared variable, an `as` its type cannot take, or a
  variable bound both to an entity property and to a tag.

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

## Montages

A **montage** is a sequence played on a character: its `animation` sections on that character play as one montage,
Unreal's montage instance. It blends in when it starts, blends out by itself at its end or when it is stopped, stops
the other montage of its slot group, and moves the character by its clips' root motion.

```yaml
# sequences/mymod/moves/punch.yaml: a strike chained into its recovery, or into a second strike on the combo input
_v: 1
tick_resolution: 60000
range: [0, 96000]
composite_sections:
  - { id: q1, name: strike1, range: [0, 24000], next: q2 }
  - { id: q2, name: recover1, range: [24000, 48000] }
  - { id: q3, name: strike2, range: [48000, 72000], next: q4 }
  - { id: q4, name: recover2, range: [72000, 96000] }
roles: [ { id: b1, name: caster, kind: caster } ]
tracks:
  - id: t1
    kind: animation
    binding: { role: caster }
    sections:
      - { id: s1, range: [0, 24000], clip: mymod:hero/strike1, ease: { in: 6000 } }   # the montage's blend in: 0.1 s
      - { id: s2, range: [24000, 48000], clip: mymod:hero/recover1 }
      - { id: s3, range: [48000, 72000], clip: mymod:hero/strike2 }
      - { id: s4, range: [72000, 96000], clip: mymod:hero/recover2, ease: { out: 12000 } }   # its blend out: 0.2 s
  - { id: t2, kind: event, side: rpg, binding: { role: caster },
      sections: [ { id: h1, range: [6000, 18000], event: mymod:hit_window } ] }
```

```java
CgSequencePlayer punch = sequencer.play(PUNCH, Map.of("caster", hero));
punch.jumpToSection("strike2");                // the combo input: a branch, the same montage
punch.nextSection("strike1", "strike2");       // or: strike1 runs on into strike2 at its end
punch.stop();                                  // interrupted: blends out over 0.2 s, its clip playing on

hero.anim().montages().listen(new CgMontages.Listener() {
    @Override public void ended(CgMontageInstance m, boolean interrupted) { ... }
});
Matrix4f step = new Matrix4f();
if (hero.anim().montages().consumeRootMotion(1f, step)) mover.move(step);   // after the frame's pose
```

| Key (on the character's sections) | What it does |
|---|---|
| `ease.in` on its first section, `ease.out` on its last | its blend in and out, linear unless `in_fn` / `out_fn` name a curve |
| `blend_out_trigger_time` | ticks before its end it starts blending out; unset, its blend-out time, blending over the time left |
| `auto_blend_out: false` | it holds its last pose at its end until stopped |
| `slot_group` | its group, `DefaultGroup` unless named: a montage played stops the others of its group, blending them out over its own blend in |
| `root_motion` | whether its clips' root motion moves the character; unset, on where any clip has it |
| `sync_group` | the graph's sync group it leads while it blends out, so a run under it picks up where the montage leaves its feet |

- **Its end** is the end of a montage section that hands play to none, or of the sequence: a looping section
  never blends out until gameplay relinks it.
- **A branch is no new play**: `jumpToSection` and `nextSection` keep the montage at its weight. Over the network they
  are `CgSequenceNet`'s jumps, predicted by their owner; a late joiner seeks to the server's time and its montage
  blends in there.
- **One montage moves the character at a time** (`CgRootMotionMode.FROM_MONTAGES_ONLY`, Unreal's default): a root-motion
  montage played stops the one moving it. Its root is held at its clip's lock while it moves the character.
  `FROM_EVERYTHING` adds every playing clip's motion at its weight: what locomotion warping reads
  ([locomotion.md](locomotion.md)).
- **Gameplay moments are `event` sections** of side `rpg` (a hit window above), heard as every sequence's events are.

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

## One graph, many characters

A graph from a file names its clips by id and is bound to each mesh: **one graph, each character its own clips**.

```java
CgAnimGraphDefinition hero = (CgAnimGraphDefinition) load.definition();     // anim_graphs/mymod/hero.yaml
goku.anim().graph(hero.graph(goku.mesh().skeleton(), id -> gokuClips.get(id)));
vegeta.anim().graph(hero.graph(vegeta.mesh().skeleton(), id -> vegetaClips.get(id)));
```

A character whose graph differs only in some clips is a **child graph**: `parent:` and the nodes it changes, each
merged field by field, so replacing a node's `clip` or `blend_space` keeps its `loop`, its inputs and its sync group
(Unreal's child anim blueprint's asset overrides).

```yaml
# anim_graphs/mymod/goku.yaml
_v: 1
parent: mymod:hero
nodes:
  idle: { clip: mymod:goku/idle }
  run:  { blend_space: mymod:goku/run }
```

### The humanoid template: `crystalgraphics:humanoid`

The engine ships a graph for any humanoid body, Minecraft's own set of states driven by the entity's properties: give
it your clips in a child graph and an actor plays a full character with no graph of your own.

```yaml
# anim_graphs/mymod/knight.yaml
_v: 1
parent: crystalgraphics:humanoid
nodes:
  idle:       { clip: mymod:knight/idle }
  walk:       { clip: mymod:knight/walk }
  run:        { clip: mymod:knight/run }
  sneak_idle: { clip: mymod:knight/crouch }
  sneak_walk: { clip: mymod:knight/crouch_walk }
  fall:       { clip: mymod:knight/fall }
  swim:       { clip: mymod:knight/swim }
  crawl:      { clip: mymod:knight/crawl }
  glide:      { clip: mymod:knight/glide }
  ride:       { clip: mymod:knight/sit }
  sleep:      { clip: mymod:knight/sleep }
  dead:       { clip: mymod:knight/death }
  swing:      { clip: mymod:knight/swing }   # held at Minecraft's own swing progress, over the upper body
```

| Node | Plays while (`entity.*`), the first that holds | Loops |
|---|---|---|
| `dead` | `dead` | no |
| `sleep` | `sleeping` | yes |
| `ride` | `riding` | yes |
| `glide` | `gliding` | yes |
| `swim` | `swimming` | yes |
| `crawl` | `crawling` | yes |
| `fall` | `airborne` | yes |
| `sneak_walk` | `sneaking` and `speed` over 0.3 | yes |
| `sneak_idle` | `sneaking` | yes |
| `run` | `sprinting` and `speed` over 0.3 | yes |
| `walk` | `speed` over 0.3 blocks a second | yes |
| `idle` | otherwise | yes |

- **Each state leaves** once its own rule stops holding or a state above it starts, through a conduit, then into the
  first state that holds.
- **Over the states**: an `upper_body` linked layer (empty: the states' own pose), which an actor's `item_layers` fill
  with a weapon's arms; an `UpperBody` slot; the swing, held at `entity.swing` (0 to 1) from the `spine_01` branch while
  `entity.swinging`; and `DefaultSlot` over everything, for whole-body moves and reactions.
- **The swing's mask names `spine_01`**, the standard humanoid's: a body with other bone names overrides `with_swing`'s
  layers in its child, as any node.
- **Bound as it is, it fails** naming one of its clips (`crystalgraphics:humanoid/<node>`): they are placeholders.

## Linked layers and linked graphs

A **layer** is a named part of a graph that another graph may play instead: a sword's arms over a hero's locomotion,
swapped at runtime. The graph declares it, with the poses it takes; a `CgLinkedAnimLayerNode` plays it, the graph's
own until a graph implementing it is linked.

```java
CgAnimGraph hero = CgAnimGraph.builder("mymod:hero", skeleton)
        .layer(CgAnimGraph.Layer.of("arms", CgLinkedInputPoseNode.of("body"), "body").blend(0.2f, 0.2f))   // its own: the body through
        .output(CgInertializationNode.of(CgLinkedAnimLayerNode.of("arms").input("body", locomotion)))
        .build();
CgAnimGraph swordArms = CgAnimGraph.builder("mymod:sword_arms", skeleton)
        .layer(CgAnimGraph.Layer.of("arms", CgLayeredBlendNode.of(CgLinkedInputPoseNode.of("body"))
                .layer(CgSequencePlayerNode.of(SWORD_IDLE).loop(), CgBlendMask.branch("clavicle_r", 0)), "body").blend(0.2f, 0.2f))
        .output(CgRefPoseNode.create())
        .build();

anim.graph(hero);
anim.linkLayers(swordArms);     // the sword's arms, blended in over 0.2 s
anim.unlinkLayers(swordArms);   // the hero's own again
```

A **linked graph** runs another graph as a node; a tag lets the game swap it.

```java
CgAnimGraph face = CgAnimGraph.builder("mymod:face", skeleton).inputs("body")
        .output(CgLayeredBlendNode.of(CgLinkedInputPoseNode.of("body")).layer(blink, CgBlendMask.branch("head", 0)))
        .build();
CgAnimGraph hero = CgAnimGraph.builder("mymod:hero", skeleton)
        .output(CgLinkedAnimGraphNode.of(face).input("body", locomotion).tag("face"))
        .build();
anim.linkGraph("face", ANGRY_FACE);    // null: the body passes through
```

```yaml
# the hero above, as a file
layers:
  arms: { inputs: [body], output: arms_own, blend_in: 0.2, blend_out: 0.2 }
nodes:
  arms_own: { kind: linked_input_pose, name: body }
  arms:     { kind: linked_anim_layer, layer: arms, inputs: { body: locomotion } }
  face:     { kind: linked_anim_graph, graph: mymod:face, tag: face, inputs: { body: arms } }
```

- **A linked graph plays inside its owner**: its variables are the owner's of the same name and type, its slots the
  owner's (a montage plays there too), its players in the owner's sync groups.
- **Layers of one `group`** linked from one graph share one run of it; an ungrouped layer has its own.
- **A layer's blend times inertialize a swap**, and need an inertialization node above the layer node; a swap with
  none fails as it is linked.
- **Fails at build or load**: a layer node naming a layer its graph does not declare, an input no layer or graph
  takes, a linked input pose naming an input its layer lacks, a `graph` that is no `anim_graph`.

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

1. **The game thread**: each instance's montages (their weights, their clips onto their slots), its `onUpdate`, then
   its variables copied for the worker.
2. **A worker** (split across workers past `crystalgraphics.animation.parallelAt` instances): the graph's update
   (weights, which nodes show, every clip's tick), its evaluation into the pose (bone controllers included, [ik.md](ik.md)), the skinning matrices.
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
