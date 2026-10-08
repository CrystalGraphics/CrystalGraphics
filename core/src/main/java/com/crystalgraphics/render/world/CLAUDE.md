# render/world — the world, drawn under the host's camera

> Root guide: [`CrystalGraphics/docs/ENGINE_API.md`](../../../../../../../../docs/ENGINE_API.md) § *CgWorldRenderer*

| Type | Role |
|------|------|
| `CgWorldRenderer` | Registered on `WORLD_OPAQUE` and `WORLD_TRANSPARENT` at `ORDER` (1000). Holds the frame's draws flat (absolute positions in doubles, local transforms, customs, queue, sort layer, order, group); at each stage culls them against the stage's view, picks a `CgMeshLods` draw's level by its screen height, sorts them, and records the prepass and the opaque or transparent pass onto the host's target, each declaring `sceneDepth`/`sceneColor` so its readers sample the target as it stands. `onFrame` listeners run once a frame, first in the first world stage's firing (only camera shake before them), so renderers registered below `ORDER` that read what they submit (VFX pools and Range lay out slots from it) see it the same frame |
| `CgWorldText` | `world.text(...)`'s labels: pooled, each a placement and a `CgTextRenderer.queuedDraw` of one manual-sized renderer, drawn into each transparent firing after its passes under a pose of view × position × rotation (or the view's turn undone, a billboard). **Retained**: label i keeps a copy of its draw and its `CgTextCapture`, captured again only when the draw differs (`Draw.sameAs`), the capture was incomplete or the atlas evicted. Records live in one persistent buffer per (rank, batch), label i's in ranges whose `node` is i; a changed label rewrites its ranges in place where it fits, else appends and leaves holes (zeroed records, no area), compacted past 4,096 and half. Each firing uploads the dirty span of each batch and every label's matrix (an OBJECT record), draws the text and line batches (ranks 0 to 2) into depth alone (`drawCapturedDepth`, alpha 0.5 and up), then every batch with `drawCaptured`: a nearer label's text hides a farther one's whatever their order. `-Dcrystalgraphics.text.retainedLabels=false` draws every label every frame (`drawQueued`) instead, far to near. Never deletes its renderer: `CgTextRendererRegistry` does at teardown |
| `CgSceneTarget` | The HDR scene's first pass, on `WORLD_TRANSPARENT` at `ORDER` 1 while `hdrScene()` is on: `world_scene_in.shader` decodes the host's colour (`sceneColor(unit, current)`) into an RGBA16F `besideCurrentDepth` texture, published as `CgFrameKeys.SCENE` and made the stage's target. A host lending no depth (framebuffer 0: the harness by default, 1.7.10 without framebuffers) gets a scene with its own depth, written from the host's by the same pass (`DEPTH`), so depth our draws write there never reaches the host. The post stack's composite encodes it back and hands the target back |
| `CgSortKey` | Filament's key layout with a log-quantised distance: slot, sort layer, then opaque order, material, distance, mesh, or transparent group distance, order, distance. No far plane |
| `CgSortLayer` | Named sort layers, Unity's: a later one draws after an earlier whatever the distance. Built in `BACKGROUND`, `DEFAULT`, `EFFECTS`, `OVERLAY`; a mod defines its own `before`/`after` one in a static field |

## Rules

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
- **Emission** (`recordEmission`, after the transparent pass): every visible draw whose chain has an Emissive pass
  draws that pass into a transient R11G11B10F target, the tier's share of the target's size (Low 0.25, Ultra 1, else 0.5;
  `emissionScale` overrides it), reading the stage
  target's depth through `sceneDepth(unit, from)`, and publishes it as `CgFrameKeys.EMISSION`. Every transparent
  surface that blends over what is behind it is drawn there too, as black at its alpha (`CgPipeline.emissionOccluder`),
  sorted with the glows: a transparent thing writes no depth, so this is what hides the glows behind it from bloom, as
  an opaque thing's depth does. **It produces and stops**:
  blurring and compositing are the post stack's (`render/post`), and with bloom off nothing reads the target, so the
  graph culls the pass. Its gate is `--mode=bloom-occlusion`: a ball behind a wall changes no pixel, at emission scales
  1 and 0.5, on gl and vulkan.
- **Merged emission** (`mergedEmission`, on by default, `mergeEmission(false)` for the comparison): where the stage's
  target is a framebuffer of the host's (`mainFramebuffer() > 0`) and the device masks attachments independently
  (`CgCapabilities.independentBlend()`: every GL context, the owned device, Minecraft 26.2's where the GPU has it), a transparent draw whose Emissive pass folds into its
  Forward pass (`CgPipeline.emissionTarget`, codeless and on one blend) draws both at once, the transparent and
  after-distortion passes writing a target-sized emission as a second attachment (`CgRasterPass.attachment`), cleared
  by a pass before them. The emission pass then draws only what did not fold (opaque, half-size and authored Emissive
  passes) into the same texture, loading it. Every other draw that blends over what is behind it writes black at its
  colour's alpha there (`CgPipeline.emissionCover`), so a surface in front of a glow hides it from bloom as it hides its
  colour; one that adds leaves the slot masked off. The emission pass is split to keep that order: the glows whose
  colour draws before the transparent pass (opaque and half-size draws: a beam's volumes) go into the emission before
  it, the rest after it, where nothing covers them. At 60 beams it took the emission pass (2.8 ms) for about 0.5 ms more in the transparent pass;
  GPU p90 15.8 to 13.3 ms. bloom-occlusion compares two transparent glows merged and apart byte for byte.
- **Glows under the HDR scene** (`CgFrameKeys.SCENE` on the firing): no emission target, merged or not. Opaque and
  half-size draws' Emissive passes add into the scene before the transparent pass (`recordSceneGlows`, reading its
  depth through `sceneDepth(unit)`); a transparent draw's Emissive pass follows its Forward draw in the transparent pass
  at the same key (`recordPass`), which SORTED keeps in order, so a nearer surface covers glow and colour alike. Bloom
  reads the scene (`CgBloom`).
- **Half resolution** (`recordHalf`, before the transparent pass): every visible transparent draw marked
  `.halfResolution()` draws into a transient R11G11B10F target half the target's size, its constants' resolution that
  size, reading the target's depth through `sceneDepth(unit, from)`; then `world_half_upsample.shader` adds it over
  the target, each pixel weighing the four texels round it by bilinear distance and by how near the depth each was
  drawn at is to its own (a joint bilateral upsample), the nearest in depth where none agrees. Only for light that
  adds: the upsample adds. GPU zones `world.half` and `world.halfAdd`.
- **Distortion** (`CgWorldDistortion`, reading the draws through its `Draws` view): every transparent draw of the stage whose chain has a
  Distortion pass draws it into a layer of a transient RGBA16F array of `distortionScale` (0.5) of the stage's size, its
  constants' resolution that size, reading the target's depth through `sceneDepth(unit, from)`, and
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
- **The emissive state is ONE ONE, never `CgBlendState.ADDITIVE`** (SRC_ALPHA ONE): emission is written with an
  alpha of 0, which ADDITIVE multiplies away.
