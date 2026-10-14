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
| **Hand-edited config files** | a parser of your own, or Gson's reflection guessing your fields | `CgFormats.YAML` or `JSON` with the same codec; missing keys keep their defaults, and a person's comments survive the mod writing the file |
| **An old save after a change** | a migration written by hand, or saves that stop loading | `CgCodecs.versioned`: each old version's steps run before today's codec reads it |
| **A crash mid-save** | half a file, and a corrupted world folder | written beside the old file, then moved over it in one step |
| **Testing** | launch the game to see whether a save loads | a codec runs in a plain JUnit test, NBT included (`CgTestTags`) |

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
| **Record codec** | A record's codec, declared as its keys and its constructor. | `CgCodecs.record` |
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
    public static final CgCodec<Spell> CODEC = CgCodecs.record(
            CgCodecs.field("name", CgCodecs.STRING, Spell::name),
            CgCodecs.field("cost", CgCodecs.INT, Spell::cost),
            Spell::new);
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
| `CgCodecs.record(...)` | a record | its keys and its constructor, below |
| `CgCodecs.dispatch(...)` | one of several kinds | below |
| `CgCodecs.versioned(n, codec)` | a save that changes shape | §9 |

### A record of your own: `CgCodecs.record`

Most data a mod sends or saves is a record: a spell, a purge request, a machine's stats. Its codec is its keys, each
with a codec and the record's accessor, then its constructor:

```java
public record Spell(String name, int cost, @Nullable String icon) {
    public static final CgCodec<Spell> CODEC = CgCodecs.record(
            CgCodecs.field("name", CgCodecs.STRING, Spell::name),
            CgCodecs.optionalField("cost", CgCodecs.INT, Spell::cost, 0),
            CgCodecs.optionalField("icon", CgCodecs.STRING, Spell::icon, null),
            Spell::new);
}
```

Both directions read that one declaration, so a key written as `"cost"` can never be read back as `"Cost"`. It is the
shape of Mojang's `RecordCodecBuilder`, if you know that, without its `App` and `Kind` types.

**Which kind of key?** Pick by what you want to happen when the key is missing:

| You want | Key | Example |
|---|---|---|
| The value is always there; a save without it is broken | `field(key, codec, get)` | a spell's name |
| The value usually has a default, so most saves need not carry it | `optionalField(key, codec, get, fallback)` | a cost of 0, an icon that may be null |
| You added the key in an update, and saves from before it should load | `optionalField(..., fallback)`: old saves read the fallback | a cooldown added in 1.2 |
| The same, but the key must be written even at its default | `fieldOr(key, codec, get, fallback)` | a content-hashed shape whose bytes must not change |

```java
CgCodecs.field("name", CgCodecs.STRING, Spell::name)                     // missing: throws "Missing required field 'name'"
CgCodecs.optionalField("cost", CgCodecs.INT, Spell::cost, 0)             // 0 is not written; missing reads 0
CgCodecs.fieldOr("exposed", CgCodecs.BOOL, Prop::exposed, true)          // always written; missing reads true
```

**A record inside a record** is a key whose codec is the inner record's:

```java
public record Loadout(Spell primary, List<Spell> spare) {
    public static final CgCodec<Loadout> CODEC = CgCodecs.record(
            CgCodecs.field("primary", Spell.CODEC, Loadout::primary),
            CgCodecs.optionalField("spare", CgCodecs.listOf(Spell.CODEC), Loadout::spare, List.of()),
            Loadout::new);
}
```

**More than eight keys**: group some into a nested record (often they belong together anyway: `audio`, `video`), or
make the class mutable and use `CgCodecs.object` (§4).

- **Keys are written in declaration order**, so a value always encodes to the same bytes.
- **Keys the codec does not declare are ignored**, so a save with a key you since removed still loads.
- **A required value that is null throws when written**, naming the key: declare it `optionalField(..., null)` instead.
- **A key's comment** goes in a YAML file above it: `.comment("Master volume, 0 to 1.")` on the key (§6).
- **Short keys** keep the wire small: a key travels with every send. A file people read can afford long ones.

### Record, object, or by hand?

| Your value | Write |
|---|---|
| A record, or any immutable value with a constructor | `CgCodecs.record` |
| A mutable class other code holds (settings, a handler, a block entity's state), or more than eight fields | `CgCodecs.object` (§4) |
| One of several kinds | `CgCodecs.dispatch` (below) |
| One value as another (a colour as an int) | `CgCodecs.xmap` (below) |
| A key written only sometimes, or one value deciding the rest | by hand (next) |

### By hand: a map built and read

A shape no record states (a key written only sometimes, a value that decides the rest) is built and read field by
field with `CgCodecs.map(ops)` and `CgCodecs.read(ops, in)`:

```java
@Override public <T> T encode(CgDynamicOps<T> ops, Slot s) {
    CgCodecs.MapCodecBuilder<T> out = CgCodecs.map(ops).field("kind", CgCodecs.STRING, s.kind());
    if (s.isTimed()) out.field("ticks", CgCodecs.INT, s.ticks());
    return out.build();
}
@Override public <T> Slot decode(CgDynamicOps<T> ops, T in) {
    CgCodecs.MapCodecReader<T> r = CgCodecs.read(ops, in);
    return new Slot(r.field("kind", CgCodecs.STRING), r.optional("ticks", CgCodecs.INT, 0));
}
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
            .field("best", Stats.CODEC, c -> c.best, (c, v) -> c.best = v)          // a nested object, replaced
            .into("skills", CgCodecs.listOf(CgCodecs.STRING), c -> c.skills)        // a final list
            .into("stats", Stats.CODEC, c -> c.stats)                              // a final object
            .build();
}
```

| Declaration | Use it for | Decoding into a live object |
|---|---|---|
| `field(key, codec, get, set)` | anything with a setter | a new value, decoded and stored through `set`; `get` is only encoded, so it may answer a snapshot or a copy |
| `optional(key, codec, get, set, omitWhen)` | a field usually at its default | as `field`; nothing is written for `omitWhen` |
| `into(key, codec, get)` | a final member the object owns | always updated in place; a codec that cannot (a string, a record) throws, naming the key |
| `extend(Parent.CODEC)` | a subclass | the parent's keys first, flat beside its own |
| `.comment(text)` | the field just declared | written above its key in a YAML file (§6) |

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
- **Comments survive** a rewrite: below.
- **Map keys are text** in JSON and YAML. Bytes are Base64 in JSON and `!!binary` in YAML.
- **A YAML file builds maps, lists and scalars only**: a `!!java.io.File`-style tag is refused, never instantiated.
- **SnakeYAML is relocated inside CrystalGraphics' jar.** Never import `org.yaml` in your mod: in game it is not at
  that name. Use `CgFormats.YAML`.

### A config file people edit, with comments

A config file explains itself when the codec says what each key means. Put `.comment(text)` after a field (or on a
record key); the comment is written above that key in YAML.

```java
public final class ModConfig {
    float volume = 1f;
    int particles = 100;
    final List<String> muted = new ArrayList<>();

    public static final CgObjectCodec<ModConfig> CODEC = CgCodecs.object(ModConfig::new)
            .field("volume", CgCodecs.FLOAT, c -> c.volume, (c, v) -> c.volume = v)
            .comment("Master volume, 0 to 1.")
            .field("particles", CgCodecs.INT, c -> c.particles, (c, v) -> c.particles = v)
            .comment("Particles per effect, in percent.\nLower it on a slow computer.")      // two lines
            .into("muted", CgCodecs.listOf(CgCodecs.STRING), c -> c.muted)
            .comment("Players whose effects you never see.")
            .build();
}

// at startup: load what is there, then write it back, so a new key appears with its comment
Path file = configDir.resolve("mymod.yml");
ModConfig config = CgFormats.YAML.read(file, ModConfig.CODEC, new ModConfig());
CgFormats.YAML.write(file, ModConfig.CODEC, config);
```

The first run writes:

```yaml
# Master volume, 0 to 1.
volume: 1.0
# Particles per effect, in percent.
# Lower it on a slow computer.
particles: 100
# Players whose effects you never see.
muted: []
```

A player then edits it, and writes their own notes:

```yaml
# Master volume, 0 to 1.
volume: 0.4        # quieter at night
# Particles per effect, in percent.
# Lower it on a slow computer.
particles: 50
# my friends spam beams
muted:
  - vegeta
```

The next time the mod writes the file, **everything the player wrote stays**: `quieter at night` beside its value,
`my friends spam beams` above `muted` in place of the mod's comment, the values they chose. What changes:

| When | The file |
|---|---|
| Your update adds a key | it is written with its value and your comment |
| Your update removes a key | it disappears, and so does any comment on it |
| A player wrote a comment on a key | theirs is kept; yours is not added beside it |
| You change a key's comment in an update | files that already have the old text keep it: once written, a comment is the file's |
| The file is not valid YAML | it is replaced; its comments cannot be read, so they go |

- **Comments live in YAML only.** JSON has none; binary files and NBT have none.
- **Nested keys** take their own comments: a record key's `.comment(...)` inside a nested record lands above that key,
  indented.
- **Read before you write**, as above: a mod that writes a fresh `ModConfig` over the file throws the player's values
  away (their comments stay, on keys that still exist).

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
- **NBT exists only inside Minecraft.** In the harness, `toNbt` throws, naming the `crystalgraphics:host_formats`
  slot. A test installs `CgTestTags` for it (§10).

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

Saves outlive the code that wrote them. A player who updates your mod keeps their world, their config and their
character, and the new code has to read what the old code wrote.

### Most changes need nothing

| You | Old saves | Do |
|---|---|---|
| Add a field | lack its key | nothing: give it a default (`optionalField(..., fallback)`, or the object's own default) |
| Remove a field | still have its key | nothing: keys the codec does not declare are ignored |
| Add a kind to a `dispatch` | never name it | nothing |
| Reorder an enum's constants | name them | nothing: enums are saved by name |

### A change old saves cannot be read through: a versioned codec

Renaming a key, changing a value's type or moving it into a section breaks old saves: the new codec looks for
something they do not have. Wrap the codec in `CgCodecs.versioned` and write one **step** per change. Each step turns a
save of one version into the next, and runs before today's codec reads it. It is Minecraft's DataFixerUpper idea, cut
down to what a mod needs.

You do not need it from day one. A save with no version reads as version 1, so add `versioned` the first time you
make a change like this, starting at 2:

```java
// The codec, as it is today. Keep using it for the network: §9 is about saves.
public static final CgCodec<Character> CODEC = ...;

// What is saved. Version 2: "Level" was renamed "level".
public static final CgCodec<Character> SAVED = CgCodecs.versioned(2, CODEC)
        .from(1, tree -> tree.rename("Level", "level"));

CgFormats.write(file, SAVED, character);        // writes "_v": 2 before the codec's keys
Character back = CgFormats.read(file, SAVED);   // an old save runs the step first
```

**Each later change raises the version and adds a step**, never edits one already released: a player may be coming
from any version, and the steps run in order from theirs.

```java
public static final CgCodec<Character> SAVED = CgCodecs.versioned(4, CODEC)
        .from(1, tree -> tree.rename("Level", "level"))                    // 1.0 to 1.1
        .from(2, tree -> tree.move("skills", "abilities.list"))            // 1.1 to 1.2: skills moved into a section
        .from(3, tree -> tree.map("cooldown", v -> v.asInt() * 20));       // 1.2 to 1.3: seconds became ticks
```

### Recipes

**I renamed a key.**

```java
.from(1, tree -> tree.rename("Level", "level"))
```

**I changed a unit or a type**: seconds to ticks, a float to an int, an enum's name to another.

```java
.from(2, tree -> tree.map("cooldown", v -> v.asInt() * 20))
.from(3, tree -> tree.map("volume", v -> (int) Math.round(v.asDouble() * 100)))      // 0.0-1.0 became 0-100
.from(4, tree -> tree.map("element", v -> v.asString().equals("LIGHTNING") ? "THUNDER" : v.asString()))
```

**I moved keys into a section.** Dotted paths name nested maps; the maps on the way are made.

```java
.from(2, tree -> tree.move("volume", "audio.volume").move("subtitles", "audio.subtitles"))
```

**I split one value into two, or joined two.**

```java
.from(3, tree -> {
    CgTreeEdit.Value size = tree.get("size");                 // was one number for both
    if (size != null) tree.set("width", size).set("height", size).remove("size");
})
```

**I changed a list into a map**, or any other structured value: read the old value with its old codec, and make the
new one with `tree.value(codec, value)`.

```java
.from(2, tree -> tree.map("skills", v -> {                    // was ["kamehameha", ...], now {kamehameha: 1, ...}
    Map<String, Integer> levels = new LinkedHashMap<>();
    for (String name : v.as(CgCodecs.listOf(CgCodecs.STRING))) levels.put(name, 1);
    return tree.value(CgCodecs.mapOf(CgCodecs.STRING, CgCodecs.INT), levels);
}))
```

**I added a field whose default depends on another.**

```java
.from(4, tree -> { if (!tree.has("maxMana")) tree.set("maxMana", tree.get("level").asInt() * 10); })
```

| Step call | Does |
|---|---|
| `rename(from, to)`, `move(from, to)` | moves a value; dotted paths; nothing if `from` is missing |
| `remove(path)` | drops a value |
| `set(path, value)` | sets one: a `String`, a `Number`, a `Boolean`, a `Value`, or `tree.value(codec, x)` |
| `map(path, v -> ...)` | replaces a value with what the function answers for it |
| `has(path)`, `get(path)` | reads; `get` answers a `Value` (`asInt`, `asString`, `asDouble`, `asBool`, `as(codec)`) or null |

### What happens with a save from a newer version

A player who downgrades your mod, or copies a world from a friend on a newer one, has a save your code cannot know.
`read` throws a `CgCodecException` naming both versions rather than guessing. **Do not write over that file**: it
is the only copy of their data. Load something else and keep it:

```java
ModData data;
try {
    data = CgFormats.read(file, ModData.SAVED);
} catch (CgCodecException newerOrBroken) {
    LOGGER.error("mymod: {} could not be read ({}); leaving it as it is", file, newerOrBroken.getMessage());
    data = new ModData();
    saveDisabled = true;                         // never write over a save you could not read
}
```

- **Steps run in whatever format the save was read from**: one step serves binary files, JSON, YAML and NBT.
- **A step on a key the save lacks does nothing**, so one step serves saves that never had the key.
- **The version key is `"_v"`**; `CgCodecs.versioned("version", 3, CODEC)` names another.
- **Versioned codecs are for saves.** A value sent over the network is read by the same build that sent it, or by a
  peer your namespace's version keeps out (`CgMessage.version`, `docs/NETWORKING.md` § *Versions*): give the network
  the plain codec, and a save the versioned one.

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
- **NBT in a test**: install `CgTestTags`. `CgFormats.toNbt` and `fromNbt` then work in a plain JUnit test, under
  the game's own rules (a boolean is a byte, a list holds one kind of tag, keys are text), with no Minecraft.

  ```java
  private CgTestTags tags;
  @Before public void tags() { tags = CgTestTags.install(); }
  @After public void untag() { tags.close(); }                        // puts back what was there

  @Test public void aCharacterSavesAsNbt() {
      Object tag = CgFormats.toNbt(Character.CODEC, character);
      assertEquals("{name:\"Goku\",level:9}", CgTestTags.toSnbt(tag));   // what /data get would show
      assertEquals(9, CgFormats.fromNbt(Character.CODEC, tag).level);
  }
  ```

  **SNBT**, the text form `/data get` prints, reads straight into tags, so presets and saves can live in a test as
  text: `CgTestTags.parse("{style:3b,dyed:1b,colors:[I;16711680,255]}")`. Compounds, lists, `[B;...]`, `[I;...]`
  and `[L;...]` arrays, quoted and bare strings, the `b s L f d` suffixes, and `true`/`false` as bytes.

- **A migration test keeps an old save as text** and checks it loads as today's value. Write one when you add a
  step, from a save the old version really wrote:

  ```java
  @Test public void aSaveFromBeforeTheRenameStillLoads() {
      String v1 = "{\"name\":\"Goku\",\"Level\":9}";                  // what 1.0 wrote
      assertEquals(9, CgFormats.JSON.fromText(Character.SAVED, v1).level);
  }
  ```

- A networked object's fields are tested in a `CgNetTestRig` (`docs/NETWORKING.md` § *Testing without Minecraft*).

---

## 11. Questions people ask

### Do I need a versioned codec from the start?

No. A save with no version reads as version 1, so wrap your codec the first time a change breaks old saves, and
start at 2. Adding a field or removing one never needs it.

### When does a version go up?

When old saves cannot be read by the new codec as they are: a rename, a type or unit change, a move. Raise the
number, add one step from the old number, and never change a step once it has shipped.

### My config's comments are gone. Why?

Comments live in YAML: a `.json` or binary file has none. In YAML, a comment is kept when its key is still written;
a key you removed takes its comment with it, and a file that was not valid YAML is replaced whole.

### Why not Mojang's `Codec`?

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

`com.crystalgraphics.serialization` (codecs, records, versions), `.format` (`CgFormats`), `.format.file` (each file
format) and `.format.nbt` (the NBT bridge and `CgTestTags`); the package guide is
`core/src/main/java/com/crystalgraphics/serialization/CLAUDE.md`.
