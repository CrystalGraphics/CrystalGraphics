# `com.crystalgraphics.compute` — kernels from `.compute` files

A `.compute` holds kernels: GLSL functions the GPU runs once per element, declared with a local size and a
**shape** that says what they write. This package reads the file, writes each kernel's GLSL for the device,
compiles and wires it, and runs it in whichever form the context can: compute, lowered to draws, or a Java body.
**Writing and running a kernel — the format, the built-ins, the graph's compute pass, the tiers, `CgGpuOps` and what
is easy to get wrong — is `docs/SHADERS.md` § *Compute*, loaded below.** This guide is the package's inside.
`crystalgraphics:shaders/example.compute` is the reference file; the graph's half is `render/graph/CLAUDE.md`.
**A pass of real work with drawing before its reader runs `async()`**: mark every one that fits (`SHADERS.md` §
*Beside the drawing*).

## Packages

| Package | Holds |
|---|---|
| (root) | `CgCompute` — a file: `load`, `fromSource(key, text)` for generated kernels, `kernel(name)`, `reload`, `releaseAll` (context teardown). `CgKernel` — a kernel and keyword set, one instance per set, holding its checks, form, program and lowered form: `program()`, `prepare()` (the program started ahead of its first dispatch), `glsl()`, `cpu(body)`, `form()`, `runs()`, `check()`, `lowered()`, and `bufferAccessors()`/`imageAccessors()` (the accessors it uses, a bit each, which a dispatch derives its access from). `CgKernelForm` — how this context runs a kernel, and the every-tier check. `CgDispatchBindings` — what a dispatch below compute binds, in GL names. `CgReplayedReads` — a check's reads without waiting: requested through `CgReadback` on a first run, answered on a second |
| `source` | What a `.compute` declares, as data, no GL: `CgComputeSource`, `CgKernelDecl` (size, shape, fallback, and what its code reaches), `CgBufferDecl`, `CgImageDecl`, `CgSourcePart` (the code, cut where kernels differ), the vocabularies `CgKernelShape`, `CgBufferAccess`, `CgImageAccess`, `CgImageFormat`, `CgImageDimension`, and the accessors `CgBufferAccessor`/`CgImageAccessor` with the rule for each |
| `parse` | `CgComputeParser`, GL-free; package-private `TopLevel` (file scope, item by item), `GlslText`, `Std430`, `ConstantInt` |
| `emit` | `CgKernelEmitter` (one kernel's GLSL for a target), `CgKernelTarget` (the device's GLSL, subgroups, float atomics, limits), `CgGlslBuiltins` (each builtin newer than GLSL 3.30, its version and its exact polyfill, or none) and `CgPropertyBlock` (where each `Properties` value sits in `CgKernelBlock`, GL-free, so a dispatch packs its values when recorded) |
| `program` | `CgKernelProgram`: a compiled, wired kernel; its direct dispatch, and `dispatchBound` for a graph that binds everything itself; `submit` links without waiting, a `Pending` that `finish()` makes the program. `CgComputeCheck`: checked mode's report slots, read back at `tickFrame` |
| `lower` | A kernel below compute (C5): `CgLowering` (the passes a shape lowers to, or the construct that stops it; GL-free), `CgLoweredEmitter` (each pass's stages, and `reader`, the buffer accessor a material's `Buffers { }` shares), `CgLoweredTarget`, `CgLoweredKernel` (the passes compiled, and its dispatch), `CgLoweredPrograms` (the helper programs), `CgLoweredResources` (scratch, texel targets, zeroed counters), `CgTexelTarget` |
| `cpu` | The CPU tier (C6): `CgCpuBody` (a kernel's Java body), `CgCpuDispatch`, `CgCpuBuffer`, `CgCpuImage` (what a body sees), `CgCpuRunner` (runs one), `CgCpuMirrors` (the CPU copies of GL buffers) |
| `ops` | The library (C7): `CgGpuOps`, `CgGpuCount`, `CgCull` (what a cull tests against, C9b), `CgRng` (`lib/rng.glsl`'s Java twin), `CgGpuOpsBodies` (every op kernel's Java body), `CgGpuOpsCheck` (every op checked against Java on this context); kernels in `shaders/env/compute/ops/` |

The engine's own kernels are `.compute` files under `shaders/env/compute/`, beside what every kernel includes
(`kernel.glsl`, `atomic.glsl`, `subgroup.glsl`, `lowered.glsl`): `args.compute` writes an indirect draw's command (C4).

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
KHR subgroup extensions where they are native), the keywords asked for, `CG_COMPUTE_STAGE`, the size macros, `cg_env.glsl`,
`env/compute/kernel.glsl` and `atomic.glsl` (and `subgroup.glsl` if it uses `CG_SUBGROUP_*`), the properties, the
engine buffers, the polyfills of the builtins its GLSL lacks, then **the file's code with every function and `shared`
variable this kernel does not reach left out**, the `Buffers`/`Images` blocks replaced in place by declarations and
accessors for the buffers and images it reaches (a stage holds 16 storage blocks on NVIDIA, and a file may declare
more), and a `main` that calls the kernel — returning first for an invocation past the count, in every shape but
`general`.

## Bindings

Wired by name after linking, so the source carries no `binding =`:

| What | Where |
|---|---|
| Buffer `i` of `Buffers { }` | storage binding point `i` (`CgBuffer_NAME`; a float atomic's `CgBufferBits_NAME` at the same point) |
| An append buffer's count | the next point after every buffer, in order (`CgCounter_NAME`), bound from the storage-aligned offset at or below the count, `cg_CounterAt_NAME` its word there: `CgKernelProgram.counter` |
| Image `i` of `Images { }` | image unit `i` |
| A sampler property | texture unit, by its place among the samplers |
| `Properties` values | `CgKernelBlock` at `CgBindingPoints.MATERIAL_PROPERTIES_UBO` |
| The frame block | `CgFrameBlock` at `FRAME_DATA_UBO`: `CgKernelProgram.frame(constants)` in a direct dispatch |
| An engine buffer (`#pragma cg_use`) | its own binding point; writable in a general kernel, read-only in the rest |
| `cg_Dispatch[6]` | base and count per dispatch, one `glUniform1iv` |
| Checked mode's slot (`CgCheck`) | the next point after the counts; each dispatch binds its own slot of `CgComputeCheck`'s buffer, so no two overlap |

## Three forms: compute, lowered, a Java body

`CgKernelForm.choose` picks a kernel's form per tier (SHADERS.md § *Every tier* has the table, and
`-Dcrystalgraphics.compute.tier` forces one); the frame graph's executor runs whichever it is.

**Lowered** (`lower`, gpu-compute §6.2): a buffer the kernel writes its own element of is a render target laid out as
the buffer's words, drawn by one fragment pass over its texels, and up to eight buffers of one layout share the pass.
Appends are emitted by a geometry stage, captured by transform feedback and counted into a 1x1 float target with
additive blending. A scatter is points into a target laid out element to texel, blended for adds, minima and maxima,
and an image is a fragment pass. Buffers are read as buffer textures. Every pass reads what the buffers held before
the dispatch. After the last, a frame graph's dispatch holds each target as its buffer's words
(`CgLoweredResources.hold`): a later lowered pass reads them from it (`_cg_word_NAME`, a unit per buffer beside its
buffer texture, where the kernel's units allow), a scatter into the whole view starts from it, and the executor reads it
into the buffer (`glReadPixels` into a pixel pack buffer) before anything else touches the buffer and when the
execution ends. A dispatch outside a graph lands at once. G40 sizes an indirect dispatch's draws from the GPU's count
(`glDrawArraysIndirect`); G33 reads the count back, a stall counted as `buffer.readbacks`. A device has no transform feedback, so a lowered tier forced on one is
refused at startup.

**The CPU tier** (`cpu`, §6.4): the body runs over CPU copies of the bound buffers (`CgCpuMirrors`), read back the
first time and kept while nothing else writes the buffer; what it writes is uploaded through the frame ring before the
next pass. A map, gather, append or image body runs as ranges on a pool of daemon workers (`crystalgraphics-compute-*`),
a scatter or general body once, in order. Images are read whole before the body and written whole after; a sampler
property the kernel names (`CgKernelDecl.samplers`) is read whole, every level and every slice of a 3D one, on the
render thread before it.

**The every-tier check** (§6.6): `CgKernelForm.check`, GL-free, asks G43, G40 and G33 for the form each would choose
and compiles the builtins it reaches at each one's lowest GLSL (`lowestGlsl`: 4.20, 4.00, 3.30) against
`CgGlslBuiltins`. A Java body answers nothing there: G40 and G33 never choose one, only the forced CPU tier does
(decision 11). `CgKernel` caches the answer per file generation; a reload asks again.

## Ops, inside

- **One lowerable algorithm per op runs on every tier**, as compute on V and G43. A reduce folds sixteen at a time,
  a level at a time; a scan scans the block sums a level up and starts each block from its prefix; a sort is LSD radix
  with four-bit digits, counting each block of 32 and ranking within it. Each kernel has a Java body
  (`CgGpuOpsBodies`).
- **Where compute runs, two ops take a faster form with the same answer**, chosen by `runs()` on a `compute_only`
  kernel: the sort is FidelityFX Parallel Sort's (a work group counts its run of 512-key blocks and sorts each 128 keys
  in shared memory), and a histogram of at most 1024 bins counts in shared memory first. At a million keys and
  values on an RTX 4070 SUPER, the sort takes 0.42 ms (0.94 with emulated subgroups) where the every-tier form took
  1.94, and a 64-bin histogram 0.01 ms where it took 0.27. The ports' licences are in `THIRD-PARTY.md`.
- **The cull** (`cull.compute`) is one append dispatch a level, each testing every instance and keeping those at its
  level, into that level's region of the output: an append writes one buffer, so the levels' records stay in one
  buffer a multi-draw can share. `FillAt` zeroes its counters where they sit, so `counts` needs only `STORAGE`.
- **Mip chains** are image kernels, one per format in `IMAGE_TYPES`. Below compute, a kernel reading one level of the
  texture it writes another of samples it with the base and max level pinned to the level read, which keeps the draw
  from being a feedback loop.
- **`CgGpuOpsCheck`** runs every op at counts from 0 to 70,000, fixed and from the GPU, and every image op on each
  format at odd and even sizes, against Java; the harness's `gpu-ops` scene runs it, and
  `-Dcrystalgraphics.compute.selfTest=true` logs it beside the compute self-test.

## Easy to get wrong, inside

- **A held buffer is current only in its target.** Anything reading a graph buffer's storage during an execution other
  than a lowered pass's loads lands it first: the executor does for its steps (`landHeld`), a dispatch for what its
  helper programs read through a buffer texture (`landInputs`). New code reading a buffer there does the same.
- **Below compute, what a dispatch binds lands in GL state**: the executor scopes it, so the next pass draws into what
  it bound itself. A lowered dispatch outside a graph opens `CgLoweredKernel.scope()`.
- **A lowered dispatch takes every texel target before binding any output**: `CgTexelTarget.create` binds its own
  framebuffer, and a target made after `bindOutputs` attaches the outputs to the wrong one.
- **The lowered tiers' texture units stop below the engine's reserved ones** (`CgBindingPoints`): a pass reads its
  buffers as buffer textures from unit 0 up.
- **`compute_only` takes no `#pragma fallback`**, and the parser refuses both: a fallback is a lowered form.

## Tests

`CgComputeParserTest`, `CgKernelEmitterTest`, `ShippedKernelStagePurityTest` (lowered stages too), `CgLoweringTest`,
`CgKernelFormTest` (the every-tier check) and `CgGlslBuiltinsTest` in core (GL-free); `ShippedKernelSpirvTest` in
`runtime/lwjgl/vulkan` compiles every shipped kernel through shaderc; the harness's `shader-compile-audit` compiles
them, and every lowered pass of them, on the driver; `compute-seam` dispatches `harness:shaders/compute_seam.compute`
on every device against a CPU reference, subgroups native and emulated.

**`CgComputeSelfTest` is the gate for the forms**, and it ships: one kernel per shape
(`crystalgraphics:shaders/env/compute/self_test.compute`), each result worked out in Java, every tier giving the same
buffers. It runs three ways:

```bash
# the harness, forced to each tier, and on the downlevel contexts, which choose their own
./gradlew :gl-debug-harness:runHarness --args="--mode=compute-tiers" -Dcrystalgraphics.compute.tier=G33
./gradlew :gl-debug-harness:runHarness --args="--mode=compute-tiers" -Pharness.downlevel=mac41
# any installed client, a frame or two after its first: run once with its reads requested, then again with them
# answered (CgReplayedReads), so it never waits, Minecraft's Vulkan device included
./gradlew prodSmoke -PcgTargets=<labels> -PcgSmokeProps=crystalgraphics.compute.selfTest=true
```

`-Pharness.downlevel=mac41|gl33` runs the harness on Mesa's llvmpipe shaped as a GL 4.1 or 3.3 context: real contexts
without compute, with Mesa's stricter GLSL compiler, where a forced tier on NVIDIA still has every feature.
`compute-graph`, `indirect-draw` and `shader-compile-audit` run there unforced too, and none of them skips for a tier.

---

Also loaded with this folder:

@../../../../../../../docs/SHADERS.md
