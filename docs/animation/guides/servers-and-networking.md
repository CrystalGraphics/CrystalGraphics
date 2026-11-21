# Servers and networking

**What you will have:** moves that play on the server, where gameplay decides them, and show on every player's
screen in step: a player arriving mid-combo sees it mid-combo, and a player's own combo input feels instant.

## In plain words

In multiplayer, the server decides what happens and every player's game shows it. A move started on the server is
sent to everyone who can see the character, so they all see the same punch at the same moment. **Prediction** lets
your own game show your move at once, without waiting for the server's reply, and take it back if the server says no.

## The code

On an entity's actor, one call does it all:

```java
// the server: the move plays, every client that sees the hero plays it too, in step
CgSequencePlayer combo = CgEntityActors.serverActor(heroId).play("mymod:moves/combo");
combo.nextSection("strike1", "strike2");                    // every viewer's copy runs on into strike2 as well
```

## How it stays in step

- **The server's play is the truth.** It sends its state (time, rate, section, that section's next) only when play
  leaves the course viewers predict: a jump, a seek, a rate change or a relinked section. Never as it plays on.
- **A player arriving later** lands on the server's time and section, laps of a loop included.
- **A viewer drifting 0.1 s**, or leaving the server's section, is put back on the server's time.
- **Stopping** the server's play ends every viewer's copy.

## Variations

### A move the player's own client jumps at once

The player pressed attack again: their own client jumps to the next strike now, under a prediction key, and the
server takes it or puts it back.

```java
// the player's client, on the input
CgPredictionKey key = CgPredictionKey.open();
CgSequenceNet.jump(combo, "strike2", key);                  // shown now; refused, it goes back where it was

// the server, at start-up: which jumps it takes
CgSequenceNet.acceptJumps((player, section) -> !player.section().equals("recover"));
```

The same key groups the input's other predictions: its tags and cues
([`../../entities/guides/the-gameplay-handle.md`](../../entities/guides/the-gameplay-handle.md)).

### A sequence you play yourself

Off an actor, serve it by hand:

```java
// server
CgSequencePlayer shot = sequencer.play(definition, roles);
CgSequenceNet.serve(shot, CgAnchor.onEntity(caster), caster).owner(player).start();

// each client, once: how a sequence it is sent plays here
CgSequenceNet.client((obj, sequence, roles, seed) -> {
    CgSequenceDefinition d = definitions.loaded(sequence);
    return d == null ? null : sequencer.play(d, rolesOf(d, roles));   // null while it loads: asked again next tick
});
```

### On a dedicated server

A server poses characters each tick for their bones, sockets and root motion, and draws nothing:

```java
CgAnimInstance body = CgAnimation.server().add(new CgAnimInstance(mesh).graph(hero(mesh)));
Matrix4f hand = body.socket(body.socket("hand_r"), new Matrix4f());   // this tick's pose, in the mesh's space
```

An actor's server copy is posed this way for you. A server hears a montage's `rpg` events at their tick with no clip
loaded.

## What goes wrong

- **A move played on a client is seen by nobody else.** Play moves on the server.
- **A viewer's copy trails the server** by the trip there; time is counted in the server's ticks so viewers agree.
- **With no `acceptJumps` rule**, the server takes every jump the owner asks for.
- **Tracks that draw** (animation, systems) never bind on a dedicated server; only the clock, sections and `rpg`
  events run there.

## Where next

- Who decides what, and prediction: [`../../entities/concepts/sides-and-authority.md`](../../entities/concepts/sides-and-authority.md).
- Networking in general: [`../../NETWORKING.md`](../../NETWORKING.md).
