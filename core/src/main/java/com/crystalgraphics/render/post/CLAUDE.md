# render/post — the post chain

> Root guide: [`CrystalGraphics/docs/ENGINE_API.md`](../../../../../../../../docs/ENGINE_API.md) § *The post chain*.
> Plan: `plan/crystalgraphics/renderer/steps/R14.md`, `R15.md`.

A view's post chain (Unreal's `AddPostProcessingPasses`): effects at points over the picture, each given the picture so
far and answering the picture after it (`FScreenPassTexture` in, out), the engine's bloom, tonemapper and looks between
them, its last step writing the host's target. The client's views share `CgPost.client()`; a view of a scene of its own
has its own (`view.post()`). Volumes are scene components (`scene.postVolume(settings)`): a view blends its scene's over
the chain's base settings (`post.settings()`). A volume's weighted blendables (Unreal's `FWeightedBlendable`, post
materials in its settings) run at their points among the chain's effects, by order and priority, an effect first on a tie.

```
CgSceneRenderer.opaque        CgPostRenderer.prepare: the scene's volumes blended at the camera, textures asked for
CgSceneRenderer.translucent   ... lights, media, decals
  SURFACES                    on the lit opaque scene (height fog, outlines), then the SURFACES stage
                              ... our translucent passes, the TRANSLUCENT stage
CgPostRenderer.record
  TRANSLUCENT                 colour regions: the scene before separate translucency
  depth of field              CgDepthOfField: the lens's blur by distance, into a texture of its own
  translucency                CgTranslucencyComposition: CgSeparateTranslucency over the scene and its graded layers
  temporal AA                 CgTemporalAA: the jittered picture resolved into the view's history (temporalAA show flag)
  SCENE                       the linear scene: what bloom sees, it blooms
  bloom                       CgBloom from the chain's picture, into the composite
  HDR                         linear, never bloomed
  composite                   CgPostLooks feeds it; one full-screen pass encoding into the host's target
  DISPLAY                     the display picture
  crossfade, debug            then a picture left elsewhere is copied into the host's target
CgPostRenderer.present        a view drawn below 100%: its picture, finished at that size in the target standing in for
                              the host's, upscaled into the host's (CgSpatialUpscale, Unreal's PrimaryUpscale); OVERLAY after
```

| Class | Holds |
|---|---|
| `CgPost` | The consumer face: `add(point[, order], effect)` answering a `Registration`, `material(...)`, `settings()` (the chain's base settings, bloom's among them), `camera()` (the camera's, blended over every volume: a rig's lens), `crossfade(s)`. `client()`; `new CgPost()` is `CgView`'s for a view of its own. Its effects an array snapshot, replaced whole |
| `CgPostRenderer` | Engine side: `prepare(view)`, `surfaces(firing)`, `record(firing)`, `present(firing)` in the frame's order, each point's effects and blendables merged (`walk`); `install`, `lookPasses`, `precacheLook`, `releaseAll` |
| `CgOutline` | The outline as a blendable: `settings(instance)` a volume's settings drawing it at `SURFACES` first (`PRIORITY`), `material(stencil)`, `tests(...)` |
| `CgPostEffect` | `CgScreenTexture apply(CgPostContext ctx, CgScreenTexture input)` |
| `CgPostPoint` | `SURFACES`, `TRANSLUCENT`, `SCENE`, `HDR`, `DISPLAY`; the format's `Location` takes the same names |
| `CgPostContext` | `view`, `settings` (the blended volumes), `scene()` (the view's scene textures), `recording`, `depth`, `constants`, `screen(material)`, `texture(name, like)`, `distorted(t)` |
| `CgScreenPass` | One full-screen draw: `from`, `reads`, `weight`, `name`, then `over(t)` or `into(t)`. Depthless, unblended where the shader declares none, in place (`replaces`) where it covers every pixel; hands `CG_CUSTOM_STENCIL`, `CG_SCENE_WORLD_NORMAL`, `CG_CUSTOM_DATA` |
| `CgPostMaterial` | A `#domain post` file as an effect (`post.material(...)`, `weight`, `remove`), or the engine's drawer (`drawer(m)`, `draw(ctx, t)`, `drawOver(ctx, layer)`). A `Step` with `Target` draws into a transient of its format and `Scale`, bound by name for the steps after; held until every pass is built where the view skips unready draws |
| `CgPostCrossfade` | `hold.shader` copies the final picture into an RGBA8 texture; `crossfade.shader` lays it over the frames after at a falling weight on `CgFrameClock` |
| `CgTranslucencyComposition` | Separate translucency (`AfterDOF`, `AfterMotionBlur`) over the scene and each graded layer (`translucency_compose.shader`) |
| `aa` | `CgTemporalAA`: temporal anti-aliasing where the view's `temporalAA` show flag is on (off by default on every view). The resolve (`shaders/post/aa/taa_resolve.shader`) keeps Unreal's classic frame (the 3x3 input filtered by where the jitter took each sample, weighed 4/(4+luma); Catmull-Rom history fetched by the nearest texel's velocity, less the jitter; YCoCg) with a variance clip and three departures, measured against the faithful port in `plan/crystalgraphics/renderer/taa-for-you.md`: the history cut to a texel's smear where the 3x3's near and far motions part, a still texel the clip keeps cutting relaxing its clip (thin geometry), and a still texel keeping up to 63 frames. The history's alpha packs age (0-63) and cut count (0-31, x64). It writes the view's history; `taa_output.shader` copies it into the chain with the picture's alpha. The jitter (`CgView.jitter`, 8 Halton samples) moves the world passes' projection from `prepare` to the chain's end; last frame's matrix stays unjittered. The velocity target is RG16 UNORM (Unreal's encoding; half floats lose the jitter). For the engine's bloom (no graded layer), `CgTemporalGlow` (`temporal_glow.shader`, one per temporal upscaler, each with its own history) takes each jittered frame's excess past bloom's threshold, follows the resolve's motion and averages it at 1/16 at the size drawn, held under the resolved picture's 3x3 (at the output size, a texel drawn apart), and `CgBloom.record` takes it as the frame's light with the threshold not taken again: past the resolve a light thinner than a texel fell under the threshold at its coverage and lost its glow. **It keeps to what the engine drew**: while it shows, every engine solid writes velocity (`requestVelocityWithStill`, still ones zero) and every blended draw marks the R8 responsive target (`CgEnginePasses.RESPONSIVE`, Unreal's Responsive AA); a texel neither wrote, nor any beside it (`cg_EngineDrew`), passes the resolve, the glow and RCAS as it came — a host's world, which draws unjittered and writes no velocity. A responsive texel takes at least a quarter of the new frame and keeps three frames at most, so a particle leaves no trail. See `-Dcrystalgraphics.post.debug=velocity` |
| `upscale` | The temporal step's seam and the spatial upscale. `CgUpscaler` (Unreal's `ITemporalUpscaler`): `minFraction`, `rebuilds(f)`, `jitterSamples(f)`, `materialMipBias(f)`, `apply(ctx, input)`, `bloomLight(...)`; the chain picks one per view by `CgAntiAliasing` (`view.antiAliasing()`, under the `temporalAA` show flag): `TAA` is `CgTemporalAA`, which never rebuilds; `TSR` is `tsr`'s. One that `presents(f)` answers its picture at the output size: the chain presents right after it (`CgStageFiring.present()`), so `SCENE`, bloom and the composite run there. The jitter's index is the view state's (`CgViewState.jitterIndex`), starting over on a camera cut. `CgSpatialUpscale` (`shaders/post/upscale/spatial_upscale.shader`): a view drawn below 100% (`view.screenPercentage`) runs its whole chain at that size into the target standing in for its host's (`CgViewRenderTarget`), and `present` upscales it into the host's by `view.upscaleFilter()` (`CgUpscaleFilter`: bilinear, Catmull-Rom the default, Lanczos 3, AMD's EASU, ported, MIT), the nearest texel's depth written where the host's target has one. While an upscaler rebuilds a picture, the jitter runs its sequence and world materials sample by its mip bias (`cg_MaterialMip`) |
| `tsr` | `CgTemporalSuperResolution`: temporal super resolution (`CgAntiAliasing.TSR`), rebuilding a world drawn at 25-100% to the output size. Kernels `shaders/post/tsr/` in order: `dilate_velocity` (Clear; Dilate: the closest depth on depth edges, the scatter of each surface's depth and motion to where it was by atomic max), `decimate_history` (last frame's render-size guide reprojected by Catmull-Rom, disocclusion against the scatter, hole filling), `reject_shading` (a 32x32 shared-memory tile, 3 of its border feeding only the inside: how much history each pixel keeps; next frame's guide), `spatial_anti_aliasing` (Medium and up: an end-of-edge search where the history is dropped, its offset 4 bits an axis), `update_history` (the output-size history, up to 16 samples, validity in R8). Each has a GPU zone `post.tsr.*`. `CgGraphicsSettings.ANTI_ALIASING` picks the kernels: Low `Reject`/`Update`, Medium and High `RejectSpatial`/`UpdateSpatial` and 3 search steps, Ultra 8 (Unreal's `sg.AntiAliasingQuality` 0-3's `RejectionAntiAliasingQuality`). Our velocity holds this frame's jitter, which `tsr_velocity` subtracts. Gate `temporal-upscale`; `upscale-lab` switches it on F5, its quality on F6 |
| `dof` | `CgDepthOfField`: depth of field from the blended settings' lens (`depthOfField...`, a post block's `depth_of_field`, the camera's focus and f-stop), off with no focus. Half size: setup (scene and signed circle of confusion), 8x8 tiles of the largest near and far radius, dilated, the far and near layers gathered on rings shaped by the aperture's blades, then a full-size recombine (`shaders/post/dof/`). Thin lens on the view's FOV and the settings' sensor; each layer's radius held to 2.5% of the width |

| Package | Holds |
|---|---|
| `bloom` | `CgBloom`: Unreal's Gaussian layers (`BloomMethod` Standard), per chain; its intensity, colour and threshold are the blended `CgPostSettings`' (`bloomIntensity`, `bloomColour`, `bloomExcessThreshold`). Its source (`bloom.compute`'s `Source`) is the half-size scene: each channel's excess past `threshold` (1), or where a volume asks for Unreal's look (`CgPostSettings.gaussianBloom`, a post block's `bloom1`-`bloom6`) Unreal's setup at its `BloomThreshold`. One compute pass (`FFilterCS`/`FDownsampleCS` form, R11G11B10F): the 4x4-box chain to a 64th, then from the 64th a separable Gaussian a layer, each adding the one before. Unless a volume asks, the layers are the engine's own (`ENGINE_SIZES`/`ENGINE_TINTS`), fitted to the CoD-style chain they replaced within 3% at every radius. Each graded layer's light blooms by its own look in the same dispatches (`BLOOMS`, 3) |
| `composite` | `CgPostComposite` (the one pass; inputs set by bloom and the looks, never by an effect), `CgCompositeFeature` (each look, a keyword), `CgCompositeForm`: `BLEND` (`composite.shader`, `dst * (1 - a) + rgb`, its added term dithered by stochastic rounding) or `COPY` (`composite_copy.shader`, reads the scene, composites in linear light, triangular dither). `FADE` is last in both. `LAYERS` (copy form): each graded layer adds what it contributes toned by its look's table, less the frame's own tone of the same |
| `look` | `CgPostLooks` (the blended settings into the composite, flash and impact scaled by `CgGraphicsSettings.FLASHES`), `CgColorGrade` (Unreal's `ColorCorrectAll` in ACEScg; `CgColorGradeTest` against a NumPy port of 4.21's shader), `CgFilmLut` (Unreal's tonemapper table, 32³, on the GPU by `post/film_lut.compute`; its CPU `bake` the reference, `CgFilmLutTest` against a Kakarot frame) |
| `settings` | `CgPostSettings` (looks a volume overrides, Unreal's `bOverride_` flags, and its weighted blendables), `CgPostBlock` (a format `post` block to and from settings), `CgImpactFrame`, `CgImpact`, `CgPostBlendable` (a post block's `materials` entry, added to a volume's settings at its material's `postLocation`). Prefabs read post blocks, so this sits under `render`, not `presentation`. The volume and the colour region are `render/scene/component`'s `CgPostVolume` and `CgColourRegion` |
| `fog` | `CgHeightFogPass`: every chain's effect at `SURFACES`, order 0, drawing the viewed scene's `CgHeightFogComponent` (Unreal's exponential height fog, one layer) through `height_fog.shader` |
| `distortion` | `CgPostDistortion`: a side input bent by the view's distortion field, once a firing; reached through `CgPostContext.distorted` |
| `debug` | `CgPostDebug`: `-Dcrystalgraphics.post.debug=emission\|level<N>\|overdraw\|distortion` drawn over the frame, every chain's last step |

What produces volumes is not here: a sequence's `post` and `colour_region` spawnables and impact sequences are
`presentation/post` and `presentation/impact`, a stage file's compiler `presentation/stage` (it compiles to a prefab).

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
  answering a texture of its own), `post-transitions` (post materials, steps, a fade, a crossfade), `bloom-occlusion`, `velocity-conformance` (rigid, attached, instanced and skinned motion against the CPU's projection; still objects, frame records and a blended pane's responsive mark), `temporal-aa` (a dithered pane settles, a turned bar's edges resolve, a moved
  bar leaves no ghost), `depth-of-field` (a board in
  focus keeps its edges, a far board and a `BeforeDOF` ring blur, an `AfterDOF` ring stays sharp), `screen-percentage` (the
  world at 50% through each upscale filter against 100%, `OVERLAY` at full size, a host's own world at 100%). `dof-lab`,
  `taa-lab` and `upscale-lab` to play with.

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
