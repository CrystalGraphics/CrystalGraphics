# Networking: gameplay tags

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
