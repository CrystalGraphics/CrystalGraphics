# object — types declared once

Unreal's reflection, cut to what the format needs (engine blueprint 01): a type's properties declared once, read by
the codec, inheritance, validation, units, undo, the editor and replication. The spine: it names no asset kind;
`asset.kind` declares the 20 against it.

| Class | What it is |
|---|---|
| `CgType` | A kind, struct, element or dispatch family, its properties in file order; `CgType.Builder` declares one |
| `CgTypes` | The registry by id; `kind`, `struct`, `element`, `dispatch` start a declaration; `UNITS` (`core.md` § Units) |
| `CgProperty` | One property: type, quantity, forms, default, required, flags, metadata (Unreal's `FProperty`) |
| `CgValueType` | A property's value type, `FLOAT` to `id-list<T>`, `dispatch<F>`, `ONE_OF`, `VALUE`, `SELF` |
| `CgPropertyFlag`, `CgPropertyMeta` | Unreal's `CPF_*` and `UM::*` metadata, the format's subset |
| `value.CgValue`, `CgValueForm`, `CgQuantity` | A value in one of `core.md`'s forms; the forms; what a number measures |

## Rules

- **Native types by builder, never by annotations read through reflection** (01 §3.2): the Java 8 copy carries
  builders unchanged, and registration costs nothing at run time.
- **Declaration order is file order** and a layout's slot order. A kind gets `_v`, `parent`, `units` first and
  `unsupported`, `editor`, `remove` last; an element gets `id` first, declares its own merge keys, and gets `editor` last unless it declares it.
- **Open types** (`open()`) take keys they do not declare, as values, after the declared ones in read order: the
  schema's `additionalProperties: true` (a section's channels, a node's own fields). Anything else unknown is
  `unsupported`.
- **A family member joins with `onRegister(t -> FAMILY.member(key, t))`**, before `CgTypes.freeze()`.
- **Headless**: nothing here draws; tests are `core/src/headlessTest/`.
