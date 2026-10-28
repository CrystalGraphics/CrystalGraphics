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
removal: { particles: [ { system: mymod:beam/end } ] }
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

### What fails at load

- a `tag` nobody declared;
- a burst cue with a looping phase, or a looping cue with `burst`;
- an entry missing what it plays (`system`, `sound`, `shake`, `material`, `sequence`);
- a reference to an asset that does not exist.

Each names the cue and the path in it. On a dedicated server systems do not compile: a cue loads there with
`definition()` null and its `writes` bound to nothing.
