# NBT — the guide

CrystalGraphics gives you Minecraft's NBT as plain Java, with one API on every version and loader. You build, read,
print and edit tags with no game running, write your codecs into them, store them as binary or text files, and copy
them in and out of the game's own tags. Your code never names `CompoundTag`, `NBTTagCompound` or a version's spelling
of either.

This page is written for someone who may never have touched NBT. Read sections 1 and 2 first: they give you the words
and a working example. After that, jump to what you need. Codecs, the thing that turns your own classes into tags, are
`docs/SERIALIZATION.md`; this page is the tags themselves.

## If you have worked with NBT before

You know the routine. Here is a character's data in an entity's save, the usual way, and then here.

**The usual way.** Every call is a version's spelling, and a wrong kind reads as zero:

```java
CompoundTag data = new CompoundTag();
data.putString("name", name);
data.putInt("level", level);
ListTag spells = new ListTag();
for (String s : known) spells.add(StringTag.valueOf(s));
data.put("spells", spells);
// reading: the element type id, or the list is empty
ListTag back = data.getList("spells", Tag.TAG_STRING);
// 1.21.5 renamed every getter, 1.12 calls all of it NBTTagCompound, 1.7.10 spells the methods func_150296_c
```

**Here.** Plain Java, the same on every version:

```java
CgNbtCompound data = CgNbt.compound()
        .put("name", name)
        .put("level", level)
        .put("spells", CgNbtList.of(CgCodecs.STRING, known));

int level = data.getInt("level");                 // 0 when absent, getInt("level", 1) names the default
String first = data.at("spells.0").asString();    // a path, null when it breaks
tag.put("character", CgNbt.toHost(data));         // into the game's tag, on any version
```

### What goes away

| The struggle | Usually | Here |
|---|---|---|
| **Every port rewrites the NBT code** | `getInt`, `getAllKeys`, `NBTTagCompound`, `func_150296_c`: renamed across versions | the tags are ours; each version's spelling is CrystalGraphics', in one class |
| **A wrong kind reads as nothing** | `getList("x", 8)` with the wrong id returns an empty list, silently | typed reads with a fallback you name; a number reads as any width |
| **A key typed twice** | the save writes `"Level"`, the load reads `"level"` | a codec declares it once (`put("spell", Spell.CODEC, spell)`) |
| **Save and load written twice** | `addAdditionalSaveData` and `readAdditionalSaveData`, then a packet, then a config | one `writeState` and one `readState`, for NBT, JSON and the wire (§3) |
| **Testing needs the game** | launch Minecraft to see what an item's tag looks like | tags build, print and parse in a plain JUnit test |
| **Reading a `level.dat` or a structure** | a tool of your own, or a library | `CgFiles.NBT.read(file, CgNbtCompound.CODEC)` |
| **Editing data a person writes** | no text form that keeps a byte a byte | SNBT: `{count:3b}`, readable, editable, every width kept |
| **Aliasing the game's tag** | edit a tag you got from the game, change the game's | tags are copied in and out; yours never alias the game's |

## Contents

1. [The words you need](#1-the-words-you-need)
2. [Quick start](#2-quick-start)
3. [State maps: the way to read and write NBT](#3-state-maps-the-way-to-read-and-write-nbt)
4. [Codecs: the other side of a state map](#4-codecs-the-other-side-of-a-state-map)
5. [Building and reading tags](#5-building-and-reading-tags)
6. [Paths, merging and editing](#6-paths-merging-and-editing)
7. [SNBT: tags as text](#7-snbt-tags-as-text)
8. [Files and bytes](#8-files-and-bytes)
9. [The game's tags](#9-the-games-tags)
10. [Tags in other formats](#10-tags-in-other-formats)
11. [Testing](#11-testing)
12. [Cost](#12-cost)
13. [Questions people ask](#13-questions-people-ask)

---

## 1. The words you need

| Word | What it is | Class |
|---|---|---|
| **Tag** | One NBT value as plain Java. Every tag is one of the three classes below. | `CgNbtTag` |
| **Compound** | A tag holding named tags, like a map. The root of every file, entity, block entity and item. | `CgNbtCompound` |
| **List** | A tag holding tags of **one kind**, in order. | `CgNbtList` |
| **Value** | A tag holding a string, a number or an array of numbers. | `CgNbtValue` |
| **Kind** | Which of NBT's twelve tags a tag is: `BYTE`, `SHORT`, `INT`, `LONG`, `FLOAT`, `DOUBLE`, `STRING`, `BYTE_ARRAY`, `LIST`, `COMPOUND`, `INT_ARRAY`, `LONG_ARRAY`. | `CgNbtTag.Type` |
| **SNBT** | NBT as text, as `/data get` prints it: `{name:"Goku",level:9,alive:1b}`. | `CgNbt.parse`, `toString()` |
| **Host tag** | The game's own tag object, whatever the version calls it. | `CgNbt.of`, `toHost` |
| **Ops** | NBT's rules as a codec format, so any codec writes into tags. | `CgNbtOps` |

How they fit together:

```
 your value ── CgCodec ──▶ CgNbtTag ◀── CgNbt.parse / CgFiles.NBT / CgFiles.SNBT ── text and files
                              │
                              ▼  CgNbt.toHost / applyTo      ▲  CgNbt.of / fromHost
                        the game's own tag, on every version
```

The twelve kinds match NBT's ids 1 to 12 on every version, so a tag means the same thing in a file, on the wire and in
the game.

---

## 2. Quick start

### Build, print, read

```java
CgNbtCompound tag = CgNbt.compound()
        .put("name", "Goku")
        .put("level", 9)
        .put("alive", true);

System.out.println(tag);                  // {name:"Goku",level:9,alive:1b}
int level = tag.getInt("level");          // 9
```

### Into an entity's save, with a state map

An object writes and reads itself through a **state map** (§3), and the same two methods serve NBT, JSON and the wire:

```java
@Override protected void addAdditionalSaveData(CompoundTag tag) {
    super.addAdditionalSaveData(tag);
    CgStateMap<CgNbtTag> out = CgNbt.state();
    writeState(out);                                           // out.putInt("level", level)...
    CgNbt.applyTo(tag, out.encode().asCompound());
}
@Override protected void readAdditionalSaveData(CompoundTag tag) {
    super.readAdditionalSaveData(tag);
    readState(CgNbt.state(CgNbt.of(tag)));                     // level = in.getInt("level", 1)...
}
```

### Into an entity's save, with a codec

A type with a fixed shape is a codec's value, stored under one key:

```java
@Override protected void addAdditionalSaveData(CompoundTag tag) {
    super.addAdditionalSaveData(tag);
    tag.put("Character", CgNbt.toHost(Character.CODEC, character));        // your codec's value, as a game tag
}
@Override protected void readAdditionalSaveData(CompoundTag tag) {
    super.readAdditionalSaveData(tag);
    if (tag.contains("Character")) character = CgNbt.fromHost(Character.CODEC, tag.get("Character"));
}
```

### A file

```java
CgNbtCompound level = CgFiles.NBT.read(Path.of("level.dat"), CgNbtCompound.CODEC);   // gzip detected
CgFiles.SNBT.write(Path.of("preset.snbt"), CgNbtCompound.CODEC, level);               // a text file to edit
```

---

## 3. State maps: the way to read and write NBT

**Start here for anything that saves or loads itself.** A `CgStateMap` is a notepad an object fills when asked and reads
back, each read with a default. It is used as a `CompoundTag` is in `addAdditionalSaveData` and
`readAdditionalSaveData`, and it is what CrystalGraphics' and CrystalGUI's own APIs hand you wherever an object saves
itself: a widget's state, a workbench session, a UI payload. Over NBT it is a `CgStateMap<CgNbtTag>`.

```java
public final class Camera {
    float zoom = 1f;
    boolean grid;
    Mode mode = Mode.ORBIT;
    final List<String> bookmarks = new ArrayList<>();

    public <T> void writeState(CgStateMap<T> out) {
        out.putFloat("zoom", zoom).putBool("grid", grid).putEnum("mode", mode)
           .putList("bookmarks", bookmarks, (m, name) -> m.putString("name", name));
    }

    public <T> void readState(CgStateMap<T> in) {
        zoom = in.getFloat("zoom", 1f);                 // a key that is not there reads its default
        grid = in.getBool("grid", false);
        mode = in.getEnum("mode", Mode.class, Mode.ORBIT);
        bookmarks.clear();
        bookmarks.addAll(in.getList("bookmarks", m -> m.getString("name", "")));
    }
}
```

The methods are generic over `T`, the tree type: they never say NBT. The caller picks where the state goes.

```java
CgStateMap<CgNbtTag> out = CgNbt.state();                // a new one, to fill
camera.writeState(out);
CgNbtCompound saved = out.encode().asCompound();         // {zoom:1.0f,grid:0b,mode:"ORBIT",bookmarks:[{name:"home"}]}

camera.readState(CgNbt.state(saved));                    // read from a compound
camera.readState(CgNbt.state(CgNbt.of(entityTag)));      // or from the game's own tag
```

| Write | Read | |
|---|---|---|
| `putString`, `putInt`, `putFloat`, `putDouble`, `putBool` | `getString`, `getInt`, ... `(key, fallback)` | the fallback is always yours |
| `putEnum(key, e)` | `getEnum(key, Mode.class, fallback)` | by name, so reordering constants breaks nothing |
| `putBytes(key, bytes)` | `getBytes(key)` | a byte array tag |
| `putList(key, values, (m, v) -> ...)` | `getList(key, m -> ...)` | each element is a state map of its own |
| `putStringIfNot(key, v, omitWhen)`, `putBoolIfNot` | | leaves out a default, so the tag stays small |
| `putRaw(key, tag)` | `getRaw(key)` | a tag, or a codec's value (below) |
| | `has(key)` | |

**A value with a codec** goes in with the map's own ops, so it is NBT here and JSON or the wire elsewhere:

```java
out.putRaw("spell", Spell.CODEC.encode(out.ops(), spell));
Spell spell = Spell.CODEC.decode(in.ops(), in.getRaw("spell"));
```

**Why this, and not a `CgNbtCompound` by hand?**

| | A state map | A compound | A codec |
|---|---|---|---|
| Where the code can run | any format: NBT, JSON, the wire | NBT only | any format |
| A missing key | the default you pass | `getInt(key, fallback)`, the same | throws, or the fallback declared |
| What you write | two methods on the object | the tag itself | one declaration of the type |
| Use it for | an object that saves and loads itself | editing a tag you were handed (an item's, an entity's) | a type with a fixed shape, saved or sent |

- **A state map reads a snapshot.** `CgNbt.state(compound)` copies the compound's entries (not their children), so
  changing the compound afterwards does not change what the map reads. `encode()` builds a new tag each time.
- **Keys are written in the order they were put**: the same state encodes to the same bytes.
- **Every read takes a default**, so a key you add in an update still reads on a save an older version wrote.
- **A state map and a codec answer different questions.** `docs/SERIALIZATION.md` §3, *An object that writes itself*,
  says which to reach for and shows both side by side: a type you describe is a codec, and a method you implement that
  is handed a state map fills and reads it.

---

## 4. Codecs: the other side of a state map

A state map (§3) and a **codec** are two opposite sides of the same coin, and you will use both.

- **A state map is the object telling its story.** The object knows itself, so it writes its own fields, key by key, when
  something asks, and reads them back the same way.
- **A codec is the type's shape, declared once.** Nobody holds an object: the declaration says what a `Spell` is, and
  anything can write or read one without asking it to.

Both end in the same tags, so each reads what the other wrote:

```java
// the object tells its story
public <T> void writeState(CgStateMap<T> out) { out.putString("name", name).putInt("cost", cost); }
public <T> void readState(CgStateMap<T> in)    { name = in.getString("name", ""); cost = in.getInt("cost", 0); }

// the type's shape, declared once
static final CgCodec<Spell> CODEC = CgCodecs.record(
        CgCodecs.field("name", CgCodecs.STRING, Spell::name),
        CgCodecs.field("cost", CgCodecs.INT, Spell::cost),
        Spell::new);

CgNbtTag tag = CgNbt.encode(Spell.CODEC, spell);              // {name:"Kamehameha",cost:30}
int cost = CgNbt.state(tag.asCompound()).getInt("cost", 0);    // a codec's tag, read through a state map
CgStateMap<CgNbtTag> out = CgNbt.state();
spell.writeState(out);
Spell same = out.encode().as(Spell.CODEC);                    // a state map's tag, read through a codec
```

| | A state map | A codec |
|---|---|---|
| It answers | "write yourself here" | "this is what the type looks like" |
| Written | by hand, on the object, in two methods | declared once, as a constant |
| Needs | an object to fill and read | only a value; none held |
| Strong at | state an object owns and may add to later | a fixed shape that is sent, saved, decoded into a live object, versioned |

**They go hand in hand.** An object that saves itself with a state map puts a part with a fixed shape in through its
codec (`putRaw`, §3), and a codec's value that holds the dynamic part of a shape carries a tag whole (below). Reach for
a state map when something hands you one; reach for a codec when you are saying what a type is. `docs/SERIALIZATION.md`
§3 has the same choice from the codec's side.

### A codec's value as a tag

```java
CgNbtTag tag = CgNbt.encode(Spell.CODEC, spell);                 // {name:"Kamehameha",cost:30}
Spell back = CgNbt.decode(Spell.CODEC, tag);
CgNbt.decode(Character.CODEC, tag, live);                        // into the live object
Spell spell = tag.as(Spell.CODEC);                               // the same, on a tag you hold
```

NBT's rules apply as the game's do, so a codec cannot write a file the game refuses: a boolean is a byte, keys are
text, a list holds one kind, and an absent value reads as an empty compound.

### The dynamic part of a shape

A codec describes the part of a value with a fixed shape. The part that has none (an item's custom data, a mod's
extras) is a tag, and a codec can carry one whole:

```java
record Loot(String table, CgNbtCompound extra) {
    static final CgCodec<Loot> CODEC = CgCodecs.record(
            CgCodecs.field("table", CgCodecs.STRING, Loot::table),
            CgCodecs.optionalField("extra", CgNbtCompound.CODEC, Loot::extra, new CgNbtCompound()),
            Loot::new);
}
```

`CgNbtTag.CODEC` carries any tag, `CgNbtCompound.CODEC` a compound. Through NBT every width survives; see §10 for the rest.

---

## 5. Building and reading tags

### Compounds

`put` takes the Java type you have, and stores the tag NBT would use for it. It answers the compound, so calls chain.

| You put | Stored as | |
|---|---|---|
| `"text"` | string | |
| `9` | int | |
| `9L`, `2.5f`, `2.5d` | long, float, double | |
| `true` | byte, `1b` or `0b` | as Minecraft stores a boolean |
| `new byte[]{...}`, `new int[]{...}`, `new long[]{...}` | byte, int, long array | |
| `putByte(key, 3)`, `putShort(key, 3)` | byte, short | an `int` would store an int |
| `put(key, codec, value)` | whatever the codec writes | §4 |
| `put(key, tag)` | the tag itself | not a copy; null removes the key |

```java
CgNbtCompound item = CgNbt.compound()
        .put("id", "minecraft:diamond_sword")
        .putByte("Count", 1)
        .put("tag", CgNbt.compound()
                .put("Damage", 12)
                .put("display", CgNbt.compound().put("Name", "Blade of Dawn")));
```

Reads never throw for a key that is absent or of the wrong kind. They answer a fallback, and a number reads as any
width:

```java
item.getInt("Damage")                 // 0 when absent
item.getInt("Damage", -1)             // -1 when absent
item.getString("id", "")              // text
item.getBool("flag")                  // a byte that is not 0
item.getLong("Count")                 // a byte reads as a long
item.getCompound("tag")               // an empty compound when absent; reading it never makes it
item.getOrCreateCompound("tag")       // the one in the compound; made and stored when absent or another kind
item.getList("Lore")                  // an empty list when absent
item.has("tag")                       // present at all
item.has("tag", CgNbtTag.Type.COMPOUND)    // present and that kind
item.get("tag")                       // the tag, or null
item.require("id")                    // the tag, or throws naming the key
for (String key : item.keys()) { ... }
item.forEach((key, child) -> ...);
```

### Lists

A list holds **one kind of tag**, as Minecraft's do on every version before 1.21.5. Adding a second kind throws,
naming both, rather than writing a file the game would refuse.

```java
CgNbtList lore = CgNbtList.of("first", "second");                  // strings
CgNbtList cells = CgNbtList.of(1, 2, 3);                           // ints
CgNbtList rows = CgNbtList.of(CgNbt.compound().put("k", "v"), CgNbt.compound().put("k", "w"));
CgNbtList spells = CgNbtList.of(Spell.CODEC, spellList);           // each element through a codec

CgNbtList empty = CgNbt.list();                                    // or new CgNbtList(); of() alone is ambiguous
empty.add("a").add("b");
empty.add(5);                                                      // throws: this list holds strings
lore.getString(0);   lore.size();   lore.set(1, tag);   lore.remove(0);
List<Spell> back = spells.toList(Spell.CODEC);
for (CgNbtTag t : lore) { ... }
```

### Values

A value is a string, a number or an array. You rarely make one yourself (`put` does), but you read them when you walk
a tree:

```java
CgNbtTag t = item.get("Damage");
switch (t.type()) {
    case INT -> t.asValue().asInt();
    case STRING -> t.asString();
    case COMPOUND -> t.asCompound();
    case LIST -> t.asList();
    default -> {}
}
CgNbtValue.ofByte(1);   CgNbtValue.ofString("x");   CgNbtValue.ofInts(new int[]{1, 2, 3});
```

- **Tags are mutable and own what they hold.** `copy()` is deep; `put(key, tag)` stores that tag itself.
- **Values are immutable**, so the small ones (flags, counts, levels from -128 to 127) are shared.
- **`toString()` is SNBT**, so a tag in a log or a debugger reads as `/data get` would print it.
- **`equals` compares kind and content**: `1b` and `1` are not equal, as in the game.

---

## 6. Paths, merging and editing

A **path** names a tag through compounds and lists with dots. A number indexes a list.

```java
item.at("tag.display.Name").asString();         // "Blade of Dawn"
item.at("tag.display.Lore.0");                  // the first lore line; null when anything on the way is missing
item.putAt("tag.display.Lore", CgNbtList.of("new"));   // makes the compounds on the way
```

A key with a dot in it is read by `get(key)`, not by a path. Namespaced keys (`minecraft:custom_name`) have a colon,
not a dot, and are fine in a path.

**`merge`** copies another compound in, deep: a compound inside a compound merges, anything else is replaced by the
other's.

```java
CgNbtCompound defaults = CgNbt.parse("{style:1,colors:{a:1,b:2}}");
CgNbtCompound mine = CgNbt.parse("{colors:{b:9}}");
defaults.merge(mine);                           // {style:1,colors:{a:1,b:9}}
```

```java
tag.remove("old");        tag.clear();        tag.size();        tag.isEmpty();
```

---

## 7. SNBT: tags as text

SNBT is the text form Minecraft prints and `/data` and function files read. Every tag keeps its exact kind through it,
which JSON and TOML cannot do:

| You write | The tag |
|---|---|
| `{name:"Goku",level:9}` | a compound; a key with no spaces or symbols needs no quotes |
| `1b`, `2s`, `3`, `4L`, `5.0f`, `6.0d` | byte, short, int, long, float, double |
| `true`, `false` | bytes, `1b` and `0b` |
| `"text"`, `'text'`, `bare` | strings |
| `[1,2,3]` | a list |
| `[B;1,2]`, `[I;1,2]`, `[L;1L,2L]` | a byte, int or long array |

```java
CgNbtCompound preset = CgNbt.parse("{style:3b,dyed:true,colors:[I;16711680,255]}");
CgNbtTag any = CgNbt.parse("[1,2,3]");        // typed by what you assign it to
String text = preset.toString();              // the same grammar back
```

A text that does not parse throws `CgCodecException`, naming what it expected and where.

---

## 8. Files and bytes

NBT is a file format like the rest of `docs/SERIALIZATION.md` §6, so it reads and writes like them:

```java
CgNbtCompound level = CgFiles.NBT.read(Path.of("level.dat"), CgNbtCompound.CODEC);      // null when there is no file
CgFiles.NBT.write(Path.of("out/structure.nbt"), CgNbtCompound.CODEC, tag);             // gzipped
CgFiles.SNBT.write(Path.of("preset.snbt"), CgNbtCompound.CODEC, tag);                  // text, for a person
CgFiles.of(Path.of("a/b.snbt"));                                                        // by name: .nbt, .snbt
```

| Format | What it is |
|---|---|
| `CgFiles.NBT` | binary NBT, gzipped, as `level.dat` and structure files are. A read detects gzip, so an uncompressed file reads too |
| `CgFiles.SNBT` | SNBT text |

- **A write replaces the file whole**: beside the old one, then moved over it, so a crash mid-save leaves the last save.
- **A codec reads or writes a file directly**: `CgFiles.NBT.write(file, Spell.CODEC, spell)` stores the spell as real
  NBT. A file's root is a compound, so a codec whose value is a number or a list is refused, naming the kind.
- **`.dat` is not mapped**: `CgFiles.of` leaves it as `BINARY`, which is your mod's own format. Name `NBT` for a
  `level.dat`.
- **A damaged file fails by name**, never half-reads: nested past 512 deep, or a length no stream could hold.

**Bytes and streams**, where there is no file (a network buffer, a region file's chunk, a test):

```java
byte[] bytes = CgNbt.toBytes(tag);                            // gzipped
CgNbtCompound back = CgNbt.fromBytes(bytes);                  // gzip detected
CgNbt.writeBinary(out, tag);   CgNbt.writeBinary(out, tag, false);   // gzipped or plain
CgNbtCompound read = CgNbt.readBinary(in);
```

The streams you pass are yours: they are never closed.

---

## 9. The game's tags

```java
// the game's tag as ours: edit it with no version's names, then give it back
CompoundTag saved = entity.saveWithoutId(new CompoundTag());
CgNbtCompound data = CgNbt.of(saved);                          // typed by what you assign it to
data.getOrCreateCompound("ForgeData").put("mymod:charge", 3);
CgNbt.applyTo(saved, data);                                    // into the game's compound, in place
CompoundTag fresh = CgNbt.toHost(data);                        // or a new one

// a codec's value in the game's container
tag.put("Character", CgNbt.toHost(Character.CODEC, character));
Character c = CgNbt.fromHost(Character.CODEC, tag.get("Character"));
CgNbt.fromHost(Character.CODEC, tag.get("Character"), this.character);    // into the live one
```

| Call | Does |
|---|---|
| `CgNbt.of(hostTag)` | a copy of the game's tag as ours; null stays null, and one of ours is answered as it is |
| `CgNbt.toHost(tag)` | a new game tag from ours |
| `CgNbt.applyTo(hostCompound, tag)` | puts each of our keys into the game's compound, in place; keys we lack are left as they are |
| `CgNbt.toHost(codec, value)` | a codec's value as a game tag |
| `CgNbt.fromHost(codec, hostTag[, target])` | a game tag read through a codec, into a target when given |

- **They copy.** A `CgNbtTag` never aliases a game tag, so editing yours changes the game's only when you `applyTo` or
  `toHost`. The copy is the cost of owning your tag; §12 has its price.
- **They throw outside Minecraft** (the harness, a test), naming the `crystalgraphics:nbt` slot. `CgNbt.present()`
  answers whether a game is there. Everything else on this page runs without one.
- **A long array tag needs 1.13 or later.** Before it the call throws, saying so, since the game has no such tag.
- **`/data` shows your values as real tags**, readable and editable.

How each version spells its tags and how that is proven: `docs/MINECRAFT_INTEGRATION.md` § *Saving and NBT*.

---

## 10. Tags in other formats

A tag goes through any file format of `docs/SERIALIZATION.md` §6 by its codec, `CgNbtCompound.CODEC`:

```java
CgFiles.JSON.write(export, CgNbtCompound.CODEC, tag);            // JSONC, TOML and YAML the same
CgNbtCompound back = CgFiles.TOML.read(config, CgNbtCompound.CODEC);
```

| Format | Keeps |
|---|---|
| `NBT`, `SNBT` | every tag exactly |
| `BINARY` | structure and values; numbers come back as int, long or double |
| `JSON`, `JSONC`, `TOML`, `YAML` | structure and values; numbers come back as int, long or double, and an array is Base64 text or a list of numbers |

- **Widths are lost** in the last two rows: `1b`, `1s` and `1` all come back as `1`. Use `NBT` or `SNBT` where a byte
  must stay a byte.
- **A TOML or YAML key with a colon** (`minecraft:id`) is quoted for you.
- **An empty compound or list survives** every format.

---

## 11. Testing

Everything but the game's own tags runs in a plain JUnit test, no Minecraft:

```java
@Test public void aCharacterSavesAsNbt() {
    CgNbtTag tag = CgNbt.encode(Character.CODEC, character);
    assertEquals("{name:\"Goku\",level:9}", tag.toString());        // what /data get would show
    assertEquals(9, tag.as(Character.CODEC).level);
}

@Test public void aPresetLoads() {
    CgNbtCompound preset = CgNbt.parse("{style:3b,dyed:true,colors:[I;16711680,255]}");
    assertEquals(3, preset.getByte("style"));
}

@Test public void aSaveRoundTrips() throws IOException {
    Path file = folder.newFile("a.nbt").toPath();
    CgFiles.NBT.write(file, CgNbtCompound.CODEC, tag);
    assertEquals(tag, CgFiles.NBT.read(file, CgNbtCompound.CODEC));
}
```

**The game's tags in a test** need a stand-in: `CgPlatform.provide(CgNbt.HOST, host)` with your own `CgNbt.Host`, and
`CgPlatform.provide(CgNbt.HOST, null)` after. CrystalGraphics' own test has one over plain Java maps
(`CgNbtTest.JavaTags`) that is worth copying.

---

## 12. Cost

Measured on an item-sized tag (about 30 tags, 488 characters of SNBT, 300 bytes gzipped), one run, after warm-up, on
the development machine. The game's own tag construction is not in the host rows: they use a stand-in.

| Operation | Time | Allocated |
|---|---|---|
| `getInt` ×4, through two compounds | 0.16 µs | 0 B |
| `at("a.b.c.d")` | 0.5 µs | 376 B |
| `copy()` | 2.5 µs | 3.0 KB |
| `toString()` (SNBT) | 3.5 µs | 2.3 KB |
| `CgNbt.parse` | 10.7 µs | 16 KB |
| `CgNbt.toBytes` (gzip) | 25 µs | 10 KB |
| `CgNbt.fromBytes` (gzip) | 18 µs | 23 KB |
| `CgNbt.toHost` / `of` | 2.2 µs | 3–4 KB |
| `encode` / `decode` of a small record | 0.3 µs | |

- **Reads allocate nothing**; the copy to and from the game is the price of a tag you own, about what the game's own
  `copy()` costs, and small against a save.
- **Keep it out of a per-frame loop**: an entity's tag is a save-time structure. Cache the typed values you read every
  frame, and write the tag when it changes.
- **Files are gzip's cost**: write them off the game's thread (`docs/JOBS.md`) when they are large.

`CgNbtCostTest` prints these for any change to compare; it asserts only a generous ceiling that catches a quadratic.

---

## 13. Questions people ask

### Why not use the game's tags directly?

Their class names and methods change across versions (a compound is `NBTTagCompound` before 1.14, `CompoundTag`
after; 1.21.5 renamed the getters), so code that touches them is rewritten at each port. A `CgNbtCompound` is
the same everywhere, and runs in a test.

### Does `CgNbt.of` copy every time?

Yes, deep. A tag you read once and keep is cheap; one you convert each tick is not. See §12.

### A list will not take my tag.

A list holds one kind, as the game's do before 1.21.5. Make a list per kind, or a compound that holds a kind
and a value.

### How do I read an item's NBT?

Through `CgNbt.of(stack.getTag())` on a version that has one. From 1.20.5 an item's data is components, which a codec
reads and writes; a version-blind item stack that wraps both is a later step.

### JSON, or SNBT, for a file a person edits?

TOML for a config, SNBT where the tags' widths matter (a preset the game reads back), JSON for data other tools read.
§10 has what each keeps.

### Where is the code?

`com.crystalgraphics.serialization.nbt` (the tags, `CgNbt`, `CgNbtOps`, SNBT and the binary reader and writer) and
`com.crystalgraphics.serialization.format.file` (`NbtFileFormat`, `SnbtFileFormat`). Each version's game side is one
class of primitives, `NbtModern`, `NbtLegacy` or `Nbt1710`. The package guide is
`core/src/main/java/com/crystalgraphics/serialization/CLAUDE.md`.
