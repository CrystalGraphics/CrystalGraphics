# `com.crystalgraphics.compute` — kernels from `.compute` files

A `.compute` holds kernels: GLSL functions the GPU runs once per element, declared with a local size and a
**shape** that says what they write. This package reads the file, writes each kernel's GLSL for the device,
compiles and wires it, and dispatches it. **`crystalgraphics:shaders/example.compute` is the reference** — every
part of the format, annotated, compiled by the tests on every target. Read it first.

```java
CgCompute particles = CgCompute.load("mymod:shaders/particles.compute");   // parsed once; any thread
CgKernelProgram simulate = particles.kernel("Simulate").withKeywords("WIND").program();   // render thread
simulate.properties().set1f("_Drag", 0.1f);
try (CgGlScope scope = CgKernelProgram.scope()) {
    simulate.use().buffer("STATE", stateBuffer).dispatch(particleCount);
}
CgGL.cgBufferBarrier(stateBuffer, CgAccess.COMPUTE_WRITE, CgAccess.VERTEX_READ);   // the reader's
```

`.compute` files live under `shaders/`, beside the `.shader` that draws what they write. In a frame, kernels run in
a graph's compute pass (`render/graph/AGENTS.md`), which binds, orders and fences them itself:

```java
CgComputePass sim = recording.compute("particles.simulate", constants);
sim.dispatch(simulate, count).bind("STATE_IN", state).bind("STATE_OUT", state).set("_Drag", 0.1f);
sim.end();

try (CgImmediate.Compute run = CgImmediate.compute("bake")) {     // outside a graph: a graph of one pass
    run.dispatch(bake, count).bind("CELLS", CgGraphBuffer.imported("cells", glBuffer, bytes));
}
```

## Packages

| Package | Holds |
|---|---|
| (root) | `CgCompute` — a file: `load`, `fromSource(key, text)` for generated kernels, `kernel(name)`, `reload`, `releaseAll` (context teardown). `CgKernel` — a kernel and keyword set, a value: `program()`, `glsl()`, `cpu(body)`, `form()`, `runs()`, `check()`, `lowered()`. `CgKernelForm` — how this context runs a kernel, and the every-tier check (below). `CgDispatchBindings` — what a dispatch below compute binds, in GL names |
| `source` | What a `.compute` declares, as data, no GL: `CgComputeSource`, `CgKernelDecl` (size, shape, fallback, and what its code reaches), `CgBufferDecl`, `CgImageDecl`, `CgSourcePart` (the code, cut where kernels differ), the vocabularies `CgKernelShape`, `CgBufferAccess`, `CgImageAccess`, `CgImageFormat`, `CgImageDimension`, and the accessors `CgBufferAccessor`/`CgImageAccessor` with the rule for each |
| `parse` | `CgComputeParser`, GL-free; package-private `TopLevel` (file scope, item by item), `GlslText`, `Std430`, `ConstantInt` |
| `emit` | `CgKernelEmitter` (one kernel's GLSL for a target), `CgKernelTarget` (the device's GLSL, subgroups, float atomics, limits), `CgGlslBuiltins` (each builtin newer than GLSL 3.30, its version and its exact polyfill, or none) and `CgPropertyBlock` (where each `Properties` value sits in `CgKernelBlock`, GL-free, so a dispatch packs its values when recorded) |
| `program` | `CgKernelProgram`: a compiled, wired kernel; its direct dispatch, and `dispatchBound` for a graph that binds everything itself |
| `lower` | A kernel below compute (C5): `CgLowering` (the passes a shape lowers to, or the construct that stops it; GL-free), `CgLoweredEmitter` (each pass's stages), `CgLoweredTarget`, `CgLoweredKernel` (the passes compiled, and its dispatch), `CgLoweredPrograms` (the helper programs), `CgLoweredResources` (scratch, texel targets, zeroed counters), `CgTexelTarget` |
| `cpu` | The CPU tier (C6): `CgCpuBody` (a kernel's Java body), `CgCpuDispatch`, `CgCpuBuffer`, `CgCpuImage` (what a body sees), `CgCpuRunner` (runs one), `CgCpuMirrors` (the CPU copies of GL buffers) |
| `ops` | The library of GPU operations (C7): `CgGpuOps` (fill, iota, copy, dispatch arguments, reduce, bounds, scan, compact, sort, histogram; downsample and blur over a texture's levels), `CgGpuCount` (a count fixed or on the GPU), `CgRng` (`lib/rng.glsl`'s Java twin), `CgGpuOpsCheck` (every op checked against Java on this context); kernels in `shaders/env/compute/ops/` |

The frame graph's compute pass is `render/graph`'s (gpu-compute C3); the primitives and readback take `ops` and
`readback` here as they land. The engine's own kernels are
`.compute` files under `shaders/env/compute/`, beside what every kernel includes: `args.compute` writes an indirect
draw's command (C4).

## The path

```
text --CgComputeParser--> CgComputeSource --CgKernelEmitter(target)--> GLSL with #includes
     --CgShaderPreprocessor--> CgShaderProgram.compileCompute --> CgKernelProgram (wired)
```

**The parser sees through includes; the emitter keeps them.** Analysis runs on the expanded text, so a helper in
an included lib counts toward what a kernel reaches; the emitted source keeps the `#include` line, so `#pragma once`
still collapses a lib the engine's env files include too.

## What a kernel's source is

The header the target needs (`#version 430 core`, or `420 core` with the ARB compute extensions below GL 4.3; the
KHR subgroup extensions where they are native), the keywords asked for, `CG_COMPUTE_STAGE`, the size macros
(`CG_LOCAL_SIZE_X/Y/Z`, `CG_GROUP_SIZE`, `CG_GROUP_POW2`, `CG_DIMENSIONS`, `CG_KERNEL_<Name>`), `cg_env.glsl`,
`env/compute/kernel.glsl` and `atomic.glsl` (and `subgroup.glsl` if it uses `CG_SUBGROUP_*`), the properties, the
engine buffers, the polyfills of the builtins its GLSL lacks, then **the file's code with every function and `shared` variable this kernel does not reach left
out**, the `Buffers`/`Images` blocks replaced in place by declarations and accessors for the buffers and images it
reaches (a stage holds 16 storage blocks on NVIDIA, and a file may declare more), and a `main` that calls the kernel —
returning first for an invocation past the count, in every shape but `general`.

| Built-in | Is |
|---|---|
| `CG_ELEMENT` | this invocation's element, x fastest |
| `CG_DISPATCH_ID`, `CG_DISPATCH_COUNT` | where it is, and the elements asked for, per axis (an indirect dispatch's count is its groups) |
| `CG_IN_RANGE`, `CG_TEXEL` | whether it is one of them; the texel an image kernel writes |
| `CG_GROUP_ID`, `CG_LOCAL_ID`, `CG_LOCAL_INDEX` | the work group's, in a general kernel only |
| `CG_SUBGROUP_*` | `env/compute/subgroup.glsl`'s operations, native or emulated |
| `CG_ATOMIC_ADD_FLOAT` / `_MIN_` / `_MAX_` | float atomics on uint storage, a compare-and-swap loop |

## Bindings

Wired by name after linking, so the source carries no `binding =`:

| What | Where |
|---|---|
| Buffer `i` of `Buffers { }` | storage binding point `i` (`CgBuffer_NAME`; a float atomic's `CgBufferBits_NAME` at the same point) |
| An append buffer's count | the next point after every buffer, in order (`CgCounter_NAME`): `CgKernelProgram.counter` |
| Image `i` of `Images { }` | image unit `i` |
| A sampler property | texture unit, by its place among the samplers |
| `Properties` values | `CgKernelBlock` at `CgBindingPoints.MATERIAL_PROPERTIES_UBO` |
| The frame block | `CgFrameBlock` at `FRAME_DATA_UBO`: `CgKernelProgram.frame(constants)` in a direct dispatch |
| An engine buffer (`#pragma cg_use`) | its own binding point; writable in a general kernel, read-only in the rest |
| `cg_Dispatch[6]` | base and count per dispatch, one `glUniform1iv` |

## Three forms: compute, lowered, a Java body

A context runs each kernel in one of three forms, chosen by its tier (`CgCapabilities.computeTier()`, forced with
`-Dcrystalgraphics.compute.tier=V|G43|G40|G33|CPU`) and asked with `kernel.form()`. The frame graph runs whichever it
is; the caller writes nothing per tier.

| Tier | A kernel runs |
|---|---|
| V, G43 | as compute |
| G40, G33 | lowered; else its `#pragma fallback` lowered; else its Java body; else its fallback's |
| CPU | its Java body, else its fallback's |

```java
CgKernel bin = kernels.kernel("Bin");              // general: shared memory, barrier()
// in the file: #pragma kernel BinLowered scatter / #pragma fallback Bin BinLowered
bin.cpu(d -> {                                     // and a Java body, for the CPU tier
    CgCpuBuffer halves = d.buffer("HALVES");
    for (int e = d.first(); e < d.end(); e++) halves.addInt(e % 2, 1);
});
```

**Lowered** (`lower`, gpu-compute §6.2): each shape becomes draws every GL from 3.3 has. A buffer the kernel writes its
own element of is a render target laid out as the buffer's words, drawn by one fragment pass over its texels, and up to
eight buffers of one layout share the pass. Appends are emitted by a geometry stage, captured by transform feedback and
counted into a 1x1 float target with additive blending. A scatter is points into a target laid out element to texel,
blended for adds, minima and maxima, and an image is a fragment pass. Buffers are read as buffer textures. Every pass
reads what the buffers held before the dispatch; after the last, each target is read into its buffer on the GPU
(`glReadPixels` into a pixel pack buffer). G40 sizes an indirect dispatch's draws from the GPU's count
(`glDrawArraysIndirect`); G33 reads the count back, a stall counted as `buffer.readbacks`. A device has no transform
feedback, so a lowered tier forced on one is refused at startup.

**The CPU tier** (`cpu`, §6.4): the body runs over CPU copies of the bound buffers (`CgCpuMirrors`), read back the
first time and kept while nothing else writes the buffer; what it writes is uploaded through the frame ring before the
next pass. A map, gather, append or image body runs as ranges on a pool of daemon workers (`crystalgraphics-compute-*`),
a scatter or general body once, in order. Images are read whole before the body and written whole after.

## Every tier, asked on any machine

A kernel's first dispatch, and its first `program()`, ask **every tier a player may have**, not only this machine's
(gpu-compute §6.6): the form G43, G40 and G33 would choose, and that each compiles the builtins the kernel reaches
at that tier's lowest GLSL (4.20, 4.00, 3.30). So a kernel a Mac would refuse is refused on the author's NVIDIA too,
naming the tier. `CgKernelForm.check` is the same question, GL-free.

A kernel that needs compute and has no lowered form says so, and its caller asks before dispatching:

```glsl
#pragma kernel Sort 256 general
#pragma compute_only Sort        // never lowered; refused below compute, asked of compute only
```

```java
CgKernel sort = kernels.kernel("Sort");
if (sort.runs()) pass.dispatch(sort, count).bind("KEYS", keys);   // false below compute without a Java body
else sortOnTheCpu(keys);
```

**Builtins newer than 3.30** (`bitCount`, `findMSB`, `uaddCarry`, `umulExtended`, `fma`, `frexp`, `packUnorm4x8`,
`packHalf2x16`, …) are called through an exact polyfill wherever the stage's GLSL lacks them: the call becomes
`_cg_bitCount(...)` and the definition goes before the kernel's code. One no polyfill gives exactly
(`textureGather`, `textureQueryLevels`, a `double`) is refused where the tier lacks it, with its version and the tier's.
`CgGlslBuiltins` is the table.

## Ops: `CgGpuOps`

What every GPU-driven consumer would otherwise write for itself, dispatched into the caller's compute pass and run on
every tier with the same answer on each:

```java
CgComputePass pass = recording.compute("particles");
pass.dispatch(simulate, capacity).bind("STATE", state).bind("ALIVE", flags);
CgGpuOps.compact(pass, flags, null, CgGpuCount.of(capacity), live, counts, 0);   // the live indices, in order
CgGpuCount alive = CgGpuCount.at(counts, 0, capacity);                         // how many, as the GPU counted
CgGpuOps.sort(pass, Element.FLOAT, Order.DESCENDING, depths, live, alive);    // back to front, stable
CgGpuOps.dispatchArgs(pass, alive, 64, args, 0);                              // for dispatchIndirect
pass.end();
```

- **A count is fixed or a word on the GPU.** A GPU count dispatches the capacity, and every kernel stops at the count
  it reads, so no op needs the count on the CPU; scratch is graph transients sized by the capacity.
- **One lowerable algorithm per op runs on every tier**, as compute on V and G43. A reduce folds sixteen at a time,
  a level at a time; a scan scans the block sums a level up and starts each block from its prefix; a sort is LSD radix
  with four-bit digits, counting each block of 32 and ranking within it. Each kernel has a Java body.
- **Where compute runs, two ops take a faster form with the same answer**, chosen by `runs()` on a `compute_only`
  kernel: the sort is FidelityFX Parallel Sort's (a work group counts its run of 512-key blocks and sorts each 128 keys
  in shared memory), and a histogram of at most 1024 bins counts in shared memory first. On an RTX 4070 SUPER at a
  million keys and values, a 32-bit sort takes 0.42 ms on GL (0.94 with emulated subgroups) where the every-tier form
  took 1.94, and a 64-bin histogram 0.01 ms where it took 0.27.
- **What every op costs**, per tier and device: the harness's `gpu-ops-cost` scene prints a table of GPU and CPU
  time per op at a million elements and a 1920x1080 chain. On an RTX 4070 SUPER, GPU ms:

  | Op | compute (G43) | lowered (G40, G33) |
  |---|---|---|
  | fill, iota, copy | 0.02-0.04 | 0.08-0.15 |
  | reduce | 0.03 | 0.4-0.5 |
  | scan, compact | 0.08, 0.11 | 0.7, 1.0 |
  | bounds | 0.29 | 2.7 |
  | histogram, 64 bins | 0.01 | 0.44 |
  | sort, 32 bits | 0.42 | 12 |
  | downsample, blur | 0.03-0.53 | 0.16-0.55 |

  Lowered, each buffer a dispatch writes lands through one `glReadPixels`, which costs this driver 15-70 µs of CPU:
  0.1-1 ms per op, and 7 ms for a 32-bit sort, whose every digit pass lands several buffers. The CPU tier takes 3-19 ms
  for the buffer ops and 360 ms for a 32-bit sort.
- **Every tier gives the same bits**: integers exactly, a float sum in the same tree order, a sort stable everywhere.
  Below compute an add is a float blend, so a histogram bin is exact to 2^24.
- **Mip chains and blurs** are image kernels, one per format (`CgGpuOps.IMAGE_TYPES`: RGBA8, RGBA16F, R16F, R32F),
  on a graph texture described with levels (`CgTextureDesc.withMips()`):

  ```java
  CgGraphTexture bloom = CgGraphTexture.transientTexture("bloom", new CgTextureDesc(w, h, HDR).withMips());
  CgGpuOps.downsample(pass, bloom, Filter.AVERAGE);    // each level from the one above, odd sizes weighted by area
  CgGpuOps.blur(pass, bloom, 3, bloom, 3, 2f);         // in place, at an eighth the size
  CgGpuOps.downsample(pass, depth, Filter.MAX);        // a depth pyramid: each texel the farthest it covers
  ```

  They answer within a rounding of the format, not in the same bits. A blur reads `2 * ceil(3 * sigma) + 1` texels an
  axis, so a wide one belongs at a smaller level. Below compute, a kernel reading one level of the texture it writes
  another of samples it with the base and max level pinned to the level read, which keeps the draw from being a
  feedback loop; reading two levels of the texture it writes is refused.
- **`lib/rng.glsl`** is a counter-based generator (PCG4D): `cg_rng4(seed, element, step, stream)`, with `CgRng` giving
  the same bits in Java. Key an element by an id it carries, never its slot.
- **`CgGpuOpsCheck`** runs every op at counts from 0 to 70,000, fixed and from the GPU, and every image op on each
  format at odd and even sizes, against Java; the harness's
  `gpu-ops` scene runs it, and `-Dcrystalgraphics.compute.selfTest=true` logs it beside the compute self-test.

## Easy to get wrong

- **The shape is checked against what the code reaches, helpers and macros included.** A map kernel calling a
  helper that calls `barrier()` is refused with the kernel, the name and the line; an accessor its buffer's access
  does not give (`STATE_STORE` on a map, `BINS_ADD` on a struct) likewise.
- **Below `general`, a buffer a kernel reaches holds 4-, 8- or 16-byte scalars and vectors, or structs of vec4,
  ivec4, uvec4 only** — what a tier without compute holds (decision 10). A general kernel keeps any std430 layout.
- **A struct a buffer holds is declared before `Buffers { }`**: the generated declarations stand where the block
  stood.
- **A general kernel runs every invocation of every group**: no early return, so its barriers stay uniform. It asks
  `CG_IN_RANGE` itself, after them.
- **`CG_SUBGROUP_*` is called where the whole work group calls it**, on float, int or uint. Emulated, the work group
  is the subgroup (`CG_SUBGROUP_COUNT` 1), it takes `(CG_GROUP_SIZE + 4) * 4` bytes of shared memory, and a ballot
  holds 128 invocations; native needs basic, vote, arithmetic, ballot and shuffle together, or every one emulates.
- **An append buffer's count is a separate `uint` the caller binds and zeroes**; it can pass the buffer's length,
  and `NAME_COUNT()` clamps it.
- **A direct dispatch orders nothing after it**: barrier what reads it. `use()` first; `scope()` saves what a
  dispatch changes. A graph's dispatch is fenced by the executor, and a reader outside the graph barriers itself.
- **A graph dispatch's access to each binding is what its kernel uses**: `STATE(i)` reads, `STATE_WRITE` writes. A
  history is read through one binding and written through another; `previous()` is read-only.
- **Limits are the device's, checked at `program()`**: size per axis, invocations, shared memory (unreadable array
  sizes are left to the driver). A dispatch past the group count limit runs as several, each from its own base.
- `vec3` properties are refused, as a material's are: the block is std140.
- **A kernel some tier cannot run is refused where its first dispatch is recorded, on every machine**, naming the
  tier and the construct (`general and uses shared memory (cache)`) or the missing body — not at `load`, since a body
  is given after it, and never in the frame that executes it. Give it a lowerable fallback, a Java body, or
  `#pragma compute_only` and a `runs()` check. The check is repeated when a body is added or the file reloads.
- **`compute_only` takes no `#pragma fallback`**: a fallback is a lowered form, which `compute_only` says it has none of.
- **A polyfill is exact, not free**: `bitCount` is a dozen instructions where the builtin is one, and only on the
  tiers that lack it. A kernel defining its own function of a builtin's name keeps its own; nothing is renamed.
- **Lowering refuses**, by name: a general kernel; an appended element wider than one capture (64 words); a counter's
  `NAME_INC`/`NAME_ADD` whose result is used (a blend answers nothing); a cube image; an SNORM image written.
- **A CPU body writes only through what it is handed**: its range's elements in a map, gather or image kernel (ranges
  run at once), anything in a scatter or general one. `d.append(name)` answers the index to write in
  `d.appended(name)`; appends land in element order.
- **A CPU copy is only as fresh as the graph knows**: a buffer written outside the graph should be bound as an
  imported graph buffer, whose copy lasts one frame. A raster pass writing a storage buffer is not seen.
- **Below compute, what a dispatch binds lands in GL state**: the executor scopes it, so the next pass draws into what
  it bound itself. A lowered dispatch outside a graph opens `CgLoweredKernel.scope()`.

## Tests

`CgComputeParserTest`, `CgKernelEmitterTest`, `ShippedKernelStagePurityTest` (lowered stages too), `CgLoweringTest`,
`CgKernelFormTest` (the every-tier check) and `CgGlslBuiltinsTest` in core (GL-free); `ShippedKernelSpirvTest` in `runtime/lwjgl/vulkan` compiles every shipped
kernel through shaderc; the harness's `shader-compile-audit` compiles them, and every lowered pass of them, on the
driver; `compute-seam` dispatches `harness:shaders/compute_seam.compute` on every device against a CPU reference,
subgroups native and emulated.

**`CgComputeSelfTest` is the gate for the forms**, and it ships: one kernel per shape
(`crystalgraphics:shaders/env/compute/self_test.compute`), each result worked out in Java, every tier giving the same
buffers. It runs three ways:

```bash
# the harness, forced to each tier, and on the downlevel contexts, which choose their own
./gradlew :gl-debug-harness:runHarness --args="--mode=compute-tiers" -Dcrystalgraphics.compute.tier=G33
./gradlew :gl-debug-harness:runHarness --args="--mode=compute-tiers" -Pharness.downlevel=mac41
# any installed client, at its first frame: the verdict lands beside the GPU report
./gradlew prodSmoke -PcgTargets=<labels> -PcgSmokeProps=crystalgraphics.compute.selfTest=true
```

`-Pharness.downlevel=mac41|gl33` runs the harness on Mesa's llvmpipe shaped as a GL 4.1 or 3.3 context: real contexts
without compute, with Mesa's stricter GLSL compiler, where a forced tier on NVIDIA still has every feature.
`compute-graph`, `indirect-draw` and `shader-compile-audit` run there unforced too, and none of them skips for a tier.
