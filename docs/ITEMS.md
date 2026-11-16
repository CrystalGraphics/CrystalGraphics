# Item stacks — the guide

`CgItemStack` is Minecraft's item stack as a value: the item, how many, and **all** of its data, the same on every
version and loader. You read a stack's name, lore, enchantments, durability and your own NBT without touching a version's
classes, edit it into a new stack, and hand it back to the game. It holds no game object, so it is safe to keep, send,
compare and test.

## If you have worked with item stacks before

| The struggle | Usually | Here |
|---|---|---|
| **The data moved** | `getTag()` to 1.20.4, data components from 1.20.5, `display.Name` as JSON then NBT, enchantments by number, then by name, then flattened | one shape for every version: `name()`, `lore()`, `enchantments()`, `customData()` |
| **Your own NBT on an item** | `getOrCreateTag().putInt(...)`, then `CustomData.update(...)` | `stack.withCustomData(d -> d.put("mymod:charge", 3))` |
| **A stack is mutable and live** | a copy you forgot to make, a stack another thread changed | a value: edits answer a new stack |
| **Telling whether it changed** | `ItemStack.matches`, `hashItemAndComponents`, `tagMatches`, by version | `stack.equals`, `stacksWith`, `CgItems.stampOf(hostStack)` |
| **A test needs the game** | launch Minecraft to build an enchanted sword | `CgItemStack.of("minecraft:diamond_sword", 1).withEnchantment(...)` in plain JUnit |

## Contents

1. [Quick start](#1-quick-start)
2. [The data: components](#2-the-data-components)
3. [Facets and edits](#3-facets-and-edits)
4. [The game's stacks](#4-the-games-stacks)
5. [Equality and change](#5-equality-and-change)
6. [Saving and sending](#6-saving-and-sending)
7. [Testing](#7-testing)
8. [How each version is read](#8-how-each-version-is-read)

---

## 1. Quick start

```java
CgItemStack stack = CgItemStack.of(slot.getItem());                  // a copy of the game's stack
String label = stack.name() != null ? stack.name() : stack.item();   // "Excalibur" or "minecraft:iron_sword"
int sharp = stack.enchantment("minecraft:sharpness");                // 0 when absent
int charge = stack.customData().getInt("mymod:charge");

CgItemStack better = stack.withName("Excalibur")
        .withEnchantment("minecraft:sharpness", 5)
        .withCustomData(d -> d.put("mymod:charge", 3));              // edits answer a new stack
player.getInventory().setItem(0, better.toHost());                   // the game's, built from the data
```

With no game (a test, a tool, the harness) everything but `of(hostStack)` and `toHost()` runs the same.

---

## 2. The data: components

A stack's data is a compound of **components**, as 1.21.5 and later keep them: `minecraft:custom_name`,
`minecraft:lore`, `minecraft:enchantments`, `minecraft:damage`, `minecraft:unbreakable`, `minecraft:custom_data`, and every
component the game adds. Versions before 1.20.5 saved `{id, Count, tag}`; CrystalGraphics projects their tags into the same
keys, so code written against components runs everywhere.

| An older tag's key | Its component |
|---|---|
| `Damage` | `minecraft:damage` |
| `display.Name`, `display.Lore` | `minecraft:custom_name`, `minecraft:lore` |
| `Enchantments` (`ench` before 1.13), `StoredEnchantments` | `minecraft:enchantments`, `minecraft:stored_enchantments` |
| `Unbreakable`, `CustomModelData`, `RepairCost` | `minecraft:unbreakable`, `minecraft:custom_model_data`, `minecraft:repair_cost` |
| every other key | inside `minecraft:custom_data` |

- **`customData()` is where a mod's own NBT lives on every version**: the `minecraft:custom_data` component on 1.20.5 and
  later, the rest of the item's `tag` before.
- **A component newer than the version has** (`minecraft:food` on 1.12) reads fine in a test; `toHost()` on that version
  throws, naming it. Put a mod's data in `customData`, which every version has.
- **A name is a chat component**, kept whole (`nameComponent()`); `name()` is its text. Before 1.13 `§` codes stay in the
  text. JSON text on 1.13 to 1.21.4 is read into the same tag and written back as JSON, styling kept.
- **Enchantments are `{id: level}`**; before 1.13 the id is the game's number, as text (`"16"` is sharpness).

---

## 3. Facets and edits

Each facet reads what it needs from the data and nothing else.

| Read | Gives |
|---|---|
| `item()`, `count()`, `isEmpty()` | the registry name (`namespace:path`), the count |
| `damage()`, `maxDamage()`, `isDamaged()` | durability used, the item's durability (read from the game), whether a bar shows |
| `name()`, `nameComponent()`, `lore()` | custom name text or null; the component; lore lines as text |
| `enchantments()`, `enchantment(id)`, `storedEnchantments()`, `isEnchanted()` | `id → level` in order |
| `isUnbreakable()`, `customModelData()` | |
| `customData()` | a copy of your data compound |
| `has(key)`, `component(key)`, `components()` | any component, as a copy |

Every edit answers a new stack and leaves the old one as it was:

```java
stack.withCount(3).withDamage(10).withItem("minecraft:stone");
stack.withName("Rock").withoutName();
stack.withLore(List.of("heavy", "grey")).withEnchantment("minecraft:unbreaking", 3).withEnchantment("minecraft:unbreaking", 0);
stack.withUnbreakable(true);
stack.withCustomData(d -> d.put("mymod:charge", 9));       // edits a copy; an emptied one drops the component
stack.with("minecraft:custom_model_data", CgNbtValue.ofInt(4)).without("minecraft:lore");
```

---

## 4. The game's stacks

```java
CgItemStack stack = CgItemStack.of(hostStack);   // typed by what you pass: any ItemStack; null is EMPTY
ItemStack back = stack.toHost();                 // typed by what you assign it to
```

- **Both copy.** Editing the game's stack afterwards does not change yours, and the reverse.
- **They throw outside Minecraft**, naming the `crystalgraphics:items` slot.
- **An empty stack** is the game's empty one on 1.11 and later, null before it, as the game's own slots hold.
- **On a modern version the data is read through the game's registries**, so a component that names an enchantment or an
  attribute works: the server's from its start, the client's from the world it is in.
- **`maxDamage()` is the item's, not data**: `of(hostStack)` fills it, a stack built in Java or read from a file has `0`.

### An item by the game's number, and item tags

What an entity's hand reports (`CgEntityProperties.MAIN_ITEM`, `OFF_ITEM`) is the game's raw number for the item, the
same for a session. `CgItems` names it and matches it against an id or a tag, with no stack copied:

```java
String name = CgItems.name(itemId);                            // "minecraft:bow", or null for none
boolean sword = CgItems.matches(itemId, "#minecraft:swords");  // an item tag: every item in it
boolean bow = CgItems.matches(itemId, "minecraft:bow");        // one item
```

- **Item tags are the game's**: from 1.13 on; Forge 1.8.9 to 1.12.2 and 1.7.10 have none, so a tag there matches nothing.
- **An id no mod registered matches nothing** and is no error: mods register items late.
- Both sides; without a game (a test) the name is null and nothing matches.

---

## 5. Equality and change

- **`equals`** compares item, count and data; every empty stack is equal; the max damage is left out.
- **`stacksWith(other)`** is the same item with the same data, whatever the count: what vanilla's `isSameItemSameComponents`
  asks, and whether two stacks merge.
- **`stamp()`** is a hash of item and data of a value you hold.
- **`CgItems.stampOf(hostStack)`** is the game's own hash (`hashItemAndComponents` from 1.20.6), taken **without a copy**:
  ask it first and copy only on a change.

```java
int now = CgItems.stampOf(hostStack);
if (now != last) { last = now; stack = CgItemStack.of(hostStack); }
```

---

## 6. Saving and sending

`CgItemStack.CODEC` is `{id, count, components}`, modern's shape on every version, so a stack goes in a file, a message or
a record of yours with the codecs of `docs/SERIALIZATION.md`:

```java
CgFiles.SNBT.write(file, CgItemStack.CODEC, stack);       // {id:"minecraft:stick",count:3,components:{...}}
record Reward(String name, CgItemStack item) { ... CgCodecs.field("item", CgItemStack.CODEC, Reward::item) ... }
```

A state map takes one with `putRaw("item", CgItemStack.CODEC.encode(out.ops(), stack))` (`docs/NBT.md` §3).

---

## 7. Testing

A stack is a value: build one and assert.

```java
CgItemStack sword = CgItemStack.of("minecraft:diamond_sword", 1).withEnchantment("minecraft:sharpness", 5).withDamage(5);
assertEquals(5, sword.enchantment("minecraft:sharpness"));
assertEquals(sword, CgNbt.encode(CgItemStack.CODEC, sword).as(CgItemStack.CODEC));
```

To test code that calls `CgItemStack.of(hostStack)`, provide a fake `CgItems.Host` over your own stack type
(`CgItemStackTest` does, for all three saved shapes).

---

## 8. How each version is read

An era's host does one thing: save a stack as the version does and load it back. Core maps the shapes (`ItemShapes`).

| | Modern 1.20.6–26.3 (`ItemsModern`) | Modern 1.13.2–1.20.4 | Legacy 1.8.9–1.12.2 (`ItemsLegacy`) | 1.7.10 (`Items1710`) |
|---|---|---|---|---|
| Saved shape | `{id, count, components}` | `{id, Count, tag}` | `{id, Count, Damage, tag}` | the same; the id is a number, replaced by the registry name |
| Save | `ItemStack.OPTIONAL_CODEC` over `RegistryOps(NbtOps)` | `save(CompoundTag)` | `writeToNBT` | `writeToNBT` |
| Load | the same codec, parsed | `ItemStack.of` | `loadItemStackFromNBT` to 1.10.2, `new ItemStack(tag)` from 1.11 | `new ItemStack(item, count, damage)` with the tag |
| The game's stamp | `hashItemAndComponents` | item and tag | item id, metadata, tag | item id, damage, tag |
| Text | NBT | JSON | plain with `§` | plain with `§` |
