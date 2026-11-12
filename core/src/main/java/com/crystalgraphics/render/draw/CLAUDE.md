# render/draw — what a recorded draw is made of

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../AGENTS.md). Plan: `plan/crystalgraphics/render-graph.md`.

The vocabulary of the frame graph's recorded draws. **Everything here a recorder touches is CPU only**, because a
recorder will run on a UI document's own thread (`render-graph` §3.1); GL happens when the render thread executes.

| Type | Is | Thread |
|---|---|---|
| `CgPipeline` | a key: shader asset + pass + keyword set + render state + instance kind, interned to an `int` | `of`/`byId` any thread; `precache`/`program`/`bind`/`instanceBase` render thread |
| `CgMaterialBindings` | what a draw of a material binds, at one version: its block version, textures and samplers, engine blocks and buffers, storage buffers as handles. The proxy makes one when any moves (Unreal's material uniform buffer); `with(unit, texture)` lays a draw's own texture over it, kept per texture | made on the recorder's thread; immutable |
| `CgFrameBindings` | the executor's index of a frame's bindings: each version its draws name, entered once by identity, and its passes' own entries (constants, view textures, dispatch values) copied as they are packed; uploads blocks, gives arena slots and shares | the frame builder's, then the render thread's |
| `CgInstanceKind` | what instances are: `QUAD`, `CURVE`, `OBJECT` (an id pair: record, material slot), and the record stride each writes | — |
| `CgBlockVersion`, `CgMaterialArena` | a material block as it was when a value moved, in a page of its own or a slot of its shader's arena (one storage buffer of slots, Bevy's material slab) | made on any thread; slots given at frame build, written at `upload` |
| `CgPassConstants` | the pass's view and environment blocks (`CgViewBlock`, `CgEnvironmentBlock`) as a value, one per pass | any |
| `CgDrawChunk` / `CgChunkBuilder` | a run of draws under one property state (spatial, clip, effect node), with its instance records per kind and bounds per draw — Blink's paint chunk. A recording's own builder (`CgChunkBuilder.pooling`) refills its chunks after the recording's reset; `with` copies one that must outlive it | the builder: one recorder; the chunk: any, once ended |
| `CgOrder`, `CgBatcher` | `LOOKBACK` (painter's order, WebRender's lookback, never across a domain) or `SORTED` (by the recorder's key, stable); the batcher groups a pass's draws by pipeline + snapshot + kind + mesh | the frame builder's |

```java
// recording, any thread:
CgPipeline p = CgMaterialProxy.of(material).pipeline(CgInstanceKind.QUAD);
CgMaterialBindings b = CgMaterialProxy.of(material).bindings();   // the same object until a value, texture or buffer moves
chunks.draw(p, b.with(0, atlasPage));                               // a draw's own texture over the material's first

// frame build, then execution on the render thread:
int id = frame.bindings.add(chunk.binding(d));                      // once per version per frame, by identity
frame.bindings.upload(ring);
CgBindingSet set = deviceSets.set(rhi, frame.bindings, id, layout, CgBindingModel.MATERIAL, null);
```

## Rules

- **A recorder never reaches the device.** `pipeline()` parses at most (`CgMaterialShader.ensureParsed()`); it never
  compiles. `bindings()` reads no device name.
- **A draw names a version, never copies one.** `CgMaterialBindings` is immutable: a value written after a draw names
  it reaches only draws naming the next. A material unchanged since the last frame answers the same object, so a frame
  enters it once and its set is the one made before.
- **A texture in a version is a handle**, resolved when its set is made: one reallocated before execution binds its new
  storage. Its `CgSampler`, if any, is part of the version (`with(unit, texture, sampler)`).
- **A buffer in a version is a `CgBufferHandle`**, resolved the same way; a `CgGraphBuffer` is read by the raster pass
  the chunk is added to, so the graph orders the draw after the pass writing it.
- **An arena block binds as its arena.** An entry holding one has a `share` with every entry equal but for which
  version of that arena it holds: the executor joins and binds by shares, and each draw's id carries its slot
  (`slotOf`, the frame builder's), so instances of one shader differing only in values draw in one multi-draw. A batch
  whose ids carry no slot (its own records, another kind) binds its slot alone.
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
