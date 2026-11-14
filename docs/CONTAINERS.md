# Containers — the guide

`CgContainers` is the player's open menu (the inventory, a chest, a furnace, an anvil, a merchant) as data, and the
player's actions into it, the same on every version and loader. You read every slot as a `CgItemStack`, learn what
changed since you last looked, read the menu's own values (an anvil's cost, a merchant's offers) as a compound, and click,
press a button, rename or pick a trade through the game's own calls, so the server sees an ordinary player.

## If you have worked with menus before

| The struggle | Usually | Here |
|---|---|---|
| **Knowing what changed** | a `ContainerListener` on the server, a diff by hand on the client | `ContainerMirror.poll()`: the changed slots, the cursor, the state; `null` when nothing did |
| **A menu's own numbers** | `ContainerData` slots, `getBurnProgress`, a different getter per menu and version | `menu.state()`, a `CgNbtCompound` with one schema per kind (`MenuTypes`) |
| **Clicking** | `handleInventoryMouseClick` to 1.21.11, `handleContainerInput` from 26.1, an int mode on 1.7.10 | `CgContainers.act(id, new Action.Click(slot, button, ClickType.PICKUP))` |
| **What a slot is** | `instanceof` on the slot and its container, by version | `slot.kind()`: `CONTAINER`, `INVENTORY`, `HOTBAR`, `ARMOR`, `OFFHAND`, `RESULT` |
| **Drag, double-click, number keys** | rewritten in every container screen | `ContainerInteraction` (CrystalGUI): vanilla's gestures once |
| **A test needs the game** | launch Minecraft | a fake `CgContainers.Host` over plain data |

## 1. Quick start

```java
ContainerMirror mirror = CgContainers.mirror();                          // one per screen, client thread
ContainerMirror.Update update = mirror.poll();                            // every frame; null unless something changed
if (update != null && !update.closed()) {
    for (int i : update.slots()) show(i, update.menu().slot(i).stack());   // the first update has every slot
    if (update.carried()) showCursor(update.menu().carried());
}

CgContainers.Menu menu = mirror.menu();
menu.type();                                      // "minecraft:furnace"
menu.state().getInt("burn");                      // 0-1000
CgContainers.act(menu.id(), new Action.Click(20, 0, ClickType.QUICK_MOVE));
```

Without a game (a test, the harness) `present()` is false, there is no menu and an action does nothing.

## 2. The menu

| | |
|---|---|
| `id()` | the window id; `0` is the player's own inventory |
| `type()` | `minecraft:furnace`, `minecraft:generic_9x3`, `minecraft:player`…; `unknown` before 1.14.3 for what is not a furnace, chest or the player's |
| `title()` | the container's name as the player reads it; empty when the game opened no screen of it |
| `slots()` | by the game's own index: the container's own first, the player's after. The player's own menu is 0 result, 1-4 crafting, 5-8 armour, 9-35 main, 36-44 hotbar, 45 offhand |
| `carried()` | what the cursor holds |
| `state()` | the menu's own values |

A slot is `{index, stack, layout, canTake}`; `layout` holds where it is in the picture (`x`, `y` in GUI units), which
container holds it and its index there, its `kind` and its `maxStack`. A `RESULT` takes nothing.

## 3. State, by kind

`MenuTypes.support(type)` says, for every kind of 26.3: `PLAIN` (slots are all there is), `STATE`, `UNSUPPORTED` (with
`reason(type)`) or `UNKNOWN` (a mod's: slots and actions work).

| Kind | Keys |
|---|---|
| furnace, blast_furnace, smoker | `burn`, `cook`: 0-1000 |
| brewing_stand | `brew` (ticks left, 0-400), `fuel` (0-20) |
| enchantment | `costs`, `clue`, `level`: int arrays of three; `seed` |
| anvil | `cost` |
| beacon | `levels`, `primary`, `secondary`: an effect id or empty |
| stonecutter | `selected` (-1 none), `count` |
| lectern | `page` |
| merchant | `level`, `xp`, `offers`: a list of `{a, b?, result, uses, max}`, stacks as `CgItemStack.CODEC` |
| loom, crafter_3x3 | unsupported: the selected pattern and offered patterns; the disabled slots and powered flag |

Modern 1.14.3 and later read all of it. 1.8.9 to 1.12.2 and 1.7.10 read the furnace's only.

## 4. Actions

| Action | Does | Where |
|---|---|---|
| `Click(slot, button, type)` | pick up, shift-click, swap, clone, throw, drag stages, double-click | every version |
| `Button(id)` | enchanting option, stonecutter recipe, lectern page, beacon confirm | every version (1.7.10 to 1.12.2: the enchanting table's) |
| `Rename(name)` | the anvil's name field | modern 1.14.3+ |
| `SelectTrade(index)` | a merchant's offer | modern 1.14.3+ |
| `Close()` | closes as the player does, telling the server | every version |

`act` answers `false` for one the version cannot carry; `CgContainers.capabilities()` says up front. A drag is three
clicks: `Click.dragStart(button)`, one `Click.dragAdd(slot, button)` per slot, `Click.dragEnd(button)`.

## 5. How each version is read

| | Modern 1.13.2–26.3 (`ContainersModern`) | Legacy 1.8.9–1.12.2 (`ContainersLegacy`) | 1.7.10 (`Containers1710`) |
|---|---|---|---|
| Change check | `CgItems.stampOf` of each slot plus its count: no copy | the same | the same |
| Click | `handleInventoryMouseClick` / `handleContainerInput` (26.1) by name | `windowClick`, an int to 1.8.9 | `windowClick`, an int |
| Button | `handleInventoryButtonClick` | `sendEnchantPacket` | `sendEnchantPacket` |
| Kind of a menu | its `MenuType`'s registry name | named from the class | named from the class |
| Kind of a slot | the slot's container (`Inventory` by index, `ResultContainer`, result slots) | `InventoryPlayer` by index, `InventoryCraftResult`, `SlotCrafting`, `SlotFurnaceOutput` | the same, `SlotFurnace` |
| Title | the open screen's | the first slot's inventory name | the same, translated |

## Open

- **A change is seen on the next poll**, not when the game sets the slot: no `setItem` hook yet.
- **No claim before vanilla's screen**: the player sees vanilla's container screen unless a mod opens ours.
- **Recipe placement** (`handlePlaceRecipe`) is not carried: it takes a recipe holder or a display id the data has no form for.
- **An item's own max stack size** is not in `CgItemStack`; a drag preview uses the slot's.
