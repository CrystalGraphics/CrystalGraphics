# Reactions and move locks

**What you will have:** a golem that flinches when hit, slams down when it lands from a height and swings its arm in
time with Minecraft's own swing, with no code. Its landing holds it still until the clip lets go.

## In plain words

A **reaction** is a reflex: when something happens to the entity (it is hit, it lands), play this move, with no
code. A **move lock** holds the entity still while a move plays, so a heavy landing keeps it in place until it stands
up again, the way a stunned boxer cannot walk until they recover.

## The code

```yaml
# assets/mymod/actors/golem.yaml
_v: 1
binds: { entities: [ mymod:golem ] }
prefab: mymod:golem
anim_graph: mymod:golem_locomotion
reactions:
  - { event: hurt, sequence: mymod:golem_flinch }
  - { event: landed, sequence: mymod:golem_land, slot: FullBody,
      when: { variable: entity.land_height, op: ">", value: 3 } }
```

A **reaction** plays a sequence on the actor's body the frame an entity event happens: here a hit plays the flinch,
and a landing from more than three blocks plays the heavy landing on the `FullBody` slot.

In Java:

```java
CgEntityActors.register("mymod:golem", CgEntityActors.plain()).prefab("mymod:golem")
        .graph("mymod:golem_locomotion")
        .react(CgReaction.of(CgEntityProperties.HURT, "mymod:golem_flinch"))
        .react(CgReaction.of(CgEntityProperties.LANDED, "mymod:golem_land", "FullBody",
                CgReaction.when(Map.of("variable", "entity.land_height", "op", ">", "value", 3))));
```

## How a reaction plays

- **`event`** is one of the nine entity events: `jumped`, `landed`, `hurt`, `swing_started`, `use_started`,
  `use_stopped`, `mounted`, `dismounted`, `died`.
- **`sequence`** is a sequence asset ([`../../vfx/sequences.md`](../../vfx/sequences.md)). Every role in it is the
  actor's body, and every animation section plays on `slot`.
- **`slot`** is a montage slot of the body's graph; `DefaultSlot` unless said.
- **`when`** is a graph rule ([`state-machines.md`](state-machines.md) § *Rules*) over the entity's properties by their
  graph names: `entity.grounded`, `mymod:ki#current`.
- **The first reaction that fires plays**, in the order written, and stops one still playing.
- **Each side plays its own** from its own events: the server's carries the entity where the sequence has root motion
  ([`root-motion-moves-the-entity.md`](root-motion-moves-the-entity.md)); a client's draws it.

## Variations

### Holding the entity while a clip plays: `crystalgraphics:move_lock`

A landing that should root the golem until it stands up again holds its movement with a span in the sequence:

```yaml
# assets/mymod/sequences/golem_land.yaml (its tracks)
- { id: t4, kind: notify, binding: { role: caster }, sections: [
    { id: n1, range: [0, 600], kind: crystalgraphics:move_lock, properties: { ai: true, input: true, gravity: false } } ] }
```

| Property | Holds | Default |
|---|---|---|
| `input` | the client's own player's movement keys | held |
| `ai` | a mob's AI, on the server | held |
| `gravity` | gravity, on the server and for a client's own player | let be |

The span holds from its start to its end, both sides. It lets go when the clip plays past it, when the sequence stops,
or when it is sought before it. Two spans holding at once let go only when both have. In code:
[`../../entities/guides/moving-an-entity.md`](../../entities/guides/moving-an-entity.md).

### A clip that follows the game: the sequence evaluator

Minecraft's swing has its own progress, 0 to 1. A sequence evaluator holds a clip at a time the graph sets, so the
swing clip follows the game exactly:

```yaml
variables:
  swing: { type: float }
  swinging: { type: bool }
entity:
  swing: entity.swing
  swinging: entity.swinging
nodes:
  swing_pose: { kind: sequence_evaluator, clip: mymod:golem/swing, time: swing, normalized: true }
```

```java
CgSequenceEvaluatorNode.of(SWING_CLIP).explicitTime(SWING).normalized();     // 0 to 1 across the clip
CgSequenceEvaluatorNode.of(KNEEL).at(0.4f);                                  // one frame, held
```

### Raising an event from gameplay

The server can make an actor react as if the game had raised an event:

```java
CgEntities.of(golem).raiseEvent("hurt");     // the server's actor plays its hurt reaction this tick
```

### A move that is not a reaction

A move your gameplay chooses (an ability, a combo) is played on the server's actor and seen by everyone near:

```java
CgEntityActors.serverActor(golemId).play("mymod:golem_slam");
```

## What goes wrong

- **Fails at load, naming the actor file**: an event that is not one of the nine, a sequence no file declares, a rule
  naming a property no entity has.
- **A reaction plays on the body's slot**: a graph with no such slot plays nothing visible. The humanoid template has
  `DefaultSlot` and `UpperBody`.
- **A clip with no `move_lock` span leaves the entity free**: a landing clip plays while the golem walks on.
- **1.7.10 has no AI or gravity switch, Forge 1.8.9 no gravity one**: those holds do nothing there.

## Where next

- Montages, sections and slots in depth: [`montages-and-slots.md`](montages-and-slots.md).
- Weapons changing the arms: [`held-items-and-item-layers.md`](held-items-and-item-layers.md).
