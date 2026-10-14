# Serialization — the guide

CrystalGraphics turns your data into bytes, text or Minecraft's tags and back, with one description of it: a
**codec**. The same codec sends a value over the network, saves it to a file of your own, writes it as JSON or YAML
for a person to edit, and stores it inside an entity's NBT, on every version and loader. You never write NBT code,
and you never keep two serializations of one value in step.

This page is written for someone who has never written a serializer. Read sections 1 and 2 first: they give you the
words and a working example. After that, jump to what you need.

## If you have saved data in a mod before

You know the routine. Here is a character's level and skills the usual way, and then here.

**The usual way.** The same fields, written out once per destination, kept in step by hand:

```java
// NBT, for the save
public CompoundTag save() {
    CompoundTag tag = new CompoundTag();
    tag.putInt("Level", level);
    ListTag list = new ListTag();
    for (String s : skills) list.add(StringTag.valueOf(s));
    tag.put("Skills", list);
    return tag;
}
public void load(CompoundTag tag) {
    level = tag.getInt("Level");
    skills.clear();
    ListTag list = tag.getList("Skills", Tag.TAG_STRING);       // the type id, or it reads nothing
    for (int i = 0; i < list.size(); i++) skills.add(list.getString(i));
}
// The packet, for the client: the same fields again, in another API
void encode(FriendlyByteBuf buf) { buf.writeInt(level); buf.writeCollection(skills, FriendlyByteBuf::writeUtf); }
// The config file: Gson, with a third set of names; and then the port: getInt became getIntOr on 1.21.5,
// getAllKeys became keySet, 1.12 spells all of it NBTTagCompound, 1.7.10 again otherwise.
```

**Here.** One declaration, everywhere:

```java
public final class Character {
    int level = 1;
    final List<String> skills = new ArrayList<>();

    public static final CgObjectCodec<Character> CODEC = CgCodecs.object(Character::new)
            .field("Level", CgCodecs.INT, c -> c.level, (c, v) -> c.level = v)
            .into("Skills", CgCodecs.listOf(CgCodecs.STRING), c -> c.skills)
            .build();
}

tag.put("Character", CgFormats.toNbt(Character.CODEC, character));           // inside an entity's NBT
CgFormats.fromNbt(Character.CODEC, tag.get("Character"), character);       // loaded into the live one
CgFormats.YAML.write(file, Character.CODEC, character);                    // a file a person edits
CgMessage<Character> SHOW = CgMessage.toClients("mymod:show", Character.CODEC);   // the wire
```

### What goes away

| The struggle | Usually | Here |
|---|---|---|
| **One value, many serializations** | NBT for saves, a buffer for packets, Gson for configs, each written by hand and kept in step | one codec for all of them |
| **Every port rewrites the NBT code** | `getInt`, `getAllKeys`, `NBTTagCompound`, `func_150296_c`: renamed across versions | your codec never names a tag; each version's spelling is CrystalGraphics' |
| **Keys typed twice** | the save writes `"Level"`, the load reads `"level"`, nothing notices | `CgCodecs.object` declares each field once, for both directions |
| **A reload breaks references** | a new object is made, and every screen holding the old one shows stale data | decode into the live object: everything holding it sees the new values |
| **Hand-edited config files** | a parser of your own, or Gson's reflection guessing your fields | `CgFormats.YAML` or `JSON` with the same codec; missing keys keep their defaults |
| **A crash mid-save** | half a file, and a corrupted world folder | written beside the old file, then moved over it in one step |
| **Testing** | launch the game to see whether a save loads | a codec runs in a plain JUnit test, no Minecraft needed |

## Contents

1. [The words you need](#1-the-words-you-need)
2. [Quick start](#2-quick-start)
3. [Writing a codec](#3-writing-a-codec)
4. [A mutable object: `CgCodecs.object`](#4-a-mutable-object-cgcodecsobject)
5. [Decoding into a live object](#5-decoding-into-a-live-object)
6. [Files: binary, JSON and YAML](#6-files-binary-json-and-yaml)
7. [Minecraft's NBT](#7-minecrafts-nbt)
8. [Formats by hand: `CgDynamicOps`](#8-formats-by-hand-cgdynamicops)
9. [Changing a saved shape](#9-changing-a-saved-shape)
10. [Testing](#10-testing)
11. [Questions people ask](#11-questions-people-ask)

Sending values over the network is `docs/NETWORKING.md`; every message, request and networked object field there
takes a codec from this page.

---

## 1. The words you need

| Word | What it is | Class |
|---|---|---|
| **Codec** | How a value of one Java type is written and read. Says what keys it has and each one's codec; nothing about bytes or files. | `CgCodec`, `CgCodecs` |
| **Format** | What the value becomes: the wire's plain tree, JSON, YAML, NBT. A codec works with every one. | `CgDynamicOps` |
| **Ops** | A format's handful of primitives: make a string, read a number, build a map. A codec talks only to these. | `CgPlainOps`, `CgJsonOps`, `CgFormats.nbt()` |
| **Tree** | A value in a format before it is bytes: maps, lists, strings, numbers. | — |
| **Object codec** | A codec for a mutable class, each field declared once with how to read and set it. | `CgObjectCodec` |
| **Target** | An object that already exists, decoded into in place instead of making a new one. | `decode(ops, in, target)` |
| **File format** | Where a tree is stored: a compressed binary file, a JSON file, a YAML file. | `CgFormats.BINARY`, `JSON`, `YAML` |

How they fit together:

```
          your value ──── CgCodec ────▶ a tree in some format ────▶ bytes, text, or tags
                                         CgPlainOps   (the wire, binary files)
                                         CgJsonOps    (JSON)
                                         plain + YAML (YAML)
                                         CgFormats.nbt() (Minecraft's tags)
```

---

## 2. Quick start

### A record, sent and saved

```java
public record Spell(String name, int cost) {
    public static final CgCodec<Spell> CODEC = new CgCodec<Spell>() {
        @Override public <T> T encode(CgDynamicOps<T> ops, Spell s) {
            return CgCodecs.map(ops).field("name", CgCodecs.STRING, s.name()).field("cost", CgCodecs.INT, s.cost()).build();
        }
        @Override public <T> Spell decode(CgDynamicOps<T> ops, T in) {
            CgCodecs.MapCodecReader<T> r = CgCodecs.read(ops, in);
            return new Spell(r.field("name", CgCodecs.STRING), r.field("cost", CgCodecs.INT));
        }
    };
}

CgFormats.write(file, Spell.CODEC, spell);          // a compressed file of your own
Spell back = CgFormats.read(file, Spell.CODEC);     // null when there is no file yet
```

### A settings object, hand-edited

```java
public final class Settings {
    float volume = 1f;
    boolean subtitles;

    public static final CgObjectCodec<Settings> CODEC = CgCodecs.object(Settings::new)
            .field("volume", CgCodecs.FLOAT, s -> s.volume, (s, v) -> s.volume = v)
            .field("subtitles", CgCodecs.BOOL, s -> s.subtitles, (s, v) -> s.subtitles = v)
            .build();
}

Path file = configDir.resolve("mymod.yml");
Settings settings = CgFormats.YAML.read(file, Settings.CODEC);   // null on first run
if (settings == null) CgFormats.YAML.write(file, Settings.CODEC, settings = new Settings());
```

### Inside an entity's NBT

```java
@Override protected void addAdditionalSaveData(CompoundTag tag) {
    super.addAdditionalSaveData(tag);
    tag.put("Spells", CgFormats.toNbt(SPELLS, this.spells));
}
@Override protected void readAdditionalSaveData(CompoundTag tag) {
    super.readAdditionalSaveData(tag);
    if (tag.contains("Spells")) this.spells = CgFormats.fromNbt(SPELLS, tag.get("Spells"));
}
static final CgCodec<List<Spell>> SPELLS = CgCodecs.listOf(Spell.CODEC);
```

---

## 3. Writing a codec

### The built-ins

| Codec | Type | Note |
|---|---|---|
| `CgCodecs.STRING`, `INT`, `LONG`, `FLOAT`, `DOUBLE`, `BOOL` | the boxed primitives | |
| `CgCodecs.BYTES` | `byte[]` | one tag on the wire and in NBT; Base64 in JSON, `!!binary` in YAML |
| `CgCodecs.UUID` | `UUID` | two longs |
| `CgCodecs.enumOf(Kind.class)` | an enum | by constant name, never ordinal: inserting a constant does not re-point old saves |
| `CgCodecs.listOf(codec)` | `List<A>` | |
| `CgCodecs.mapOf(keyCodec, valueCodec)` | `Map<K, V>` | read back in written order; JSON, YAML and NBT take only text keys |
| `CgCodecs.xmap(base, from, to)` | anything | one codec in terms of another |
| `CgCodecs.dispatch(...)` | one of several kinds | below |

### A record of your own

A record is built and read field by field, with `CgCodecs.map(ops)` and `CgCodecs.read(ops, in)`, as `Spell` above.

- **`field(key, codec, value)`** writes a key; the reader's **`field(key, codec)`** reads it and throws, naming the
  key, when it is missing.
- **`optional(key, codec, value, omitWhen)`** writes nothing when the value equals `omitWhen`; the reader's
  **`optional(key, codec, fallback)`** gives `fallback` for a missing key. A usual default costs nothing then.
- **A record inside a record** is a field with the inner record's codec: `.field("stats", Stats.CODEC, s.stats())`.
- **Short keys** keep the wire small: a key travels with every send. A file people read can afford long ones.

```java
.optional("full", CgCodecs.BOOL, p.full(), false)       // encode: most values send no "full" at all
r.optional("full", CgCodecs.BOOL, false)                // decode
```

### A value in terms of another: `xmap`

```java
static final CgCodec<Color> COLOR = CgCodecs.xmap(CgCodecs.INT, Color::new, Color::getRGB);
static final CgCodec<Path> PATH = CgCodecs.xmap(CgCodecs.STRING, Paths::get, Path::toString);
```

### One of several kinds: `dispatch`

A skill that is a beam, a dash or a buff, each with its own fields. A key names the kind, and each kind's codec
reads the rest (DataFixerUpper's `Codec.dispatch`):

```java
static final CgCodec<Skill> CODEC = CgCodecs.dispatch("kind", CgCodecs.STRING,
        Skill::kind,                       // which kind a value is
        kind -> switch (kind) {            // and the codec for each
            case "beam" -> Beam.CODEC;
            case "dash" -> Dash.CODEC;
            default -> null;               // an unknown kind fails, naming it
        });
```

- Encoded as `{ kind: "beam", value: { ...the beam's own keys... } }`: the value always sits under `"value"`.
- A kind's codec can be looked up from a registry: `kind -> Skills.byId(kind).codec()`.

### Rules

- **Encode and decode must agree on keys and codecs.** A key read that was never written throws on the reading side.
  `CgCodecs.object` (§4) cannot get this wrong: one declaration serves both.
- **A decode never answers null for a failure**: it throws `CgCodecException`, which names what it could not read.
- **Encoding order is the map's**: a codec over a `LinkedHashMap` or a record writes the same bytes every time, which
  a content hash relies on.

---

## 4. A mutable object: `CgCodecs.object`

A class whose instances live on (settings, a handler, a machine's state, a character) declares each field once:
its key, its codec, how to read it and how to set it. That one declaration encodes, decodes into a new object, and
decodes into an object that already exists.

```java
public final class Character {
    String name = "";
    int level = 1;
    Stats best;
    final List<String> skills = new ArrayList<>();
    final Stats stats = new Stats();

    public static final CgObjectCodec<Character> CODEC = CgCodecs.object(Character::new)
            .field("name", CgCodecs.STRING, c -> c.name, (c, v) -> c.name = v)
            .optional("level", CgCodecs.INT, c -> c.level, (c, v) -> c.level = v, 1)   // 1 is not written
            .field("best", Stats.CODEC, c -> c.best, (c, v) -> c.best = v)          // a nested object
            .into("skills", CgCodecs.listOf(CgCodecs.STRING), c -> c.skills)        // a final list
            .into("stats", Stats.CODEC, c -> c.stats)                              // a final object
            .build();
}
```

| Declaration | Use it for | Decoding into a live object |
|---|---|---|
| `field(key, codec, get, set)` | anything with a setter | the held value is updated in place when its codec can (a nested object, a list, a map), otherwise replaced through `set` |
| `optional(key, codec, get, set, omitWhen)` | a field usually at its default | as `field`; nothing is written for `omitWhen` |
| `into(key, codec, get)` | a final member the object owns | always updated in place; a codec that cannot (a string, a record) throws, naming the key |
| `extend(Parent.CODEC)` | a subclass | the parent's keys first, flat beside its own |

- **A key the input lacks leaves the field as it is**: the constructor's default for a new object, the live value
  for a target. A partial file is a patch.
- **A field holding null is not written.**
- **Keys the codec does not declare are ignored**, so an old file with a removed field still loads.
- **An object made elsewhere** (from a registry, or a constructor with arguments) takes the factory-less form,
  `CgCodecs.object(Machine.class)`. Its plain `decode` throws: it has nothing to decode into, only targets.

```java
static final CgObjectCodec<Machine> STATE = CgCodecs.object(Machine.class)
        .field("fuel", CgCodecs.INT, m -> m.fuel, (m, v) -> m.fuel = v)
        .build();

Machine machine = new Machine(level, pos);                 // made however it must be
STATE.decode(CgPlainOps.INSTANCE, saved, machine);         // then filled
```

A subclass:

```java
static final CgObjectCodec<DownState> CODEC = CgCodecs.object(DownState::new)
        .extend(HurtState.CODEC)
        .field("bounce", CgCodecs.FLOAT, s -> s.bounce, (s, v) -> s.bounce = v)
        .build();
```

---

## 5. Decoding into a live object

Every codec has two decodes: one that makes a new value, and one that fills an object you already hold.

```java
Settings fresh = Settings.CODEC.decode(ops, in);          // a new one
Settings same  = Settings.CODEC.decode(ops, in, live);    // live itself, refreshed; answers live
```

Use the second when other code holds the object: a screen showing it, a registry, a field of another object. They
keep their reference and see the new values. A config reload, an entity loading its saved data, a client refreshing
its copy: all of these are decodes into a target.

**What each codec does with a target**, the answer being what now holds the data:

| Codec | With a target |
|---|---|
| `CgCodecs.object(...)` | updates it field by field and answers it |
| `listOf` | refills the same list; element `i` goes through its own codec's in-place decode; extras are dropped, new ones added |
| `mapOf` | keeps the same map: a key already there is updated in place, a new key added, a missing one removed |
| `dispatch` | the same kind: updated in place by that kind's codec. Another kind: a new value |
| primitives, `STRING`, `UUID`, records, enums, `xmap` | a new value: these cannot change, so the caller stores the answer |

So always store what it answers:

```java
holder.title = CgCodecs.STRING.decode(ops, in, holder.title);    // a string: the new one is stored
```

- **A failure part-way can leave the target half-updated**: the fields read before the bad one are in. A file is
  parsed whole first, so a damaged file throws before the target is touched.
- **A null target decodes anew.**
- **A collection target must be mutable**: `List.of(...)` cannot be refilled.
- **A codec you write by hand** decodes anew into a target unless it overrides `decode(ops, in, target)`; if it does,
  it answers the target.

Every way in has the target form: `CgFormats.read(file, codec, target)`, `CgFormat.read(...)`,
`CgTextFormat.fromText(codec, text, target)`, `CgFormats.fromNbt(codec, tag, target)`.

---

## 6. Files: binary, JSON and YAML

| Format | Use it for | What it is |
|---|---|---|
| `CgFormats.BINARY` | your mod's own data: player files, caches, anything a person does not open | the wire's binary format, gzipped. Smallest and fastest |
| `CgFormats.JSON` | data packs, exports, files a tool reads | pretty-printed JSON, through Gson (every Minecraft ships it) |
| `CgFormats.YAML` | config files people edit | block-style YAML, through SnakeYAML (CrystalGraphics carries it) |

```java
CgFormats.write(file, Data.CODEC, data);                  // BINARY, the shorthand
Data data = CgFormats.read(file, Data.CODEC);             // null when there is no file
CgFormats.read(file, Data.CODEC, live);                   // into a live object; no file leaves it as it is

CgFormats.YAML.write(config, Settings.CODEC, settings);
CgFormats.JSON.read(export, Settings.CODEC);
CgFormats.of(file).read(file, Settings.CODEC);            // by name: .json, .yml/.yaml, anything else binary

String text = CgFormats.YAML.toText(Settings.CODEC, settings);    // text, not a file
Settings back = CgFormats.YAML.fromText(Settings.CODEC, text);
```

A YAML file as `Settings` writes it, which a person may then edit, comment and cut down:

```yaml
# mymod.yml
volume: 0.5
muted:
  - vegeta
skills:
  kamehameha: 3
```

- **A write replaces the file whole**: it is written beside the old one and moved over it, so a crash mid-save leaves
  the last save. The folders are made.
- **A read parses the whole file before decoding.** A file that is not its format, or holds what the codec cannot
  read, throws `CgCodecException`.
- **Missing keys keep their defaults**, with an object codec: a hand-written file need hold only what it changes.
- **Comments a person wrote are lost** when the mod writes the file again.
- **Map keys are text** in JSON and YAML. Bytes are Base64 in JSON and `!!binary` in YAML.
- **A YAML file builds maps, lists and scalars only**: a `!!java.io.File`-style tag is refused, never instantiated.
- **SnakeYAML is relocated inside CrystalGraphics' jar.** Never import `org.yaml` in your mod: in game it is not at
  that name. Use `CgFormats.YAML`.

---

## 7. Minecraft's NBT

Minecraft's own containers (an entity's saved data, a block entity's, an item's) take only its tags. `CgFormats` turns
any codec's value into them and back, on every version, so your mod writes no NBT code.

```java
tag.put("character", CgFormats.toNbt(Character.CODEC, character));            // typed as put takes it
Character character = CgFormats.fromNbt(Character.CODEC, tag.get("character"));
CgFormats.fromNbt(Character.CODEC, tag.get("character"), this.character);     // into the live one
```

- **`toNbt` answers the type you assign it to** (`Tag`, `CompoundTag`, `NBTBase`): no cast. Asking for a kind it
  did not make is a `ClassCastException` there.
- A map becomes a compound, a list a list tag, a number the tag of its width, `BYTES` a byte array; **a boolean is a
  byte**, as in Minecraft's own `NbtOps`.
- **NBT's own rules apply**: map keys must encode as text (`mapOf(CgCodecs.STRING, …)`), and a list holds one kind of
  value.
- `/data get` shows your values as real tags, readable and editable.
- **NBT exists only inside Minecraft.** In a test or the harness, `toNbt` throws, naming the
  `crystalgraphics:host_formats` slot. Test the codec through a file or `CgPlainOps` (§10) instead.

Use NBT only where Minecraft owns the container. Your mod's own files are better as `CgFormats.BINARY`: smaller,
faster, and no NBT rules.

How each version spells its tags, and how that is proven: `docs/MINECRAFT_INTEGRATION.md` § *Saving and NBT*.

---

## 8. Formats by hand: `CgDynamicOps`

A codec only ever talks to a `CgDynamicOps<T>`: a format's primitives over its tree type `T`. Use one directly when
something else wants the tree rather than bytes.

| Ops | Tree | For |
|---|---|---|
| `CgPlainOps.INSTANCE` | JDK maps, lists, strings, boxed numbers, `byte[]` | the wire and binary files; tests |
| `CgJsonOps.INSTANCE` | Gson's `JsonElement` | anything that already speaks Gson |
| `CgFormats.nbt()` | Minecraft's tags, as `Object` | a codec read or written into NBT by hand |

```java
JsonElement json = Settings.CODEC.encode(CgJsonOps.INSTANCE, settings);
Settings back = Settings.CODEC.decode(CgJsonOps.INSTANCE, json);
```

- `CgBinaryFormat.encode(tree)` / `decode(bytes)` turn a plain tree into bytes and back, uncompressed: what the wire
  sends.
- `CgContentHash.of(ops, tree)` is a stable hash of a tree: the same value hashes the same on every run.
- A new format is a `CgDynamicOps` of your own: every codec already works with it.

---

## 9. Changing a saved shape

Saves outlive the code that wrote them. With codecs, most changes need no migration:

| Change | What old saves do | Do this |
|---|---|---|
| **Add a field** | lack its key | an object codec keeps the default; a record reads it with `optional(key, codec, fallback)` |
| **Remove a field** | still have its key | nothing: undeclared keys are ignored |
| **Rename a key** | have the old name | read both for a while: `r.has("old") ? r.field("old", c) : r.field("new", c)` |
| **Change a field's type** | have the old shape | a new key for the new type; read the old one once, then stop writing it |
| **Add a kind to a `dispatch`** | never name it | nothing |
| **An enum constant** | name it | by name, so reordering is safe; removing one throws on old saves: map it in `xmap` |

For a value sent over the network, a change an older copy of your mod cannot read raises your namespace's version
(`CgMessage.version`, `docs/NETWORKING.md` § *Versions*). Files have no peer to ask: they are read with today's
codec, which is why the table above keeps old saves readable.

---

## 10. Testing

A codec runs in a plain JUnit test, no Minecraft:

```java
@Test public void aCharacterRoundTrips() {
    Character c = new Character();
    c.level = 9;
    Object tree = Character.CODEC.encode(CgPlainOps.INSTANCE, c);
    assertEquals(9, Character.CODEC.decode(CgPlainOps.INSTANCE, tree).level);
}

@Test public void aConfigFileLoads() throws IOException {
    Path file = folder.newFile("mymod.yml").toPath();
    Files.write(file, "volume: 0.5\n".getBytes(StandardCharsets.UTF_8));
    assertEquals(0.5f, CgFormats.YAML.read(file, Settings.CODEC).volume, 0f);
}
```

- Run a codec through `CgPlainOps`, `CgJsonOps` and a file: three formats, one codec, and a codec that secretly
  depends on one format shows itself.
- NBT needs a running Minecraft (§7); everything else here does not.
- A networked object's fields are tested in a `CgNetTestRig` (`docs/NETWORKING.md` § *Testing without Minecraft*).

---

## 11. Questions people ask

### Why not Mojang's `Codec`?

It is not on every version (1.12 and 1.7.10 have none), it is not loader-blind, and its `DataResult` and
`RecordCodecBuilder` are a lot to learn for "write these keys". `CgCodec` keeps its shape (a codec over
`DynamicOps`, `dispatch`, `xmap`) without the library, so a Mojang codec's design ports across directly.

### Why not Gson or Jackson reflection?

Reflection guesses your fields from your class: a rename breaks old files silently, a field you did not mean to save
is saved, and it cannot reach NBT or the wire. A codec says exactly what is stored, under which key.

### NBT or a file of my own?

NBT only where Minecraft owns the container: an entity's, a block entity's or an item's data. Anything your mod
owns (per-player data, caches, configs) is a file: `BINARY` for data, `YAML` for what people edit.

### Is decoding into an object like Jackson's `readerForUpdating`?

The same idea, from the same lineage: Jackson's `readerForUpdating`, serde's `deserialize_in_place`, Unity's
`JsonUtility.FromJsonOverwrite`. Absent keys stay, as in Jackson and Unity; a held list is refilled in place, as in
serde.

### Where is the code?

`com.crystalgraphics.serialization` (codecs), `.format` (`CgFormats`), `.format.file` (each file format) and
`.format.nbt` (the NBT bridge); the package guide is `core/src/main/java/com/crystalgraphics/serialization/CLAUDE.md`.
