# render/post — the post chain

> Root guide: [`CrystalGraphics/docs/ENGINE_API.md`](../../../../../../../../docs/ENGINE_API.md) § *The post chain*.
> Plan: `plan/crystalgraphics/renderer/steps/R14.md`.

A view's post chain (Unreal's `AddPostProcessingPasses`): effects at points over the picture, each given the picture so
far and answering the picture after it (`FScreenPassTexture` in, out), the engine's bloom, tonemapper and looks between
them, its last step writing the host's target. The client's views share `CgPost.client()`; a view of a scene of its own
has its own (`view.post()`). Volumes are scene components (`scene.postVolume(settings)`): a view blends its scene's.

```
CgSceneRenderer.opaque        CgPostRenderer.prepare: the scene's volumes blended at the camera, textures asked for
CgSceneRenderer.translucent   ... lights, media, decals
  SURFACES                    on the lit opaque scene (height fog, outlines), then the SURFACES stage
                              ... our translucent passes, the TRANSLUCENT stage
CgPostRenderer.record
  TRANSLUCENT                 colour regions: the scene before separate translucency
  translucency                CgTranslucencyComposition: CgSeparateTranslucency over the scene and its graded layers
  SCENE                       the linear scene: what bloom sees, it blooms
  bloom                       CgBloom from the chain's picture, into the composite
  HDR                         linear, never bloomed
  composite                   CgPostLooks feeds it; one full-screen pass encoding into the host's target
  DISPLAY                     the display picture
  crossfade, debug            then a picture left elsewhere is copied into the host's target
```

| Class | Holds |
|---|---|
| `CgPost` | The consumer face: `add(point[, order], effect)` answering a `Registration`, `material(...)`, `bloom()`, `crossfade(s)`. `client()`; `new CgPost()` is `CgView`'s for a view of its own. Its effects an array snapshot, replaced whole |
| `CgPostRenderer` | Engine side: `prepare(view)`, `surfaces(firing)`, `record(firing)` in the frame's order; `install`, `lookPasses`, `precacheLook`, `releaseAll` |
| `CgPostEffect` | `CgScreenTexture apply(CgPostContext ctx, CgScreenTexture input)` |
| `CgPostPoint` | `SURFACES`, `TRANSLUCENT`, `SCENE`, `HDR`, `DISPLAY`; the format's `Location` takes the same names |
| `CgPostContext` | `view`, `settings` (the blended volumes), `scene()` (the view's scene textures), `recording`, `depth`, `constants`, `screen(material)`, `texture(name, like)`, `distorted(t)` |
| `CgScreenPass` | One full-screen draw: `from`, `reads`, `weight`, `name`, then `over(t)` or `into(t)`. Depthless, unblended where the shader declares none, in place (`replaces`) where it covers every pixel; hands `CG_CUSTOM_STENCIL`, `CG_SCENE_WORLD_NORMAL`, `CG_CUSTOM_DATA` |
| `CgPostMaterial` | A `#domain post` file as an effect (`post.material(...)`, `weight`, `remove`), or the engine's drawer (`drawer(m)`, `draw(ctx, t)`, `drawOver(ctx, layer)`). A `Step` with `Target` draws into a transient of its format and `Scale`, bound by name for the steps after; held until every pass is built where the view skips unready draws |
| `CgPostCrossfade` | `hold.shader` copies the final picture into an RGBA8 texture; `crossfade.shader` lays it over the frames after at a falling weight on `CgFrameClock` |
| `CgTranslucencyComposition` | Separate translucency over the scene and each graded layer (`translucency_compose.shader`) |

| Package | Holds |
|---|---|
| `bloom` | `CgBloom`: Unreal's Gaussian layers (`BloomMethod` Standard) and its settings, per chain. Its source (`bloom.compute`'s `Source`) is the half-size scene: each channel's excess past `threshold` (1), or where a volume asks for Unreal's look (`CgPostSettings.gaussianBloom`, a post block's `bloom1`-`bloom6`) Unreal's setup at its `BloomThreshold`. One compute pass (`FFilterCS`/`FDownsampleCS` form, R11G11B10F): the 4x4-box chain to a 64th, then from the 64th a separable Gaussian a layer, each adding the one before. Unless a volume asks, the layers are the engine's own (`ENGINE_SIZES`/`ENGINE_TINTS`), fitted to the CoD-style chain they replaced within 3% at every radius. Each graded layer's light blooms by its own look in the same dispatches (`BLOOMS`, 3) |
| `composite` | `CgPostComposite` (the one pass; inputs set by bloom and the looks, never by an effect), `CgCompositeFeature` (each look, a keyword), `CgCompositeForm`: `BLEND` (`composite.shader`, `dst * (1 - a) + rgb`, its added term dithered by stochastic rounding) or `COPY` (`composite_copy.shader`, reads the scene, composites in linear light, triangular dither). `FADE` is last in both. `LAYERS` (copy form): each graded layer adds what it contributes toned by its look's table, less the frame's own tone of the same |
| `look` | `CgPostLooks` (the blended settings into the composite, flash and impact scaled by `CgGraphicsSettings.FLASHES`), `CgColorGrade` (Unreal's `ColorCorrectAll` in ACEScg; `CgColorGradeTest` against a NumPy port of 4.21's shader), `CgFilmLut` (Unreal's tonemapper table, 32³, on the GPU by `post/film_lut.compute`; its CPU `bake` the reference, `CgFilmLutTest` against a Kakarot frame) |
| `volume` | `CgPostSettings` (looks a volume overrides, Unreal's `bOverride_` flags), `CgPostBlock` (a format `post` block to and from settings), `CgImpactFrame`, `CgImpact`, `CgImpactSequence`, `CgPostBlendable` (a post block's `materials` entry, drawn at its material's `post.stage`), `CgPostVolumeSpawner` (a sequence's `post` spawnable), `CgPostVolumePreparer`, `CgColourRegion` (an effect at `TRANSLUCENT`) and its spawner. The volume itself is `render/scene/component/CgPostVolume` |
| `fog` | `CgHeightFogPass`: every chain's effect at `SURFACES`, order 0, drawing the viewed scene's `CgHeightFogComponent` (Unreal's exponential height fog, one layer) through `height_fog.shader` |
| `stage` | `CgStageDefinition`, `CgStage` (a level's post volumes, materials and fog placed over the client scene), `CgStagePreparer` |
| `distortion` | `CgPostDistortion`: a side input bent by the view's distortion field, once a firing; reached through `CgPostContext.distorted` |
| `debug` | `CgPostDebug`: `-Dcrystalgraphics.post.debug=emission\|level<N>\|overdraw\|distortion` drawn over the frame, every chain's last step |

Shaders: `shaders/post/` — `composite.shader`, `composite_copy.shader`, `debug.shader`, `hold.shader` (the crossfade's
hold and the chain's last copy), `crossfade.shader`; bloom's kernels in `shaders/post/bloom.compute`; a post material's
triangle and weight in `shaders/env/material/post.glsl`.

**The HDR scene**: when the view has one (`scene().linear()`), `SURFACES`, `TRANSLUCENT`, `SCENE` and `HDR` draw into
it, and the composite reads the chain's picture (`composite.scene`) and encodes it into the host's target, which the
stage then has back (`retarget(null, null)`). It rolls a colour past white toward white keeping its hue, clamps, and
writes a value within 0.1 of an 8-bit code back exact, so a pixel nothing drew over returns byte for byte. Without one
every point draws onto the host's target and the composite draws in its blend form.

## Rules

- **The scene renderer produces, the chain consumes.** No post pass is recorded from `render/scene`, and no scene draw
  from `render/post`. The renderer calls `CgPostRenderer` at three points in the frame's order (review 5.2: no hook on a
  blackboard); what one hands the other goes through the view (`view.textures()`, `view.publish/read`).
- **An effect answers its picture.** At `SURFACES` and `TRANSLUCENT` it answers its input (the renderer goes on drawing
  into it; another answer throws). Elsewhere it may answer a texture of its own, which the chain carries on.
- **One composite pass a firing.** A new screen-wide look is a `CgCompositeFeature`; an effect cannot feed the composite
  (Unreal's blendables run as their own passes too).
- **An effect's look is a volume, never a write to a chain's settings**: it removes its volume when it ends, and the
  player's comfort settings scale what it asks for.
- **Dither the blend form by stochastic rounding, never by +-1 noise** (`post_round8`): an 8-bit attachment clamps a
  fragment's output to 0..1 before blending, so noise would lose its negative half and brighten the picture.
- **Recording allocates nothing**: effect lists are array snapshots, `CgScreenTexture` is kept on its texture, and
  `ctx.screen` and `ctx.texture` reuse theirs.
- Gates: `post-looks` (every look through a volume, from the pixels), `post-effects` (an effect at each point, one
  answering a texture of its own), `post-transitions` (post materials, steps, a fade, a crossfade), `bloom-occlusion`.

## Writing an effect

```java
CgPost.Registration tint = CgPost.client().add(CgPostPoint.DISPLAY, (ctx, input) -> ctx.screen(tintMaterial).over(input));

CgPost.client().add(CgPostPoint.HDR, (ctx, input) -> {                    // into a texture of its own
    CgScreenTexture out = ctx.texture("mymod.outline", input);
    return ctx.screen(outline).reads(ctx.scene().customDepth()).into(out);
});
tint.close();
```

- **`SCENE`** sees the scene and blooms with it; **`HDR`** is linear and never blooms; **`DISPLAY`** is the final
  picture.
- A full-screen material's `CG_SCENE_COLOR` reads the effect's input unless `from(...)` says otherwise; a texture it
  samples by a property is set on the material and declared with `reads(...)`.
- Anything else records through `ctx.recording()` with `ctx.constants()`.
- **A side input of the scene (emission, a mask) goes through `ctx.distorted(texture)`**, or it sits unbent over every
  haze; the picture is bent already.
- Allocate nothing per frame: materials and names in fields.
