# Debugging: seeing what a character plays

**What you will have:** the live state of any animated entity you look at, in game: its graph, every variable and its
value, its tags, each state machine's state and time in it, the clips playing this frame with their weights, and its
montages.

## The code

In game, look at the entity and type:

```
/cgc anim debug
```

The panel shows top right, over the HUD. Look at another entity to switch to it; the same command hides it.

The same lines, from code, for a log:

```java
StringBuilder text = new StringBuilder();
CgAnimDebug.describe("knight", anim, CgEntityTags.client(entityId), text);
LOGGER.info(text);
```

## What it shows

| Line | Is |
|---|---|
| graph | the graph's id |
| variables | every variable and its value this frame, entity-fed ones included |
| tags | the entity's tags and counts |
| machines | each state machine's state, its time in it, and any transition under way |
| clips | every clip played this frame, its weight and position |
| montages | each playing montage, its section and weight |

## Variations

### A state machine from code

```java
CgAnimStateMachineNode.Machine m = machineNode.in(anim.graph());
String state = m.currentState();
float weight = m.stateWeight("run");
```

### Trace an entity's properties

What the entity feeds is its motion: read it straight ([`../../entities/guides/what-it-is-doing.md`](../../entities/guides/what-it-is-doing.md)).

## What goes wrong

- **"Animation: entity #… has none"**: the entity has neither an actor's body nor a vanilla model the engine poses (`CgVanillaPoses`).
- **A variable that never changes**: it is not bound to the entity (`entity:` block) and no `onUpdate` sets it.
- **It costs while open**: the panel formats ten times a second and allocates as it does. It is a tool; close it
  before measuring.
- **Client only.** A server's actor is not shown; its montages are the ones its clients play.

## Where next

- Why a transition does not fire: [`state-machines.md`](state-machines.md) § *What goes wrong*.
- Why a variable is not fed: [`entity-variables.md`](entity-variables.md).
