# tag — gameplay tags

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../AGENTS.md)

Hierarchical names (`mod:a.b.c`), matched by ancestry, with the index table a server and its clients agree on.
Usage: `docs/vfx/networking.md`. Mirrors Unreal's `UGameplayTagsManager` and `FGameplayTagContainer`.

| Type | Role |
|---|---|
| `CgGameplayTag` | One interned name; `matches` is a bit test against its ancestors |
| `CgTagRegistry` | Interns tags (ancestors made too, names compare without case), reads `tags.yaml`, builds the net table |
| `CgTagContainer` | Explicit tags plus their ancestors as bit sets: `has*` as `FGameplayTagContainer` |
| `CgTagTable` | Every tag sorted by name, and a CRC-32 of the lowercase names |
| `CgTagLink` | One connection's agreement: wire index to local tag and back |
| `CgTagNet` | The server's table as a networked object, and the links on each side |

- **A tag's `index()` never goes on a wire**: it is registration order. The wire index is `CgTagLink`'s.
- **An index before the table is agreed throws.** `invalid()` is the table's size and always fits `bits()`; Unreal's
  `size + 1` does not at `2^k - 1` tags.
- **Headless**: no GL, no font. Tests: `CgTagsTest` (`:core:headlessTest`), `CgTagNetTest` (`:core:test`, on the rig).
