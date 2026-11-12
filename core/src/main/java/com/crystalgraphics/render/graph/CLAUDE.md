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
CgRasterPass pass = rec.raster("ui.layer").color(target, CgLoad.clear(0, 0, 0, 0))
        .constants(constants).state(uiState).order(CgOrder.LOOKBACK);
pass.add(chunk);
pass.end();
CgFrame frame = builder.build(new CgFrameGraph().add(rec.seal()));
CgExecutor.execute(frame);
builder.recycle(frame);
```

**Declared accesses** (Unreal's pass parameters). A raster pass states what it draws into and reads before its first
draw: `color(texture, load)` (a second call is colour 1), `depth(texture, load, CgDepthAccess)`, `reads(texture)`,
`reads(buffer)`, `indirect(buffer)`, `readsColor(texture, unit)` and `readsDepth(texture, unit)` (`cg_SceneColor`,
`cg_DepthBuffer`), `texture(unit, texture)`; then `constants`, `state`, `order`. A read is ordered as of its declaration:
declare it before the draw that reads it, and a pass held open while another records (a layer composited into its
parent) declares it after that pass. A draw names its own reads as it is added (`renderer.md` § 4.2): the graph
textures its bindings sample, the graph buffers it binds as storage, its objects and indirect count.

**The compile** (`CgFrameCompiler`, Unreal RDG's `Compile`): at an execution's start, before its first step, every
decision the step loop used to make is written into the frame's per-step arrays, and the loop replays them. Which raster
steps share a render pass and their second colour (`runEnd`, `runAttachment`), read-only depth, each step's prologue of
transitions (a run's by its first step) and the split transitions it begins, async placement, multi-draw runs
(`Raster.joinEnd`), a re-execution's skips, a depth read sampled in place. It runs at execution, not at build, because
what an external target lends is known once the execution opens it. What stays in the loop is tracked state: a
transition issued from the state `CgResourceStates` holds, an async fence waited on where a step's storage is pending
on one, the shared depth copy, a history's version, transient storage taken at the step the build scheduled.

**The execution** replays the compile through encoders, as RDG runs a pass lambda on a command list: `CgTransitions`
issues a step's prologue and splits, `CgRasterEncoder` the render pass and its batches (with the frame's instance
buffers and indirect commands), `CgComputeEncoder` dispatches and async compute, `CgCopyEncoder` copies, uploads,
buffer steps and readbacks. One `CgExecutor` per nesting depth is an execution context; what outlives an execution --
pools, placed heaps, tracked states, async fences, opened externals, the open current targets -- is `CgGraphDevice`'s,
reset at teardown. No class here keeps a static mutable field: new state goes on the execution or the device.

**Six pass kinds** (`CgPass`, as Unreal's render graph): raster, compute, copy (a texture region, a buffer range),
upload (bytes into a texture or a buffer, a buffer filled), readback, and external (a callback, an included frame).
A release and a compile are not passes: the recording carries them, and the frame frees a released resource after the
last step touching it (before the first where none does) and compiles before its first step, both on its first
execution only.

**The undeclared-access check** (`CgGraphCheck`, RDG's `bAllowRHIAccess`): while a step executes, the resources it
recorded may be bound and no other; a binding resolving a graph texture (`rhiView()`) or buffer (`boundId()`) outside
them is refused. `-Dcrystalgraphics.graph.check=throw|log|off`: `throw` in a dev client, the harness and tests, off in a
jar. The executor's own resolution (`storage()`, `bufferId()`) is not a binding and is not checked.

```java
rec.raster("world.glow")
        .color(glow, CgLoad.clear(0, 0, 0, 0))
        .depth(sceneDepth, CgLoad.load(), CgDepthAccess.WRITE)
        .reads(noise)
        .readsDepth(CgGraphTexture.current(), CgBindingPoints.DEPTH_TEXTURE_UNIT)
        .constants(constants);
```

- A colour's load clears colour, a depth's depth and stencil (`CgLoad.clearDepth(1).andStencil(0)`); each throws for
  the other's. A second colour loads as the first.
- A pass declaring a depth and no colour draws depth alone.

**Targets and storage.** A `CgGraphTexture` is a handle, never a texture you bind: while the passes using it execute,
its storage is a `CgGraphStorage`, device textures by colour slot and a depth (Unreal's pooled render target behind an
`FRDGTexture`). It is a `CgTexture` only in the sense every binding takes: a view and a sampling spec, null outside its
passes. What someone else owns enters as a `CgExternalTarget` (Unreal's `RegisterExternalTexture`): a host's framebuffer
(`CgExternalTarget.host(fbo, w, h)`, the one place a framebuffer name becomes a target), or a framebuffer of ours
(`framebuffer.external()`). `CgExecutor.into(target)` opens one round an execution as `CgGraphTexture.current()`;
`CgGraphTexture.imported(name, target)` names one for good. Both resolve to storage like any other: no branch of the
executor knows a host.

```java
try (CgExecutor.Into ignored = CgExecutor.into(CgExternalTarget.host(mainFbo, w, h))) {
    stage.fire();                                          // current() is the host's target
}
if (frame.host().target().lendsAttachments()) { ... }     // the window's and a multisampled one lend none
```

- What a target lends is known when imported, never learned by a refusal: ask `lendsAttachments()` before drawing
  beside its depth or giving it a second attachment.
- A storage is known to the executor's states by its first texture's device name, so a target imported twice is one
  resource. Whole single-level storage moves as one; levels and layers move by subresource.
- `CgLoad` is a load per aspect (`clearsColor`, `clearsDepth`, `clearsStencil`), the render pass's load operations.

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
draws into any (`color(texture, level, 0, load)`), a kernel writes any (`dispatch.image(name, texture, level, -1)`),
and anything sampling it filters trilinearly. `texture.level(k)` samples level k alone, which is how a pass drawing one
level reads another of the same texture with no feedback loop. `CgGpuOps.downsample` fills the chain with kernels. An
imported target's texture carries its own count.

```java
CgGraphTexture bloom = CgGraphTexture.transientTexture("bloom", CgTextureDesc.of(w, h, CgFormat.RGBA16_FLOAT).withMips());
CgGraphTexture hiZ = CgGraphTexture.requested("hi-z", new CgTextureDesc(w, h, CgFormat.R32_FLOAT, 6));   // six levels

CgRasterPass down = rec.raster("bloom.down").color(bloom, 3, 0, CgLoad.load()).constants(constants)
        .order(CgOrder.LOOKBACK);   // into level 3
int reads = rec.bindings().withTexture(material.captureBindings(rec.bindings()), 0, bloom.level(2));
```

- A pass into level k has the level's viewport, and no depth above level 0; its constants' resolution is the caller's.
  Above level 0 it reads no copy of its target: `readsColor` and `readsDepth` of it throw.
- A level view pins the texture's base and max level when bound (on Vulkan, a view of the one level), so a shader
  reads it at LOD 0 and `textureSize(s, 0)` is the level's. The executor unpins after the pass, and binding the
  texture whole unpins it too.
- For ordering a level view reads the whole texture: the pass runs after every pass writing it before.

**Layers.** A 2D array the graph allocates (`CgTextureDesc.array(w, h, layers, format)`) is drawn into a layer at a time
(`color(texture, 0, layer, load)`) and sampled whole, as a material's `sampler2DArray`. Each layer is a target of its
own, so its load clears or keeps that layer alone, and on Vulkan passes into different layers never merge. Its storage
is one array texture, each pass attaching its layer.

```java
CgGraphTexture field = CgGraphTexture.transientTexture("field", CgTextureDesc.array(w, h, 5, CgFormat.RGBA16_FLOAT));
CgRasterPass slot = rec.raster("field.slot").color(field, 0, k, CgLoad.clear(0, 0, 0, 0)).constants(constants).state(additive);
material.applyProperties(b -> b.sampler("_Fields", 0, field).set1i("_Count", used));   // texture(_Fields, vec3(uv, k))
rec.readback(field, 0, 0, 0, k, w, h, 1, data -> check(data));                         // layer k
```

- One colour level. Keep the layer count fixed from frame to frame so the pool hands back
  the same storage, and read only the layers drawn this frame: the rest hold whatever the storage last held.
- A pass sampling the array it draws into throws, whichever layer it draws: sampled, the array is read whole.
- For ordering the array is one resource, as a level view is: each layer's pass runs after the one before it.
- Above layer 0 a pass reads no copy of its target: `readsColor` and `readsDepth` of it throw. Kernels take no arrays,
  a copy or an update of one throws, and a readback takes a layer as its z.

**Volumes** (gpu-compute C11, E3). A 3D graph texture (`CgTextureDesc.volume(w, h, d, format)`): kernels write it as a
`3d` image, kernels and materials sample it as a `sampler3D`, and boxes of it are updated and read back. Its storage is
a 3D texture made without attachment use, since nothing draws into it.

```java
CgGraphTexture voxels = CgGraphTexture.requested("voxels", CgTextureDesc.volume(128, 96, 128, CgFormat.R8_UINT));
pass.dispatch(fill, 128, 96, 128).image("VOXELS", voxels);                 // Images { VOXELS (..., r8ui, writeonly, 3d) }
step.dispatch(collide, n).texture("_Voxels", voxels);                       // Properties { _Voxels (..., sampler3D) }
rec.update(voxels, 0, 0, 0, 32, 128, 96, 16, slab);                         // 16 new slices from z 32
rec.readback(voxels, 0, 0, 0, 40, 128, 96, 2, data -> check(data));         // slices 40 and 41
```

- A `3d` image takes a volume and a `2d` one anything else, and a `sampler3D` property a volume, or the dispatch throws;
  a raster pass into a volume and a copy of one throw.
- One colour level. Its format decides the filter: linear, nearest for an integer format, clamped.
  A volume of the caller's own, with its sampling and mip levels, is imported: `CgGraphTexture.imported(name,
  CgTextures.target(name, volume, null))`, each level a kernel's `image(name, volume, level, -1)`
  (`CgNoiseVolumes` bakes its chains so).
- Below compute an `image` kernel writes a volume in one draw, an instance a slice; one that loads the volume it writes is refused
  there, as for any image other than 2D. The CPU tier reads a volume whole and writes it whole.
- A readback attaches each slice in turn to one framebuffer bound for reading (`CgReadback.slices`): on a device a
  slice of a 3D image is no draw target, but a copy reads it at its z.
- `--mode=volumes` is the gate: two volumes filled, spread across slices and sampled, a third updated by boxes, every
  texel read back and checked, on `gl` at every tier, on both downlevel contexts and on `vulkan`.

**A second colour beside a target** (a second `color(texture, load)`): the pass draws into `texture` as slot 1
beside a target it may not own, the current one included, so a fragment's `: RT1` output lands there. A description is
one texture, so this is how a pass draws two colours. Both take one load; where it loads, `texture` keeps what it
holds. Beside the current target the device's
pass attaches the host's colour and depth as imported textures, so the host's own framebuffer is never changed. The world's normal target is the user (`render/world/CLAUDE.md`).

```java
recording.raster("world.normalsClear").color(normals, CgLoad.clear(0, 0, 0, 0)).constants(constants).end();
CgRasterPass pass = recording.raster("world.opaque").color(CgGraphTexture.current(), CgLoad.load())
        .color(normals, CgLoad.load()).constants(constants).state(state);
chunks.draw(material.pipeline(CgInstanceKind.OBJECT).as(CgPipelineForm.NORMALS), bindings, mesh);    // writes both slots
```

- `texture` is the target's size (GL would draw into the intersection, quietly): the executor throws otherwise.
- A target lending no attachments (the window's, a multisampled one) takes none: it throws.
  `colorIfTaken(texture, load)` draws on the target alone there instead and logs once; ask `lendsAttachments()` first.
- A pipeline whose program does not write slot 1 has it masked off for its draws (`CgPipeline.slotWrites`; on a
  device, the pipeline's per-attachment write mask), which needs `CgCapabilities.independentBlend()`: a recorder checks
  it before asking.
- One blend serves both slots below GL 4.0 (`glBlendFunci` is 4.0): what a pipeline writes into slot 1 must suit the
  pass's blend.

**A colour drawn with another texture's depth**: a description is one texture, so a pass drawing colour and depth
names both, `color(colour, load)` and `depth(depth, load, access)`. The depth is the host's (an external target, as
the world's HDR scene is drawn over the host's: `render/world/CLAUDE.md`) or a depth texture of the graph's. A pass
attaches a depth only where it declares one: one into an external target that tests depth declares that target's
(`depth(CgGraphTexture.current(), ...)`). It is ordered as a read and write of that depth too. A stage's renderers draw
into `stage.target()` with `stage.depth()`, which the HDR scene sets with `retarget(scene, depth)`.

```java
CgGraphTexture scene = CgGraphTexture.transientTexture("scene", CgTextureDesc.of(w, h, CgFormat.RGBA16_FLOAT));
recording.raster("world.scene").color(scene, CgLoad.load()).depth(CgGraphTexture.current(), CgLoad.load(), CgDepthAccess.WRITE)
        .readsColor(CgGraphTexture.current(), CgBindingPoints.SCENE_COLOR_TEXTURE_UNIT)   // the host's colour, copied whole
        .constants(constants);

CgGraphTexture depth = CgGraphTexture.transientTexture("map_depth", CgTextureDesc.of(s, s, CgFormat.DEPTH32_FLOAT));
CgGraphTexture map = CgGraphTexture.transientTexture("map", CgTextureDesc.of(s, s, CgFormat.R32_FLOAT));
recording.raster("sun.map").color(map, CgLoad.clear(1e30f, 0, 0, 0))
        .depth(depth, CgLoad.clearDepth(1.0), CgDepthAccess.WRITE).constants(constants);   // clears both
```

- Only a depth of the graph's may be cleared by a pass's `depth(...)`: an external target's is its owner's, and the pass throws.
- A pass reading a depth (`readsDepth(depth, unit)`) names the depth texture; one that draws with it reads it as its
  own, copied as its draws write it, and any other samples it in place or copies it once.

- A target lending no attachments lends no depth: the recorder asks `lendsAttachments()` and gives its texture a depth
  of its own there. One drawn anyway draws without depth, logged once.
- The requested form is kept until released: drawn in one firing, read in a later one (the world's opaque scene).
- A pass into one may take a second colour (`color(texture, load)`): ours at slot 1 beside ours at 0 and the other's
  depth.
- `readsColor(from, unit)` is `readsDepth(from, unit)`'s twin: another target's colour, copied whole when the pass
  begins, in that colour's own format.

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

**Reading the target** (`CgRasterPass.readsColor(target, unit)`, `readsDepth(target, unit)` of its own colour): a draw whose shader reads
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
CgRasterPass pass = recording.raster("world.haze").color(target, CgLoad.load()).constants(constants).state(state)
        .readsColor(target, CgBindingPoints.SCENE_COLOR_TEXTURE_UNIT)    // haze, glass, water: the target as drawn so far
        .readsDepth(target, CgBindingPoints.DEPTH_TEXTURE_UNIT);         // soft particles, depth fades
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

**Another target's depth** (`readsDepth(from, unit)`): a pass into a target of its own (a bloom target, any size)
reads `from`'s depth as it stands after every write recorded before the call, copied once, whole, when the pass begins.
Readers sample by `gl_FragCoord.xy / CG_RESOLUTION`. The world's bloom is the user (`render/world/CLAUDE.md`).

**One depth copy, shared.** The executor keeps one copy of a framebuffer's depth per execution, and every pass after it
reading that depth, as another target's or as its own before any draw of its own wrote depth, reads that copy until
something may write it: a raster pass into that framebuffer's depth that clears it or has a batch whose state writes it
or does not say, a callback, an upload, a release, or the framebuffer going back to the pool. Unreal resolves scene depth once after the base pass the
same way. Counters `graph.target-copies` (blits) and `graph.depth-copies-shared` (copies read again). A pass's depth
state that says `DepthWrite OFF` keeps the copy alive; one that says nothing ends it.

```java
recording.raster("world.glow").color(glow, CgLoad.clear(0, 0, 0, 0)).constants(constants)
        .readsDepth(CgGraphTexture.current(), CgBindingPoints.DEPTH_TEXTURE_UNIT);
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

`CgRenderer.executeNow(recording)` (one package up) is the same three stages at once, for a caller with no view or
stage to record into; `CgRenderer.drawNow(chunk, camera, order)` is what an immediate canvas's `flush()` calls, into
the current target (`render-graph` G2).

**Drawing a mesh** (`renderer.md` § 4.4): `pass.draw(mesh, material)` answers a `CgDraw`, one instance an object record
the engine writes (`transform`, `customData`; `instance()` for the next, `records(...)` for many, `objects(...)` for a
kernel's); every pass of the material's chain draws the same records. A pass is seen through a `CgCamera`
(`camera.CgCamera`: view, projection, position, resolution, time; `pass.camera(camera)`), the depth convention and the
target's encoding the renderer's. The chunk builder and `captureBindings` are the engine's own path, and a test's of
the builder itself.

**What a recorded draw keeps of an immediate one** (G2's gate found each): a draw runs under the material as the
last `useMaterial` before it left it, since a bind used to upload values set after the records were queued; state a
caller sets after binding rides on the pipeline (`CgPipeline.withState`), or the pipeline's declared state would
overwrite it at the draw; and a texture bound by hand goes in through `bindTexture(unit, texture)`, winning the
unit as GL's last bind did.

**The canvas** (G4; lookback G5.3; `render/canvas`): `CgCanvas` records 2D items into raster passes:
`recordInto(recording, target, load, constants)` picks the target, `scissor`/`noScissor` and `constants` are its
set-state, a change of constants starts a pass, `endPass()` splits; nothing is read from GL. The caller executes the
recording once with `CgRenderer.executeNow(recording)`. A recorded draw executes after the canvas has moved on, so a
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

`--mode=graph-executor-test` draws one picture through an immediate canvas, a recording executed now, and a frame recorded and built
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
