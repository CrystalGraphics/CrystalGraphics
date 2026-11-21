# Tutorial 2: a character that runs and jumps

**What you will have at the end:** your own player drawn as your hero, idling, jogging and sprinting by speed, jumping,
falling and landing, every change blended. A graph of your own, fed by the player with no code.

You need tutorial 1's setup (a model, a material, a prefab) for `mymod:hero`, with animations named `idle`, `jog`,
`sprint`, `jump`, `fall` and `land`.

## 1. Speed into one locomotion: a blend space

```yaml
# assets/mymod/blend_spaces/hero_run.yaml
_v: 1
axes: [ { name: speed, min: 0, max: 8 } ]
samples:
  - { clip: mymod:hero/idle, at: [0] }
  - { clip: mymod:hero/jog, at: [4] }
  - { clip: mymod:hero/sprint, at: [8] }
weight_speed: 5
```

At 0 blocks a second it idles, at 4 it jogs, at 8 it sprints, and between them it blends.

## 2. The graph: ground, jump, fall, land

```yaml
# assets/mymod/anim_graphs/hero.yaml
_v: 1
variables:
  speed:    { type: float }
  grounded: { type: bool, default: true }
  jumped:   { type: bool }
entity:
  speed: entity.speed
  grounded: entity.grounded
  jumped: entity.jumped
output: out
nodes:
  out:    { kind: slot, source: loco }
  loco:   { kind: state_machine, machine: locomotion }
  ground: { kind: blend_space_player, blend_space: mymod:hero_run, x: speed }
  jump:   { kind: sequence_player, clip: mymod:hero/jump }
  fall:   { kind: sequence_player, clip: mymod:hero/fall, loop: true }
  land:   { kind: sequence_player, clip: mymod:hero/land }
machines:
  locomotion:
    states:
      ground: { pose: ground }
      jump:   { pose: jump, always_reset_on_entry: true }
      fall:   { pose: fall }
      land:   { pose: land, always_reset_on_entry: true }
    transitions:
      - { from: ground, to: jump, rule: { variable: jumped, op: '==', value: true }, crossfade: 0.1 }
      - { from: ground, to: fall, rule: { variable: grounded, op: '==', value: false }, crossfade: 0.2 }
      - { from: jump, to: fall, automatic: true, crossfade: 0.15 }
      - { from: fall, to: land, rule: { variable: grounded, op: '==', value: true }, crossfade: 0.05 }
      - { from: land, to: ground, automatic: true, crossfade: 0.2 }
      - { from: land, to: ground, rule: { variable: speed, op: '>', value: 2 }, crossfade: 0.15 }
```

The `entity:` block is the whole wiring: the player's speed, whether it stands on the ground, and the frame it jumps,
set every frame before the graph runs.

## 3. Wear it

```yaml
# assets/mymod/actors/hero.yaml
_v: 1
prefab: mymod:hero
anim_graph: mymod:hero
```

No `binds`, so nothing wears it yet. In game: `/cgc actor hero`. Press F5 to see yourself.

## 4. Try it

Walk, sprint, jump off a ledge. The jog blends into the sprint as you speed up; a jump plays once into the fall; the
landing plays, and a run cancels it early.

## The same graph in Java

```java
static final CgAnimVariable<Float> SPEED = CgAnimVariable.floating("speed");
static final CgAnimVariable<Boolean> GROUNDED = CgAnimVariable.bool("grounded", true);
static final CgAnimVariable<Boolean> JUMPED = CgAnimVariable.bool("jumped", false);

static final CgBlendSpace RUN = CgBlendSpace.oneD("mymod:hero_run", "speed", 0, 8)
        .sample(IDLE, 0).sample(JOG, 4).sample(SPRINT, 8).weightSpeed(5).build();

static final CgAnimStateMachine LOCOMOTION = CgAnimStateMachine.builder("locomotion")
        .state(CgAnimState.of("ground", CgBlendSpacePlayerNode.of(RUN, SPEED)))
        .state(CgAnimState.of("jump", CgSequencePlayerNode.of(JUMP)).alwaysResetOnEntry())
        .state(CgAnimState.of("fall", CgSequencePlayerNode.of(FALL).loop()))
        .state(CgAnimState.of("land", CgSequencePlayerNode.of(LAND)).alwaysResetOnEntry())
        .transition(CgAnimTransition.of("ground", "jump", CgAnimRule.isTrue(JUMPED)).crossfade(0.1f))
        .transition(CgAnimTransition.of("ground", "fall", CgAnimRule.not(CgAnimRule.isTrue(GROUNDED))))
        .transition(CgAnimTransition.automatic("jump", "fall").crossfade(0.15f))
        .transition(CgAnimTransition.of("fall", "land", CgAnimRule.isTrue(GROUNDED)).crossfade(0.05f))
        .transition(CgAnimTransition.automatic("land", "ground"))
        .build();

static CgAnimGraph hero(CgSkeletalMesh mesh) {
    return CgAnimGraph.builder("mymod:hero", mesh.skeleton())
            .variables(SPEED, GROUNDED, JUMPED)
            .entity(SPEED, CgEntityProperties.SPEED)
            .entity(GROUNDED, CgEntityProperties.GROUNDED)
            .entity(JUMPED, CgEntityProperties.JUMPED)
            .output(CgSlotNode.of(CgAnimGraph.DEFAULT_SLOT, CgAnimStateMachineNode.of(LOCOMOTION)))
            .build();
}
```

## What goes wrong

| You see | Because |
|---|---|
| An error naming a node of `anim_graphs/hero.yaml` | an unknown `kind`, a node no other reads, or a name with a typo |
| It never leaves `ground` | a rule's variable is not fed: check the `entity:` block, then `/cgc anim debug` |
| The jump never plays | `jumped` is true for one frame: a transition must test it from the state it leaves |
| Feet slide on the jog-to-sprint blend | the clips' strides differ: mark their feet ([`../guides/sync-groups.md`](../guides/sync-groups.md)) |

## Where next

[Tutorial 3](3-a-punch-with-a-hit-window.md): a punch over the running legs.
