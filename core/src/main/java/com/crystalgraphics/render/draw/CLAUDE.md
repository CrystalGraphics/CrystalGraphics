# render/draw — what a recorded draw is made of

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../AGENTS.md). Plan: `plan/crystalgraphics/render-graph.md`.

The vocabulary of the frame graph's recorded draws. **Everything here a recorder touches is CPU only**, because a
recorder will run on a UI document's own thread (`render-graph` §3.1); GL happens when the render thread executes.

| Type | Is | Thread |
|---|---|---|
| `CgPipeline` | a key: shader asset + pass + keyword set + render state + instance kind, interned to an `int` | `of`/`byId` any thread; `precache`/`program`/`bind`/`instanceBase` render thread |
| `CgBindingTable` | a recording's binding snapshots: block bytes copied, textures, storage buffers and buffer textures as handles, interned by content | filled by one recorder; `upload`/`bind` on the render thread after handover |
| `CgInstanceKind` | what instances are: `QUAD`, `CURVE`, `OBJECT`, and the record stride each writes | — |
| `CgPassConstants` | the pass block (`CgFrameBlock`'s layout) as a value, one per pass | any |
| `CgDrawChunk` / `CgChunkBuilder` | a run of draws under one property state (spatial, clip, effect node), with its instance records per kind and bounds per draw — Blink's paint chunk. A recording's own builder (`CgChunkBuilder.pooling`) refills its chunks after the recording's reset; `with` copies one that must outlive it | the builder: one recorder; the chunk: any, once ended |
| `CgOrder`, `CgBatcher` | `LOOKBACK` (painter's order, WebRender's lookback, never across a domain) or `SORTED` (by the recorder's key, stable); the batcher groups a pass's draws by pipeline + snapshot + kind + mesh | the frame builder's |

```java
// recording, any thread:
CgPipeline p = material.pipeline(CgInstanceKind.QUAD);
int bindings = material.captureBindings(table);
int pass = constants.capture(table);

// execution, render thread:
table.upload(ring);
table.bind(pass);
if (p.bind()) { table.bind(bindings); CgMeshStore.get().draw(mesh, instances, first); }
```

## Rules

- **A recorder never reaches GL.** `pipeline()` parses at most (`CgMaterialShader.ensureParsed()`); it never
  compiles. `captureBindings()` reads no GL name.
- **A texture in a snapshot is a handle**, resolved at `bind`: one reallocated before execution binds its new
  storage. Its `CgSampler`, if any, is part of
  the snapshot (`texture(unit, texture, sampler)`): equal samplers intern to one id, another makes another.
- **A buffer in a snapshot is a `CgBufferHandle`**, resolved at `bind` the same way; a `CgGraphBuffer` is read by the
  raster pass the chunk is added to, so the graph orders the draw after the pass writing it.
- **A sampler's unit is its index among the shader's declared samplers.** `CgMaterialShader` wires every program
  that way at compile, so nothing per material is left to wire at execution.
- **A draw carries its bases**: its first instance (where its batch's records start, `CgMeshStore.SHARED_RECORD` set
  where every instance reads one) and its base vertex, read by `CG_INSTANCE_ID` and `CG_VERTEX_ID` through
  `gl_BaseInstance`/`gl_BaseVertex`. No uniform is set per draw, so one pipeline draws a mesh alone or joined into a
  multi-draw (`render/graph/CLAUDE.md`).
- **A pipeline's forms are `CgPipelineForm`s**, added by `as(form)` (null where the form does not apply) and asked by
  `has(form)`; the enum's table is the one place saying which combine. `COVER` is for glows drawn apart from the scene
  (an impact frame's subject), `VOLUME` the world's volume pass (composable with `FOLD`), `FOLD` the HDR scene's glow
  added to the colour, `DEPTH` a `"Depth" = "Prepass"` material's prepass, `NORMALS` and `CUSTOM_DATA` an opaque draw's
  extra targets, `SOLID` a layer's cover, `OVERDRAW` the overdraw view. `SKINNED` is the skinned vertex factory:
  `CgChunkBuilder.draw` takes it for a mesh in a skinned format, so a recorder never asks, and every form of a skinned
  pipeline stays skinned. `slotWrites()` is the colour attachments a pipeline's program writes, which a pass with more
  than one masks the rest off by.
- A pipeline lives for the session, and is keyed by the *identity* of its render state: the first compile reuses the
  parse of unchanged source, so a key named before it is the one that draws after it.
