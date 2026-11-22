# mc — Minecraft's own concepts, as plain Java

`com.crystalgraphics.mc` holds what is specific to Minecraft's game, not to a renderer or a codec. **A new Minecraft concept
(an item, an entity, a block, a container) gets its own sub-package here**, never a class in `serialization/` or the
platform root (the user, 2026-11-14). Each is plain Java the same on every version, with the game's own objects crossing
only through a `Host` slot an era fills.

| Package | Owns |
|---|---|
| `mc` | The reload path: `CgAssetReloader`, `CgHotReload`, `CgReloadable`, `CgReloadListener`; `CgCommandReader`, the cursor every value's `parse` reads its command text with |
| `mc.item` | `CgItemStack` (item, count and all its data as modern's components; facets and `with*` edits; a codec), `CgIngredient` (a stack, alternatives or an item tag), `CgItems` (the `HOST` slot, `of`/`toHost`/`stampOf`/`tagItems`); `CgItemStack.parse` reads any era's `/give`, `toCommand` writes the running era's, and `ItemShapes`, `TextComponents`: each era's saved stack projected into components and back, pure on tags. Guide: [`docs/ITEMS.md`](../../../../../../docs/ITEMS.md) |
| `mc.block` | `CgBlockState` (a block, its properties, its block entity's data; `/setblock`'s text), `CgBlocks` (the `HOST` slot: `{Name, Properties}` saved and loaded; none on 1.7.10). Hosts `BlocksModern`, `BlocksLegacy`. Guide: [`docs/VALUES.md`](../../../../../../docs/VALUES.md) |
| `mc.entity` | `CgEntityData` (a type and `/summon`'s data, the game's saved `{id, ...}`), `CgEntityRef` (data, the player, a profile, a live entity's UUID) |
| `mc.fluid` | `CgFluidStack` (a fluid, droplets kept exact, its data), `CgFluids` (client: name, still sprite, tint; hosts `FluidsForge`, `FluidsNeoForge`, `FluidsFabric`, `FluidsLegacy`, `Fluids1710`, sharing `FluidsModern`) |
| `mc.level` | `CgBlockPos`, `CgDirection`, `CgBlockBox`: numbers and names, no host; `CgStructure` (blocks and entities of a structure: built, vanilla's structure NBT read and written, `load(id)` from `data/<ns>/structure(s)/`) |
| `mc.render` | Client only. `CgHostPicture` (a picture Minecraft draws: sizes, state, refresh), `CgHostPictures` (the draw point, its `HOST` slot, budget, isolation, statistics), `CgPictureRefresh`, `CgPictureLighting`, `CgStandInPictures` (the host where no Minecraft runs); `.custom`: `CgCustomPicture`, `CgPictureRenderer`, `CgHostDrawContext`; `.item`: `CgItemIcons` (the icon atlas's draw point, its `HOST` slot, `prewarm`, statistics), `CgItemIcon`, `CgStandInItemIcons`; `.atlas`: `CgIconAtlas` (one size's grid page, icons or overlays; internal); `.tooltip`: `CgTooltipPicture`, `CgItemTooltip`, `CgTooltips` (its `HOST` slot), `CgStandInTooltips`; `.entity`: `CgEntityPicture`, `CgEntityPose`, `CgEntityPictures` (its `HOST` slot), `CgStandInEntityPictures`; `.block`: `CgBlockPicture`, `CgBlockPictures` (its `HOST` slot), `CgStandInBlockPictures`; `.world`: `CgHostWorld` (the scene's draw point and its `HOST` slot), `CgItemStackComponent`, `CgChildEntityComponent`, `CgBlockStateComponent`, `CgItemDisplay`, `CgSequenceEntity` (the `entity` spawnable), `CgPocketLevel` (a client-only level of your blocks Minecraft draws under a scene; its `HOST` slot, `Host.absent(reason)` for a node without one; made on any thread, Minecraft's copy at the next draw point), `CgLevelPicture` (that level alone as a picture), `CgStandInPocketLevels` (the host where no Minecraft runs: shaded faces of the engine's own). Each era's hosts are `PicturesModern` and `ItemIconsModern`, `PicturesLegacy` and `ItemIconsLegacy`, `Pictures1710` and `ItemIcons1710`; tooltips are `TooltipsModern`, `TooltipsLegacy`, `Tooltips1710`; entities and blocks `EntityPicturesModern` and `BlockPicturesModern`, `EntityPicturesLegacy` and `BlockPicturesLegacy`, `EntityPictures1710`; the scene's `HostWorldModern` (submitted from 1.21.10 by `WorldSubmitHook`), `HostWorld114`, `HostWorldLegacy`, `HostWorld1710`; pocket levels `PocketLevelsModern` (1.15-26.3), `PocketLevels114`, `PocketLevelsLegacy`, none on 1.13.2 and 1.7.10. Guide: [`docs/MINECRAFT_OBJECTS.md`](../../../../../../docs/MINECRAFT_OBJECTS.md) |
| `mc.container` | `CgContainers` (the `HOST` slot, `mirror()`, `act`, the slot and menu records), `ContainerMirror` (deltas: the changed slots, cursor and state), `Action`, `ClickType`, `SlotKind`, `Capabilities`, `MenuTypes` (every 26.3 kind: plain, state or unsupported); built on `CgItemStack`. Guide: [`docs/CONTAINERS.md`](../../../../../../docs/CONTAINERS.md) |
| `mc.model` | Minecraft's own entity models posed by the engine: `CgVanillaHumanoid` (the humanoid as a skeleton at the game's pivots, its default rig, `parts` writing a pose as the game's part fields), `CgVanillaPoses` (an entity's animation instance; `parts(id, out)` for a host's model setup, `posing(id)` and `instance(id)`, `hosted()` once a host applies them). Each era's `VanillaPoseHook` applies them right after the game's own setup: the living renderer's to 1.21.8, each submitted model's from 1.21.10 (`VanillaPosesModern`), `ModelBiped.render` after its angles on legacy and 1.7.10 (`VanillaPosesLegacy`, `VanillaPoses1710`). Gate: `CgVanillaHumanoidTest` (headless) |

## Rules

- **A value never holds the game's object.** `CgItemStack.of(host)` copies, `toHost()` builds; a cheap change check is
  `CgItems.stampOf(host)`, the game's own hash.
- **An era's host does one small thing**: save a stack in its version's shape and load it back (`ItemsModern`,
  `ItemsLegacy`, `Items1710`). The mapping between shapes is `ItemShapes`, tested headless with a fake host.
- **A key an old tag has no component for stays in `minecraft:custom_data`**, so a round trip loses none.

---

Also loaded with this folder:

@../../../../../../docs/ITEMS.md

@../../../../../../docs/CONTAINERS.md
