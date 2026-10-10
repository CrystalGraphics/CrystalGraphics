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
| `bloom` | `CgBloom`: the built-in bloom and its settings. `CgBloomChain`: its chain as raster passes, CoD's scheme (13-tap down with Karis on the first step, tent up), each level drawn through `CgGraphTexture.level(k)`; each level's share of the glow is set in the upsample's blend (`ONE, SRC_ALPHA`: keep `WEIGHTS[k]` of the level, add the tent below), so level 0 holds the whole glow and the composite reads it once. R11G11B10F; the top level at most 512 tall, an emission more than twice that halved first (`prefilter.shader`), the levels down to about 16 tall so the glow reaches as far at every resolution; Low's filters are `CHEAP` (4x4 box down, four taps up, 4 levels), High and up weigh the first step by Karis's average |
| `composite` | `CgPostComposite` (the one pass), `CgCompositeFeature` (each look, a keyword), `CgCompositeForm`: `BLEND` (`composite.shader`, `dst * (1 - a) + rgb`, no copy, adding in the target's encoding, its added term dithered by stochastic rounding) or `COPY` (`composite_copy.shader`, reads `cg_SceneColor`, composites in linear light, triangular dither), chosen each firing from what its inputs ask |
| `volume` | `CgPostSettings` (looks a volume overrides: bloom scale, flash in stops, vignette, chromatic, impact with its `CgImpactFrame` and seed, focus), `CgPostVolume` (everywhere, or a radius and blend distance round a point; priority; a weight an effect animates), `CgImpactFrame` (an impact frame's look as data: a picture-only kind, or drawn with paper, subject, focus lines, hatching, star, cross and jitter), `CgImpact` (its presets), `CgImpactSequence` (beats at 24 a second, a seed each). The stack blends every volume at the camera each firing into `CgPostContext.settings()`, a placed one's focus where it is on screen |
| `look` | `CgPostLooks`: the built-in effect at `BEFORE_COMPOSITE` turning the blended settings into composite inputs, flash and impact scaled by `CgGraphicsSettings.FLASHES`. A drawn impact frame puts `CgFrameKeys.SUBJECT_READ` in `demand()` and reads the world's `SUBJECT`, bent; its GLSL is `lib/post/impact.glsl`. Every look but bloom draws in the copy form until dual-source blending lands |
| `distortion` | `CgPostDistortion`: a side input bent by the firing's `CgFrameKeys.DISTORTION` field (`distortion_bend.shader`, GPU zone `post.distortionBend`), each input once a firing; reached through `CgPostContext.distorted`. The bend sums the field's layers in use through one `sampler2DArray`, so it binds two units however many hazes' slots there were |
| `debug` | `CgPostDebug`: `-Dcrystalgraphics.post.debug=emission\|level<N>\|overdraw\|distortion`, the emission, a chain level (`CgBloom.CHAIN`), the world renderer's overdraw count (`CgFrameKeys.OVERDRAW`, through a heat ramp) or its distortion offsets summed (`CgFrameKeys.DISTORTION`, `debug_distortion.shader`) drawn over the frame, last |

Shaders: `shaders/post/` — `composite.shader`, `composite_copy.shader`, `debug.shader`; bloom's passes under `shaders/post/bloom/` (`prefilter`, `down`, `up`), their
filters in `shaders/lib/post/bloom.glsl`.

**The HDR scene** (`CgWorldRenderer.hdrScene`): when the firing carries `CgFrameKeys.SCENE`, the stack hands the
composite the scene (`composite.scene`) and the stage its host target back (`retarget(null)`) before the composite
records. The composite then always draws, in the copy form with `SCENE`: it reads the scene rather than a copy of the
target, rolls a colour past white toward white keeping its hue (`c / peak` mixed toward white by
`(peak - 1) / (peak + 2)`, halfway at 4x white: a hot core with a coloured fringe, where a clamp per channel bands, and
a saturated glow at 2x still three quarters its colour), clamps, and writes a value within 0.1 of an 8-bit code back exact rather than dithered, so a pixel nothing drew
over returns byte for byte. `AFTER_WORLD` and `BEFORE_COMPOSITE` effects draw into the scene; `AFTER_COMPOSITE` into the
host. Bloom's source is then the scene's light past `CgBloom.threshold` (`excess.shader`: half size, R11G11B10F, each
texel's excess soft-capped at 32 before the 2x2 average), already bent, rather than the emission. A shader drawing into
the scene with linear colour is tagged `"ColorSpace" = "Linear"`, as every one here is, or the compiler decodes it.

## Rules

- **The world renderer produces, the post stack consumes.** No post pass is recorded from `render/world`, and no
  scene draw from `render/post`. What one hands the other goes through `CgStageFrame.resources()`
  (`CgFrameKeys`), never a field read across classes.
- **One composite pass a firing.** A new screen-wide look is a `CgCompositeFeature`, or an effect at a point; never a
  second full-screen add.
- **A mod's effect cannot join the composite** (Unreal's blendables run as their own passes too): it records at a point.
- **What the stack asks of the world renderer goes through the blackboard too**: at `DEMAND_ORDER`, before the world
  records, it blends the volumes and puts `CgFrameKeys.EMISSION_READ` while bloom will draw, which is what lets the
  world write glows in their own draws. With bloom off nothing reads the emission target and the graph culls its pass.
- **Dither the blend form by stochastic rounding, never by +-1 noise** (`post_round8`): an 8-bit attachment clamps a
  fragment's output to 0..1 before blending, so noise would lose its negative half and brighten the picture.
- **An effect's look is a volume, never a write to the global settings**: it closes its volume when it ends, and the
  player's comfort settings scale what it asks for.
- **Recording allocates nothing**: the effect and volume lists are array snapshots, replaced whole by adding and removal.
- Gates: `--mode=post-looks` (every look through a volume, checked from the pixels) and `--mode=post-effects` (an
  effect at each point), on `gl` and `vulkan`; `--mode=bloom-occlusion` for bloom.

## Writing an effect

What a mod adds is a `CgPostEffect`, registered with `CgPostStack.get().add(effect)` and removed by closing what that
returns. `CgPostEffectsTestScene` in the harness has one at each point.

```java
final class Tint implements CgPostEffect {
    private final CgMaterial material = CgMaterial.newInstance("mymod:shaders/tint.shader");   // #type none, Blend set
    public CgPostPoint point() { return CgPostPoint.AFTER_COMPOSITE; }
    public boolean active(CgPostContext post) { return strength > 0f; }
    public void record(CgPostContext post) {
        CgRasterPass pass = post.recording().raster(post.target(), CgLoad.load(), post.constants(), null, CgOrder.SORTED);
        CgChunkBuilder chunks = post.recording().chunks().begin();
        chunks.draw(material.pipeline(CgInstanceKind.OBJECT), material.captureBindings(post.recording().bindings()), CgMesh.quads(1));
        chunks.instance();
        pass.add(chunks.end());
        pass.end();
    }
}
```

- **`AFTER_WORLD`** sees the scene before any look; **`AFTER_COMPOSITE`** the final picture. A pass that reads the
  target declares `sceneColor(unit)` and its shader a `SceneColorMargin`; one that reads depth, `sceneDepth(unit)`.
- **`BEFORE_COMPOSITE`** is for feeding the composite (`post.composite()`), not for drawing: a look drawn there would
  sit under bloom.
- `order()` sorts effects within a point, ties in the order added. `active` is asked every firing; record nothing
  when inactive, and allocate nothing in either.
- Read the firing's blackboard (`post.resources()`) and blended looks (`post.settings()`); never hold either.
- **A side input of the scene (emission, a mask) goes through `post.distorted(texture)`**, or it sits unbent over
  every haze; the target is bent already.
