# asset.kind — the format's 20 kinds

Every kind of `spec/README.md` § Kinds, its structs, elements and dispatch families, declared once against
`object`'s model (engine blueprint 01, O1). `CgAssetKinds.register()` declares them all; `all()` and `byFolder()` read
them.

| Class | Declares |
|---|---|
| `CgParticleKinds` | `system`, `emitter`, `module`, `effect_type`, `ending`; renderers (`RENDERERS`) |
| `CgLookKinds` | `material`, `texture`, `mesh`, `parameters`, `curve` |
| `CgBodyKinds` | `prefab`, `skeleton`, `springs`, `rig`, `retargeter`; retarget ops (`OPS`) |
| `CgSequenceKinds` | `sequence`; tracks (`TRACKS`), each with its section |
| `CgCameraKinds` | `camera_rig`, `shake`; camera nodes (`NODES`), blends, shake patterns, and the post block (`POST`) |
| `CgGameplayKinds` | `cue` and its effects block |
| `CgFieldKinds` | `field`; graph nodes (`NODES`) |
| `CgCommonStructs` | what several kinds share: placement, binding, condition, mesh slot, transform, input declaration |

## Rules

- **The spec is the bible**: `spec/schema/*.schema.json` (keys, order, required, enums), then the prose, then
  `aaa-design` §2.48–2.50 (an open struct's fields are its Unreal class's `UPROPERTY`s, snake-cased, the prose's
  name where it has one). A mismatch is this package's bug, never the spec's; a disagreement in the spec goes to REA.
- **Public**: the 20 kinds, the families a mod extends, and the shared post block, placement, condition, binding
  and input declaration. Every other struct is package-private: reach it through its kind's properties.
- **Nothing is guessed**: a type the spec and Unreal leave open is `VALUE`; a subtree kept as written (`editor`,
  `unsupported`, an event's `args`) is `TREE`.
