# Montages and slots: a move over the graph

**What you will have:** a punch that plays over whatever the character is doing, chains into a second strike when the
player presses again in time, opens a hit window your gameplay hears, blends out on its own, and moves the character by
its clip's root motion.

## In plain words

Most of what a character does runs on its own: walking, idling. But sometimes gameplay says "punch now". A
**montage** is that one-off move played on top, like an actor stepping in front of the scenery for a line, then
stepping back. A **slot** is the place in the graph where such moves may appear: the whole body, or just the upper body.
**Sections** are named chapters of a move (wind-up, strike, recover) that play can jump between, which is how combos
work.

A **slot** is a place in the graph where moves play over its pose. A **montage** is a sequence played on a character:
its `animation` sections play on that character's slot as one move.

## The code

The move, `assets/mymod/sequences/moves/punch.yaml`:

```yaml
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
      - { id: s1, range: [0, 24000], clip: mymod:hero/strike1, ease: { in: 6000 } }       # blend in: 0.1 s
      - { id: s2, range: [24000, 48000], clip: mymod:hero/recover1 }
      - { id: s3, range: [48000, 72000], clip: mymod:hero/strike2 }
      - { id: s4, range: [72000, 96000], clip: mymod:hero/recover2, ease: { out: 12000 } } # blend out: 0.2 s
  - { id: t2, kind: event, side: rpg, binding: { role: caster },
      sections: [ { id: h1, range: [6000, 18000], event: mymod:hit_window } ] }
```

Playing it on an entity's actor, from the server, seen by everyone near:

```java
CgSequencePlayer punch = CgEntityActors.serverActor(heroId).play("mymod:moves/punch");
// the player pressed attack again inside the window:
punch.jumpToSection("strike2");
```

Playing it on any character you hold:

```java
CgSequencePlayer punch = sequencer.play(PUNCH, Map.of("caster", hero));
punch.nextSection("strike1", "strike2");       // strike1 runs on into strike2 at its end
punch.stop();                                  // interrupted: blends out over 0.2 s
```

The character's graph needs a slot for it to play on: `CgSlotNode.of(CgAnimGraph.DEFAULT_SLOT, locomotion)`, or
`{ kind: slot, source: locomotion }` in a file.

## How a montage plays

- **It blends in** as it starts, and **out by itself** at its end or when stopped.
- **A montage played stops the other montage of its slot group**, blending it out over its own blend in.
- **A branch is no new play**: `jumpToSection` and `nextSection` keep the montage at its weight.
- **Its end** is the end of a section that hands play to none, or of the sequence. A looping section never blends out
  until gameplay relinks it.
- **Gameplay moments are `event` sections** of side `rpg` (the hit window above), heard as every sequence's events are
  ([`notifies-and-events.md`](notifies-and-events.md)).

| Key, on the character's sections | Does |
|---|---|
| `ease.in` on its first, `ease.out` on its last | its blend in and out, linear unless `in_fn` / `out_fn` name a curve |
| `blend_out_trigger_time` | ticks before its end it starts blending out; unset, its blend-out time |
| `auto_blend_out: false` | it holds its last pose at its end until stopped |
| `slot_group` | its group, `DefaultGroup` unless named |
| `root_motion` | whether its clips' root motion moves the character; unset, on where any clip has it |
| `sync_group` | the graph's sync group it leads while it blends out, so a run under it picks up where it leaves the feet |

## Variations

### Hearing a montage end

```java
hero.anim().montages().listen(new CgMontages.Listener() {
    @Override public void ended(CgMontageInstance m, boolean interrupted) {
        if (!interrupted) comboWindowClosed();
    }
});
```

On an actor's play, `punch.onFinished(() -> ...)` hears the sequence's end.

### Upper body only

A slot under a layered blend plays only over the bones it masks: a reload while the legs keep running.

```java
CgLayeredBlendNode.of(locomotion)
        .layer(CgSlotNode.of("UpperBody", locomotion), CgBlendMask.branch("spine_01", 0));
```

Play a montage there by naming the slot on its sections, or on a reaction (`slot: UpperBody`).

### Slots by hand, frame by frame

```java
CgAnimSlot slot = anim.slot("DefaultSlot");      // null where the graph has none
slot.play(roar, 0.4f, 1f);                       // this frame: roar at 0.4 s over the graph
slot.play(roar, 0.4f, 0.25f);                    // a quarter roar over three quarters of the graph
anim.slot("FacialSlot").stop(1f);                // its entries blended out
```

A slot entry lasts one frame: set it every frame it should hold, in `onUpdate`. Past a total weight of 1 the entries
are normalised and the source is gone.

### Root motion

One montage moves the character at a time: a root-motion montage played stops the one moving it. On an actor, the
server's montage carries the entity ([`root-motion-moves-the-entity.md`](root-motion-moves-the-entity.md)). For a
character of your own, take the step after the frame's pose:

```java
Matrix4f step = new Matrix4f();
if (hero.anim().montages().consumeRootMotion(1f, step)) mover.move(step);
```

Modes and extraction: [`root-motion.md`](root-motion.md).

## What goes wrong

- **A slot the graph lacks plays nothing.** A montage names its slot on its sections; `DefaultSlot` where it names none.
- **A slot entry is sampled over the rest pose**, as a montage's track is: limit a slot to the bones its clips key
  with a layered blend, or the rest pose shows on the others.
- **Two montages of one slot group** never play together: the second stops the first.
- **Over the network**, play moves on the server: a client's own `sequencer.play` is seen by nobody else
  ([`servers-and-networking.md`](servers-and-networking.md)).

## Where next

- Moves fired by events with no code: [`reactions-and-locks.md`](reactions-and-locks.md).
- Hit windows, sounds and effects on frames: [`notifies-and-events.md`](notifies-and-events.md).
- Sequences in full: [`../../vfx/sequences.md`](../../vfx/sequences.md).
