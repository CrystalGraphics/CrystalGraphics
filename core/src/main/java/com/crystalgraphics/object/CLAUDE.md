# object — types declared once

Unreal's reflection, cut to what the format needs: a type's properties declared once, read by the
codec, inheritance, validation, units, undo, the editor and replication. The spine: it names no asset kind;
`asset.kind` declares the 20 against it.

| Class | What it is |
|---|---|
| `CgType` | A kind, struct, element or dispatch family, its properties in file order; `CgType.Builder` declares one |
| `CgTypes` | The registry by id; `kind`, `struct`, `element`, `dispatch` start a declaration; `UNITS`, an asset's `units` block |
| `CgProperty` | One property: type, quantity, forms, default, required, flags, metadata (Unreal's `FProperty`) |
| `CgPropertyPath` | A node of an asset by name: map keys, element ids, list indices (Unreal's `FPropertyPath`) |
| `CgValueType` | A property's value type, `FLOAT` to `id-list<T>`, `dispatch<F>`, `ONE_OF`, `VALUE`, `SELF`, `NULL` |
| `CgPropertyFlag`, `CgPropertyMeta` | Unreal's `CPF_*` and `UM::*` metadata, the format's subset |
| `value.CgValue`, `CgValueForm`, `CgQuantity` | A value in one of the format's forms; the forms; what a number measures |
| `validate.CgValidation`, `CgIssue`, `CgValidator` | Unreal's `FDataValidationContext`, its issues, and `IsDataValid`: what a type checks of its own values |

## Rules

- **Native types by builder, never by annotations read through reflection**: the Java 8 copy carries builders
  unchanged, and registration costs nothing at run time.
- **Declaration order is file order** and a layout's slot order. A kind gets `_v`, `parent`, `units` first and
  `unsupported`, `editor`, `remove` last; an element gets `id` first, declares its own merge keys, and gets `editor`
  last unless it declares it.
- **Open types** (`open()`) take keys they do not declare, as values, after the declared ones in read order: a
  section's channels named by data, a node's own fields. Any other unknown key fails validation (`unknown_key`); an
  importer keeps what it cannot map in `unsupported`.
- **A check the declarations cannot state is a `validator(...)` on its type**, reporting at the path it is given;
  the declared checks (keys, required, shapes, enums, forms, ids) run first, in `asset.tree`.
- **A family member joins with `onRegister(t -> FAMILY.member(key, t))`**, before `CgTypes.freeze()`.
- **Headless**: nothing here draws; tests are `core/src/headlessTest/`.
