# render/world — the world, drawn under the host's camera

> Root guide: [`CrystalGraphics/docs/ENGINE_API.md`](../../../../../../../../docs/ENGINE_API.md) § *CgWorldRenderer*

| Type | Role |
|------|------|
| `CgWorldRenderer` | Registered on `WORLD_OPAQUE` and `WORLD_TRANSPARENT` at `ORDER` (1000). Holds the frame's draws flat (absolute positions in doubles, local transforms, customs, queue, sort layer, order, group); at each stage culls them against the stage's view, picks a `CgMeshLods` draw's level by its screen height, sorts them, and records the prepass and the opaque or transparent pass onto the host's target, each declaring `sceneDepth`/`sceneColor` so its readers sample the target as it stands. The client world's schedule (`CgWorld.client()`) runs once a frame, first in the first world stage's firing (only camera shake before it), so renderers registered below `ORDER` that read what they submit (VFX pools and Range lay out slots from it) see it the same frame. After them it uploads the parameter collections (`CgParameterCollection.upload()`), so a value an effect set reaches this frame's draws. Its labels (`text(...)`, `labels()`) are the canvas's `CgCanvasLabels`: placed and their depth drawn at the top of each transparent firing (before any glow, so a glow behind label text is hidden from bloom), their colour after its passes |
| `CgPipelinePrecache` | The one way to build pipelines ahead of use (Unreal's PSO precaching): `register(name, collector)` at load, collected and built at the graphics start inside the loading screen (one registered later at the next world frame, over frames); `request(collector)` at runtime, polled with `Request.ready()`; `steps(name).ahead(work).step(c)...start()` a request too big for a frame, its worker phase first, then each step collected while `CgWarmBudget` lasts (the list's `advance`, from `ready()` and each frame). Every warm-up that holds something until built (VFX looks and kernels, post volume looks, demo and harness props) is a request; renderers' own draw-time holds (`skipUnready`, `CgPostMaterial`) stay theirs |
| `CgPipelineList` | What every collector named, kept built for the session: materials (`add(material, half[, skinned])`, manifests, shader keyword sets through a probe material), renderer passes (`addPass`: the Forward program alone) and kernels (`add(kernel)`, `kernels(preparer)`). Polled each frame inside `CgWarmBudget`; a settings change (HDR, merge, joins, half size) rebuilds every material under the new settings before it applies, never holding it past `MAX_WAIT_FRAMES`. A request polls only its own entries |
| `CgSceneTarget` | The HDR scene's first pass, on `WORLD_TRANSPARENT` at `ORDER` 1, once its programs are built: `world_scene_in.shader` decodes the host's colour (`readsColor(current, unit)`) into an RGBA16F texture drawn with the current target's depth, published as `CgFrameKeys.SCENE` and made the stage's target with that depth (`retarget(scene, depth)`: passes into `stage.target()` declare `stage.depth()`). A host lending no depth (`target().lendsAttachments()` false, the window's: the harness under `mainTarget=false`, 1.7.10 without framebuffers) gets a scene with its own depth, written from the host's by the same pass (`DEPTH`), so depth our draws write there never reaches the host. The post stack's composite encodes it back and hands the target back. **The opaque firing's scene**, as Unreal's base pass writes SceneColor: an opaque firing with draws of ours decodes the host into a kept requested RGBA16F, drawn with the host's depth, before its opaque pass and makes it the target (`recordOpaqueIn`, GPU zone `world.opaqueSceneIn`), then `world_scene_out.shader` writes the nearest code of each pixel back into the host (`recordOpaqueOut`, `world.opaqueSceneOut`; with its own depth, that depth too), so the host's draws before the transparent stage blend over ours as before. The transparent way in, `KEEP`, then takes the kept value where the host still holds that code and decodes the rest: an unlit colour past white reaches bloom. A firing with no opaque draw of ours runs as before, byte for byte. About 0.04 ms of GPU at 1440p. `--mode=hdr-scene` holds both: every pixel outside an opaque cube back byte for byte, with the host's overdraw between the stages too, and the cube's excess rolled and bloomed |
| `CgWorldMedia` | Smoke in the air as a froxel grid (`world.media()`): at the top of each transparent firing (`record`, before the half-size, emission and transparent passes), compute passes (`world.media`, `world.mediaScatter`, `world.mediaIntegrate`, each its own GPU zone) ping-pong two R16F volumes of `TILE`-pixel froxels by `SLICES` exponential slices through every `Injector` (each first `upload`s what it binds, before the pass opens, its eye known; each `dispatch` reads the sum so far and writes its own added), then `CgWorldLights.scatter` lights each froxel from the light grid (Unreal's volumetric fog), and `media.compute`'s Integrate sums both from the eye out into the light scattered toward the eye and the transmittance; `world_media_light.shader` adds that light over the opaque scene. Recorded whenever an injector is added. `CgWorldHaze` is a box of haze as an injector. Bound at `CgBindingModel.MEDIA` by `mediated(pass)` on the transparent, half-size, glow and scene-glow passes; `CgFrameBuilder` binds `CgFallbackTextures.WHITE_VOLUME` there otherwise. Its camera is the view's position plus the eye `toWorld` puts it at, which a host may keep off the origin (the harness) |
| `CgWorldLights` | The world's lights (`world.lights()`, [`docs/LIGHTS.md`](../../../../../../../../docs/LIGHTS.md)), lit **deferred, with the G-buffer rebuilt from what the host drew**. Nothing changes Minecraft's draws or shaders. After the level, `record` bins the lights into Unreal 5's light grid (`light_grid.compute`), and `apply` draws one full-screen pass, `world_lights.shader`, over the host's target, blended `DST_COLOR ONE`, so each light adds `scene × light / lightmap`. The rebuilt G-buffer: position from depth; normal as our opaque pass drew it into `cg_world.normals` (`CgPipeline.normalTarget()`, its eye depth in alpha, so a pixel is ours while its depth still matches), else from the nearer neighbouring depths, snapped to a world axis within 10 degrees, written once a firing into an RGBA16F `cg_world_lights.gbuffer` (`lights/gbuffer.compute`) that the apply and every shadow pass read; albedo as the scene's colour over Minecraft's lightmap at the light's block; no roughness, so no specular. The lights only ever add: Minecraft's own light (torches, sky, its baked AO and face shading) is never darkened, and a shadow (plan `render-shadows`) only withholds a light. Lights are placed from the eye, not the view's position (`toView` = bob × inverse(`toWorld`) × translate(eye)). **Shadows** (MegaLights, `docs/LIGHTS.md` §7): a light with `shadowed` (every CPU light unless told otherwise) is left out of that pass and lit by four more before it, into transients and one history: `lights/sample.compute` (four samples a pixel, or a downsampled one under `crystalgraphics.world.lightsDownsample`, by weighted reservoir, each traced across the screen's depth, then through `CgWorldVoxelWindow`'s distance field, `CgWorldEntityBoxes`' capsules and our meshes' distance fields), `lights/shade.compute`, `lights/temporal.compute` and `lights/denoise.compute`; the apply adds the result as `_Shadowed` |
| `CgWorldVoxelWindow` | The world on the GPU: the host world round the camera, 128x96x128 blocks of solid octants, light and fluid (`CgWorldVoxels` packs a section), filled from `CgWorldQuery` a section at a time under a budget and addressed toroidally; and, while a reader calls `useDistance()`, each block's distance to the nearest solid octant, a jump flood on the GPU around the sections that changed. Recorded on `WORLD_OPAQUE` once something `use()`s it. Unreal's global distance field in role: the lights' shadow rays and the VFX engine's particles (collision, floors, Range's light) read it. A kernel includes `shaders/lib/vfx/fx_world.glsl`, declares `PROPERTIES` and binds `bind(dispatch)`; a material `bind(bindings, unit)`. With no level nothing reads it. `--mode=vfx-world` is the gate |
| `CgMeshDistanceFields` | Unreal's mesh distance fields: one per mesh and submesh, built off the render thread on first ask (16 voxels a block, at most 48 an axis), in one R32F atlas 1024 floats a row. Static meshes only, as Unreal's: a skinned mesh has none. `CgWorldLights.caster` takes each opaque draw as a caster of its field, so our meshes shadow off screen too. `CgMeshDistanceFieldsTest` holds the field |
| `CgWorldEntityBoxes` | The living entities nearest the camera, at most 64 within 64 blocks, gathered from `CgEntityQuery` at most once a frame a camera: the lights' shadow rays take each as a capsule, particle steps collide with them (`ENTITIES`). Items and projectiles are left out |
| `CgSortKey` | Filament's key layout with a log-quantised distance: slot, sort layer, then opaque order, material, distance, mesh, or transparent group distance, solid bit, order, distance. No far plane |
| `CgSortLayer` | Named sort layers, Unity's: a later one draws after an earlier whatever the distance. Built in `BACKGROUND`, `DEFAULT`, `EFFECTS`, `SEPARATE_TRANSLUCENCY` (where `add` lifts a translucent draw whose material state says `separateTranslucency`, its queue to `AFTER_DISTORTION` at least; under the HDR scene drawn apart, § *Separate translucency*), `OVERLAY`; a mod defines its own `before`/`after` one in a static field; at most 128 |

## Rules

- **The world lights are deferred: a lighting feature goes into the full-screen pass, never into a draw's shader.**
  Minecraft's blocks and entities are drawn by Minecraft (and by Sodium, Embeddium or Iris in its place), version by
  version, so anything that must light them (shadows, light functions, new light kinds) reads depth and colour in
  `world_lights.shader`. Only the front surface of each pixel exists there: what is behind it (transparent surfaces,
  the air) needs its own path.
- **Camera-relative.** A model matrix is the draw's local transform plus `position - view position`, the subtraction
  in doubles. The pass constants come from `CgStageFrame.constants()`: the host's view and projection, the eye at the
  view's origin, `cg_WorldOrigin` at the host's absolute position.
- **A draw lives one ring frame** (`CgFrameRing.frame()`); a stage drops the previous frame's draws before it records.
  A host advancing no ring frame (a test) keeps them.
- **`cg_DepthBuffer` and `cg_SceneColor` are the graph's copies of the target** (`CgRasterPass.sceneDepth`,
  `sceneColor`): a reader sees every draw sorted before it except readers in a row with it, so a heat haze bends what is sorted
  under it and nothing above. `render/graph/AGENTS.md` § *Reading the target*.
- **Material chains** (`setNextPass`) are drawn as further draws on the same instances, in the forward passes only.
- **A Forward pass that writes nothing is skipped** (`CgRenderState.writesNothing()`: colour masked off, depth unwritten,
  stencil off): a pure haze or glow is all Distortion or Emissive pass. The overdraw view counts its Distortion pass.
- **An indirect draw** (`.indirect(count, offset, mode, factor)`) carries its count into each pass's chunk. Its
  culling is by the bounds it states: what the count will be is unknown when it is culled. A count written in the same
  stage comes from a renderer registered below `ORDER` recording a compute pass into the stage's frame.
- **A set of instances** (`.instances(records, count)`) is culled on the GPU: each stage drawing one builds a depth
  pyramid of its target before its passes, culls every set it draws at once (`CgCullSets`: a few dispatches however
  many sets, each level's records in the set's order) against the view and the pyramid, and draws each level kept as
  one indirect draw, the levels joined into one multi-draw. `.bounds()` states the whole set's box. `docs/SHADERS.md` § *Drawing what a kernel wrote*.
- The prepass takes a material's depth pass when it has one, else its forward pipeline with colour writes off
  (`CgRenderState.withColorMask`), cached per render state.
- **The transparent prepass**: a transparent draw whose material is `"Depth" = "Prepass"` (`prepassed(i)`) draws
  `CgPipeline.as(DEPTH)` in a depth-only pass at the top of the transparent firing, after the labels' depth and
  before the half-size, emission and transparent passes, so what blends over it, its own back lobes and the glows behind
  it, are hidden where it is solid. Readers of `cg_DepthBuffer` after it see its depth, as they see the labels'.
- **Solids first**: a transparent draw whose Forward draw writes depth (`CgMaterial.writesDepth()`: Clip, or
  `DepthWrite ON`) clears the solid bit of its key and draws before the rest of its group whatever its order. A Prepass
  one keeps its order: its depth is already down, and its blend first would let what is behind its edge cover it.
- **An opaque colour** goes into the opaque firing's scene (`CgSceneTarget`): a `"ColorSpace" =
  "Linear"` unlit material past white keeps its excess, as Unreal's unlit emissive in SceneColor. A lit opaque
  material's Emissive pass still adds its glow at the transparent stage, below: its Forward colour is held at white,
  and the glow is the only light it gives past it.
- **Glows** (into the HDR scene, `CgFrameKeys.SCENE` on the firing; none before its programs are built). Opaque and
  half-size draws' Emissive passes add into the scene before the transparent pass (`recordSceneGlows`, reading its
  depth through `readsDepth(target, unit)`); a transparent draw's Emissive pass follows its Forward draw in the transparent pass
  at the same key (`recordPass`), which SORTED keeps in order, so a nearer surface covers glow and colour alike. One
  that merges is folded into the Forward draw (`CgPipeline.sceneFold`, while `mergeEmission`): one draw, not two, which
  at 60 beams is most of what the scene cost the transparent pass. Their
  constants carry `sceneEmission` (0.5) as `CG_SCENE_GLOW`, which every Emissive pass's `CG_EMISSION` multiplies by
  under the scene alone. Bloom reads the scene (`CgBloom`). Its gate is `--mode=bloom-occlusion`: a ball behind a wall
  changes no pixel, on gl and vulkan.
- **The subject** (only while `CgFrameKeys.SUBJECT_READ` is on the firing: the post stack puts it while a drawn impact
  frame shows): what the effect glows with, published as `CgFrameKeys.SUBJECT` at the transparent stage for the impact
  frame to fill in ink. **Keyed** (`Draw.subject(key)`, named by `CgPostSettings.subject`; every VFX effect tags its
  draws): the keyed draws' Emissive passes, drawn by `recordOwnedSubject` at the top of the opaque stage into a
  quarter-size requested R11G11B10F target, so the depth hiding them is the host's world and no other effect's opaque
  smoke covers them. **Unkeyed**: every visible Emissive pass at quarter size with transparent surfaces covering
  (`CgPipeline.emissionOccluder`), after the transparent pass. Light pools (Forward-only) are in neither.
- **Half resolution** (`recordHalf`, before the transparent pass): every visible transparent draw marked
  `.halfResolution()` draws into a transient R11G11B10F target half the target's size, its constants' resolution that
  size, reading the target's depth through `readsDepth(from, unit)`; then `world_half_upsample.shader` adds it over
  the target, each pixel weighing the four texels round it by bilinear distance and by how near the depth each was
  drawn at is to its own (a joint bilateral upsample), the nearest in depth where none agrees. Only for light that
  adds: the upsample adds. GPU zones `world.half` and `world.halfAdd`.
- **Volumes** (`recordVolumes`, a half-size draw whose Forward pass blends `ONE ONE_MINUS_SRC_ALPHA`): drawn through
  `CgPipeline.as(VOLUME)` (folded first under the HDR scene, so their glow needs no march of its own) into a
  transient half-size target of two slots, RGBA16F light and the R16F eye depth each names with `CG_VOLUME_DEPTH`; then
  `env/compute/volume_resolve.compute`'s `Resolve` kernel blends it over this firing ordinal's `CgGraphTexture.history` (four kept: a fifth
  firing composites its frame alone), last frame reprojected through `cg_PrevViewProj` at that depth (the scene's where
  it names none) and clipped to the 3x3 neighbourhood's spread (Salvi's variance clipping). `CgStageFrame` decides
  validity; a history's first frame blends nothing. `world_volume_composite.shader` lays it over the target as the
  transparent pass's first draw (`sortKey(Long.MIN_VALUE)`), upsampled like the added light: what draws over it is
  dimmed by the media instead, so a spark inside smoke fades as it goes deeper. GPU zones `world.volume` and
  `world.volumeResolve`. The composite and the upsample are `"Media" = "Off"`.
- **Distortion** (`CgWorldDistortion`, reading the draws through its `Draws` view): every transparent draw of the stage whose chain has a
  Distortion pass draws it into a layer of a transient RGBA16F array of `distortionScale` (0.5) of the stage's size, its
  constants' resolution that size, reading the target's depth through `readsDepth(from, unit)`, and
  `world_distortion_apply.shader` bends the target by it over its hazes' rect, the offsets read bilinearly, reading
  a `sceneColor` copy cut to that rect, and writing the depth where each pixel read, so a draw after it is hidden by
  the bent scene, not by each thing's outline before the bend. `plan` keeps the per-haze order: walking the transparent draws in key order, a draw
  in `CgRenderQueue.AFTER_DISTORTION` and up (`Draw.afterDistortion()`) that the hazes pending before it overlap gets
  their apply as a draw in the transparent pass, sorted just before it and cut to their rect, drawn from one of four
  slots whose applies never overlap (recorded ahead of the pass, so it keeps one depth copy); it then draws in
  place. Where no slot is free it draws in the after-distortion pass, after the final apply, which takes every haze
  left. Each slot used, the final last, draws into the next layer of one five-layer array, so the layers in use are
  the first. Published as `CgFrameKeys.DISTORTION`, a `CgDistortionField`: the array and its count; the emission is published
  unbent, and bloom bends it by the field (`CgPostContext.distorted`). Its gate is `--mode=distortion`, every
  pixel against where it should have sampled at `distortionScale(1)`, on gl, gl33 and vulkan with synchronization
  validation.
- **The overdraw view** (`recordOverdraw`, last, only while `overdraw(true)`, which
  `-Dcrystalgraphics.post.debug=overdraw` sets): every transparent draw of the stage, half-size ones too, again
  through `CgPipeline.overdraw()` into an R16F target of the stage's size, published as `CgFrameKeys.OVERDRAW`. The
  variant keeps the draw's vertex stage and `discard`, makes its depth test a discard behind the stage's depth, and
  adds 1 a fragment. Its gate is `--mode=overdraw-count`, exact on gl and vulkan. While the `crystalgraphics` trace
  channel records, the count is read back into counters a frame or two later: `world.overdraw-covered` (the share of
  pixels reached), and over those `-mean`, `-p95` and `-max`. Half-size draws count at full size.
- **Before translucency**: after its lights, media and scene glows and before any transparent pass, a transparent
  firing runs the blackboard's `CgFrameKeys.BEFORE_TRANSLUCENCY` hook (the post stack's before-translucency
  materials), also in a firing with nothing of ours to draw. Custom data, custom depth and the world normals are
  published before it.
- **CustomDepth** (`recordCustomDepth`, near the top of the transparent firing, after custom data, as Unreal's after
  its base pass, CustomDepthRendering.cpp): every draw in
  view marked `Draw.customDepth(stencil, writeMask)` (or a `CgAnimInstance.customDepth`), opaque or writing depth,
  drawn by its depth pipeline into a transient DEPTH32F_STENCIL8 target of the stage's size, **tested against that
  target alone, never the scene's** (aaa-design §2.88: a mesh behind a wall is written), writing its stencil under its
  mask, depth-fail kept under 0xFF and replaced under a partial mask. Published as `CgFrameKeys.CUSTOM_DEPTH`; a pass
  reads its depth with `readsDepth(it, unit)` and its stencil through `it.stencil()`, a `usampler2D` (Unreal's
  CustomStencil, an SRV of the same texture). A set of instances refuses it. Its gate is `--mode=custom-depth`, exact on
  gl and vulkan. Each material's CustomDepth draw is its depth pipeline's program: the pass has none of its own.
- **World normals on demand**: the opaque pass writes `cg_world.normals` (`normalsFor`) while the lights cast shadows
  or the opaque firing carries `CgFrameKeys.WORLD_NORMALS_READ`, and the transparent firing publishes it as
  `WORLD_NORMALS`, the scene texture a post outline reads (Unreal's GBuffer normal). `--mode=custom-depth` checks it.
  A host framebuffer that takes no second attachment (0, multisampled) gets none, warned once: the lights then rebuild
  our normals from depth as blocks', and a character under a point light shades in facets.
- **Custom data** (`recordCustomData`, near the top of a transparent firing carrying `CgFrameKeys.CUSTOM_DATA_READ`):
  every opaque draw in view whose material writes custom data or a shading model (`CgMaterial.writesCustomData`) drawn
  again through `CgPipeline.as(CUSTOM_DATA)` into an RG32F texture beside the host's depth, tested and unwritten,
  published as `CUSTOM_DATA`. A host lending no depth (framebuffer 0) gets a target with its own depth, the host's
  copied in first (`world_depth_in.shader`); untested, a hidden writer's data would show through what covers it. A
  writer's redraw is compiled with `invariant gl_Position`, so its depth equals its colour draw's. Our forward passes have no G-buffer to carry Unreal's GBufferD, so this is one more draw
  of each writer, only in a frame a post material reads it.
- **Graded layers** (`recordGradeLayers`, after the after-distortion pass, under the HDR scene): every transparent draw
  marked `Draw.grade(look)` is drawn again into an RGBA16F layer of its look's beside the host's depth, cleared, tested at
  LEQUAL and unwritten, through the programs the transparent pass drew it with (folded or with its glow), under
  `layered(pipeline)`: its colour blend kept, its alpha the coverage (over for a blend that covers, nothing for one that
  adds); a multiply has no layer form and keeps the frame's look, as half-size draws do. A graded opaque or masked draw
  (drawn in the opaque firing) is drawn into its layer too, as its `CgPipeline.as(SOLID)` form, coverage 1 whatever
  alpha it writes, tested against the depth it wrote (an effect material's vertex stage is `invariant gl_Position`); the
  layers record in every transparent firing under the scene, transparent draws or none. At most `CgGradeLayers.MAX`
  looks a frame, each keeping its slot while it draws; published as `CgGradeLayers.KEY`, which the post stack blooms
  and tones by each look alone. A host lending no depth to share gets none, warned once. A graded separate draw goes
  into its look's `cg_grade_apart<k>` instead, laid over the layer with the scene's separate translucency.
- **Separate translucency** (`recordSeparate`, after the after-distortion pass, under the HDR scene where the host lends
  its depth): a draw sorted after distortion whose material state says `separateTranslucency` takes phase `APART`. It
  is left out of the passes and the distortion and drawn into `cg_separate_translucency`, an RGBA16F beside the depth,
  cleared, through `layered(pipeline)`: rgb the light it adds, a its coverage. Published as `CgSeparateTranslucency.KEY`;
  the post stack lays it over the scene after its colour regions (Unreal's `FTranslucencyComposition` after
  `PrePostProcessPass`). A blend that multiplies has no layer form, so it stays in the scene, warned once. GPU zone
  `world.separateTranslucency`.
- **The emissive state is ONE ONE, never `CgBlendState.ADDITIVE`** (SRC_ALPHA ONE): emission is written with an
  alpha of 0, which ADDITIVE multiplies away.
