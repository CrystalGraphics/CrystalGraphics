# The VFX wiki

How to make effects with CrystalGraphics' VFX engine, grouped by what you are trying to do. It is being written as
the engine moves toward Unreal's (Niagara's) model; what it does not cover yet is still in [`../VFX.md`](../VFX.md).

| Page | For |
|---|---|
| [`assets.md`](assets.md) | the files an effect is made of: kinds, ids, values, inheritance, editing and undo, validation |
| [`effects.md`](effects.md) | how a layer draws: its blend, two-sidedness, depth and clip, and where it draws among the rest |
| [`particles.md`](particles.md) | emitters and their particles: when they spawn (loops, rates, bursts), turning them with their effect, stopping and killing them |
| [`materials.md`](materials.md) | a particle's material: the vertex and particle it reads on a sprite or a mesh, Unreal's expressions |
| [`prefabs.md`](prefabs.md) | models made of parts: a node tree an effect plays, moving and fading its nodes |
| [`sequences.md`](sequences.md) | a clip on every particle: a prefab moving over frames, events at its nodes, children's children |
| [`camera.md`](camera.md) | the viewer's camera: rigs blending in and out, modifiers, a camera animation from a sequence |
| [`networking.md`](networking.md) | gameplay tags: names a server and its clients agree on, parent matching, tag sets |
| [`performance.md`](performance.md) | what a particle frame costs: pools, and the shapes that share one |

Each page is written for someone who has never used the engine: every example is complete and checked against the
code. Where the engine follows an Unreal feature, the page names it, so Niagara's documentation reads across.
