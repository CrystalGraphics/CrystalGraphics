# render/scene/renderer — a scene drawn through a view

> Root guide: [`CrystalGraphics/docs/ENGINE_API.md`](../../../../../../../../../docs/ENGINE_API.md) § *The scene and its views*

Unreal's `FDeferredShadingSceneRenderer`, cut to a forward renderer inside a host's world: one `CgSceneRenderer` per
`CgScene` (`scene.renderer()`), run once per view inside the host's two world callbacks (`CgStageHost.WORLD_OPAQUE`,
`WORLD_TRANSPARENT`) or whole by `CgView.renderInto`. Consumers never call it: they spawn primitives, add rows
(`scene.once()`, a dynamic primitive), and register on the world stages it runs (`PREPARE`, `OPAQUE`, `SURFACES`,
`TRANSLUCENT`, `OVERLAY`).

| Type | Role |
|------|------|
| `CgSceneRenderer` | The scene's frame for a view: `opaque(firing)` then `translucent(firing)`, in Unreal's order (below). Its switches are `CgVar`s read through the view (`view.flag(MERGE_EMISSION)`, `HALF_RESOLUTION`, `SKIP_UNREADY`, `DISTORTION_SCALE`, `SCENE_EMISSION`, `GRADE_DRAWS`), overridable per view. It owns the scene's lights, media and labels until R13 splits them. `releaseAll()` at context teardown |
| `CgScenePipelines` | Every program the renderer draws with, collected at install into the precache and booted behind the loading screen (`beginBoot`, `bootStep`, `booting`); `settle()` once a frame; `precache(material, half, skinned, settings)` a material's |
| `CgSceneColour` | The HDR scene's colour: `sceneIn` decodes the host's colour into an RGBA16F with the host's depth (or a depth of its own where the host lends none, the window's), made the firing's target; `opaqueIn`/`opaqueOut` the opaque callback's, so the host's draws before the transparent one blend over ours. The post chain's composite encodes it back |
| `feature/glow/CgSceneGlows` | Emissive passes into the HDR scene (`recordSceneGlows`), and the impact frame's subject (`recordSubject`, keyed `recordOwnedSubject`) |
| `feature/translucency/CgHalfResolution` | Half-size draws and volumes: drawn small, upsampled by depth, volumes resolved against the view's history |
| `feature/translucency/CgLayerPasses`, `CgSeparateTranslucency` | Look channels (`render/look/CgLookChannels`) and separate translucency: a graded translucent row drawn again into its channel's layer, the light and cover the look weights read; a graded solid writes its look id in custom stencil bits 5-7 instead |
| `feature/distortion/CgDistortion`, `CgDistortionField` | Heat haze: bends drawn into an array, applies placed in the translucent pass, the final apply; the field published on the view |
| `feature/light/CgSceneLights` | The scene's lights, lit deferred over the host's picture ([`docs/LIGHTS.md`](../../../../../../../../../docs/LIGHTS.md)): a firing's order over its parts, each one role. `CgLightCollector` the frame's CPU rows (components, `onceLights`, dynamic sources); `CgLightList` the firing's list, blocks and appenders; `CgLightGrid` the view's placement and the binned cells (LightGridInjection); `CgShadowCasters` our opaque draws as distance-field and sun casters; `CgLightGBuffer` the normals; `CgMegaLights` the shadowed lights' sample, shade, temporal and denoise; `CgSunShadows` the sun's cascades; `CgLightVolume` the translucency volume and the lit air; `CgLightSettings` the switches and quality tiers |
| `feature/media/CgSceneMedia` | Smoke in the air as a froxel grid, lit by the lights; the scene's `CgHazeComponent`s inject through the renderer's own injector |
| `feature/field/CgVoxelWindow`, `CgVoxels`, `CgMeshDistanceFields`, `CgEntityBoxes` | The world, meshes and entities as distance fields: what shadow rays and particle collision read |
| `feature/debug/CgOverdraw` | The overdraw view, under `view.showFlags().overdraw()` |
| `scene/sort/CgSortKey` | Filament's key layout with a log-quantised distance: slot, sort layer, then opaque priority, material, distance, mesh, or translucent sort-point distance, solid bit, priority, distance. No far plane |
| `scene/sort/CgSortLayer` | Named sort layers, Unity's: a later one draws after an earlier whatever the distance. Built in `BACKGROUND`, `DEFAULT`, `EFFECTS`, `SEPARATE_TRANSLUCENCY`, `OVERLAY`; a mod defines its own `before`/`after` one in a static field; at most 128 |

## A view's frame

**Opaque callback**: the client world's schedule once a frame (the scene's first view), the parameter collections,
`CgScenePipelines.settle`; the scene's post volumes blended at the view's camera (`CgPostRenderer.prepare`); **`PREPARE`**; the keyed subject; the
opaque scene in; the GPU cull; the masked prepass; the base pass (normals beside it where asked); the opaque scene out;
**`OPAQUE`**.

**Translucent callback**: the scene in; the labels' depth; custom data; custom depth; normals; the lights (record,
casters, apply); the media; scene glows; decals; post before translucency; **`SURFACES`**; the translucent passes
(prepass, late solids, half size, volumes, distortion bends, the main pass with its applies, after distortion,
separate); the layers; the subject; the overdraw view; the labels; **`TRANSLUCENT`**; post; **`OVERLAY`**.

## Rules

- **The scene's lights are deferred: a lighting feature goes into the full-screen pass, never into a draw's shader.**
  Minecraft's blocks and entities are drawn by Minecraft (and by Sodium, Embeddium or Iris in its place), version by
  version, so anything that must light them reads depth and colour in `world/lights/lights.shader`. Only the front
  surface of each pixel exists there: what is behind it needs its own path.
- **Camera-relative.** A row's model matrix is its basis plus `position - view position`, the subtraction in doubles.
  A stage's pass constants come from `CgStageFrame.constants()`.
- **A world stage runs per view.** Work done once a frame whatever the views (a simulation's step) keeps its own frame
  stamp and skips a rehearsal's passes (`CgRehearsal.active()`); a renderer reaches the client scene unless its
  registration names others (`.scene(s)`, `.allScenes()`).
- **What a stage hands a later one goes on the view**: `view.textures()` (scene colour, depth, normals, custom depth
  and data, the pyramid, distortion, subject, overdraw) and `view.publish(key, value)` / `view.read(key)`. Both reset
  at the view's next frame. Ask for an optional texture before the opaque pass (`requestNormals`, `requestCustomData`,
  `requestSubject`), at `PREPARE`.
- **`cg_DepthBuffer` and `cg_SceneColor` are the graph's copies of the target** (`CgRasterPass.sceneDepth`,
  `sceneColor`): a reader sees every draw sorted before it, so a heat haze bends what is sorted under it and nothing
  above. `render/graph/CLAUDE.md` § *Reading the target*.
- **One material a row.** A second look on the same mesh is a second row.
- **A Forward pass that writes nothing is skipped** (`CgRenderState.writesNothing()`): a pure haze or glow is all
  Distortion or Emissive pass. The overdraw view counts its Distortion pass.
- **Rows made once a frame are taken by each view** (`CgMeshCollector.into(rows, owner)`, then `take(rows)` in
  `collect`): each view culls a row by its box (its mesh's, or `bounds(...)` with `pad`) and picks a `CgMeshLods` level
  by its screen height. A GPU-placed row (a count, records or instances) is never culled on the CPU.
- **Culled records** (`culled(scale, pad)`) are culled on the GPU per view against its depth pyramid, a few
  dispatches for every set; `docs/SHADERS.md` § *Drawing what a kernel wrote*.
- **The translucent prepass**: a material that is `"Depth" = "Prepass"` draws its depth pipeline first, so what blends
  over it, its own back lobes and the glows behind it, are hidden where it is solid.
- **Solids first**: a translucent row whose Forward draw writes depth draws before the rest of its group whatever its
  priority. A Prepass one keeps its order.
- **An opaque colour** goes into the opaque scene: a `"ColorSpace" = "Linear"` unlit material past white keeps its
  excess, as Unreal's unlit emissive in SceneColor. A lit opaque material's Emissive pass adds its glow in the
  translucent callback: its Forward colour is held at white.
- **Glows**: opaque and half-size rows' Emissive passes add into the scene before the translucent pass; a translucent
  row's Emissive pass follows its Forward draw at the same key, or is folded into it (`MERGE_EMISSION`): one draw, not
  two. Every Emissive pass is scaled by `SCENE_EMISSION` (0.5) under the scene. Gate: `--mode=bloom-occlusion`.
- **The subject**, while the post chain shows a drawn impact frame (it asks at `prepare`): keyed rows
  (`subject(key)`, every VFX effect's) drawn at the top of the opaque callback into a quarter-size target; unkeyed, every
  visible Emissive pass after the translucent pass.
- **Half resolution**: a translucent row marked `halfResolution()` draws into a half-size target, then a joint
  bilateral upsample adds it. Volumes (a half-size row blending `ONE ONE_MINUS_SRC_ALPHA`) resolve against the view's
  history and lay over the target as the translucent pass's first draw. With `HALF_RESOLUTION` off both draw at full size.
- **Distortion**: bends draw into an array at `DISTORTION_SCALE` of the view; a row sorted after distortion
  (`afterDistortion()`) that pending hazes overlap gets their apply placed just before it, on one of four slots; the
  rest go to the final apply. Published as `CgDistortionField.KEY` on the view. Gate: `--mode=distortion`.
- **The overdraw view** (`showFlags().overdraw(true)`, or `-Dcrystalgraphics.post.debug=overdraw`): every translucent
  row again into an R16F count, `textures().overdraw()`. Gate: `--mode=overdraw-count`.
- **CustomDepth**: a primitive's `customDepth(stencil, mask)` (a dynamic primitive's rows take their primitive's),
  drawn by its depth pipeline into a DEPTH32F_STENCIL8 target of the view, **tested against that target alone**
  (a mesh behind a wall is written). `textures().customDepth()`, its stencil through `stencil()`. Gate:
  `--mode=custom-depth`.
- **Custom data**, while asked (`requestCustomData`, a post material that reads it): every opaque row whose material
  writes custom data drawn again into an RG32F beside the depth, tested and unwritten.
- **Graded layers** (`grade(look)`, while `GRADE_DRAWS`): drawn again into a layer of their look's, bloomed and toned
  by it alone. **Separate translucency**: a material state's `separateTranslucency` row drawn apart and laid over the
  scene after its colour regions (Unreal's `FTranslucencyComposition`).
- **The emissive state is ONE ONE, never `CgBlendState.ADDITIVE`**: emission is written with an alpha of 0, which
  ADDITIVE multiplies away.
- **Show flags** (`view.showFlags()`): lights, media, post, labels, distortion, decals, translucency off per view, as
  Unreal's `FEngineShowFlags`; a view of a scene of its own blends none of the client's post volumes.
