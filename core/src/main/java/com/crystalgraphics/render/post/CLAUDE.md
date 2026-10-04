# render/post — the post-processing stack

> Root guide: [`CrystalGraphics/docs/ENGINE_API.md`](../../../../../../../../docs/ENGINE_API.md) § *The post stack*.
> Plan: `plan/crystalgraphics/render-post-process.md`.

What runs after the world renderer has drawn the scene, on the same `WORLD_TRANSPARENT` firing: effects at named
points, and one composite pass laying every screen-wide look over the target, as Unreal's tonemapper, URP's UberPost
and Godot's tonemap pass compose bloom.

```
CgWorldRenderer (ORDER 1000)  draws the scene, then the emission target ──► frame.resources(): EMISSION
CgPostStack     (ORDER 2000)
  AFTER_WORLD       effects that read the scene
  BEFORE_COMPOSITE  effects that make the composite's inputs: bloom's chain
  composite         one full-screen pass: every active CgCompositeFeature
  AFTER_COMPOSITE   effects over the final picture
```

| Package | Holds |
|---|---|
| (root) | `CgPostStack` (the singleton: registration, the effect list, recording), `CgPostEffect` (the SPI the engine's and a mod's effects implement), `CgPostPoint`, `CgPostContext` (what an effect records with) |
| `bloom` | `CgBloom`: the built-in bloom and its settings. `CgBloomChain`: its chain as raster passes, CoD's scheme (13-tap down with Karis on the first step, tent up), each level drawn through `CgGraphTexture.level(k)`; each level's share of the glow is set in the upsample's blend (`ONE, SRC_ALPHA`: keep `WEIGHTS[k]` of the level, add the tent below), so level 0 holds the whole glow and the composite reads it once |
| `composite` | `CgPostComposite` (the one pass, `shaders/post/composite.shader`), `CgCompositeFeature` (each look, a keyword) |

Shaders: `shaders/post/` — `composite.shader`; bloom's passes under `shaders/post/bloom/` (`down`, `up`), their
filters in `shaders/lib/post/bloom.glsl`.

## Rules

- **The world renderer produces, the post stack consumes.** No post pass is recorded from `render/world`, and no
  scene draw from `render/post`. What one hands the other goes through `CgStageFrame.resources()`
  (`CgFrameKeys`), never a field read across classes.
- **One composite pass a firing.** A new screen-wide look is a `CgCompositeFeature`, or an effect at a point; never a
  second full-screen add.
- **A mod's effect cannot join the composite** (Unreal's blendables run as their own passes too): it records at a point.
- **Nothing is asked of the world renderer.** With bloom off, nothing reads the emission target and the graph culls
  its pass; there is no flag between the two.
- **Recording allocates nothing**: the effect list is an array snapshot, replaced whole by `add` and removal.
