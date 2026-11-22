# Minecraft's values — the guide

Blocks, entities, fluids, positions and recipe ingredients as plain Java, the same on every Minecraft version: what a
widget shows, a file saves and a packet sends. Each prints as the command that makes it and parses back. Item stacks
have their own guide, [`ITEMS.md`](ITEMS.md); this one covers the rest and the text form they all share.

## Contents

1. [Quick start](#1-quick-start)
2. [As text: the command each one is](#2-as-text-the-command-each-one-is)
3. [Block states](#3-block-states)
4. [Entities](#4-entities)
5. [Fluids](#5-fluids)
6. [Positions, faces and boxes](#6-positions-faces-and-boxes)
7. [Ingredients](#7-ingredients)
8. [How each version is read](#8-how-each-version-is-read)

## 1. Quick start

```java
CgItemStack sword = CgItemStack.parse("diamond_sword[enchantments={levels:{sharpness:3}}]");   // /give
CgBlockState furnace = CgBlockState.parse("furnace[facing=east,lit=true]");                     // /setblock
CgEntityData sheep = CgEntityData.parse("sheep{Color:2b}");                                     // /summon
CgFluidStack lava = CgFluidStack.parse("lava 1000");                                            // a bucket
CgBlockPos spawn = CgBlockPos.parse("10 64 -3");
CgIngredient planks = CgIngredient.parse("#minecraft:planks");

BlockState state = furnace.toHost();                 // the game's own, where you ask
CgBlockState back = CgBlockState.of(state);          // a copy of the game's
```

## 2. As text: the command each one is

`toString()` is the text, and `parse(v.toString())` is `v`, on every value:

| Value | Text | Read by |
|---|---|---|
| `CgItemStack` | `minecraft:stone[minecraft:damage=3] 16` | `/give` |
| `CgBlockState` | `minecraft:furnace[facing=east,lit=true]{CookTime:3s}` | `/setblock` |
| `CgEntityData` | `minecraft:sheep{Color:2b}` | `/summon` |
| `CgFluidStack` | `minecraft:water 250`, or `27d` for droplets | — |
| `CgBlockPos`, `CgBlockBox`, `CgDirection` | `10 64 -3`; two corners; `north` | `/tp`, `/fill`, a face |
| `CgIngredient` | `#minecraft:planks`, or stacks joined by ` \| ` | — |

- An id without a namespace is `minecraft:`'s, as the game reads one.
- A refusal throws `CgCodecException` naming what it wanted and where: `wanted ']' at 18 in "..."`.
- `CgItemStack.toCommand()` writes the running version's own `/give` text (see [`ITEMS.md`](ITEMS.md)); every other
  value's text is the same on every version.
- Their parsing is shared: `CgCommandReader` (ids, words, numbers, an SNBT tag from mid-text), and
  `CgNbt.parse(text, ParsePosition)` for a tag inside a longer text.

## 3. Block states

```java
CgBlockState chest = CgBlockState.parse("chest[facing=south]{Items:[{Slot:0b,id:\"minecraft:apple\",count:3}]}");
String facing = chest.property("facing");          // "south"
CgNbtCompound items = chest.blockEntity();          // what a chest's block entity holds
CgBlockState open = chest.with("type", "left");     // edits answer a new state

CgBlockState here = CgBlocks.of(level.getBlockState(pos));   // every property, defaults included
level.setBlock(pos, open.toHost(), 3);
```

- A state holds the properties written; `toHost` gives the rest the block's defaults. A property or value the block
  has not got throws, naming the ones it has.
- The block entity's data travels with the value but is not part of the game's state: `toHost` builds the state alone.
- `CODEC` is the game's saved form, `{Name, Properties}` (structures, `NbtUtils`), with `nbt` for the block entity.
- 1.7.10 has no block states: `CgBlocks` is absent there, and a state's `meta` property is the block's metadata.

## 4. Entities

```java
CgEntityData sheep = CgEntityData.parse("minecraft:sheep{Color:2b,NoAI:1b}");
CgNbtCompound saved = sheep.toNbt();               // {id:"minecraft:sheep",Color:2b,NoAI:1b}, as the game saves one

CgEntityRef shown = CgEntityRef.of(sheep);          // what a view shows
CgEntityRef me = CgEntityRef.player();
CgEntityRef notch = CgEntityRef.profile("Notch");   // any player, by name or UUID
CgEntityRef that = CgEntityRef.live(entity.getUUID());
```

- `CgEntityData` is the type and `/summon`'s data, the version's own keys; the game ignores a key the type does not
  read, as `/summon` does.
- A type is always namespaced. Before 1.11 an entity's id is its name: `minecraft:Sheep` there.
- `CgEntityRef` never holds the game's entity; a host finds or makes it where it draws it, and never adds one to the
  level.

## 5. Fluids

```java
CgFluidStack water = CgFluidStack.of("minecraft:water", 250);        // millibuckets
CgFluidStack drop = CgFluidStack.ofDroplets("minecraft:water", 27);  // Fabric's unit, kept exact
String name = CgFluids.name(water);                                   // "Water", in the client's language
String sprite = CgFluids.stillSprite(water);                          // a block-atlas sprite id
int argb = CgFluids.tint(water);                                      // the colour the sprite is multiplied by
```

- Amounts are droplets inside (81 a millibucket), so Forge's millibuckets and Fabric's amounts both stay exact.
- `CgFluids` is the client's: each loader answers from its own fluid API, and from 26.1 the sprite is the game's
  fluid model's on every loader. A dedicated server, Fabric before 1.16.5 and 1.13.2's Forge answer the id as the
  name, no sprite and white.
- A host answers by the fluid alone; a stack's data does not change its sprite or tint here.
- Before 1.13 a fluid's name is Forge's own (`water`): `minecraft:` is taken off to find it.

## 6. Positions, faces and boxes

```java
CgBlockPos p = CgBlockPos.parse("10 64 -3");
CgBlockPos front = p.relative(CgDirection.NORTH);        // north is -z
long key = p.asLong();                                   // the game's packing from 1.14
CgBlockBox room = CgBlockBox.parse("0 64 0 9 70 9");     // /fill's corners, any order
```

Plain numbers and names: no host. `CgDirection`'s order and names are the game's (`ordinal()` is
`get3DDataValue`). `asLong` is 1.14's `BlockPos` packing; legacy's packed differently.

## 7. Ingredients

```java
CgIngredient planks = CgIngredient.tag("#minecraft:planks");
List<CgItemStack> shown = planks.items();         // one of each item in the tag, from the game
boolean fits = planks.test(stack);                 // its item is in the tag
CgIngredient either = CgIngredient.of(CgItemStack.of("minecraft:coal", 1), CgItemStack.of("minecraft:charcoal", 1));
```

A tag is the game's to resolve (`CgItems.tagItems`): none before 1.13, which has no item tags, and none without a game.

## 8. How each version is read

| | Block states | Items' text | Entities |
|---|---|---|---|
| 1.20.5 on | `{Name, Properties}` | `id[components]` | `EntityType.loadEntityRecursive` |
| 1.13 to 1.20.4 | the same | `id{tag}` | the same |
| 1.8.9 to 1.12.2 | `IBlockState`'s properties | `id count damage {tag}` | `EntityList`, names before 1.11 |
| 1.7.10 | none (block and metadata) | the same | `EntityList`, names |

The gauntlet test `crystalgraphics:values` reads each value's text with the game's own parser and with ours on a
dedicated server and a client, and compares them: `values.item`, `.block`, `.entity`, `.faces`, `.position`, `.tag`,
`.fluid`.
