# mc — Minecraft's own concepts, as plain Java

`com.crystalgraphics.mc` holds what is specific to Minecraft's game, not to a renderer or a codec. **A new Minecraft concept
(an item, an entity, a block, a container) gets its own sub-package here**, never a class in `serialization/` or the
platform root (the user, 2026-11-14). Each is plain Java the same on every version, with the game's own objects crossing
only through a `Host` slot an era fills.

| Package | Owns |
|---|---|
| `mc` | The reload path: `CgAssetReloader`, `CgHotReload`, `CgReloadable`, `CgReloadListener` |
| `mc.item` | `CgItemStack` (item, count and all its data as modern's components; facets and `with*` edits; a codec), `CgItems` (the `HOST` slot, `of`/`toHost`/`stampOf`), and `ItemShapes`, `TextComponents`: each era's saved stack projected into components and back, pure on tags. Guide: [`docs/ITEMS.md`](../../../../../../docs/ITEMS.md) |
| `mc.container` | `CgContainers` (the player's open menu as data and clicks through the game's own call); built on `CgItemStack` |

## Rules

- **A value never holds the game's object.** `CgItemStack.of(host)` copies, `toHost()` builds; a cheap change check is
  `CgItems.stampOf(host)`, the game's own hash.
- **An era's host does one small thing**: save a stack in its version's shape and load it back (`ItemsModern`,
  `ItemsLegacy`, `Items1710`). The mapping between shapes is `ItemShapes`, tested headless with a fake host.
- **A key an old tag has no component for stays in `minecraft:custom_data`**, so a round trip loses none.

---

Also loaded with this folder:

@../../../../../../docs/ITEMS.md
