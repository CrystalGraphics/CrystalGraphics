# render — recorded drawing, and where it draws

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../AGENTS.md)

## What This Package Is

The engine's drawing model: draws are recorded into chunks and passes, built into a frame and executed on the render
thread. The root holds what every recorder shares; each sub-package is one layer of it.

| Type or package | Role |
|------|------|
| `CgImmediate` | "Draw this now" through the same recording and executor: chunks into the bound framebuffer, built and executed at once. `CgImmediate.constants()` is what a renderer's immediate `flush` draws under |
| `CgFrameClock` | The frame's time, advanced once per host frame; every pass's `cg_Time` |
| `CgViewFrustum` | AABB and sphere tests against a view-projection: the world renderer culls by it, the text culler too |
| `draw/` | Pipelines, binding snapshots, instance kinds, chunks, pass constants, the batcher — what a recorded draw is made of. Its own guide |
| `graph/` | Recordings, the frame graph, the frame builder and the executor. Its own guide |
| `mesh/` | `CgMeshStore` (where meshes' GPU copies live: placed per frame, uploaded before the first pass, drawn with base-vertex calls, or a run of them sharing a slab as one multi-draw), `CgMeshPool` (one vertex format's slabs: a vertex buffer, an index buffer and one VAO each), `CgOffsetAllocator` (O(1) ranges, ported from Aaltonen's OffsetAllocator) |
| `stage/` | `CgRenderStage`, `CgStageFrame`, `CgHostFrame`, `CgHostView`: points in a host's frame, and the host's camera at each. `CgFrameKey`, `CgFrameResources`, `CgFrameKeys`: one firing's blackboard (`CgStageFrame.resources()`) |
| `world/` | `CgWorldRenderer`: meshes drawn into the world under the host's camera. Its own guide |
| `post/` | `CgPostStack`: what runs after the world on the same firing, effects at points and one composite; bloom is its built-in effect. Its own guide |

---

Also loaded with this folder:

@../../../../../../../docs/ENGINE_API.md
