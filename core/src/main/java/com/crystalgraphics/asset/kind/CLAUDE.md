# asset.kind — the format's 20 kinds

Every kind of the format (`docs/vfx/assets.md` § The kinds), its structs, elements and dispatch families, declared once
against `object`'s model. `CgAssetKinds.register()` declares them all; `all()` and `byFolder()` read them.

| Class | Declares |
|---|---|
| `CgParticleKinds` | `system`, `emitter`, `module`, `effect_type`, `ending`; renderers (`RENDERERS`) |
| `CgLookKinds` | `material`, `texture`, `mesh`, `parameters`, `curve` |
| `CgBodyKinds` | `prefab`, `skeleton`, `springs`, `rig`, `retargeter`; retarget ops (`OPS`) |
| `CgSequenceKinds` | `sequence`; tracks (`TRACKS`), each with its section |
| `CgCameraKinds` | `camera_rig`, `shake`; camera nodes (`NODES`), blends, shake patterns, and the post block (`POST`) |
| `CgGameplayKinds` | `cue` and its effects block |
| `CgFieldKinds` | `field`; graph nodes (`NODES`) |
| `CgCommonStructs` | what several kinds share: placement, binding, condition, socket, mesh slot, transform, input declaration |
| `CgKitFiles` | the files at a kit's root that are not assets: `subtitle_style.yaml` (`SUBTITLE_STYLE`) |
| `CgModuleTypes` | the built-in modules, read from the shipped `vfx/modules.yaml` (a byte copy of the format's) into a struct of inputs each |

**The bible gate** is `CgBibleGateTest` (`:core:headlessTest`): every declaration against the format's JSON schemas both
ways, and the shipped `modules.yaml` against the format's. It reads them from `-Dcrystalgraphics.spec`, which the build
sets only where the private plan checkout sits beside this one, and skips without it.

## Rules

- **The schemas are the bible**: keys, order, required, enums. A struct they leave open takes its Unreal class's
  `UPROPERTY`s, snake-cased. A mismatch is this package's bug; a disagreement in the format goes to its designer.
- **The declarations order exactly what the schemas order**: where a schema is open, the reference writer keeps read
  order, so a declared order there makes the two writers disagree. The gate reports it.
- **Public**: the 20 kinds, the families a mod extends, and the shared post block, placement, condition, binding
  and input declaration. Every other struct is package-private: reach it through its kind's properties.
- **Nothing is guessed**: a type the format and Unreal leave open is `VALUE`; a subtree kept as written (`editor`,
  `unsupported`, an event's `args`) is `TREE`.
