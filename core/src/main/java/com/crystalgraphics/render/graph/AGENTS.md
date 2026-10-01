# render/graph — recordings, the frame graph, and its execution

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../AGENTS.md). Plan: `plan/crystalgraphics/render-graph.md`.
> What a draw is made of is [`render/draw/AGENTS.md`](../draw/AGENTS.md).

Three stages, three threads at most (`render-graph` §3.1):

| Stage | Type | Thread | GL |
|---|---|---|---|
| record | `CgRecording` — passes, chunks, snapshots, requests; sealed when done | any (one at a time per recording) | none |
| build | `CgFrameBuilder.build(CgFrameGraph)` → `CgFrame` — order, cull, transient lifetimes, batch, pack | any (one builder per thread) | none |
| execute | `CgExecutor.execute(CgFrame)` — uploads, passes, requests | render thread, inside a frame | all of it |

```java
CgRecording rec = new CgRecording();
CgRasterPass pass = rec.raster(target, CgLoad.clear(0, 0, 0, 0), constants, uiState, CgOrder.LOOKBACK);
pass.add(chunk);
pass.end();
CgFrame frame = builder.build(new CgFrameGraph().add(rec.seal()));
CgExecutor.execute(frame);
builder.recycle(frame);
```

`CgImmediate` (one package up) is the same three stages in one `try` block, for a caller with no graph — and
`CgImmediate.flush(chunk, order)` is what `CgQuadRenderer`/`CgVectorRenderer.flush()` call, under the frame block
the caller prepared (`render-graph` G2).

**What a recorded draw keeps of an immediate one** (G2's gate found each): a draw runs under the material as the
last `useMaterial` before it left it, since a bind used to upload values set after the records were queued; state a
caller sets after binding rides on the pipeline (`CgPipeline.withState`), or the pipeline's declared state would
overwrite it at the draw; and a texture bound by hand goes in through `bindTexture(unit, texture)`, winning the
unit as GL's last bind did.

**Held flushes** (G4): `CgImmediate.deferInto(target)` holds every flush made while `target` is bound until
`drain()`, each chunk under the scissor GL had at its flush (`CgRasterPass.scissor`, read from the state shadow) and
a pass per frame block. A flush into another target executes at once; one the shadow cannot place drains first. A
held draw executes after its renderer has moved on, so a snapshot keeps by value what an immediate draw read live:
a `CgTextureMutable` as the id it points at now, an attached uniform block as its bytes. A renderer's binding table
resets once a frame, not per `begin()`, since held chunks still name its snapshots.

## Rules

- **Order comes from reads and writes, not from creation.** A raster pass reads every `CgGraphTexture` its chunks'
  snapshots bind, as of `add`; it writes its target at `end`. A read sees the last write recorded before it — in its
  recording or one added to the graph earlier. Creation order only breaks ties.
- **A pass nobody reads is culled** unless it writes a texture that outlives the frame (imported, current,
  requested) or carries a request.
- **Transients live from their first to their last use** in the executed order, from a pool keyed by description.
- **Requests** (`upload`, `callback`, `compile`) report `DONE`/`FAILED` on `CgRequest`, readable from any thread; a
  pass that throws fails its request and the frame goes on.
- **One upload per kind per frame.** The executor binds its own instance buffers at the engine binding points and
  draws each batch's range through `cg_InstanceBase`. A nested execution (an immediate inside a callback) gets its
  own buffers and ring.
- **A recording is a value once sealed**: nothing changes it, nothing in it refers to its recorder.
  `CgRecording.reset()` is only for an owner that knows nobody else holds it.

## Gate scene

`--mode=graph-executor-test` draws one picture through `CgQuadRenderer`, `CgImmediate`, and a frame recorded and built
on a worker thread; the three PNGs in its output directory must be byte-identical, on `--device=gl` and `vulkan`.
