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
them as from a raster pass's; `fill`, `update` and `copy` on buffers are passes ordered like any write. `update` copies
its bytes when recorded; its `float[]` form reads the caller's array when the frame executes, for a buffer rewritten
every frame, so that array must not change until then.
`resize(buffer, desc)` is copies of each version into a new handle and a release of the old: a pool outgrowing its
capacity. A persistent or history buffer's storage is made at its first use, unless `CgExecutor.makeStorage(buffer)`
made it ahead (a precache, so the frame that first uses it allocates nothing). The executors of the first two
nesting depths are made at the engine's start (`CgExecutor.ahead`): each maps a ring, and a map waits for a threaded
driver to drain its queue.

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

**Layers.** A 2D array the graph allocates (`CgTextureDesc.array(w, h, layers, format)`) is drawn into a layer at a time
(`rec.raster(texture, 0, layer, ...)`) and sampled whole, as a material's `sampler2DArray`. Each layer is a target of its
own, so its load clears or keeps that layer alone, and on Vulkan passes into different layers never merge. Its storage
is `CgFrameBuffer.createArray`: a `CgTexture2DArray` and a framebuffer per layer, made with it.

```java
CgFrameBufferFormat rgba16f = CgFrameBufferFormat.builder("field").color(0, CgTextureType.RGBA16F).build();
CgGraphTexture field = CgGraphTexture.transientTexture("field", CgTextureDesc.array(w, h, 5, rgba16f));
CgRasterPass slot = rec.raster(field, 0, k, CgLoad.clear(0, 0, 0, 0), constants, additive, CgOrder.SORTED);
material.applyProperties(b -> b.sampler("_Fields", 0, field).set1i("_Count", used));   // texture(_Fields, vec3(uv, k))
rec.readback(field, 0, 0, 0, k, w, h, 1, data -> check(data));                         // layer k
```

- One level, one colour attachment, no depth. Keep the layer count fixed from frame to frame so the pool hands back
  the same storage, and read only the layers drawn this frame: the rest hold whatever the storage last held.
- A pass sampling the array it draws into throws, whichever layer it draws: sampled, the array is read whole.
- For ordering the array is one resource, as a level view is: each layer's pass runs after the one before it.
- Above layer 0 a pass reads no copy of its target: `sceneColor` and `sceneDepth(unit)` throw. Kernels take no arrays,
  a copy or an update of one throws, and a readback takes a layer as its z.

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
  A volume of the caller's own, with its sampling and mip levels, is imported: `CgFrameBuffer.createVolume(name, texture3d)`,
  each level a kernel's `image(name, volume, level, -1)` (`CgNoiseVolumes` bakes its chains so).
- Below compute an `image` kernel writes a volume in one draw, an instance a slice; one that loads the volume it writes is refused
  there, as for any image other than 2D. The CPU tier reads a volume whole and writes it whole.
- A readback attaches each slice in turn to one framebuffer bound for reading (`CgReadback.slices`): on a device a
  slice of a 3D image is no draw target, but a copy reads it at its z.
- `--mode=volumes` is the gate: two volumes filled, spread across slices and sampled, a third updated by boxes, every
  texel read back and checked, on `gl` at every tier, on both downlevel contexts and on `vulkan`.

**More than one colour attachment.** A graph texture whose format has `color(1, ...)` and up is drawn into whole by a
raster pass: every slot is a draw buffer (`CgFrameBuffer` sets them at creation), so a fragment's `: RT1` output lands
in slot 1. `texture.attachment(k)` samples slot k, ordered like the texture itself.

```java
CgFrameBufferFormat both = CgFrameBufferFormat.builder("scene+glow")
        .color(0, CgTextureType.RGBA8).color(1, CgTextureType.R11F_G11F_B10F).depth(CgTextureType.DEPTH24_STENCIL8).build();
int reads = rec.bindings().withTexture(material.captureBindings(rec.bindings()), 0, target.attachment(1));
```

- A slot above 0 that a pipeline's program does not write is masked off for its draws (`CgPipeline.slotWrites`;
  on a device, the pipeline's per-attachment write mask), so an unwritten output never lands. Not on a device without
  `independentBlend` (`CgCapabilities.independentBlend()`), where one mask serves every slot: there
  a shader drawn into such a target writes every slot.
- One blend serves every slot below GL 4.0 (`glBlendFunci` is 4.0): what a pipeline writes into slot 1 must suit the
  pass's blend. The executor never assumes a blend per attachment.
- `sceneColor` copies slot 0; hazards and the pool key on the texture as a whole.

**A second attachment beside a target** (`CgRasterPass.attachment(texture)`): the pass draws into `texture` as slot 1
beside a target it may not own, the current one included, keeping what it holds. Beside the current target the device's
pass attaches the host's colour and depth as imported textures, so the host's own framebuffer is never changed. The world's normal target is the user (`render/world/CLAUDE.md`).

```java
recording.raster(normals, CgLoad.clear(0, 0, 0, 0), constants, null, CgOrder.SORTED).end();   // cleared first
CgRasterPass pass = recording.raster(CgGraphTexture.current(), CgLoad.load(), constants, state, CgOrder.SORTED)
        .attachment(normals);
chunks.draw(material.pipeline(CgInstanceKind.OBJECT).as(CgPipelineForm.NORMALS), bindings, mesh);    // writes both slots
```

- `texture` is the target's size (GL would draw into the intersection, quietly): the executor throws otherwise.
- Framebuffer 0 takes none, and nor does a multisampled host target: both throw. `attachmentIfTaken(texture)` draws
  on the target alone there instead, logs once, and `CgRasterPass.refusesAttachment(fb)` answers true from then on.
- Draws that leave slot 1 masked need `CgCapabilities.independentBlend()`; a recorder checks it before asking.

**A target beside the current depth** (`CgGraphTexture.besideCurrentDepth`): a transient colour texture whose depth
and stencil are the current target's, which is what the world's HDR scene is (`render/world/CLAUDE.md`). A pass into it
attaches it at slot 0 and the host's depth, imported, so it tests and writes the host's depth; `sceneDepth` on it, its own or from it, copies the host's. A pass
into it reads and writes the current target for ordering, and may not clear depth.

```java
CgGraphTexture scene = CgGraphTexture.besideCurrentDepth("scene", new CgTextureDesc(w, h, rgba16f));
recording.raster(scene, CgLoad.load(), constants, null, CgOrder.SORTED)
        .sceneColor(CgBindingPoints.SCENE_COLOR_TEXTURE_UNIT, CgGraphTexture.current());   // the host's colour, copied whole
```

- Framebuffer 0 and a multisampled host lend no depth: `takesCurrentDepth(fb)` says so, and the recorder gives its
  texture a depth of its own. A refusal at execution draws that pass without depth, logged once.
- `requestedBesideCurrentDepth` is the same, kept until released: drawn in one firing, read in a later one (the
  world's opaque scene).
- A pass into one may take a second attachment (`attachment(texture)`): ours at slot 1 beside ours at 0 and the host's
  depth, the host's colour left out.
- `sceneColor(unit, from)` is `sceneDepth(unit, from)`'s twin: another target's colour, copied whole when the pass
  begins, RGBA8 for the current target.

**A pass timed on its own** (`CgRasterPass.timed(zone)`, `CgComputePass.timed(zone)`, the zone a name made once
with `CgGpuTrace.name`): the executor brackets that pass in a GPU zone, which splits the stage's own (`gpu:<name>`).
With `crystalgraphics.gpu.groups` on, the executor marks each batch's material inside that zone (or the stage's, for
an untimed pass), so the pass's time also lands per material as `gpu:<zone>/<shader path>`; the target copy a batch
needs is charged to it.

**A pass charged to a CPU meter** (`CgComputePass.charged(meter)`, a `render.CgCpuMeter`): the executor adds the
nanoseconds its execution takes on the render thread, its GPU timing and barriers included, so a consumer budgets its
own passes' CPU as `timed(budget)` budgets their GPU.

**Transitions are the executor's** (`CgResourceStates`, Unreal RDG's subresource states): before each access it
compares the storage's state — per GL name, so pooled transients, a history's two versions and a buffer used across
frames each come out right — and states what must move. On a device that takes them (`CgRHI.takesTransitions()`, Vulkan)
every access's: buffers as barriers, images with their layouts, a host's target as one framebuffer
(`CgHostImports.framebufferBarrier`), any other by subresource; a texture outliving the execution is taken from where it
rests (`CgAccess.RESTING`) and returned there at its end. The device infers no state of its own: what the executor does
not state does not move. On GL only those a kernel takes part in, as the reader's
`glMemoryBarrier` bits; GL orders the rest. A transition whose next access is a read two or more steps on is split from
its producer's end to its consumer (`CgGraphBarriers.endSplit`), as RDG splits one. `-Dcrystalgraphics.graph.barriers=false`
keeps the bookkeeping and issues nothing; synchronization validation must then fail `--mode=compute-graph`.

Two rules keep a pass of many small dispatches from waiting dispatch by dispatch, Unreal RDG's:

- **A compute pass's prologue**: each storage's first access in the pass, by whichever dispatch touches it first, is
  barriered before the pass's first dispatch, so dispatches reading what earlier passes wrote wait once together. A
  history is left to its binding, since its version moves within the pass.
- **Skip sets** (`CgDispatch.skipBarrier(name, group)`, RDG's `SkipBarrier`, Niagara's `BeginUAVOverlap`):
  accesses, reads or writes, through skipping bindings of one group of a pass to the same storage wait for nothing
  between them; the group's first waits as any access would, and any access after the group, through another group or
  none, waits for all of it. For dispatches each touching their own slice of one buffer: the particle range's keys and
  draw order, and the pools' counts, an entry a pool, read and appended to through a stage of steps.

```java
for (Pool p : pools) {
    pass.dispatch(key, p.size()).bind("KEYS", keys, p.first() * 4L, p.size() * 4L).skipBarrier("KEYS");
}
pass.dispatch(sort, all).bind("KEYS", keys);   // waits for every slice

for (Pool p : pools) {   // one stage: each reads and appends to its own entries of one count buffer
    pass.dispatch(p.step(), p.n()).bind("LIVE", counts, p.at(), 16).counter("OUT", counts, p.next())
            .skipBarrier("LIVE", 1).skipCountBarrier("OUT", 1);
}
pass.dispatch(look, n).bind("LIVE", counts, p.at(), 16);   // the next stage: waits once, for every step
```

- Nothing checks that the slices are disjoint; synchronization validation does, so a gate using one runs with it.
- An append's count skips only where every dispatch of the group appends to a word of its own
  (`skipCountBarrier`). One count shared by them never is: every append writes that word, which validation reports.
- A stage's group must differ from the stage before it in the pass, or the second would not wait for the first.

**A compute pass's constants are one snapshot**, bound before its first dispatch (and again after a lowered one), as a
raster pass's; each dispatch's snapshot holds its values and samplers alone, and is not interned: two dispatches
rarely share values, so hashing them found nothing (`CgBindingTable.endUnique`).

**Reading the target** (`CgRasterPass.sceneColor(unit)`, `sceneDepth(unit)`): a draw whose shader reads
`cg_SceneColor` or `cg_DepthBuffer` samples a copy of the pass's own target (`CgTargetCopy`). The builder walks the
pass's batches in their sorted order and places a copy before a reader of what a draw since the last copy wrote:
colour by any draw with colour writes on, depth only by a depth write. The pass starts with neither copied, so its
first reader always copies. A reader's own colour writes leave what it reads clean, so readers in a row share one
copy and never see each other (Godot's screen-texture rule). A depth write is seen by the next depth reader even when
a reader made it: the distortion apply writes the depth it read from, and a reader after it must test that. There is no cap: a colour copy is cut to the union of its
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
- **A pass whose first draw writes every pixel says so** (`replaces()`): where its target is a transient of one colour
  slot and no depth of its own (the HDR scene) and that draw would copy all of it, the executor gives the target fresh
  storage from the pool and binds the old as the copy, giving it back when the pass ends (`graph.target-renames`).
  Unreal's post passes each write a new texture the same way. A full-screen copy is half a post pass's cost: two in
  skinned-play's arena saved 0.05 ms of GPU. `CgPostMaterial` says it for a draw that blends nothing and never
  discards.
- The copy has the target's formats (a float target keeps its range); the current target's colour is RGBA8 and its
  depth in its own format, the viewport's size.
- Sort order decides what a reader sees: it bends what sorted before it. The world renderer's groups and orders
  place a haze after what it should bend (`render/world/AGENTS.md`).

**Another target's depth** (`sceneDepth(unit, from)`): a pass into a target of its own (a bloom target, any size)
reads `from`'s depth as it stands after every write recorded before the call, copied once, whole, when the pass begins.
Readers sample by `gl_FragCoord.xy / CG_RESOLUTION`. The world's bloom is the user (`render/world/CLAUDE.md`).

**One depth copy, shared.** The executor keeps one copy of a framebuffer's depth per execution, and every pass after it
reading that depth, as another target's or as its own before any draw of its own wrote depth, reads that copy until
something may write it: a raster pass into that framebuffer's depth that clears it or has a batch whose state writes it
or does not say, a callback, an upload, a release, or the framebuffer going back to the pool. Unreal resolves scene depth once after the base pass the
same way. Counters `graph.target-copies` (blits) and `graph.depth-copies-shared` (copies read again). A pass's depth
state that says `DepthWrite OFF` keeps the copy alive; one that says nothing ends it.

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
mesh store placed, then draws it with `glDrawElementsIndirect`/`glDrawArraysIndirect`. A pass's commands are one
`DrawArgsMany` dispatch per count buffer, each command a row of a frame-local table. On a context where no draw takes a
count from a buffer, the batch is drawn with its count read back (a stall, `buffer.readbacks`).

```java
CgComputePass live = rec.compute("sparks.count");
live.dispatch(compact, capacity).bind("STATE", state).counter("ALIVE", alive, 0);
live.end();
chunks.draw(pipeline, bindings, CgMesh.quads(capacity)).indirect(alive, 0, CgIndirect.INDICES, 6);   // a quad each
chunks.instance();                                                                                      // its record
```

- `INDICES` and `VERTICES` draw count x factor of the range, never more than it holds; `INSTANCES` draws the range
  count x factor times, every instance reading the draw's one record, and `CG_DRAW_INSTANCE` is which element it is.
- An indirect draw is a batch of its own, and its command's first instance is its batch's instance base, as any draw's
  is; with `CgMeshStore.SHARED_RECORD` set for an `INSTANCES` draw of the frame's records, whose every instance reads one.
- Each command has a slot of its own, aligned for a storage binding (`CgCapabilities.storageOffsetAlignment`), so the
  kernels writing them share nothing and need no barrier between them.

**Object records from a buffer** (gpu-compute C9b): `CgChunkBuilder.objects(records, first, n)` draws a mesh once per
record of a GPU buffer in `CgInstanceKind.OBJECT`'s layout, instead of records written into the chunk. The executor
binds the buffer where `CG_OBJECT_DATA` reads (`CgBindingPoints.OBJECT_DATA`, a storage block) with `first` as the instance base, so instance i reads record `first + i`, and binds the frame's own
records back for the next batch that reads them. With `.indirect(count, offset, INSTANCES, factor)` the GPU's count says how many, held to n by
the command's kernel (`_Most`) or, read back, by the executor.

```java
chunks.draw(pipeline, bindings, rock).objects(visible, 0, capacity).indirect(visibleCount, 0, CgIndirect.INSTANCES, 1);
```

- The raster pass reads the buffer as a vertex and fragment stage would, so the pass writing
  it runs first and, below compute, lands it.
- Such a draw is a batch of its own, joined into a multi-draw only with indirect draws of the same buffer (below); a
  pass whose only object draws are of `objects()` uploads no object records.

**Multi-draw** (gpu-compute C9a): where `CgCapabilities.multiDraw()` holds, consecutive batches under one pipeline,
bindings and scissor, with no target copy between them, drawn directly from meshes in one slab or one ring page,
are one `glMultiDrawElementsIndirect`. The store takes each draw while its mesh joins the run (`CgMeshStore.join`)
and writes the commands into the frame ring (`drawJoined`), under the same pipeline a draw alone binds: every draw
carries its bases. A run of one is drawn the plain way.

Indirect draws join too, where their commands are written on the GPU (compute, and G40 with indirect draws):
consecutive indirect batches under one pipeline, bindings and scissor, reading the same object records, from meshes
that join (`CgMeshStore.joins`). The executor decides the runs before the pass, when it writes the commands: each into
consecutive slots, by indices (`CgMeshStore.range`) with each batch's first instance, then draws the run with
`drawIndirectJoined` at the slots' stride. A culled set's levels (`CgGpuOps.cull`) are such a run: one call however many
levels. An `INSTANCES` draw of the frame's records joins too: its shared record rides in its own command.

**A command a record** (`CgChunkBuilder.indirectEach`, `CgWorldRenderer.Draw.indirectEach`): one draw whose every
record is its own command, its count at the offset `countAt` gave it: one batch, the frame's `eachOffsets` holding
its records' counts. The executor writes a row a record, each with its record as its first instance, and draws them as
one multi-draw where its mesh joins, else a draw a record under `instanceBase`. What many particle slots of one
material are: one recorded draw instead of one a slot, so the CPU's sort, pack and batch cost is one draw's.

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

**A built frame as a step** (`CgRecording.include(frame)`, `includeAgain(frame, keepRequested)`): a frame recorded and
built elsewhere -- a UI recorded on its own thread -- executes at its place in another recording's frame, which is
built once with it. The step (`CgPass.Include`) is ordered as a pass touching what the built frame touches beyond its
own transients, and executes the frame on the next depth's executor (the callback's re-entrancy, without its GL scope),
after every split transition of the outer frame has ended, so it takes its storage from where it rests. A stage's
firing is the user (`CgStageFrame.include`); the caller recycles the frame after the outer one executes.

```java
CgPass.Include step = firing.include(ui).timed(GPU_UI).then(UI_EXECUTED);   // a held runnable
```

`CgImmediate` (one package up) is the same three stages in one `try` block, for a caller with no graph — and
`CgImmediate.flush(chunk, order)` is what `CgQuadBatch`/`CgCurveBatch.flush()` call, under the frame block
the caller prepared (`render-graph` G2).

**What a recorded draw keeps of an immediate one** (G2's gate found each): a draw runs under the material as the
last `useMaterial` before it left it, since a bind used to upload values set after the records were queued; state a
caller sets after binding rides on the pipeline (`CgPipeline.withState`), or the pipeline's declared state would
overwrite it at the draw; and a texture bound by hand goes in through `bindTexture(unit, texture)`, winning the
unit as GL's last bind did.

**The canvas** (G4; lookback G5.3; `render/canvas`): `CgCanvas` records 2D items into raster passes:
`recordInto(recording, target, load, constants)` picks the target, `scissor`/`noScissor` and `constants` are its
set-state, a change of constants starts a pass, `endPass()` splits; nothing is read from GL. The caller executes the
recording once with `CgImmediate.execute(recording)`. A recorded draw executes after the canvas has moved on, so a
snapshot keeps by value what an immediate draw read live: a texture as the handle it was given, an attached uniform
block as its bytes; the canvas's binding table resets once a frame. A canvas's passes are `LOOKBACK`: a recorded chunk is one spatial node's
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

**A copy** (`CgRecording.copy`) blits a region of one 2D texture's slot 0 into another's, scaled when the two differ;
its source may be `current()`, as it stands after every write recorded before: what is behind a UI, read into a
texture of its own (CrystalGUI's backdrop). **Teardown**, where no frame will execute a recorded release, frees a
requested or history texture's storage with `CgExecutor.freeStorage(texture)`, the buffer's twin.

```java
rec.copy(CgGraphTexture.current(), x, y, w, h, capture, 0, 0, w, h, false);
CgExecutor.freeStorage(picture);   // the context is going away
```

## Rules

- **Order comes from reads and writes, not from creation.** A raster pass reads every `CgGraphTexture` its chunks'
  snapshots bind, as of `add`; it writes its target at `end`. A read sees the last write recorded before it — in its
  recording or one added to the graph earlier. Creation order only breaks ties, after the async placement below.
- **A pipeline's undeclared state slots are its pass's** (`pass.state`, applied before each pipeline), and what neither
  declares is inherited from the last draw, but for the colour mask: one neither declares writes every channel, so a
  depth-only draw never blanks the passes after it.
- **A pass nobody reads is culled** unless it writes a resource that outlives the frame (an imported, current or
  requested texture; an imported, persistent or history buffer) or carries a request.
- **Transients live from their first to their last use** in the executed order, from a pool keyed by description
  (textures) or size class (buffers): two that never live at once share storage.
  The texture pool frees one idle five minutes, and past `crystalgraphics.graph.poolMb` (400) one unused ten frames,
  as Unreal's pool does past `r.RenderTargetPoolMin`: a transient sized from something that moves (a region on
  screen) is rounded to a size class first (`CgGpuOps.scratchSize`), or every frame's size is new storage.
  `graph.pool-framebuffers` and `graph.pool-kb` count what it holds, on every backend.
- **A compute pass runs where compute does**: a dispatch on a context without it throws when it is recorded
  (`CgKernel.requireCompute`).
- **An `async()` compute pass runs on the device's compute queue** where it has one (the owned device), and in order
  elsewhere: on Minecraft's device only with `-Dcrystalgraphics.vulkan.asyncCompute=true` (`docs/ENGINE_API.md`
  § *Async compute*), and then it may not touch Minecraft's own images, which only its graphics queue may use. The
  executor waits before the first later step touching any storage it touched (by GL name, so a pooled transient handed
  to another counts), in this execution or a later one of the frame, and before a callback. Storage the host may touch
  (imported, current, requested) is waited for at the end of its execution; the device's frame end waits for the rest.
  The builder runs an async pass and what it depends on as early as the graph allows, and what depends on it as late,
  so the steps between overlap it; under `asyncAll` that is every compute pass. A consumer marks every pass that fits
  (`docs/SHADERS.md` § *Beside the drawing*).
- **Requests** (`upload`, `callback`, `compile`, `readback`) report `DONE`/`FAILED` on `CgRequest`, readable from any
  thread; a pass that throws fails its request and the frame goes on. A readback's is done frames after its execution,
  once `CgReadback.poll` has run its sink; executing the frame again does not read it again.
- **One upload per kind per frame.** The executor binds its own instance buffers at the engine binding points and
  draws each batch's range from its first instance. A nested execution (an immediate inside a callback) gets its
  own buffers and ring.
- **A recording is a value once sealed**: nothing changes it, nothing in it refers to its recorder.
  `CgRecording.reset()` is only for an owner that knows nobody else holds it: it refills its chunks and raster passes
  after a reset, so a steady frame allocates none: a chunk kept past it (a `CgReplay`) is a copy, and a raster
  pass a built frame still holds is left to that frame until `CgFrameBuilder.recycle`.

## Gate scene

`--mode=graph-executor-test` draws one picture through `CgQuadBatch`, `CgImmediate`, and a frame recorded and built
on a worker thread; the three PNGs in its output directory must be byte-identical, on `--rhi=gl` and `--rhi=vulkan`.
`--mode=compute-graph` is the compute half: kernels, a history, an indirect dispatch and a raster pass in one frame
built on a worker, matched against the CPU's picture, executed again too; with synchronization validation clean.
`--mode=indirect-draw` is the indirect half: four indirect draws, one per mode and one past its mesh, each matched
against a direct draw of what its count means, in a graph, executed again and through the world renderer.
`--mode=material-buffer` is a material reading a kernel's buffers through `Buffers { }`, drawn indirect. All three pass on
`gl` and `vulkan`, as does `--mode=compute-tiers` (`compute/CLAUDE.md` § *Tests*). `--mode=raster-levels` is a chain drawn level by level through raster passes, each
level reading the one above through a level view, every texel checked; on `gl`, `vulkan` with synchronization
validation, and both downlevel contexts. `--mode=raster-layers` is arrays': 2 to 5 layers of one array drawn each
frame and summed through a `sampler2DArray`, every layer and the sum checked, on one storage throughout; on the same
four. `--mode=multi-draw` is the joined draws': the same picture joined and
separate, and the calls each took.
