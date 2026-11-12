# render/post — the post-processing stack

> Root guide: [`CrystalGraphics/docs/ENGINE_API.md`](../../../../../../../../docs/ENGINE_API.md) § *The post stack*.
> Plan: `plan/crystalgraphics/render-post-process.md`.

What runs after the world renderer has drawn the scene, on the same `WORLD_TRANSPARENT` firing: effects at named
points, and one composite pass laying every screen-wide look over the target, as Unreal's tonemapper, URP's UberPost
and Godot's tonemap pass compose bloom.

```
CgWorldRenderer (ORDER 1000)  draws the scene, glows added into it ──► frame.resources(): SCENE
CgPostStack     (ORDER 2000)
  BEFORE_SEPARATE_TRANSLUCENCY  colour regions: the scene before separate translucency
  translucency      CgTranslucencyComposition: CgSeparateTranslucency over the scene and its graded layers
  AFTER_WORLD       effects that read the scene
  BEFORE_COMPOSITE  effects that make the composite's inputs: bloom's layers
  composite         one full-screen pass: every active CgCompositeFeature
  AFTER_COMPOSITE   effects over the final picture
```

| Package | Holds |
|---|---|
| (root) | `CgPostStack` (the singleton: registration, the effect list, recording, `crossfade`), `CgPostEffect` (the SPI the engine's and a mod's effects implement), `CgPostPoint`, `CgPostContext` (what an effect records with), `CgPostMaterial` (an effect that is a `.shader` over the screen at `AFTER_WORLD` or `AFTER_COMPOSITE`, its weight in the draw's `custom0.x`, `POST_WEIGHT`; drawn unblended where its shader declares no blend, and then, discarding nothing, over the scene it reads with no copy of it (`CgRasterPass.replaces`); `of(path)` takes a `#domain post` file's `Location`; a pass tagged `"Target"` (a format file's `Step`) draws into a transient of its `"Format"` (RGBA16F by default) and `"Scale"`, bound to that sampler for the passes after, through `CgMaterial.pipeline(pass, kind)`, and the `"Emission"` tag binds the emission or the HDR scene), `CgPostCrossfade` (built in, last at `AFTER_COMPOSITE`: `hold.shader` copies the target into a requested RGBA8 texture of the host's size through `readsColor(target, unit)`, `crossfade.shader` lays it over the frames after at a falling alpha on `CgFrameClock`) |
| `bloom` | `CgBloom`: the built-in bloom, Unreal's Gaussian layers (`BloomMethod` Standard), and its settings. Its source (`bloom.compute`'s `Source`) is the half-size scene: each channel's excess past `threshold` (1), held toward `_Highlight`, or where a volume asks for Unreal's look (`CgPostSettings.gaussianBloom`, a post block's `bloom1`-`bloom6`) Unreal's setup (`UNREAL_SETUP`) at its `BloomThreshold`. Its passes (`Chain`, one compute pass, Unreal's `FFilterCS`/`FDownsampleCS` form, R11G11B10F): the 4x4-box chain to a 64th (`Down`, a dispatch a level), then from the 64th in a separable Gaussian a layer (`Across`, `Layer`, Unreal's taps; from a 7-texel radius the horizontal pass at half width, `r.FastBlurThreshold`), each adding the one before. Unless a volume asks, the layers are the engine's own (`ENGINE_SIZES`/`ENGINE_TINTS`, at a size scale of 1): fitted to the CoD-style chain they replaced (rhi, 2026-11-04) by a model of both round one hot texel, within 3% at every radius, its glow matching on `vfx-blasts` to 0.87 of 255. A texture a stage, never levels of one, and no clears. **Graded layers** (`render/world`'s `CgGradeLayers`): each layer's light blooms by its own look (Unreal's where it asks, else the engine's layers), and the engine's bloom reads the scene less every layer's light, so none blooms twice. Every bloom of a firing runs in the same dispatches, one a z with its own targets and rows (`BLOOMS`, 3): one chain however many looks |
| `composite` | `CgPostComposite` (the one pass), `CgCompositeFeature` (each look, a keyword), `CgCompositeForm`: `BLEND` (`composite.shader`, `dst * (1 - a) + rgb`, no copy, adding in the target's encoding, its added term dithered by stochastic rounding) or `COPY` (`composite_copy.shader`, reads `cg_SceneColor`, composites in linear light, triangular dither), chosen each firing from what its inputs ask. `FADE` is last in both: the blend form fades its added term and its alpha, the copy form mixes the encoded colour, so a fade looks the same in either. `LAYERS` (copy form, with the scene): each graded layer adds what it contributes toned by its look's table (`CgPostLooks`' per-slot `CgFilmLut`), less the frame's own tone of the same (`frame_tone`), with the background behind it taken back from its coverage; a pixel no layer reaches is unchanged |
| `volume` | `CgPostSettings` (looks a volume overrides: bloom scale, flash in stops, Unreal's vignette and colour fringe, the tonemapper and its film settings (Unreal's, at its defaults, blended by weight), Unreal's colour grading (a `Region` and `Control` each its own field) and scene tint, impact with its `CgImpactFrame` and seed, focus), `CgPostVolume` (everywhere, or a radius and blend distance round a point; priority; a weight an effect animates), `CgImpactFrame` (an impact frame's look as data: a picture-only kind, or drawn with paper, subject, focus lines, hatching, star, cross and jitter), `CgImpact` (its presets), `CgImpactSequence` (beats at 24 a second, a seed each), `CgPostBlendable` (a post block's `materials` entry compiled: Unreal's weighted blendable, its `values` and `textures` a derived material, drawn at its material's `post.stage`; a sequence's post spawnable and a stage's volumes both draw them). The stack blends every volume at the camera each firing into `CgPostContext.settings()`, a placed one's focus where it is on screen |
| `fog` | `CgHeightFog`: Unreal's exponential height fog (`HeightFogCommon.ush`'s one layer, the observer held as `FogRendering.cpp` holds it), in blocks, drawn at `BEFORE_TRANSLUCENCY` by `height_fog.shader`, decoding and encoding the host's target without the HDR scene |
| `stage` | `CgStageDefinition` (the `stage` kind compiled: a level's post volumes and fog in the host's blocks round its anchor; its Java `Builder`), `CgStage` (one placed over the world until closed, a weight for all of it), `CgStagePreparer` (its materials, looks and fog built ahead) |
| `look` | `CgPostLooks`: the built-in effect at `BEFORE_COMPOSITE` turning the blended settings into composite inputs, flash and impact scaled by `CgGraphicsSettings.FLASHES`. `CgColorGrade`: Unreal's `ColorCorrectAll` in ACEScg with the white balance, packed for the composite's `GRADE` (before the roll, its tint after) or baked into the table; `CgColorGradeTest` checks it against a NumPy port of 4.21's shader, and a neutral grade turns `GRADE` off. `CgFilmLut`: Unreal's tonemapper table (its combined LUT, 4.21's legacy film curve or 5.1's, 32³), built on the GPU by `env/compute/post/film_lut.compute` when the film settings or grade move (Unreal's `PostProcessCombineLUTs`), kept as a 3D graph texture and read by the composite's `TONEMAP` through Unreal's log shaper, times 1.05. Its CPU `bake` is the reference: `CgFilmLutTest` checks it against a Kakarot frame's table and pixels (`-PgameLut`), and `post-looks` holds the GPU's table to it cell by cell. A drawn impact frame puts `CgFrameKeys.SUBJECT_READ` in `demand()` and reads the world's `SUBJECT`, bent; its GLSL is `lib/post/impact.glsl`. Every look but bloom draws in the copy form until dual-source blending lands |
| `distortion` | `CgPostDistortion`: a side input bent by the firing's `CgFrameKeys.DISTORTION` field (`distortion_bend.shader`, GPU zone `post.distortionBend`), each input once a firing; reached through `CgPostContext.distorted`. The bend sums the field's layers in use through one `sampler2DArray`, so it binds two units however many hazes' slots there were |
| `debug` | `CgPostDebug`: `-Dcrystalgraphics.post.debug=emission\|level<N>\|overdraw\|distortion`, the emission, a bloom stage (`CgBloom.LEVELS`), the world renderer's overdraw count (`CgFrameKeys.OVERDRAW`, through a heat ramp) or its distortion offsets summed (`CgFrameKeys.DISTORTION`, `debug_distortion.shader`) drawn over the frame, last |

Shaders: `shaders/post/` — `composite.shader`, `composite_copy.shader`, `debug.shader`, the crossfade's `hold.shader`
and `crossfade.shader`; bloom's kernels in `shaders/env/compute/post/bloom.compute`; a post material's triangle and weight in `shaders/lib/post/post_material.glsl`.

**The HDR scene**: when the firing carries `CgFrameKeys.SCENE`, the stack hands the
composite the scene (`composite.scene`) and the stage its host target back (`retarget(null, null)`) before the composite
records. The composite then always draws, in the copy form with `SCENE`: it reads the scene rather than a copy of the
target, rolls a colour past white toward white keeping its hue (`c / peak` mixed toward white by
`(peak - 1) / (peak + 2)`, halfway at 4x white: a hot core with a coloured fringe, where a clamp per channel bands, and
a saturated glow at 2x still three quarters its colour), clamps, and writes a value within 0.1 of an 8-bit code back exact rather than dithered, so a pixel nothing drew
over returns byte for byte. `AFTER_WORLD` and `BEFORE_COMPOSITE` effects draw into the scene; `AFTER_COMPOSITE` into the
host. Bloom's source is then the scene's light past `CgBloom.threshold` (`bloom.compute`'s `Source`: half size, R11G11B10F, each
texel's excess soft-capped at 32 before the 2x2 average), already bent. A shader drawing into
the scene or over the picture with the colour it read is tagged `"ColorSpace" = "Target"`, as every one here is: the
compiler neither decodes it into the scene nor encodes it onto the host.

## Rules

- **The world renderer produces, the post stack consumes.** No post pass is recorded from `render/world`, and no
  scene draw from `render/post`. What one hands the other goes through `CgStageFrame.resources()`
  (`CgFrameKeys`), never a field read across classes. The one crossing is `BEFORE_TRANSLUCENCY` (Unreal's
  `BL_BeforeTranslucency`, 4.27 `PostProcessing.cpp:476`): the stack puts a `CgStageHook` on the blackboard at
  `DEMAND_ORDER`, and the world renderer runs it onto the lit opaque scene before its transparent passes, custom
  depth, normals and custom data published. A firing without the scene (its programs unbuilt) records them with
  `AFTER_WORLD`.
- **One composite pass a firing.** A new screen-wide look is a `CgCompositeFeature`, or an effect at a point; never a
  second full-screen add.
- **A mod's effect cannot join the composite** (Unreal's blendables run as their own passes too): it records at a point.
- **What the stack asks of the world renderer goes through the blackboard too**: at `DEMAND_ORDER`, before the world
  records, it blends the volumes and puts `CgFrameKeys.SUBJECT_READ` while a drawn impact frame shows, which is what
  lets the world draw its subject's glows apart.
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
    private final CgMaterial material = CgMaterial.load("mymod:shaders/tint.shader");   // #type none, Blend set
    public CgPostPoint point() { return CgPostPoint.AFTER_COMPOSITE; }
    public boolean active(CgPostContext post) { return strength > 0f; }
    public void record(CgPostContext post) {
        CgRasterPass pass = post.recording().raster("mymod.outline").color(post.target(), CgLoad.load()).constants(post.constants());
        CgChunkBuilder chunks = post.recording().chunks().begin();
        chunks.draw(CgMaterialProxy.of(material).pipeline(CgInstanceKind.OBJECT), CgMaterialProxy.of(material).bindings(), CgMesh.quads(1));
        chunks.instance();
        pass.add(chunks.end());
        pass.end();
    }
}
```

- **`AFTER_WORLD`** sees the scene before any look; **`AFTER_COMPOSITE`** the final picture. A pass that reads the
  target declares `readsColor(target, unit)` and its shader a `SceneColorMargin`; one that reads depth, `readsDepth(target, unit)`.
- **`BEFORE_COMPOSITE`** is for feeding the composite (`post.composite()`), not for drawing: a look drawn there would
  sit under bloom.
- `order()` sorts effects within a point, ties in the order added. `active` is asked every firing; record nothing
  when inactive, and allocate nothing in either.
- Read the firing's blackboard (`post.resources()`) and blended looks (`post.settings()`); never hold either.
- **A side input of the scene (emission, a mask) goes through `post.distorted(texture)`**, or it sits unbent over
  every haze; the target is bent already.
