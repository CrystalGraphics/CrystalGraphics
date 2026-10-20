# asset — the format's files, trees and kinds

An asset is one YAML file (`docs/vfx/assets.md`), read into a plain tree, resolved over its parent into a `CgAssetData`,
edited there with undo, validated, and written back by one writer. The 20 kinds are `kind/`, with its own guide.

| Package | Owns |
|---|---|
| `codec` | `CgAssetYaml` (a file's text and tree: comments into `editor` on read, the writer's rules on write; a port of the format's reference writer, byte for byte), `CgAssetComments` (where each comment goes), `CgAssetBinary` (the cooked form: the tree less its `editor` blocks), `CgAssetVersions` (a kind's `_v` steps) |
| `tree` | `CgAssetData` (a file resolved over its parent: maps merge by key, id lists by `id`, `after`/`before`, `remove`; its `delta()` is the file; `apply(CgEdit)` edits it and answers the inverse; `validate(usecase)` checks it), `CgEdit`, `CgChange`, `CgAssetChanged`, `CgAssetNode`, `CgOrigin` (where a value comes from), `CgShape` (what a declaration says of a value: its key order, its children's types, whether it is an id list), `CgAssetException`; `CgAssetValidation`, the declared checks behind `validate` |
| `kind` | the 20 kinds, their structs and families, and the value forms' shapes (`CgCommonStructs.VALUE_FORMS`, `CURVE`) |

## Rules

- **One writer.** Every file the engine writes goes through `CgAssetYaml.write`; the same tree always gives the same
  bytes, and the bytes are the reference writer's. A change to the rules changes both writers together.
- **Order is the declaration's.** A key the declaration does not order follows in the order held: only name-keyed maps
  (`user`, `values`, `inputs`) and open structs' own keys. A dispatch member is its `id`, the family's key, then its
  fields.
- **A value form is a value only where the declaration expects one**: `{ range: [...] }` under a number replaces
  whole, while a kind's top-level `curves:` is a key like any other.
- **The file is the delta.** `CgAssetData.delta()` writes exactly what the child's file says, overrides kept even where
  they equal the parent's; opening never resaves.
- **An edit changes the file, never the resolved tree**, then re-resolves; its inverse restores the file to the byte.
- **Validation reads the resolved asset**: a child is valid only with what it inherits. An error refuses it at load
  and fails a cook; `unsupported` entries warn and are skipped.
- Gates: `CgAssetYamlTest`, `CgAssetDataTest`, `CgAssetEditTest`, `CgAssetValidationTest`; the kits' round trip,
  random-edit undo and validation are `CgAssetRoundTripGateTest`, run with `-Pkits=<namespace>=<dir>;...`.
