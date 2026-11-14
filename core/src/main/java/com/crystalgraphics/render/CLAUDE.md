# render — recorded drawing, and where it draws

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../AGENTS.md)

## What This Package Is

The engine's drawing model: draws are recorded into chunks and passes, built into a frame and executed on the render
thread. The root holds what every recorder shares; each sub-package is one layer of it.

| Type or package | Role |
|------|------|
| `CgRenderer` | Drawing outside the world: `executeNow(recording)` builds and executes a recording at once (a preview, a bake, a test); `drawNow(chunk, camera, order)` is an immediate canvas's flush into the current target; `clear` and `copy` work on an external target outside any graph |
| `CgFrameClock` | The frame's time in double seconds and its index, advanced once per host frame; every pass's `cg_Time`, narrowed to a float only there (`CgPassConstants.time`). CPU code never keeps clock time in a float |
| `CgWarmBudget` | The CPU milliseconds a frame warm-ups may take (`crystalgraphics.warmBudget`, 4): `CgPipeline.precache`, `CgMaterial.precache` and `CgVfxSystem`'s queue start work only while it allows, so a warm-up spreads over frames. Unlimited until the graphics start (`CgGraphicsLifecycle.init`) starts it |
| `CgCpuMeter` | A consumer's CPU nanoseconds: added by its own code from any thread, and by the executor for each compute pass charged with `charged(meter)`, timed as it runs; taken once a frame by whoever budgets it (`vfx.scalability.CgVfxBudget`) |
| `CgGpuBudget` | A consumer's GPU milliseconds a frame and the scale that holds its passes inside them: passes charged with `timed(budget)` are timed through `CgGpuTrace`'s budget slots whatever the trace, and `tickFrame` steps each scale |
| `CgViewFrustum` | AABB and sphere tests against a view-projection: the world renderer culls by it, and `CgCanvas.visible` |
| `draw/` | Pipelines, material bindings, instance kinds, chunks, pass constants, the batcher — what a recorded draw is made of. Its own guide |
| `graph/` | Recordings, the frame graph, the frame builder and the executor. Its own guide |
| `mesh/` | `CgMeshStore` (where meshes' GPU copies live: placed per frame, uploaded before the first pass inside `cgBeginTransfer` brackets, kept content then staged bytes, so on Vulkan the copies run on the transfer queue and the frame's queue waits for them at its next pass, drawn with base-vertex calls, the vertex stage pulling its vertices, or a run of them of one format as one multi-draw; a draw unplaced in the prologue throws), `CgMeshPool` (one vertex format's vertex buffer and index buffer, grown by copy), `CgOffsetAllocator` (O(1) ranges, ported from Aaltonen's OffsetAllocator) |
| `stage/` | `CgRenderStage`, `CgStageFrame`, `CgHostFrame`: points in a host's frame, and the host's camera at each (`host().camera()`, a `camera.CgCamera`; a firing's ready to draw through as `frame.camera()`, a GUI stage's GUI pixels as `frame.guiCamera()`). `CgDirectionalLights`: directional lights besides the host's sun, the highest priority (then the brightest) taking `cg_SunDirection` in `CgWorldAtmosphere` and its collection `directional_light`. `CgFrameKey`, `CgFrameResources`, `CgFrameKeys`: one firing's blackboard (`CgStageFrame.resources()`) |
| `view/` | `CgView`: one camera's picture frame after frame (`CgView.main()`, a host's own for an anaglyph eye or a portal, named by `host().view(...)`; `unkept()` for a rehearsal and a repeat firing), as Unreal's `FSceneView`. `CgViewState`, its `FSceneViewState`: what one firing hands a later one (the opaque scene, normals, subject, the depth pyramid) and each temporal history (`history(key, desc, recording)`, `fresh(key)`), as a renderer's record made at its first ask (`get(Key)`). Never a renderer's field, and never keyed by which firing of the frame it is |
| `scene/` | The scene: primitives kept until removed, as Unreal's `FScene`; it replaces `world/`. `CgScene` (`client()`, or one of your own) makes components. `component/`: `CgPrimitiveComponent` and its kinds (`CgMeshComponent`, `CgInstancedMeshComponent`, `CgDynamicComponent` over a `CgDynamicPrimitive` and its `CgMeshCollector`), `CgScenePrimitives` the spawned set and the gather: frustum, reach, levels, each primitive's world box kept until it moves. `gpu/`: `CgGpuScene`, the records kept on the GPU (Unreal's GPUScene), a slot per primitive or instance placed by a tile and a model within it, its previous transform beside it; every pass binds them (`cg_SceneObjects`) and an object id with the scene bit names one, decoded by `cg_env.glsl`'s `cg_ObjectData`. `visibility/`: `CgViewVisibility`, what a view sees of a scene, made once a frame at the first ask and kept in the view's state (Unreal's `FViewInfo` from `InitViews`); a world stage's firing asks for the client scene's before its callbacks; `CgInstanceCulling`, its instanced rows culled per instance on the GPU against the frustum and a depth pyramid (`instance_cull.compute`), kept ids and a count per row. `batch/`: `CgMeshBatches`, a view's rows (Unreal's `FMeshBatch`), each with its distance and sort key. `sort/`: `CgSortLayer`, `CgSortKey` |
| `world/` | `CgWorldRenderer`: meshes drawn into the world under the host's camera. Its own guide. Being replaced by `scene/`: build nothing new on it |
| `post/` | `CgPostStack`: what runs after the world on the same firing, effects at points and one composite; bloom is its built-in effect. Its own guide |

---

Also loaded with this folder:

@../../../../../../../docs/ENGINE_API.md
