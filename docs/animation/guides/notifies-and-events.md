# Notifies and events: what happens on a clip's frames

**What you will have:** a sword swing whose whoosh plays on frame 9, whose slash trail spawns at the hand, whose hit
window opens and closes for your gameplay to deal damage in, and which tells your mod each moment, on the server and
on every client that sees it.

## In plain words

A clip is a timeline, and things should happen at exact moments on it: the whoosh as the sword swings, the spark as
it lands, the instant the hit counts. A **notify** is a sticky note at a moment on the clip saying "play this sound
here". An **event** is a note for your game code, "the hit window opens now", so the damage lands exactly when the fist
is out, not early or late.

## The code

A clip's moments are a **notify sequence**: a sequence that plays on the clip's own time. From Blockbench, they are
the animation's own markers:

```json
// assets/mymod/animations/hero/melee.animation.json: its animation "swing" is the clip mymod:hero/melee/swing
"swing": {
  "animation_length": 0.5,
  "timeline":         { "0.1": "begin=charge;", "0.25": "notify=trail;", "0.4": "end=charge;hit" },
  "particle_effects": { "0.3": { "effect": "slash", "locator": "hand_r" } },
  "sound_effects":    { "0.3": { "effect": "mymod:swing" } }
}
```

| Marker | Plays |
|---|---|
| `notify=<sequence>;` | that sequence from the key |
| `begin=<sequence>;` … `end=<sequence>;` | that sequence looping over the span |
| `particle_effects` `{ effect, locator }` | the system at the caster's socket `locator` |
| `sound_effects` `{ effect }` | the sound, on the caster |
| any other instruction (`hit`) | a gameplay event of that name, for your mod |

A gameplay window written in a move's sequence instead:

```yaml
- { id: t2, kind: event, side: rpg, binding: { role: caster },
    sections: [ { id: h1, range: [6000, 18000], event: mymod:hit_window, args: { damage: 40 } } ] }
```

Your mod hears both the same way:

```java
sequencer.listen(CgSequenceEventListener.Side.RPG, (player, phase, event, args) -> {
    if (event.equals("mymod:hit_window")) {
        hitWindowOpen(player, phase == CgSequenceEventListener.Phase.BEGIN, (Map<?, ?>) args);
    }
    if (event.equals("hit")) dealDamage(player);
});
```

## How they fire

- **`rpg` events are the gameplay's**: a dedicated server hears them too, on the clip's time, with no clip loaded.
  `ours` events are presentation, heard on clients only.
- **A key fires once**, in the frame whose range crosses it, however long the frame. A jump fires nothing it skips.
- **A span begins** as play enters it, a jump landing inside included, and **ends** as play leaves it or stops.
- **A key fires and forgets; a span ends with the clip.** When the clip stops, however it stops, a particle key's
  system, a sound and a `notify=` sequence play out to their own end; a `begin`/`end` span ends there.
- **`args` are the file's**, untouched: what your gameplay expects.

## Variations

### On a montage

An `animation` section playing the clip plays its notifies with it, sought and jumped with it:

```yaml
- { id: s1, range: [0, 120], clip: mymod:hero/melee/swing }
```

### On your own playback, outside any sequence

```java
CgClipNotifies.request("mymod:hero/melee/swing");                                  // at load
CgSequencePlayer notifies = CgClipNotifies.start(sequencer, role, "mymod:hero/melee/swing");   // the clip starts
if (notifies != null) notifies.drive(clipSeconds, loopedSinceLastFrame);         // each frame
if (notifies != null) notifies.stop();                                           // it stops
```

### A notify of your own

A moment only your mod knows how to play (a glow, a puff) is a notify kind, registered once with its properties
declared: [`../../vfx/sequences.md`](../../vfx/sequences.md) § *Notifies of your own*. The engine ships one,
`crystalgraphics:move_lock` ([`reactions-and-locks.md`](reactions-and-locks.md)).

## What goes wrong

- **Fails at load**, naming the file, the animation, the key's time and the id: an id no asset holds, an `end` with no
  `begin` open, a `begin` still open at the clip's end, a `notify=` sequence with no length, a looping system on a
  particle key (put it in a `begin=` … `end=` span).
- **An event track names its side** (`rpg` or `ours`), or it fails at play.
- **A glTF clip's markers** carry only sync markers: [`clips.md`](clips.md).
- **Nothing is sent**: each viewer fires its own playback's notifies. Gameplay that must happen once, on the server,
  listens on the server.

## Where next

- Moves and hit windows over the network: [`servers-and-networking.md`](servers-and-networking.md).
- Events in sequences in full: [`../../vfx/sequences.md`](../../vfx/sequences.md) § *Events*.
