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
| `delay` | Seconds after its phase starts that it plays: a projectile's loop after its start, as Kakarot's beam swaps to its loop after `DurationST`. Removed first, it never plays. 0 unless written |
| `replace` | As it starts, what the play has on its `placement.socket` is cleared, particles and all: `SetTemplate` on one component. False unless written |

`delay` and `replace` play only in `application`, `looping` and `removal`: a burst has no play to start them later
or to hold what they replace.

```yaml
application: { particles: [ { system: mymod:beam/start, placement: { socket: root } } ] }
looping:     { particles: [ { system: mymod:beam/body, placement: { socket: root }, delay: 0.3, replace: true } ] }
removal:     { particles: [ { system: mymod:beam/end, placement: { socket: root }, replace: true } ] }   # the body ends as it starts
```

A block may also play **sequences**, each once from its start, as a game's master sequence plays its spark, its
screen flash and its shake together:

```yaml
# assets/mymod/cues/hit_heavy.yaml
_v: 1
tag: mymod:cue.hit.heavy
kind: burst
burst: { sequences: [ { sequence: mymod:hit/heavy } ] }
```

```java
CgCueDefinition.builder("mymod:hit_heavy").tag("mymod:cue.hit.heavy").burst(e -> e.sequence("mymod:hit/heavy"));
```

- **It plays at its placement**: the play's origin ([sequences](sequences.md) § *The engine's sequencer, and where a
  play stands*) rides the target's socket where the entry attaches, else stands fixed where the socket was as the cue
  played. Its unattached spawnables stand there.
- **Its sequences load before the cue first plays**, as its other assets do; one that fails is logged once, by name,
  and the rest of the block plays.
- Its roles are not bound: a sequence a cue plays names none, or `absent: skip` ones.

### Where an effect goes, and who sees it

Every entry (a system, a sound, a shake) has a **placement** and a **spawn condition**: its own, else its block's,
else the cue's `default_placement` and `default_spawn_condition`. An override replaces the default **whole**, as in
Unreal: an entry that writes `placement: { socket: head }` is not attached, even when the default is.

| Unwritten | Means |
|---|---|
| `attach` | `do_not_attach`: spawned at the socket, then left there. Write `attach_to_target` to follow it |
| `rule` | `keep_world` |
| `scale` | 1. `scale: inherit` spawns at the scale of where it spawns: the target's socket (`CgAttachment.scale`, a bigger character's sockets are scaled), 1 at a point. Attached, it follows the socket's scale as it changes, `keep_world` and `snap_to_target` from the scale it spawned at, as Unreal's `SpawnSystemAttached`. What the scale reaches is each module's `owner_scale` ([`particles.md`](particles.md) § *Sized by its owner*) |
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

#### Per-hit values: a play's own writes

A burst has no frames to write in, so the values a play starts with go on its parameters, by name: a hit spark's size,
the attacker's colour. Every system the play spawns, a burst's or a looping cue's in any phase, starts with them, and
they reach every viewer with the play.

```yaml
# assets/mymod/cues/hit_spark.yaml
_v: 1
tag: mymod:cue.hit_spark
kind: burst
burst: { particles: [ { system: mymod:hit_spark } ] }     # its system's user: declares size and tint
writes: { size: the spark's size in blocks, tint: the attacker's ki colour }
```

```java
CgCueParameters hit = new CgCueParameters().at(x, y, z).target(victim).anchor(CgAnchor.on(victim))
        .write("size", 1.5f)                         // a float
        .write("tint", 0xFF66CCFF, 2f);              // a colour, 0xAARRGGBB, its rgb times 2
cues.execute(MYMOD_CUE_HIT_SPARK, hit);
```

- `write(name, x, y, z)` gives a vec3 or position, `write(name, x, y, z, w)` any four floats.
- A name the play leaves out keeps each system's own default; a name the cue does not declare reaches nothing and is
  warned of once. To write only what a tag's cue declares, ask it first:
  `cues.cue(MYMOD_CUE_HIT_SPARK).get().writes().containsKey("tint")`, on either side (`cue(tag)` resolves as a play
  does, through the tag's parents, and answers null for no cue). Ask once and keep the answer: `get()` loads the cue
  now if it has not compiled.
- A looping play starts with them, then its providers (`beam.writes(...)`) take over each frame.
- At most 32 names a play, as a cue declares.

### Where a play stands, which way it faces, how big it is

Unreal's `FindSpawnTransform`: a play stands at the cue's location facing its normal, or at its target's socket
where the location is the origin. A play's scale multiplies every system's spawn scale and every sequence it plays.

```java
CgCueParameters hit = new CgCueParameters().at(x, y, z).target(victim)
        .normal(dx, 0f, dz)                          // its x along the attack, kept level: yaw alone
        .scale(1.6f);                                // every system and sequence it plays, 1.6 times
cues.execute(MYMOD_CUE_HIT_SPARK, hit);
```

- **The normal turns x along it**, yaw then pitch, never roll (`Normal.Rotation()`). A zero normal, the default, turns
  nothing. A placement's `rotation` replaces it.
- **A location wins over the target's socket** for where it stands, as Unreal's does: leave `at` at the origin to stand
  on the socket. An attached play rides its target either way, turned by the normal only under `keep_world`.
- **The scale** multiplies a placement's `scale` (or `inherit`ed one) and reaches a sequence's spawnables and locators
  as their origin's scale. It travels with the play to every viewer.

### What fails at load

- a `tag` nobody declared;
- a burst cue with a looping phase, or a looping cue with `burst`;
- a `lens` anywhere but `looping`: it lasts as long as the cue, and a burst gives it no duration;
- an entry missing what it plays (`system`, `sound`, `shake`, `material`, `sequence`);
- a reference to an asset that does not exist.

Each names the cue and the path in it. On a dedicated server systems do not compile: a cue loads there with
`definition()` null and its `writes` bound to nothing.

## Playing cues

Each world has one `CgCueManager`, the engine's, made on first ask: `CgCueManager.server()` on the server,
`CgCueManager.client()` on a client, `CgCueManager.of(world)` for a world of your own. Gameplay plays cues by tag
through it from its tick; it plays them in the frame's `PLAYS` group. The client's is wired as the game starts: its
effects, the local player for `local_only` and `not_local`, the viewer for in-world shakes and lenses, entities by
network id, and the cues the server sends.

```java
CgCueManager cues = CgCueManager.server();
CgCueParameters at = new CgCueParameters().at(x, y, z).target(victim).seed(hitId).anchor(CgAnchor.on(victim));
cues.execute(MYMOD_CUE_HIT, at);                              // a burst, once
```

A scene of your own (a preview, a test) keeps its own manager and wires it:

```java
CgCueManager cues = world.add(new CgCueManager(CgVfxAssets.instance()));
cues.effects(new CgVfxCueEffects(vfx, cues)).localControl(a -> a.entity() == localPlayerId);
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
- **Your mod's cues load as the game starts** ([`assets.md`](assets.md) § *Shipping them in your mod*), so a play
  finds them ready. One not yet loaded (built in Java and routed by hand) waits: asking for it starts its load. Once it
  is ready, a looping cue starts as old as it would be, and a burst plays only if it is under 0.2 seconds late; later
  than that it is dropped and counted (`cue.late-dropped`), since a hit spark two seconds late is wrong. A burst is
  judged at the time it arrived, so a hitch after it never makes it late.
- **Calls keep their order.** A `remove()` sent before the cue is ready waits behind its `add`; `kill()` before then
  means it never starts.
- **Pooled.** A looping cue's effects are reused once a play ends, reset whole first. `cues.prewarm(cue, n)` grows a
  pool ahead; once warm, playing allocates nothing.
- **A lens is a post volume for the cue's life.** Its `post` is a post block: `bloom`, `vignette`, `chromatic`,
  `exposure.flash`, `white_balance`. It covers the whole screen at full strength, decided once as the cue starts.
  With `in_world` it plays where the viewer stands within `outer` of the cue's point (within `inner` where `outer` is
  not past it; anywhere where neither is set): `cues.viewer(out -> ...)` gives the local player's position, or the
  camera's. Without `in_world` it plays only on the machine that controls the cue's target (`localControl`).
- **`look:` picks the look its systems are graded by**: another kit's namespace, or `none` for the engine's own.
  Unset, each system plays under its own kit's `look.yaml` ([`effects.md`](effects.md) § *A kit's own look*).
- **A shake plays its shake asset** ([`camera.md`](camera.md) § *Shakes*) where its placement puts it, in `space`
  (`camera_local`, unless it says `world`). With `in_world` every viewer within `outer` feels it at `scale` times its
  distance's falloff, `((outer - d) / (outer - inner)) ^ falloff`, decided as it starts and then held; without it,
  only the machine controlling the cue's target feels it, at `scale`. A `looping` block's shake blends out at removal.
  Nothing scales it per play, magnitude included: a harder hit is another cue.

  ```yaml
  # cues/ground_break_heavy.yaml: a light one is the same at scale 0.4, outer 10
  kind: burst
  burst:
    shake: { shake: mymod:ground_break, scale: 1, in_world: true, inner: 2, outer: 18, falloff: 2 }
  ```
- **Seeded.** An entry's `chance` is rolled from the parameters' `seed`, so every viewer plays the same entries.
  The systems a cue spawns roll their own seed per instance, as Unreal's do, so each viewer's differs. A system whose
  layout must match on every viewer fixes it in its file, as Niagara's `bDeterminism` does: the same layout everywhere,
  on every play.

  ```yaml
  # vfx/systems/vanish.yaml
  determinism: { seed: 7 }                       # every instance: its behaviour's seed(), its emitters' rolls
  emitters:
    - id: cards
      emitter:
        properties: { capacity: 12, determinism: { seed: 3 } }   # or one emitter alone
  ```

  In Java: `definition.withRandomSeed(7)`, `CgVfxEmitter.builder("cards").determinism(3)`.
- **On a dedicated server** only `authoritative: true` cues play, with no effects.

A handle is reused once `active()` is false: drop it then.

### Stepping through a hit, for your dev tools

`CgCueInspector.client()` follows the last burst this client played, a server's included: pause it, step it a frame
either way, fire it again, and show where it stands. Bind your own keys and draw its lines in your own HUD.

```java
CgCueInspector inspector = CgCueInspector.client();
inspector.setPaused(!inspector.paused());    // holds the client world's clock, and puts its speed back
inspector.step(1);                           // a frame forward, simulated
inspector.step(-1);                          // a frame back, drawn this frame: the same particles play showed
inspector.refire();                          // the last cue again where it played, with new seeds
for (String line : inspector.lines(new CgCueInspector.Keys("N/B", "R", "P", ", .", "X"))) hud.draw(line);
```

- Frames run from 0 to the hit's duration less a frame and round again, either way; every step back keeps the hit's
  seeds, and only a fire draws new ones.
- Call `step` once per key repeat: steps forward queue, one simulated a frame.
- It steps a burst's sequences and the systems its `particles` spawn. A sequence's length is known as it starts, a
  system's only once it completes: until then `duration()` is NaN and the age line reads `0.200 / ? s`, steps forward
  simulate on, and a step back from 0 stays at 0. Once it has played out it wraps as a sequence does.
- It follows bursts alone, never a looping cue, and only on a client.
- A `CgCueEffects` of your own hands each system it spawns for a burst to `cues.inspector().spawned(part)`, a
  `CgCueInspector.Part` that can play it again at an age with its seed; `CgVfxCueEffects` does.

### Over the network

A server's `CgCueManager` sends every play to the players who see it, and each player's own manager plays what it is
sent, so gameplay calls the server's and nothing else. **A server's play needs an anchor**, and one without throws, naming its cue:

```java
CgCuePlaying beam = cues.add(MYMOD_CUE_BEAM, new CgCueParameters().at(x, y, z).anchor(CgAnchor.on(caster)));
beam.field("level", 3);                                     // one of the cue's own fields, to every viewer
```

```java
// each client: the engine's manager already plays what the server sends
CgCueManager.client().onNetworked(play -> play.writes("BeamLength", ...));   // a cue that is not authoritative: computed here
```

- **Who sees it** is the anchor's: the players tracking what it is on, or everyone within the cue's `range` of a point
  (64 blocks unwritten). `CgAnchor.on(entity)` for an actor, `CgAnchor.at(level, x, y, z)` for a place; a client's
  `predict` needs one too, since the server is asked through it.
- **A burst** reaches whoever sees it as it plays, and its systems start fresh however long the trip took, as GAS
  plays one: a 0.2 s spark shows whole. One that arrives past the one-shot window plays nothing. A player arriving
  later never sees it. **A looping cue** reaches a player arriving later too, started at its age, with its sequence
  sought there.
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
- A cue sends at most 32 `writes`, 8 number or bool `fields` and 4 string ones.
