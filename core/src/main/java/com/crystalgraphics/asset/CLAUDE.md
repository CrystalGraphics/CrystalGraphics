# asset — the format's files, trees and kinds

An asset is one YAML file (`docs/vfx/assets.md`), read into a plain tree, resolved over its parent into a `CgAssetData`,
edited there with undo, validated, and written back by one writer. The 20 kinds are `kind/`, with its own guide.

| Package | Owns |
|---|---|
| `codec` | `CgAssetYaml` (a file's text and tree: comments into `editor` on read, the writer's rules on write; a port of the format's reference writer, byte for byte), `CgAssetComments` (where each comment goes), `CgAssetBinary` (the cooked form: the tree less its `editor` blocks), `CgAssetVersions` (a kind's `_v` steps) |
| `tree` | `CgAssetData` (a file resolved over its parent: maps merge by key, id lists by `id`, `after`/`before`, `remove`; its `delta()` is the file; `apply(CgEdit)` edits it and answers the inverse; `validate(usecase)` checks it), `CgEdit`, `CgChange`, `CgAssetChanged`, `CgAssetNode`, `CgOrigin` (where a value comes from), `CgShape` (what a declaration says of a value: its key order, its children's types, whether it is an id list), `CgAssetException`; `CgAssetValidation`, the declared checks behind `validate` |
| `registry` | `CgAssetRegistry` (every asset by kind and id from folder listings and builders, redirects, the content index, a namespace's hash; Unreal's `IAssetRegistry`), `CgAssetEntry`, `CgAssetSource`, `CgAssetRegistryEvent` |
| `load` | `CgAssetPipeline` (assets to `COMPILED` on workers on its own `CgJobLoop`, published at `drain`: read, merge, validate, compile after hard dependencies; Unreal's `FStreamableManager`), `CgAssetLoad`, `CgAssetState`, `CgAssetCompiler` (a kind's compiler; the resolved asset until it has one) |
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
- **An id is unique within its kind**, never across kinds: a cue and the sequence it plays may share one, since a
  reference's field declares the kind. Key anything by kind and id; a binary's id (folder and extension kept) alone.
- **The registry reads listings, not files**: only a file small enough to be a redirect is opened at scan.
  `registerEngine()` registers the engine's own assets (its built-in modules) as code with their definitions.
- **A load's state changes only on its pipeline's loop**: workers parse, merge, validate and compile; `drain`
  publishes. Hard references (`CgAssetReferences`) compile first; a failure fails its referrers, naming it.
- **Validation reads the resolved asset**: a child is valid only with what it inherits. An error refuses it at load
  and fails a cook; `unsupported` entries warn and are skipped.
- Gates: `CgAssetYamlTest`, `CgAssetDataTest`, `CgAssetEditTest`, `CgAssetValidationTest`, `CgAssetRegistryTest`,
  `CgAssetPipelineTest`; the kits' round trip, random-edit undo, registration, validation and loading are `CgAssetRoundTripGateTest`, run with `-Pkits=<namespace>=<dir>;...`.
