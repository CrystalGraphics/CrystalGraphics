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
`resize(buffer, desc)` is copies of each version into a new handle and a release of the old: a pool outgrowing its
capacity.

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
draws into any (`rec.raster(texture, level, ...)`), a kernel writes any (`dispatch.image(name, texture, level, -1)`),
and anything sampling it filters trilinearly. `texture.level(k)` samples level k alone, which is how a pass drawing one
level reads another of the same texture with no feedback loop. `CgGpuOps.downsample` fills the chain with kernels. An
imported framebuffer carries its own count (`CgFrameBuffer.createOwned(name, w, h, format, levels)`).

```java
CgGraphTexture bloom = CgGraphTexture.transientTexture("bloom", new CgTextureDesc(w, h, HDR).withMips());
CgFrameBufferFormat r32f = CgFrameBufferFormat.builder("hi-z").color(0, CgTextureType.R32F).build();
CgGraphTexture hiZ = CgGraphTexture.requested("hi-z", new CgTextureDesc(w, h, r32f, 6));   // six levels

CgRasterPass down = rec.raster(bloom, 3, CgLoad.load(), constants, null, CgOrder.LOOKBACK);   // into level 3
int reads = rec.bindings().withTexture(material.captureBindings(rec.bindings()), 0, bloom.level(2));
```

- A pass into level k has the level's viewport, and no depth above level 0; its constants' resolution is the caller's.
  Above level 0 it reads no copy of its target: `sceneColor` and `sceneDepth(unit)` throw.
- A level view pins the texture's base and max level when bound (on Vulkan, a view of the one level), so a shader
  reads it at LOD 0 and `textureSize(s, 0)` is the level's. The executor unpins after the pass, and binding the
  texture whole unpins it too.
- For ordering a level view reads the whole texture: the pass runs after every pass writing it before.

**Volumes** (gpu-compute C11, E3). A 3D graph texture (`CgTextureDesc.volume(w, h, d, format)`): kernels write it as a
`3d` image, kernels and materials sample it as a `sampler3D`, and boxes of it are updated and read back. Its storage is
`CgFrameBuffer.createVolume`, a `CgTexture3D` and no framebuffer object, since nothing draws into it.

```java
CgFrameBufferFormat r8ui = CgFrameBufferFormat.builder("voxels").color(0, CgTextureType.R8UI).build();
CgGraphTexture voxels = CgGraphTexture.requested("voxels", CgTextureDesc.volume(128, 96, 128, r8ui));
pass.dispatch(fill, 128, 96, 128).image("VOXELS", voxels);                 // Images { VOXELS (..., r8ui, writeonly, 3d) }
step.dispatch(collide, n).texture("_Voxels", voxels);                       // Properties { _Voxels (..., sampler3D) }
rec.update(voxels, 0, 0, 0, 32, 128, 96, 16, slab);                         // 16 new slices from z 32
rec.readback(voxels, 0, 0, 0, 40, 128, 96, 2, data -> check(data));         // slices 40 and 41
```

- A `3d` image takes a volume and a `2d` one anything else, and a `sampler3D` property a volume, or the dispatch throws;
  a raster pass into a volume and a copy of one throw.
- One level, one colour attachment. Its format's type decides the filter: linear, nearest for an integer type, clamped.
- Below compute an `image` kernel writes a volume as a draw per slice; one that loads the volume it writes is refused
  there, as for any image other than 2D. The CPU tier reads a volume whole and writes it whole.
- A readback attaches each slice in turn to one framebuffer bound for reading (`CgReadback.slices`): on a device a
  slice of a 3D image is no draw target, but a copy reads it at its z.
- `--mode=volumes` is the gate: two volumes filled, spread across slices and sampled, a third updated by boxes, every
  texel read back and checked, on `gl` at every tier, on both downlevel contexts and on `vulkan`.

**More than one colour attachment.** A graph texture whose format has `color(1, ...)` and up is drawn into whole by a
raster pass: every slot is a draw buffer (`CgFrameBuffer` sets them at creation), so a fragment's `: RT1` output lands
in slot 1. `texture.attachment(k)` samples slot k, ordered like the texture itself. `--mode=mrt-emission` is the gate:
colour and emission in one pass against each alone, on `gl` and `vulkan` with synchronization validation.

```java
CgFrameBufferFormat both = CgFrameBufferFormat.builder("scene+glow")
        .color(0, CgTextureType.RGBA8).color(1, CgTextureType.R11F_G11F_B10F).depth(CgTextureType.DEPTH24_STENCIL8).build();
int reads = rec.bindings().withTexture(material.captureBindings(rec.bindings()), 0, target.attachment(1));
```

- Every material drawn into such a target must write every slot: an output it leaves unwritten is undefined there.
- `sceneColor` copies slot 0; hazards and the pool key on the texture as a whole.

**A pass timed on its own** (`CgRasterPass.timed(zone)`, `CgComputePass.timed(zone)`, the zone a name made once
with `CgGpuTrace.name`): the executor brackets that pass in a GPU zone, which splits the stage's own (`gpu:<name>`).
With `crystalgraphics.gpu.groups` on, the executor marks each batch's material inside that zone (or the stage's, for
an untimed pass), so the pass's time also lands per material as `gpu:<zone>/<shader path>`; the target copy a batch
needs is charged to it.

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
  `cg_InstanceBase`, as for any draw. A command a multi-draw draws is the exception (below).
- Each command has a slot of its own, aligned for a storage binding (`CgCapabilities.storageOffsetAlignment`), so the
  kernels writing them share nothing and need no barrier between them.

**Object records from a buffer** (gpu-compute C9b): `CgChunkBuilder.objects(records, first, n)` draws a mesh once per
record of a GPU buffer in `CgInstanceKind.OBJECT`'s layout, instead of records written into the chunk. The executor
binds the buffer where `CG_OBJECT_DATA` reads (`CgBindingPoints.OBJECT_DATA`: a storage block, or a buffer texture on
the TBO path) with `first` as the instance base, so instance i reads record `first + i`, and binds the frame's own
records back for the next batch that reads them. With `.indirect(count, offset, INSTANCES, factor)` the GPU's count says how many, held to n by
the command's kernel (`_Most`) or, read back, by the executor.

```java
chunks.draw(pipeline, bindings, rock).objects(visible, 0, capacity).indirect(visibleCount, 0, CgIndirect.INSTANCES, 1);
```

- The raster pass reads the buffer as a vertex and fragment stage would (sampled on the TBO path), so the pass writing
  it runs first and, below compute, lands it.
- Such a draw is a batch of its own, joined into a multi-draw only with indirect draws of the same buffer (below); a
  pass whose only object draws are of `objects()` uploads no object records.

**Multi-draw** (gpu-compute C9a): where `CgCapabilities.multiDraw()` holds, consecutive batches under one pipeline,
bindings and scissor, with no target copy between them, drawn directly from meshes in one slab or one ring page,
are one `glMultiDrawElementsIndirect`. The store takes each draw while its mesh joins the run (`CgMeshStore.join`)
and writes the commands into the frame ring (`drawJoined`); the executor binds the pipeline's `multiDraw()` variant,
which reads each draw's first instance and base vertex from its command rather than `cg_InstanceBase` and
`cg_VertexBase`. A run of one is drawn the plain way.

Indirect draws join too, where their commands are written on the GPU (compute, and G40 with indirect draws):
consecutive indirect batches under one pipeline, bindings and scissor, reading the same object records, from meshes
that join (`CgMeshStore.joins`). The executor decides the runs before the pass, when it writes the commands: each into
consecutive slots, in the joined form (`joinedRange`) with the batch's instance base as its first instance, then draws
the run with `drawIndirectJoined` at the slots' stride. A culled set's levels (`CgGpuOps.cull`) are such a run: one
call however many levels. An `INSTANCES` draw of the frame's records never joins, since every instance reads one
record and the multi-draw variant has no shared record.

- Every joined draw is by indices: a mesh without them is drawn by a shared run of 0, 1, 2 ..., since GL gives an
  array draw's base vertex as 0 where Vulkan gives its first vertex. Meshes with and without indices never share a
  call.
- The picture is the same either way: `--mode=multi-draw` draws 78 instances of 76 meshes in 4 calls, then with
  `CgMeshStore.multiDraw(false)` in 77, and compares the two byte for byte; `--mode=gpu-cull` does the same for a
  culled set's levels. `-Dcrystalgraphics.mesh.multiDraw=false`
  turns it off for a process.
- What a consumer builds on all of this, and how to design for joins: `docs/GPU_DRIVEN_RENDERING.md`.

**Inspecting a buffer** (gpu-compute C10): `CgBufferInspector` is a debugger's buffer view. While watching, each
compute pass notes the buffers its dispatches bind, a `Site` each (the buffer, the pass, the kernel's declaration of
it); a read of one is served right after its pass next runs (`CgExecutor.inspect`: landed, barriered, copied as a
`CgReadback`), and `Read.value(i, field)` decodes a field by its GLSL type.

```java
CgBufferInspector.watch(true);
CgBufferInspector.Site site = CgBufferInspector.sites().get(0);   // sparks after particles.step: 4096 x Spark, ...
CgBufferInspector.read(site, 0, 64, read -> show(read.value(0, site.decl().field("positionLife"))));
```

- Not watching costs a check per compute pass. A read waits for its pass to run again, so it fails once a pass stops
  running and its site is dropped (`FORGET_AFTER` frames).
- Its gate is `--mode=readback`: words an iota wrote, read through the inspector each frame and checked, on every
  tier and on `vulkan` with every pass async.

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
  recording or one added to the graph earlier. Creation order only breaks ties, after the async placement below.
- **A pass nobody reads is culled** unless it writes a resource that outlives the frame (an imported, current or
  requested texture; an imported, persistent or history buffer) or carries a request.
- **Transients live from their first to their last use** in the executed order, from a pool keyed by description
  (textures) or size class (buffers): two that never live at once share storage.
- **A compute pass runs on every tier**: each dispatch as its kernel's form (`compute/CLAUDE.md` § *Three forms*),
  chosen when the dispatch is recorded, which is where a kernel that can run nowhere throws. A pass with a dispatch
  below compute runs inside `CgLoweredKernel.scope()`, so what those draws bind never reaches the next pass. What a
  lowered dispatch writes stays in its target until a step other than a compute pass touches the buffer, or the
  execution ends: ops chained in a frame land only what leaves them.
- **An `async()` compute pass runs on the device's compute queue** where it has one (the owned device, and Minecraft
  26.2's through the queue it leaves unused), and in order elsewhere. On Minecraft's device it may not touch
  Minecraft's own images, which only its graphics queue may use. The
  executor waits before the first later step touching any storage it touched (by GL name, so a pooled transient handed
  to another counts), before a callback, and at the end of the execution (`docs/SHADERS.md` § *Beside the drawing*).
  The builder runs an async pass and what it depends on as early as the graph allows, and what depends on it as late,
  so the steps between overlap it; under `asyncAll` that is every compute pass.
- **Requests** (`upload`, `callback`, `compile`, `readback`) report `DONE`/`FAILED` on `CgRequest`, readable from any
  thread; a pass that throws fails its request and the frame goes on. A readback's is done frames after its execution,
  once `CgReadback.poll` has run its sink; executing the frame again does not read it again.
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
(`compute/CLAUDE.md` § *Tests*). `--mode=raster-levels` is a chain drawn level by level through raster passes, each
level reading the one above through a level view, every texel checked; on `gl`, `vulkan` with synchronization
validation, and both downlevel contexts. `--mode=multi-draw` is the joined draws': the same picture joined and
separate, and the calls each took.
