# mc — Minecraft's own concepts, as plain Java

`com.crystalgraphics.mc` holds what is specific to Minecraft's game, not to a renderer or a codec. **A new Minecraft concept
(an item, an entity, a block, a container) gets its own sub-package here**, never a class in `serialization/` or the
platform root (the user, 2026-11-14). Each is plain Java the same on every version, with the game's own objects crossing
only through a `Host` slot an era fills.

| Package | Owns |
|---|---|
| `mc` | The reload path: `CgAssetReloader`, `CgHotReload`, `CgReloadable`, `CgReloadListener` |
| `mc.item` | `CgItemStack` (item, count and all its data as modern's components; facets and `with*` edits; a codec), `CgItems` (the `HOST` slot, `of`/`toHost`/`stampOf`), and `ItemShapes`, `TextComponents`: each era's saved stack projected into components and back, pure on tags. Guide: [`docs/ITEMS.md`](../../../../../../docs/ITEMS.md) |
| `mc.render` | Client only. `CgHostPicture` (a picture Minecraft draws: sizes, state, refresh), `CgHostPictures` (the draw point, its `HOST` slot, budget, isolation, statistics), `CgPictureRefresh`, `CgPictureLighting`, `CgStandInPictures` (the host where no Minecraft runs); `.custom`: `CgCustomPicture`, `CgPictureRenderer`, `CgHostDrawContext`; `.item`: `CgItemIcons` (the icon atlas's draw point, its `HOST` slot, `prewarm`, statistics), `CgItemIcon`, `CgStandInItemIcons`; `.atlas`: `CgIconAtlas` (one size's grid page; internal). Each era's hosts are `PicturesModern` and `ItemIconsModern`, `PicturesLegacy` and `ItemIconsLegacy`, `Pictures1710` and `ItemIcons1710`. Guide: [`docs/MINECRAFT_OBJECTS.md`](../../../../../../docs/MINECRAFT_OBJECTS.md) |
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
