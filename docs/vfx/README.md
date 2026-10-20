# The VFX wiki

How to make effects with CrystalGraphics' VFX engine, grouped by what you are trying to do. It is being written as
the engine moves toward Unreal's (Niagara's) model; what it does not cover yet is still in [`../VFX.md`](../VFX.md).

| Page | For |
|---|---|
| [`assets.md`](assets.md) | the files an effect is made of: kinds, ids, values, inheritance, editing and undo in Java |
| [`effects.md`](effects.md) | how a layer draws: its blend, two-sidedness, depth and clip, and where it draws among the rest |
| [`particles.md`](particles.md) | emitters and their particles: when they spawn (loops, rates, bursts), turning them with their effect, stopping and killing them |
| [`prefabs.md`](prefabs.md) | models made of parts: a node tree an effect plays, moving and fading its nodes |
| [`sequences.md`](sequences.md) | a clip on every particle: a prefab moving over frames, events at its nodes, children's children |

Each page is written for someone who has never used the engine: every example is complete and checked against the
code. Where the engine follows an Unreal feature, the page names it, so Niagara's documentation reads across.
