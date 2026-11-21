# State machines: idle, run, fall, land

**What you will have:** a character that moves between states (idle, run, fall, land) when rules on its variables
hold, crossfading as it goes, with the landing played once and then back to idle on its own.

## In plain words

Think of what a character is doing as rooms: *standing*, *running*, *falling*, *landing*. It is always in exactly
one room. Doors lead between rooms, and each door has a rule written on it ("open when speed is above 0.1"). Every
frame the character checks the doors of its room and walks through the first one whose rule is true. That is a
**state machine**: the rooms are **states**, the doors are **transitions**.

A **state machine** plays one state at a time. States are poses (any node, another machine included); a **conduit** is
a branch point with a rule of its own.

## The code

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

The same as a file: machines sit beside the nodes, by name, their states' poses naming nodes.

```yaml
_v: 1
variables:
  speed: { type: float }
  grounded: { type: bool, default: true }
output: body
nodes:
  body: { kind: slot, source: loco }
  loco: { kind: state_machine, machine: locomotion }
  idle: { kind: sequence_player, clip: mymod:hero/idle, loop: true }
  run:  { kind: sequence_player, clip: mymod:hero/run, loop: true }
  fall: { kind: sequence_player, clip: mymod:hero/fall, loop: true }
  land: { kind: sequence_player, clip: mymod:hero/land }
machines:
  locomotion:
    states:
      idle: { pose: idle }
      run:  { pose: run }
      fall: { pose: fall }
      land: { pose: land, always_reset_on_entry: true }
    transitions:
      - { from: idle, to: run, rule: { variable: speed, op: '>', value: 0.1 } }
      - { from: run, to: idle, rule: { variable: speed, op: '<=', value: 0.1 }, crossfade: 0.3 }
      - { from: idle, to: fall, rule: { variable: grounded, op: '==', value: false }, priority: 0 }
      - { from: run, to: fall, rule: { variable: grounded, op: '==', value: false }, priority: 0 }
      - { from: fall, to: land, rule: { variable: grounded, op: '==', value: true }, crossfade: 0.05 }
      - { from: land, to: idle, automatic: true, crossfade: 0.15 }
```

## Rules

| Java | File | Holds when |
|---|---|---|
| `CgAnimRule.variable(v, op, value)`, `isTrue(bool)` | `{ variable, op, value }` | a variable compares so (`<`, `<=`, `>`, `>=`, `==`, `!=`) |
| `timeRemaining(op, s)`, `timeRemainingFraction(op, f)` | `{ time_remaining: <op>, value }`, `{ time_remaining_fraction: <op>, value }` | the state's clip has so long left: its highest weighted clip |
| `stateTime(op, s)` | `{ state_time: <op>, value }` | the machine has been in the state so long |
| `event(name)` | `{ event }` | the game requested the event: `anim.requestTransitionEvent("jump", 0.25)` |
| `all(...)`, `any(...)`, `not(r)`, `always()` | `all`, `any`, `not` | combinations; `always()` is an empty `all` |

## How it moves between states

- **Which door first**: a state's exits are checked by `priority`, lowest number first (1 unless set), then in the
  order you wrote them. The first whose rule is true is taken. In one frame the machine may walk through up to three
  doors in a row (`maxTransitionsPerFrame`).
- **Starting a state over**: a state starts its clip from the beginning when it is entered while not showing at all,
  or every time with `alwaysResetOnEntry()` (a landing should).
- **`automatic`**: the door opens by itself as the state's clip nears its end, `triggerTime` before it (the crossfade's
  length unless set).
- **The very first frame** jumps straight to wherever the doors lead, with no crossfade, so a character spawned in
  mid-air starts falling at once.
- **`minReentry(s)`** keeps a state from being entered again within `s` seconds: no flicker between two states.
- **Events**: `anim.requestTransitionEvent("jump", 0.25)` leaves a note for 0.25 s; the first door whose rule is
  `{ event: jump }` takes it and uses it up. `requestTransitionEvent(name, ttl, true, Replace.APPEND)` lets every door
  take it once.

How a transition blends, and inertialization: [`transitions-and-blending.md`](transitions-and-blending.md).

## Variations

### A conduit

A state with no pose and an entry rule: one place to branch from.

```yaml
states:
  moving: { entry_rule: { variable: grounded, op: '==', value: true } }   # a conduit
transitions:
  - { from: idle, to: moving, rule: { variable: speed, op: '>', value: 0.1 } }
  - { from: moving, to: run, rule: { all: [] }, crossfade: 0.25, blend: cubic_in_out }
```

### Fed by an entity

On an actor, rules read the variables its entity feeds: `speed` bound to `entity.speed`, `stunned` to a tag
([`entity-variables.md`](entity-variables.md)). The rule still names the graph's own variable.

### Reading a machine

For a tool or a debug view, `machineNode.in(anim.graph())` reads a machine's `currentState()`, `elapsed()`,
`stateWeight(name)` and `activeTransitions()`. In game, `/cgc anim debug` shows them ([`debugging.md`](debugging.md)).

### A machine's own settings

In a file: `initial` (its first state), `max_transitions_per_frame` (3), `skip_first_update_transition` (true),
`reinitialize_on_becoming_relevant` (true).

## What goes wrong

- **A rule naming an undeclared variable** fails at build or load, naming it.
- **A transition that never fires**: its rule compares against the wrong type (a bool against a number), or a
  higher-priority exit always wins first.
- **A landing that starts halfway through**: it lacks `alwaysResetOnEntry()`, and was entered while it still showed.
- **Blend profiles, custom curves and custom transition graphs** fail at load: not supported.

## Where next

- Crossfades, curves and inertialization: [`transitions-and-blending.md`](transitions-and-blending.md).
- Speed into a blend rather than a state: [`blend-spaces.md`](blend-spaces.md).
