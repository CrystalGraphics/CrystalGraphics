# render/graph — recordings, the frame graph, and its execution

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../AGENTS.md). Plan: `plan/crystalgraphics/render-graph.md`.
> What a draw is made of is [`render/draw/CLAUDE.md`](../draw/CLAUDE.md).

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

**Kernels and buffers** (gpu-compute C3): a compute pass runs kernels from `.compute` files (`compute/CLAUDE.md`) on
`CgGraphBuffer`s — the twin of `CgGraphTexture`: transient (pooled by size class), persistent, history (the newest two
versions, every write making the next) or imported — and on graph textures as storage images. A dispatch's bindings
say what it reads and writes, taken from the accessors its kernel uses, so ordering, culling and lifetimes come from
them as from a raster pass's; `fill`, `update` and `copy` on buffers are passes ordered like any write.

```java
CgGraphBuffer state = CgGraphBuffer.history("particles", CgBufferDesc.elements(n, 32, CgBufferUsage.STORAGE));
CgComputePass sim = rec.compute("particles.step", constants);
sim.dispatch(step, n).bind("IN", state).bind("OUT", state).set("_Drag", 0.1f);   // reads the newest, writes the next
sim.end();
CgComputePass paint = rec.compute("particles.paint");
paint.dispatchIndirect(draw, args, 0).bind("IN", state).bind("BEFORE", state.previous()).image("OUT", picture);
paint.end();
int bindings = rec.bindings().begin().storage(CELLS_POINT, state).end();   // a draw reading it as a storage block
```

**Mip levels.** A graph texture the graph allocates may hold levels (`CgTextureDesc.withMips()`, or a count): a pass
draws into level 0, a kernel writes any (`dispatch.image(name, texture, level, -1)`), and anything sampling it filters
trilinearly. `CgGpuOps.downsample` fills the chain. An imported framebuffer carries its own count
(`CgFrameBuffer.createOwned(name, w, h, format, levels)`).

```java
CgGraphTexture bloom = CgGraphTexture.transientTexture("bloom", new CgTextureDesc(w, h, HDR).withMips());
CgFrameBufferFormat r32f = CgFrameBufferFormat.builder("hi-z").color(0, CgTextureType.R32F).build();
CgGraphTexture hiZ = CgGraphTexture.requested("hi-z", new CgTextureDesc(w, h, r32f, 6));   // six levels
```

**A pass timed on its own** (`CgRasterPass.timed(zone)`, `CgComputePass.timed(zone)`, the zone a name made once
with `CgGpuTrace.name`): the executor brackets that pass in a GPU zone, which splits the stage's own (`gpu:<name>`).

**Barriers are the executor's** (`CgHazards`): before each access it compares the storage's last accesses — per GL
name, so pooled transients, a history's two versions and a buffer used across frames each come out right — and issues
`cgBufferBarrier`/`cgImageBarrier` wherever a kernel takes part: exact on the tracked backend, the reader's
`glMemoryBarrier` bits on GL. Draws, copies and uploads among themselves stay the backend's to order, as they were.
`-Dcrystalgraphics.graph.barriers=false` keeps the bookkeeping and issues nothing; synchronization validation must then
fail `--mode=compute-graph`. Below compute (G40, G33, CPU) the bookkeeping runs and no barrier is issued: a lowered
kernel's writes are draws, ordered like any draw, and a CPU body's are uploads.

**Reading the target** (`CgRasterPass.sceneColor(unit)`, `sceneDepth(unit)`): a draw whose shader reads
`cg_SceneColor` or `cg_DepthBuffer` samples a copy of the pass's own target (`CgTargetCopy`). The builder walks the
pass's batches in their sorted order and places a copy before a reader of what a draw since the last copy wrote:
colour by any draw with colour writes on, depth only by a depth write. The pass starts with neither copied, so its
first reader always copies. A reader's own writes leave what it reads clean, so readers in a row share one copy and
never see each other (Godot's screen-texture rule). There is no cap: a colour copy is cut to the union of its
readers' screen bounds, each grown by its shader's `SceneColorMargin` tag (a share of the target's height: how far
it samples past its geometry, required of every shader reading `cg_SceneColor`, which otherwise fails to parse), and
refreshed in place in one texture per pass; a reader whose rect the last copy holds and no draw since wrote into
takes none. Depth is copied whole. A world draw's bounds are its box projected to the screen, cut at the near plane,
so only an eye inside the box covers the whole screen; a draw with none copies the whole target. The executor blits before the batch, scissor off, and binds the copy at the pass's
unit (`graph.target-copies`, `graph.target-copy-pixels`); a blit is ordered among draws by the backend, as any copy
is.

```java
CgRasterPass pass = recording.raster(target, CgLoad.load(), constants, state, CgOrder.SORTED)
        .sceneColor(CgBindingPoints.SCENE_COLOR_TEXTURE_UNIT)    // haze, glass, water: the target as drawn so far
        .sceneDepth(CgBindingPoints.DEPTH_TEXTURE_UNIT);         // soft particles, depth fades
```

- Each pass copies into one framebuffer from the transient pool, held from its first copy to its end; a later copy
  overwrites the earlier, so nesting and passes of other sizes never disturb it, and a steady frame allocates nothing.
- The copy has the target's formats (a float target keeps its range); the current target's colour is RGBA8 and its
  depth in its own format, the viewport's size.
- Sort order decides what a reader sees: it bends what sorted before it. The world renderer's groups and orders
  place a haze after what it should bend (`render/world/AGENTS.md`).

**Another target's depth** (`sceneDepth(unit, from)`): a pass into a target of its own (a bloom target, any size)
reads `from`'s depth as it stands after every write recorded before the call, copied once, whole, when the pass begins.
Readers sample by `gl_FragCoord.xy / CG_RESOLUTION`. The world's bloom is the user (`render/world/CLAUDE.md`).

```java
recording.raster(glow, CgLoad.clear(0, 0, 0, 0), constants, null, CgOrder.SORTED)
        .sceneDepth(CgBindingPoints.DEPTH_TEXTURE_UNIT, CgGraphTexture.current());
```

- **`CgGraphTexture.current()` is the framebuffer and viewport bound when the execution began**, not whatever is
  bound now: after a pass into another target the executor binds it back, so a pass into `current()` after a bloom
  pass draws onto the host's target rather than the bloom's.
- Give the pass the host's depth convention (`CgStageFrame.defaults`), or the eye depths disagree.

**Indirect draws** (gpu-compute C4): a mesh draw takes how much it draws from a `uint` a kernel wrote, through
`CgChunkBuilder.indirect(count, offset, mode, factor)` or `CgWorldRenderer`'s `.indirect`. The raster pass reads the
count as a kernel would, so the pass that writes it runs first; before the pass begins the executor writes each indirect
draw's command with an engine kernel (`env/compute/args.compute`, `CgIndirectArgs`) from the count and the range the
mesh store placed, then draws it with `glDrawElementsIndirect`/`glDrawArraysIndirect`. On G40 that kernel runs lowered;
on G33 and the CPU tier, where no draw takes a count from a buffer, the batch is drawn with its count read back (a stall,
`buffer.readbacks`) or, where the CPU tier wrote it, taken from the CPU's copy with no read.

```java
CgComputePass live = rec.compute("sparks.count");
live.dispatch(compact, capacity).bind("STATE", state).counter("ALIVE", alive, 0);
live.end();
chunks.draw(pipeline, bindings, CgMesh.quads(capacity)).indirect(alive, 0, CgIndirect.INDICES, 6);   // a quad each
chunks.instance();                                                                                      // its record
```

- `INDICES` and `VERTICES` draw count x factor of the range, never more than it holds; `INSTANCES` draws the range
  count x factor times, every instance reading the draw's one record, and `CG_DRAW_INSTANCE` is which element it is.
- An indirect draw is a batch of its own, and its command's first instance is 0 on every device: the instance base is
  `cg_InstanceBase`, as for any draw.
- Each command has a slot of its own, aligned for a storage binding (`CgCapabilities.storageOffsetAlignment`), so the
  kernels writing them share nothing and need no barrier between them.

**A frame executes again** (`CgExecutor.executeAgain(frame, keepRequested)`) with what its passes read as it stands
now — property values, above all — and its uploads, compiles and releases not repeated; `keepRequested` skips every
pass writing a requested texture too. It is how a compositor moves something without a recording. A compute pass or buffer operation that writes
anything outliving the frame is not taken twice — a frame shown again must not step a simulation — unless marked
`again()`; one writing only transients runs again. A skipped pass whose transient a pass run again reads throws,
naming both.

`CgImmediate` (one package up) is the same three stages in one `try` block, for a caller with no graph — and
`CgImmediate.flush(chunk, order)` is what `CgQuadRenderer`/`CgVectorRenderer.flush()` call, under the frame block
the caller prepared (`render-graph` G2).

**What a recorded draw keeps of an immediate one** (G2's gate found each): a draw runs under the material as the
last `useMaterial` before it left it, since a bind used to upload values set after the records were queued; state a
caller sets after binding rides on the pipeline (`CgPipeline.withState`), or the pipeline's declared state would
overwrite it at the draw; and a texture bound by hand goes in through `bindTexture(unit, texture)`, winning the
unit as GL's last bind did.

**Recorded flushes** (G4; lookback G5.3): a renderer given a `CgChunkSink` (`CgQuadRenderer.sink`, `CgVectorRenderer.sink`,
`CgTextRenderer.sink`) hands each flush's chunk to it instead of drawing. `CgPassRecorder` is the sink a recorder
owns: `recordInto(recording, target, load, constants)` picks the target, `scissor`/`noScissor` and `constants` are
its set-state, a change of constants starts a pass, `endPass()` splits; nothing is read from GL. The caller executes
the recording once with `CgImmediate.execute(recording)`. A recorded draw executes after its renderer has moved on,
so a snapshot keeps by value what an immediate draw read live: a `CgTextureMutable` as the id it points at now, an
attached uniform block as its bytes. A renderer's binding table resets once a frame, not per `begin()`, since
recorded chunks still name its snapshots. A recorder's passes are `LOOKBACK`: a recorded chunk is one spatial node's
(the run ends it where the records' node changes) and each draw carries its records' bounds there, so a draw moves
back past the draws it does not touch, within its node's domain.

**Replay** (G6.3): `CgReplay` keeps the chunks a recorder took between two `chunksTaken` readings -- found on its
per-frame tape, `taken(n)` -- with the clip entries and shapes the stretch added and its snapshots copied into a table
that outlives the recordings, and adds them to a later recording renumbered: clip entries, shapes and nodes, and
nothing allocated when nothing renumbers. A stretch is kept only if it recorded nothing but chunks and changed no
pass or scissor (`stateChanges`, `CgRecording.operations`), drew in one node, named no clip from outside its chain
and binds no texture made for one frame. A snapshot holds a texture view as what it pointed at, so whatever moves a
kept record's target -- an atlas grown or evicted -- must void the stretch: the caller's epoch. `differs` compares two
stretches but for what replay renumbers, which is the check mode.

**Damage** (G7.3): `CgRasterPass.damage(x, y, w, h)` limits a pass into a target that keeps its contents -- a window's
surface -- to what changed: the executor clears and scissors every draw to the rect, and skips a pass whose rect is
empty (`graph.passes.undamaged`). The rect is the target's bottom-left pixels; executing the pass again is idempotent.
`CgExecutor.executeAgain(frame, true)` skips every pass into a requested texture -- a surface keeps its picture while
its node moves (`graph.again.requested-kept`); with `false` they draw whole (`graph.again.requested-drawn`).
`CgTargetCompare` reads two targets back and names where they differ, for a check mode.

## Rules

- **Order comes from reads and writes, not from creation.** A raster pass reads every `CgGraphTexture` its chunks'
  snapshots bind, as of `add`; it writes its target at `end`. A read sees the last write recorded before it — in its
  recording or one added to the graph earlier. Creation order only breaks ties.
- **A pass nobody reads is culled** unless it writes a resource that outlives the frame (an imported, current or
  requested texture; an imported, persistent or history buffer) or carries a request.
- **Transients live from their first to their last use** in the executed order, from a pool keyed by description
  (textures) or size class (buffers): two that never live at once share storage.
- **A compute pass runs on every tier**: each dispatch as its kernel's form (`compute/CLAUDE.md` § *Three forms*),
  chosen when the dispatch is recorded, which is where a kernel that can run nowhere throws. A pass with a dispatch
  below compute runs inside `CgLoweredKernel.scope()`, so what those draws bind never reaches the next pass.
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
`--mode=compute-graph` is the compute half: kernels, a history, an indirect dispatch and a raster pass in one frame
built on a worker, matched against the CPU's picture, executed again too; with synchronization validation clean.
`--mode=indirect-draw` is the indirect half: four indirect draws, one per mode and one past its mesh, each matched
against a direct draw of what its count means, in a graph, executed again and through the world renderer.
`--mode=material-buffer` is a material reading a kernel's buffers through `Buffers { }`, drawn indirect. All three pass
forced to every tier (`-Dcrystalgraphics.compute.tier=G40|G33|CPU`), as does `--mode=compute-tiers`
(`compute/CLAUDE.md` § *Tests*).
