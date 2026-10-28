# asset — the format's files, trees and kinds

An asset is one YAML file (`docs/vfx/assets.md`), read into a plain tree, resolved over its parent into a `CgAssetData`,
edited there with undo, validated, and written back by one writer. The 20 kinds are `kind/`, with its own guide.

| Package | Owns |
|---|---|
| `codec` | `CgAssetYaml` (a file's text and tree: comments into `editor` on read, the writer's rules on write; a port of the format's reference writer, byte for byte), `CgAssetComments` (where each comment goes), `CgAssetBinary` (the cooked form: the tree less its `editor` blocks), `CgAssetVersions` (a kind's `_v` steps) |
| `tree` | `CgAssetData` (a file resolved over its parent: maps merge by key, id lists by `id`, `after`/`before`, `remove`; its `delta()` is the file; `apply(CgEdit)` edits it and answers the inverse; `validate(usecase)` checks it), `CgEdit`, `CgChange`, `CgAssetChanged`, `CgAssetNode`, `CgOrigin` (where a value comes from), `CgShape` (what a declaration says of a value: its key order, its children's types, whether it is an id list), `CgAssetException`; `CgAssetValidation`, the declared checks behind `validate` |
| `registry` | `CgAssetRegistry` (every asset by kind and id from folders and builders, each file's redirect and references, referencers, the content index, a namespace's hash; Unreal's `IAssetRegistry` and its gatherer), `CgAssetEntry`, `CgAssetSource`, `CgAssetRegistryEvent`; `CgAssetRegistryCache`, what a scan read, by modification time |
| `load` | `CgAssetPipeline` (assets to `COMPILED` on workers on its own `CgJobLoop`, published at `drain`: read, merge, validate, compile after hard dependencies; Unreal's `FStreamableManager`), `CgAssetLoad`, `CgAssetState`, `CgAssetCompiler` (a kind's compiler; the resolved asset until it has one); `CgVfxAssets` (the process's assets: refs, bundle loads to `READY`, prepare in budget, `edit`, saved files, `publish`), `CgAssetRef` (an asset by kind and id holding its published definition and generation; `get` sync-loads, counted), `CgLoadHandle` and `CgLoadPriority` (a bundle's load, its readiness and progress by bytes), `CgAssetPreparer` (a kind's GPU work: uploads, warm steps, particle peaks), `CgPrepareBudget` (a frame's upload bytes and staging chunk), `CgPreparePlan` (one asset's steps), `CgAssetFollower` (a playing instance's definition: adopt a `VALUE` swap or restart on a `STRUCTURAL` one), `CgAssetFileDiff` (a saved file as the edits an editor makes) |
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
- **A scan reads each file once, on the workers, for its own references** (`CgAssetReferences.ofFile`, never
  resolved), its redirect and its kind's `SEARCHABLE` values (`entry.searchable()`, Unreal's asset registry tags: a
  cue's `tag`), or takes them from the cache while the file's modification time holds. It adds all its
  entries or none, then announces them: the batch, each alone, `SCANNED`. `registerEngine()` registers the engine's
  own assets (its built-in modules) as code with their definitions.
- **A load's state changes only on its pipeline's loop**: workers parse, merge, validate and compile; `drain`
  publishes. Hard references (`CgAssetReferences`) compile first; a failure fails its referrers, naming it.
- **Validation reads the resolved asset**: a child is valid only with what it inherits. Its own checks run first (the
  declared ones, then its types' validators); registered `CgAssetValidator`s only if those passed. An error refuses
  it at load and fails a cook; `unsupported` entries warn and are skipped.
- **Definitions change only at the frame boundary** (`frame.CgFrameBoundary`): `drain` then `publish`, never inside a
  stage firing, so every firing of a frame reads one generation. A change that no longer decodes, resolves, validates
  or compiles keeps the last good definition.
- **Nothing uploads or compiles on first use**: request ahead; `CgAssetRef.get` before a load is counted
  (`asset.sync-loads`) and refused inside a firing.
- Gates: `CgVfxAssetsTest`, `CgAssetBundleTest`, `CgAssetPublishTest`, `CgAssetHotReloadTest`, `CgAssetYamlTest`, `CgAssetDataTest`, `CgAssetEditTest`, `CgAssetValidationTest`, `CgAssetRegistryTest`,
  `CgAssetPipelineTest`; the kits' round trip, random-edit undo, registration, validation and loading are `CgAssetRoundTripGateTest`, run with `-Pkits=<namespace>=<dir>;...`.
