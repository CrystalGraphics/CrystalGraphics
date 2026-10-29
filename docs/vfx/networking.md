# Networking: gameplay tags and cues

A gameplay tag is a hierarchical name, `mymod:cue.beam.fire`, that gameplay and effects use to find each other without
sharing a class. A tag matches itself and its ancestors: `mymod:cue.beam.fire` matches `mymod:cue.beam` and
`mymod:cue`, never the reverse. Tags follow Unreal's gameplay tags.

## Declare tags

In code, at init:

```java
CgTagRegistry tags = CgTagRegistry.global();
CgGameplayTag fire = tags.register("mymod:cue.beam.fire");   // also makes mymod:cue.beam and mymod:cue
```

Or in `assets/mymod/tags.yaml`, which the registry reads with the namespace it belongs to:

```yaml
_v: 1
tags: [ mymod:cue.beam.fire, mymod:state.charging ]
```

The asset registry declares it when it scans the namespace, before any asset of it compiles. By hand:

```java
tags.registerFile("mymod", yamlText, "assets/mymod/tags.yaml");
```

A tag outside the file's namespace, a malformed name or an unknown key fails the file by name; nothing in it is
declared. A file that names a tag looks it up with `tags.require("mymod:cue.beam.fire")`, which fails for one nobody
declared.

## Test and combine tags

```java
CgGameplayTag beam = tags.require("mymod:cue.beam");
fire.matches(beam);                                   // true
beam.matches(fire);                                   // false

CgTagContainer owned = new CgTagContainer().add(fire).add(charging);
owned.has(beam);                                      // true: beam is an ancestor of something added
owned.hasExact(beam);                                 // false: only what was added counts
owned.hasAny(wanted);                                 // wanted: another container; false when it is empty
owned.hasAll(wanted);                                 // true when it is empty
```

A container is bit sets: each test is one AND per 64 tags. It holds tags of one registry.

## Send a tag

A tag's `index()` is its registration order in this process, which differs between mod sets, so it is never sent. The
server holds a table of every tag it has, sorted by name, as a networked object every player sees; each client maps
the server's tags onto its own by name.

```java
// server
out.writeVarInt(CgTagNet.server().toWire(tag));

// client
CgGameplayTag tag = CgTagNet.fromServer().fromWire(in.readVarInt());   // null: this client lacks that tag
```

- A client reading an index before the table arrives gets an exception, never a wrong tag.
- A tag registered after the table was published is sent from the next server tick; writing it earlier throws.
- A client missing a tag the server has logs it once; its index reads as `null`.
- `CgTagLink.bits()` is the width that holds every index and `invalid()`, the index meaning "no tag".

## Cues: what a gameplay moment plays

A cue is what plays when gameplay says *this happened*: a hit, a charge, a beam. Gameplay names it by its tag and
never by the effects, so a resource pack can restyle a move without touching its code. Cues follow Unreal's gameplay
cue notifies: a **burst** plays once, and a **looping** cue plays `application` when it starts, `looping` while it lasts,
`recurring` each time it recurs and `removal` when it ends.

```yaml
# assets/mymod/cues/hit.yaml: a burst
_v: 1
tag: mymod:cue.hit
kind: burst
burst:
  particles: [ { system: mymod:hit/sparks } ]
  sounds: [ { sound: mymod:hit } ]
  shake: { shake: mymod:small, in_world: true, inner: 2, outer: 12 }
```

```yaml
# assets/mymod/cues/beam.yaml: a looping cue, with events and the values gameplay writes each frame
_v: 1
tag: mymod:cue.beam
kind: looping
default_placement: { socket: hands, attach: attach_to_target, rule: keep_relative }
application: { particles: [ { system: mymod:beam/start } ] }
looping:
  particles: [ { system: mymod:beam/body } ]
  sounds: [ { sound: mymod:beam_loop, fade_out: 0.3 } ]
  lens: { post: { vignette: 0.6, chromatic: 2 }, in_world: true, inner: 8, outer: 24 }
removal: { particles: [ { system: mymod:beam/end, time_dilation: 2 } ] }   # collapses twice as fast
events:
  - { id: e1, event: hit_world, particles: [ { system: mymod:beam/hit } ] }
writes:
  BeamLength: the head's distance from the root
```

The same beam from Java. The builder writes exactly the file above, so either form loads the same cue:

```java
CgCueDefinition.Builder beam = CgCueDefinition.builder("mymod:beam").tag("mymod:cue.beam").looping()
        .defaultPlacement(CgCueDefinition.Placement.attached("hands"))
        .application(e -> e.particles("mymod:beam/start"))
        .looping(e -> e.particles("mymod:beam/body").sound("mymod:beam_loop"))
        .removal(e -> e.particles("mymod:beam/end"))
        .event("e1", "hit_world", e -> e.particles("mymod:beam/hit"))
        .writes("BeamLength", "the head's distance from the root");
String yaml = CgAssetYaml.write(beam.tree(), CgGameplayKinds.CUE);
```

Reading a loaded cue:

```java
CgCueDefinition cue = CgVfxAssets.<CgCueDefinition>ref(CgGameplayKinds.CUE, "mymod:beam").get();
for (CgCueDefinition.Particles p : cue.looping().particles()) {
    p.definition();                 // the compiled system, on a client
    p.where().placement().socket(); // "hands", from the default
}
cue.writes().get("BeamLength").slots();   // every played system's BeamLength user parameter
```

A particle entry's own clock, from its Unreal component:

| Field | Means |
|---|---|
| `time_dilation` | `CustomTimeDilation`: the system ages at this multiple of its clock, its particles too. 1 unless written |
| `warmup` | `WarmupTime`, in seconds: it is simulated that far before it is first shown (`CgVfxEffect.startAt`). 0 unless written |

### Where an effect goes, and who sees it

Every entry (a system, a sound, a shake) has a **placement** and a **spawn condition**: its own, else its block's,
else the cue's `default_placement` and `default_spawn_condition`. An override replaces the default **whole**, as in
Unreal: an entry that writes `placement: { socket: head }` is not attached, even when the default is.

| Unwritten | Means |
|---|---|
| `attach` | `do_not_attach`: spawned at the socket, then left there. Write `attach_to_target` to follow it |
| `rule` | `keep_world` |
| `policy` | `always`: every viewer. `local_only` is the controlling player alone, `not_local` everyone else |
| `chance` | 1 |
| a sound's `fade_out`, `fade_volume` | 0.5 seconds, to silence |
| a shake's `scale` | 1 |

### Writes

`writes` names the values gameplay sets each frame while the cue plays, a beam's length for one. Each name reaches the
user parameter of that name in **every** system the cue plays (`user:` in the system's file). A system without it
keeps its own default; a name no played system has is reported once when the cue loads, and listed in
`cue.unbound()`.

A name may also say how an authoritative cue sends it to the other players:

```yaml
writes:
  BeamLength: { meaning: the head's distance from the root, replication: goal }   # each viewer springs toward it
  Glow: how bright                                                               # interpolated
```

| `replication` | For | Each viewer |
|---|---|---|
| `interpolate` (unwritten) | smooth values: a radius, a brightness, a colour | blends between the last two it received |
| `goal` | a target the effect moves to: a beam's end | moves toward it on a critically damped spring, `rate` its ω in 1/s (20 unwritten) |
| `snap` | discrete state: a phase, a count | takes it as it arrives |

A beam's length as `goal` never slides back through an obstacle that stops it, as an interpolated one would.

### What fails at load

- a `tag` nobody declared;
- a burst cue with a looping phase, or a looping cue with `burst`;
- a `lens` anywhere but `looping`: it lasts as long as the cue, and a burst gives it no duration;
- an entry missing what it plays (`system`, `sound`, `shake`, `material`, `sequence`);
- a reference to an asset that does not exist.

Each names the cue and the path in it. On a dedicated server systems do not compile: a cue loads there with
`definition()` null and its `writes` bound to nothing.

## Playing cues

A world's `CgCueManager` plays cues by tag. Gameplay calls it from its tick; it plays in the frame's `PLAYS` group.

```java
CgCueManager cues = world.add(new CgCueManager(CgVfxAssets.instance()));
cues.effects(new CgVfxCueEffects(vfx, cues));                 // a client; a dedicated server keeps none
cues.localControl(a -> a.entity() == localPlayerId);          // what local_only and not_local test

CgCueParameters at = new CgCueParameters().at(x, y, z).target(victim).seed(hitId);
cues.execute(MYMOD_CUE_HIT, at);                              // a burst, once
```

A looping cue returns its play, which gameplay feeds and ends:

```java
CgCuePlaying beam = cues.add(MYMOD_CUE_BEAM, at);
beam.writes("BeamLength", (time, out) -> out[0] = projectile.lengthAt(time));  // pulled each frame
beam.event("hit_world", hitAt);                               // the cue's event bursts
beam.recur(at);                                               // its recurring block
beam.remove();                                                // removal plays; it lingers until its effects end
```

- **By tag, falling back to parents.** `mymod:cue.hit.crit` with no cue of its own plays `mymod:cue.hit`'s. A cue that
  does not handle the call (a burst sent `add`) passes it up too. A tag nothing answers is dropped and counted
  (`cue.unrouted`).
- **Routes come from the files.** The asset registry reads each cue's `tag` as it scans, without loading the cue. A cue
  built in Java is routed by hand: `cues.route(tag, CgVfxAssets.ref(CgGameplayKinds.CUE, "mymod:hit"))`.
- **A cue not yet loaded waits.** Asking for it starts its load. Once it is ready, a looping cue starts as old as it
  would be, and a burst plays only if it is under 0.2 seconds late; later than that it is dropped and counted
  (`cue.late-dropped`), since a hit spark two seconds late is wrong.
- **Calls keep their order.** A `remove()` sent before the cue is ready waits behind its `add`; `kill()` before then
  means it never starts.
- **Pooled.** A looping cue's effects are reused once a play ends, reset whole first. `cues.prewarm(cue, n)` grows a
  pool ahead; once warm, playing allocates nothing.
- **A lens is a post volume for the cue's life.** Its `post` is a post block: `bloom`, `vignette`, `chromatic`,
  `exposure.flash`, `white_balance`. It covers the whole screen at full strength, decided once as the cue starts.
  With `in_world` it plays where the viewer stands within `outer` of the cue's point (within `inner` where `outer` is
  not past it; anywhere where neither is set): `cues.viewer(out -> ...)` gives the local player's position, or the
  camera's. Without `in_world` it plays only on the machine that controls the cue's target (`localControl`).
- **Seeded.** An entry's `chance` is rolled from the parameters' `seed`, so every viewer plays the same entries.
- **On a dedicated server** only `authoritative: true` cues play, with no effects.

A handle is reused once `active()` is false: drop it then.

### Over the network

A server's `CgCueManager` sends every play to the players who see it. Each player's own manager plays what it is
sent, so gameplay calls the server's and nothing else:

```java
CgCuePlaying beam = cues.add(MYMOD_CUE_BEAM, new CgCueParameters().at(x, y, z).anchor(CgAnchor.on(caster)));
beam.field("level", 3);                                     // one of the cue's own fields, to every viewer
```

```java
// each client, once its world's manager exists
CgCueNet.client(obj -> cues);
cues.entities(id -> attachmentOf(id));                      // the instigator and target the server names
cues.onNetworked(play -> play.writes("BeamLength", ...));   // a cue that is not authoritative: computed here
```

- **Who sees it** is the anchor's: the players tracking what it is on, or everyone within the cue's `range` of a point
  (64 blocks unwritten). A play without an anchor is not sent.
- **A burst** reaches whoever sees it as it plays; a player arriving later never sees it. **A looping cue** reaches a
  player arriving later too, started at its age, with its sequence sought there.
- **An authoritative cue's `writes`** are the server's: computed there and sent each tick in their `replication`
  mode. Any other cue's are each player's own.
- **A cue's `sequence`** starts with it through `cues.sequences((cue, sequence, at) -> ...)`, where the host binds the
  sequence's roles; without it, the sequence plays nowhere. The server's jumps and section links reach every viewer
  (below).

### A sequence over the network

A sequence that plays on the server is shown on every viewer from its state: its time, rate, montage section and
that section's next. The state is sent only when play leaves the course it predicts, a jump, a seek, a rate or a
relinked section, never as it plays on. A cue's sequence is served for you. One played alone is served by hand:

```java
// server
CgSequencePlayer shot = sequencer.play(definition, roles);
CgSequenceNet.serve(shot, CgAnchor.onEntity(caster), caster).owner(player).start();
shot.nextSection("charge", "fire");                          // every viewer's copy runs out into fire too

// each client, once: how a sequence it is sent plays here
CgSequenceNet.client((obj, sequence, roles, seed) -> {
    CgSequenceDefinition d = definitions.loaded(sequence);
    return d == null ? null : sequencer.play(d, rolesOf(d, roles));   // null while it loads: asked again next tick
});
```

The owner's client jumps at once, under the input's prediction key, and the server says whether it takes it:

```java
// the owner's client, on an input
CgPredictionKey key = CgPredictionKey.open();
CgSequenceNet.jump(shot, "fire", key);                       // shown now; refused, it goes back where it was
CgCuePlaying beam = cues.predict(MYMOD_CUE_BEAM, at, key);   // the same input's cue, kept or undone with it

// server, at init: which jumps it takes (every one of the owner's, without a rule)
CgSequenceNet.acceptJumps((player, section) -> !player.section().equals("recover"));
```

- **A player arriving later** lands on the server's time and section, laps of a loop included.
- **A viewer** is put back on the server's time when it drifts 0.1 s or leaves the server's section, as Unreal
  corrects a simulated montage. **The owner** keeps its own jumps and is never pulled back by the trip's delay.
- **Stopping** the server's play ends every viewer's copy.
- Time is in the server's game ticks, so it agrees across viewers. A viewer's copy trails the server by the trip there.
- A cue sends at most 16 `writes`, 8 number or bool `fields` and 4 string ones.
