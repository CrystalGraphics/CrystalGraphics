# GPU-driven rendering — kernels write what draws, multi-draw indirect draws it

**The main doc for building a rendering pipeline on compute shaders.** A kernel writes records and counts into GPU
buffers; draws take how much they draw and where from those buffers; the engine culls on the GPU, picks levels of
detail there, and joins the draws into multi-draw indirect calls. The CPU never reads a count, never touches an
element, and records the same few draws every frame however many elements there are.

It is written for a person or an agent building a consumer (particles, foliage, debris, crowds, procedural geometry,
a renderer of your own) and runs on every context the engine supports, with the same picture. The kernel language
itself is [`SHADERS.md` § *Compute*](SHADERS.md#compute--compute-kernels); the world renderer is
[`ENGINE_API.md` § *CgWorldRenderer*](ENGINE_API.md#cgworldrenderer--drawing-into-the-world).

**The one rule**: work that grows with the number of **elements** (particles, instances, vertices) runs on the GPU;
work that grows with the number of **effects or draws** (tens) stays on the CPU. An effect's life, look, transform and
spawn schedule are the CPU's; every particle's position, cull, sort and draw command is the GPU's.

## Contents

1. [The pipeline](#1-the-pipeline)
2. [Which entry point](#2-which-entry-point)
3. [Recipe: instances placed by a kernel](#3-recipe-instances-placed-by-a-kernel)
4. [Recipe: particles drawn by the count a kernel wrote](#4-recipe-particles-drawn-by-the-count-a-kernel-wrote)
5. [Recipe: many draws from one pool, ordered on the GPU](#5-recipe-many-draws-from-one-pool-ordered-on-the-gpu)
6. [Recipe: culling by hand in a renderer of your own](#6-recipe-culling-by-hand-in-a-renderer-of-your-own)
7. [Recipe: dispatching only what is alive](#7-recipe-dispatching-only-what-is-alive)
8. [What joins into one call](#8-what-joins-into-one-call)
9. [Writing the material](#9-writing-the-material)
10. [Designing the pipeline](#10-designing-the-pipeline)
11. [Every tier](#11-every-tier)
12. [What it costs](#12-what-it-costs)
13. [Checking it](#13-checking-it)
14. [Easy to get wrong](#14-easy-to-get-wrong)
15. [Not available yet](#15-not-available-yet)
16. [Checklist for an agent](#16-checklist-for-an-agent)

## 1. The pipeline

```
CPU, per frame: O(draws)                          GPU, per frame: O(elements)
  record a compute pass ahead of the draws  --->    kernels: simulate, spawn, place, bin      (compute pass)
  submit one draw per (mesh, material, slot) --->   records + counts in graph buffers
                                                    cull + level per instance (CgGpuOps.cull)  (world renderer)
                                                    draw commands written from the counts      (executor)
                                                    one multi-draw per run of joinable draws   (executor)
```

- **Recording order is the only ordering you write.** A compute pass recorded before a draw that reads its buffers
  runs first; the graph derives every barrier from what each step reads and writes, on every device.
- **Nothing waits.** A count goes from the kernel that wrote it to the command that draws it on the GPU. What the CPU
  wants to know (live bounds, events, a count for a budget) comes back through `CgReadback` frames later.

## 2. Which entry point

| You have | Use | The engine does |
|---|---|---|
| A buffer of object records (transforms and customs) for one mesh or `CgMeshLods` | `world.draw(lods, material).instances(records, count)` | per stage: a depth pyramid, a GPU cull against view and pyramid, a level per instance, a draw per level, the levels one call |
| A count a kernel wrote, and a material that pulls its own vertices from your buffers | `world.draw(mesh, material).indirect(count, offset, mode, factor)` | a draw command from the count, joined with its neighbours |
| A renderer of your own recording chunks | `chunks.draw(...).objects(records, first, n).indirect(...)`, `CgGpuOps.cull`, `CgGpuOps.depthPyramid` | commands, joining, barriers; the cull and pyramid are yours to record |
| Work whose size only the GPU knows | `CgGpuOps.dispatchArgs` + `pass.dispatchIndirect` | group counts written on the GPU |

Start from the first row that fits: the world renderer's paths sort, light, cull and join for you.

## 3. Recipe: instances placed by a kernel

A forest of trees, placed once by a kernel and drawn every frame as one world draw, culled and given its level of
detail per tree on the GPU.

```glsl
// mymod:shaders/forest.compute
#pragma kernel Place map
#include "crystalgraphics:shaders/lib/rng.glsl"

Properties {
    _Columns ("Trees per row", int)   = 256
    _Spacing ("Spacing",       float) = 6.0
}

struct Tree {   // CgInstanceKind.OBJECT's record, 48 floats: what every instanced draw reads as CG_OBJECT_DATA
    vec4 model0;  vec4 model1;  vec4 model2;  vec4 model3;    // model matrix columns, in the draw's own space
    vec4 normal0; vec4 normal1; vec4 normal2; vec4 normal3;   // normal matrix columns; normal3 is the engine's
    vec4 custom0; vec4 custom1; vec4 custom2; vec4 custom3;   // CG_OBJECT_CUSTOM0..3: whatever the material reads
};

Buffers {
    TREES ("Trees", Tree, writeonly)
}

void Place() {
    int i = CG_ELEMENT;
    vec4 r = cg_rng_unit4(cg_rng4(7u, uint(i), 0u, 0u));   // keyed by the element: the same forest every run
    float s = 0.8 + r.z * 0.6, a = r.w * 6.2831853, c = cos(a), n = sin(a);
    Tree t;
    t.model0 = vec4(c * s, 0.0, -n * s, 0.0);
    t.model1 = vec4(0.0, s, 0.0, 0.0);
    t.model2 = vec4(n * s, 0.0, c * s, 0.0);
    t.model3 = vec4(float(i % _Columns) * _Spacing + r.x, 0.0, float(i / _Columns) * _Spacing + r.y, 1.0);
    t.normal0 = vec4(c, 0.0, -n, 0.0);
    t.normal1 = vec4(0.0, 1.0, 0.0, 0.0);
    t.normal2 = vec4(n, 0.0, c, 0.0);
    t.normal3 = vec4(0.0);                                  // light and emission: stamped by the engine
    t.custom0 = vec4(r.xyz, 1.0);                           // a tint per tree
    t.custom1 = vec4(0.0); t.custom2 = vec4(0.0); t.custom3 = vec4(0.0);
    TREES_WRITE(t);
}
```

```java
CgKernel place = CgCompute.load("mymod:shaders/forest.compute").kernel("Place");
CgGraphBuffer trees = CgGraphBuffer.persistent("forest.trees",
        CgBufferDesc.elements(N, CgGpuOps.cullRecordBytes(), CgBufferUsage.STORAGE));
CgMeshLods treeLods = CgMeshLods.builder().level(treeHigh, 0.30f).level(treeMid, 0.12f).level(treeLow, 0.04f).build();

// Once, ahead of the first draw: a renderer below the world renderer records into the stage's frame
CgRenderStage.WORLD_OPAQUE.register(CgWorldRenderer.ORDER - 1, frame -> {
    if (placed) return;
    CgComputePass pass = frame.recording().compute("forest.place");
    pass.dispatch(place, N).bind("TREES", trees).set("_Spacing", 6f);
    pass.end();
    placed = true;
});

// Every frame: one draw, whatever N is
world.draw(treeLods, bark).instances(trees, CgGpuCount.of(N)).at(x, y, z).bounds(0, 0, 0, w, 40, d).submit();
```

- **Each stage that draws the set** builds a depth pyramid of what was drawn before the world renderer (in Minecraft,
  the terrain), culls every tree against the view and the pyramid, picks its level by screen height, and draws each
  level kept as one indirect draw; the levels are one multi-draw call where draws join (§8).
- **Records are in the draw's own space**: `.at()` places the set, a record places a tree within it. `.bounds()` is the
  whole set's box, culled once on the CPU; each tree is culled by its mesh's box, grown by `.pad()`.
- A count only the GPU knows works the same way: `CgGpuCount.at(alive, 0, capacity)`.
- A persistent buffer outlives the frame, so the pass writing it is never culled; release it with
  `recording.release(trees)` when the forest goes.

## 4. Recipe: particles drawn by the count a kernel wrote

The particle shape: a history buffer stepped by a kernel, the live ones compacted, a quad per live particle pulled
from the buffers by the material. [`SHADERS.md` § *Start to finish*](SHADERS.md#start-to-finish) is this recipe in
full; the core:

```java
CgRenderStage.WORLD_OPAQUE.register(CgWorldRenderer.ORDER - 1, frame -> {
    CgComputePass pass = frame.recording().compute("sparks.step");
    pass.dispatch(step, CAPACITY).bind("IN", sparks).bind("OUT", sparks).bind("ALIVE", alive).set("_Step", dt);
    CgGpuOps.compact(pass, alive, null, CgGpuCount.of(CAPACITY), live, count, 0);   // live indices, and how many
    pass.end();
});

sparkMaterial.buffer("SPARKS", sparks).buffer("LIVE", live);
world.draw(CgMesh.quads(CAPACITY), sparkMaterial).indirect(count, 0, CgIndirect.INDICES, 6).at(x, y, z).bounds(box).submit();
```

```glsl
Spark s = SPARKS(LIVE(CG_VERTEX_ID >> 2));   // quad n draws the nth live spark
```

- **`count` is persistent**: a transparent material draws in `WORLD_TRANSPARENT`, another stage's frame, and a
  transient lasts one stage.
- **`INDICES` × 6** draws six indices per live spark from `CgMesh.quads(CAPACITY)`, never more than the mesh holds.
  `INSTANCES` instead draws the mesh once per element, each instance finding its element as `CG_DRAW_INSTANCE`: a
  billow mesh per live puff.
- **Bounds are yours**: the count is unknown when the draw is culled, so state a box every element stays inside
  (authored, or a `CgGpuOps.bounds` read back frames late and grown by the fastest speed times the delay).

## 5. Recipe: many draws from one pool, ordered on the GPU

The production shape for many effects sharing one simulation (Niagara's GPU sims, Unity VFX Graph's instancing, Wicked
Engine and AMD's *Holy Smoke* drawing from the alive list): every playing effect's particles live in **one pool**,
stepped by one dispatch, and each (effect, renderer) pair is a **slot** the CPU numbers when it submits the draws. Each
frame one pass bins every particle into its slot, one sort orders every slot back to front at once, and each slot is
one indirect draw reading its run of the sorted list. Every slot shares the material and its buffers, so the slots join
into one call.

```java
// Bin (a map you write): KEYS(i) = slot << 16 | (65535 - quantised view depth) for a particle drawn, 0xFFFFFFFF for
// one culled or dead (it sorts last); INDEX(i) = i.
CgComputePass lists = rec.compute("sparks.lists");
lists.dispatch(bin, capacity).bind("STATE", pool).bind("KEYS", keys).bind("INDEX", index);
CgGpuOps.histogram(lists, keys, live, slotCounts, slots + 1, 16);             // per slot, the last bin the culled
CgGpuOps.scan(lists, Scan.EXCLUSIVE, Fold.SUM, Element.UINT, slotCounts, CgGpuCount.of(slots), slotFirst);
CgGpuOps.sort(lists, Element.UINT, Order.ASCENDING, keys, index, live);       // by slot, then back to front
lists.end();

sparkMaterial.buffer("SPARKS", pool).buffer("INDEX", index).buffer("FIRST", slotFirst);
for (int s = 0; s < slots; s++) {
    world.draw(CgMesh.quads(capacity), sparkMaterial).indirect(slotCounts, s * 4L, CgIndirect.INDICES, 6)
         .custom(0, s, 0, 0, 0).at(effectX[s], effectY[s], effectZ[s]).bounds(effectBox[s]).submit();
}
```

```glsl
uint slot = uint(CG_OBJECT_CUSTOM0.x);
Spark s = SPARKS(INDEX(FIRST(slot) + uint(CG_VERTEX_ID >> 2)));
```

- **Why**: N effects cost one simulation dispatch, one bin, one sort and one draw call, where one pool per effect
  costs N of each. The sort runs only for pools with an alpha-blended renderer; additive and opaque slots skip it.
- **Inverting the depth** puts far first inside an ascending sort, so a slot's run stays where the scan says it starts.
- Composed from built ops (`histogram`, `scan`, `sort`, indirect draws), each gated on every tier; the composition
  itself has no gate scene yet.

## 6. Recipe: culling by hand in a renderer of your own

What `.instances()` does inside the world renderer, for a renderer recording its own passes. `CgGpuOps.cull` writes
each level's kept records into one buffer and each level's count into `counts`; one chunk draw per level reads them.

```java
CgCull cull = new CgCull().mesh(rockLods);                                    // once
// In the stage, after what hides the rocks has drawn:
CgGraphTexture pyramid = CgGraphTexture.transientTexture("rocks.pyramid",
        new CgTextureDesc(w, h, CgGpuOps.PYRAMID_FORMAT).withMips());
CgGraphBuffer visible = CgGraphBuffer.transientBuffer("rocks.visible",
        CgBufferDesc.elements(CgGpuOps.cullRecords(cull, n), CgGpuOps.cullRecordBytes(), CgBufferUsage.STORAGE));
CgGpuOps.depthPyramid(rec, stage.target(), stage.constants(), pyramid);
CgComputePass pass = rec.compute("rocks.cull");
CgGpuOps.cull(pass, cull.view(view, projection).place(place).pyramid(pyramid), rocks, CgGpuCount.of(n), visible, counts, 0);
pass.end();

CgRasterPass draw = stage.pass(stage.constants(), CgOrder.LOOKBACK);
CgChunkBuilder c = rec.chunks().begin();
for (int l = 0; l < cull.levels(); l++) {
    c.draw(pipeline, bindings, rockLods.level(l)).objects(visible, CgGpuOps.cullFirst(l, n), n)
     .indirect(counts, l * 4L, CgIndirect.INSTANCES, 1);
}
draw.add(c.end());
draw.end();
```

- `objects(records, first, n)`: instance i reads record `first + i` through `CG_OBJECT_DATA`, in any material.
- `counts` needs `STORAGE` and `INDIRECT`; a word per level.
- `place` is where the set sits, camera-relative (the camera subtracted in doubles).

## 7. Recipe: dispatching only what is alive

A kernel that should run once per live element, where only the GPU knows how many:

```java
CgGpuOps.dispatchArgs(pass, CgGpuCount.at(count, 0, capacity), 64, args, 0);   // three group counts; args: INDIRECT
pass.dispatchIndirect(collide, args, 0).bind("STATE", sparks).bind("LIVE", live);
```

A GPU count passed to an op (`CgGpuCount.at(buffer, word, capacity)`) already does this inside the op: it dispatches
the capacity and every kernel stops at the count it reads.

## 8. What joins into one call

Where `CgCapabilities.multiDraw()` holds (multi-draw indirect, draw parameters and a first instance: desktop GL 4.3+
drivers and the Vulkan device; not macOS's GL), the executor joins **consecutive** draws into one
`glMultiDrawElementsIndirect`. Indirect draws join when their commands are written on the GPU. A run joins while every
draw has:

| Same | So |
|---|---|
| **pipeline** | one material shader and keyword set; a different material ends the run |
| **bindings** | the same textures, buffers and property values: two `CgMaterial` instances with different textures do not join (`graph.multi-draw.binding-breaks` counts these). Share one material and vary per instance through `CG_OBJECT_CUSTOM*` and atlases or texture arrays |
| **scissor**, and no target copy between | a `sceneColor`/`sceneDepth` reader between two draws splits them |
| **slab** | meshes of one vertex format share a slab of 64K vertices and 256K indices until it fills; a bigger mesh gets its own. `FRAME` meshes join within one ring page |
| **indexing** | meshes with indices and meshes without never share a call |
| **object records** (indirect draws) | the same records buffer: the frame's own, or one `objects()` buffer |

- **`INSTANCES` draws of the frame's records never join**: every instance reads one record, and the joined variant has
  no shared record. `INDICES` and `VERTICES` draws do.
- **Ordering decides adjacency.** The world renderer sorts opaque draws by sort layer, material, distance, mesh, so
  draws of one material and slab tend to sit together; transparent draws sort back to front, a `.group()` as one.
- **The picture never changes**: joining is a pure call reduction, and `-Dcrystalgraphics.mesh.multiDraw=false` proves
  it (§13).

## 9. Writing the material

```glsl
#type none                                         // no vertex data: the shader places every vertex
Queue = "Geometry"

struct Spark { vec4 positionLife; vec4 velocitySeed; };
Buffers {
    SPARKS ("Sparks", Spark, readonly)             // what the kernel wrote, readonly here
    LIVE   ("Live",   uint,  readonly)
}

Pass {
    struct v2f { float life; vec4 tint; };
    void vertex(out v2f o) {
        Spark s = SPARKS(LIVE(CG_VERTEX_ID >> 2));
        vec3 p = s.positionLife.xyz + vec3(CG_VERTEX_CORNER - 0.5, 0.0) * 0.1;
        o.life = s.positionLife.w;
        o.tint = CG_OBJECT_CUSTOM0;                // this draw's own parameters
        gl_Position = CG_MATRIX_MVP * vec4(p, 1.0);
    }
    void fragment(in v2f i, out vec4 fragColor) { fragColor = i.tint * clamp(i.life, 0.0, 1.0); }
}
```

| Use | Never | Why |
|---|---|---|
| `CG_VERTEX_ID`, `CG_VERTEX_CORNER` | `gl_VertexID` | a joined draw's base vertex is the mesh's place in the slab |
| `CG_INSTANCE_ID`, `CG_DRAW_INSTANCE` | `gl_InstanceID` | a joined draw's first instance is its batch's base; `CG_DRAW_INSTANCE` is the element an `INSTANCES` draw draws |
| `CG_OBJECT_DATA`, `CG_OBJECT_TO_WORLD`, `CG_OBJECT_CUSTOM0..3` | a uniform per draw | a multi-draw changes no uniform between its commands |

- The engine compiles the `CG_MULTI_DRAW` variant of every pipeline itself; a material using the macros needs nothing
  else.
- `Buffers { }` takes the kernel's element grammar (scalars, 2- and 4-vectors, structs of `vec4`, `ivec4`, `uvec4`),
  at most four buffers, bound with `material.buffer(name, buffer)`
  ([`SHADERS.md` § *Reading a kernel's buffers*](SHADERS.md#reading-a-kernels-buffers)).
- A record's light is `CG_OBJECT_LIGHT`, its emission scale `CG_OBJECT_EMISSION`: `.light()` on the draw, or the
  world's at its position.

## 10. Designing the pipeline

What production engines do, and what follows here. Research: `plan/crystalgraphics/vfx-gpu.md` §2-§5;
`plan/crystalgraphics/gpu-compute.md` §4.

1. **Keep elements on the GPU from birth to draw.** A spawn kernel creates them, an update kernel moves them, the draw
   reads them. Uploading or reading one per frame puts the CPU back in the loop.
2. **Let the CPU know the counts it can.** A spawn schedule is deterministic and every life is bounded, so the CPU
   sizes buffers and knows when an effect is finished without reading anything back. Read back only what it cannot
   predict (collision events, live bounds, counts for a budget), frames late, and steer by what arrived.
3. **Pick a state layout every tier runs.** *Compacted* (Niagara's GPU sims): each tick appends survivors into the next
   version of a history buffer, spawns after them; dense, no fragmentation, and `append` lowers. *Fixed slots*
   (Godot): slot i is particle i forever, a dead one inactive; simplest, wastes capacity on bursty effects. *Dead and
   alive lists* (Wicked, AMD): fastest on compute, but every push and pop is an atomic, so `compute_only`.
4. **One pool per kernel shape, not per effect.** Every effect whose kernels are the same shares a pool and one
   dispatch per tick (Niagara Data Channels, VFX Graph instancing); an effect's numbers are a row of a parameter table.
5. **Build draw lists on the GPU**: bin, count per slot, scan, sort (§5). One sort keyed (slot, depth) orders every
   alpha slot at once.
6. **Meshes go through object records**: a kernel writes each element's record, and `.instances()` culls and levels
   them (§3). Haar and Aaltonen (SIGGRAPH 2015) and Wihlidal (GDC 2016) are the model.
7. **Join by design.** One material per look with per-instance variation in customs and atlases; meshes of one vertex
   format built together so they share a slab; vertex pulling (`#type none`) where a mesh is only a quad or a strip.
8. **Bounds**: authored for a definition (Niagara's fixed bounds), or a GPU reduce read back late and grown by the
   speed limit times the delay. A draw with no bounds cannot be culled on the CPU.
9. **Transparency**: sort what blends (the (slot, depth) key); additive needs no order.
10. **Overlap**: compute that leaves the GPU idle (small dispatches, a reduction's last levels) goes in an `async()`
    pass beside fill-bound drawing, on a device with a compute queue (§12).
11. **Design for every tier** ([`SHADERS.md` § *Designing for every tier*](SHADERS.md#designing-for-every-tier)):
    lowerable shapes, `vec4` records, few dispatches below compute, and work sized by `kernel.form()`.

## 11. Every tier

| | V (Vulkan device) | G43 (GL 4.3+) | G40 (GL 4.0-4.2, macOS 4.1) | G33 (GL 3.3) | CPU (forced) |
|---|---|---|---|---|---|
| Kernels | compute | compute | lowered to draws | lowered | Java bodies |
| A draw's count | a command written by a kernel | the same | a command written by a lowered kernel | read back before the draw: a stall (`buffer.readbacks`) | the CPU's copy, no read |
| Multi-draw | where the device enables it | where `multiDraw()` | where `multiDraw()` (not on macOS) | plain draws where `multiDraw()`; indirect ones never (their counts are read back) | plain draws, as its context |
| GPU cull | compute | compute | lowered | lowered, counts read back | Java body |
| Async compute | the owned device's compute queue; in order on Minecraft's | in order | in order | in order | in order |

The picture is the same on every tier; each one's gate is the same scene compared byte for byte (§13).

## 12. What it costs

Measured on an RTX 4070 SUPER, warm, median of 100 frames: CPU time per frame of both world stages for a set of
spheres, most off screen.

| Spheres | Culled on the CPU, GL / Vulkan | GPU cull, own pass | GPU cull, one world draw |
|---|---|---|---|
| 500 | 0.4 / 0.2 ms | 0.5 / 0.5 ms | 0.5 / 0.35 ms |
| 50,000 | 3.9 / 3.3 ms | 0.7 / 0.6 ms | 0.5 / 0.5 ms |
| 500,000 | 31 / 30 ms | 0.8 / 0.7 ms | 0.55 / 0.5 ms |

- **The GPU path is flat in the element count**; the CPU path grows with it. Below a few hundred elements either is
  cheap, and the CPU cull skips a dispatch.
- **Ops** at a million elements, GPU time on compute: sort 0.4-0.5 ms, compact 0.11, scan 0.08, reduce 0.03,
  histogram 0.01. Lowered, up to 40 times that (a 32-bit sort 8 ms). The full table and per-op CPU:
  [`SHADERS.md` § *Ops*](SHADERS.md#ops-cggpuops).
- **A dispatch's CPU**: under 1 µs on GL, 3-4 µs on the Vulkan device; 0.1-0.5 ms per op lowered.
- **Joining** takes `--mode=multi-draw`'s 77 calls to 4. Its CPU saving on GL is within noise at 64 draws (drivers
  batch well); on the Vulkan device the raster loop's CPU halves. Its real worth is the GPU-written commands of a
  culled set landing in one call.
- **Async**: beside eight 1080p blurs (2.5 ms), half of 1 ms of fill-bound drawing disappears on the Vulkan device.

## 13. Checking it

| Check | How |
|---|---|
| Joining changes no pixel | run with `-Dcrystalgraphics.mesh.multiDraw=false` and compare; `--mode=multi-draw` and `--mode=gpu-cull` do it byte for byte |
| How many calls | counters `mesh.multi-draws`, `mesh.multi-draw-commands`, `mesh.indirect-draws`, `graph.multi-draw.binding-breaks`; `CgMeshStore.get().drawCalls()` |
| What a kernel wrote | the Frame Profiler's **Buffers** tab, or `CgBufferInspector` (`render/graph/CLAUDE.md`) |
| An index out of range | `-Dcrystalgraphics.compute.checked=true`: the kernel, the line and the index |
| A missing barrier | the Vulkan device with `-Dcrystalgraphics.vulkan.syncValidation=true`; `-Dcrystalgraphics.graph.asyncAll=true` checks every async wait |
| Every tier | `-Dcrystalgraphics.compute.tier=G43|G40|G33|CPU` on your GPU; `-Pharness.downlevel=mac41|gl33` for a context that lacks the features |
| Its cost | the `profiling` skill; a pass's CPU zone is its name, its GPU zone on `crystalgraphics.gl.detail` |

## 14. Easy to get wrong

- **A kernel recorded after the draw reading it** draws last frame's data or nothing: in a world stage, register the
  renderer recording it below `CgWorldRenderer.ORDER`.
- **A transient count read in another stage**: a count a transparent draw reads must be persistent.
- **New buffer storage is not zeroed**: fill a count before an append, write every element before a draw reads it.
- **`gl_VertexID` or `gl_InstanceID` in a material** draws garbage once draws join; use the `CG_` macros (§9).
- **A transparent `.instances()` set** draws its instances in no order among themselves: sort them yourself (§5) or
  keep the set opaque or additive.
- **Different textures per draw** break joining: share one material, use customs and atlases.
- **`INSTANCES` draws of the frame's records never join**: a mesh per element wants `objects()` or `.instances()`.
- **No bounds** on an indirect draw: the count is unknown when it is culled, so it is culled by its mesh's box alone.
- **`CgKernelProgram`** orders nothing after it and does not run below compute; a graph's dispatch does both.

## 15. Not available yet

What a pipeline here cannot do today, so a design does not assume it:

- `.instances()` over a range of a records buffer, so several mesh slots share one buffer.
- History buffers that grow: size a pool for its peak.
- 3D textures in the graph: use an atlas of slices.
- Persistent world draws (a GPU scene scatter-updated only when something moves): records are rewritten every frame.
- Two-phase occlusion for the engine's own draws: the pyramid is of what the host drew first.
- Clustered lights and decals, an order-independent transparent queue, bindless textures.
- `CgGpuBudget`: nothing scales a consumer's work for it.
- Multi-draw on macOS's GL: each draw is its own call there.

## 16. Checklist for an agent

1. Decide what is per element (GPU) and per effect or draw (CPU), by the rule at the top.
2. Pick the entry point (§2); prefer the world renderer's `.instances()` or `.indirect()`.
3. Write kernels in lowerable shapes with `vec4` records; library ops for sort, scan, compact, histogram, bounds.
4. Record the compute pass ahead of its draws; make every buffer a later stage reads persistent.
5. Write the material with the `CG_` macros and `Buffers { }`; one material per look.
6. Bounds on every indirect draw.
7. Check: `multiDraw=false` gives the same picture, the counters show the calls you expect, checked mode is quiet,
   synchronization validation is clean, and it passes forced to G40, G33 and CPU.
8. Measure before and after with the profiler, warm.
