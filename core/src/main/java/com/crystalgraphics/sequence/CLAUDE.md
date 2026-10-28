# sequence — Unreal's Sequencer and a montage's sections

Plays `sequence` assets: a world's `CgSequencer` evaluates every `CgSequencePlayer` at once, one evaluator a track
kind, in Unreal's phases (advance, spawn, import, evaluate, blend, events). **Using it**: `docs/vfx/sequences.md`.

## Where things go

The root holds only what coordinates the subpackages. A new class goes in the subpackage of its concern, never the
root; a new track kind is a subpackage of its own when it brings types besides its evaluator.

| Package | Owns |
|---|---|
| `sequence` | `CgSequencer` (the world's linker and its phases), `CgSequencePlayer` (one play: its clock, roles, spawnables, montage sections, seek), `CgSequenceEvaluator` (a kind's work: the SPI), `CgSequenceTarget` (what a track writes: named slots) |
| `sequence.definition` | The compiled sequence, read-only once built: `CgSequenceDefinition`, its compiler `CgSequenceCompiler`, `CgSequenceTrack`, `CgSequenceSection`, `CgSequenceChannel` (keys as columns), `CgSequenceBinding`, `CgSequenceCondition`, `CgSequenceField` (what is active over which ticks) |
| `sequence.time` | Ticks and sub-frames, frame rates, the playback position and each update's evaluation range |
| `sequence.state` | The state kinds (`transform`, `parameter`, `material`, `property`, `visibility`, `global`): `CgSequenceStateEvaluator`, `CgSequenceBlender` (blend by type and weight, pre-animated state restored), `CgSequenceGlobals` |
| `sequence.binding` | What tracks bind to and place by: `CgSequenceRole` (a host's character: its `character()` and `variants()` for conditions), `CgRoleOutline` (its outline colour slot), `CgSequenceLocator`, `CgSequencePlacement` (an `at` resolved each frame) |
| `sequence.condition` | What answers a section's `condition`: `CgConditionKind` (a kind's reader), `CgConditionKinds` (the registry; `character` and `variant` built in). The player tests a section's condition at its first entry and keeps the answer for the play; a server plays no conditional section |
| `sequence.spawn` | Spawnables: `CgSequenceSpawner` (a kind's maker), `CgSequenceSpawned`, and the `system` kind: `CgSequenceSystem`, `CgSequenceSystemEvaluator` |
| `sequence.animation` | The `animation` kind: `CgSequenceAnimationEvaluator`, `CgSequenceSkeletal` (a target playing clips) |
| `sequence.event` | The `event` kind and triggered sections: `CgSequenceEventEvaluator`, `CgSequenceEventListener` (sides `rpg`, `ours`), `CgEventChannel` (the sweep), `CgEventTrigger` |
| `sequence.notify` | Notify kinds a mod registers: `CgNotifyKind`, `CgNotifyContext`, `CgNotifyEvaluator` |

## Rules

- **Evaluators allocate nothing per frame** and run on the frame thread; a kind's state is bound once
  (`CgSequenceEvaluator.bind`, `player.data`).
- **The definition is shared and never changed by playing**: per-play state lives in the player (montage links,
  triggers, cursors) or an evaluator's bound data.
- **What runs on a dedicated server**: the clock, sections, `rpg` events; nothing that draws (`CgSequencer.server()`).
  The whole package's tests are headless (`core/src/headlessTest`), the `animation` kind's aside.
- **Unreal decides behaviour**: each evaluator's javadoc cites the source file it follows.
