# `com.crystalgraphics.compute` — kernels from `.compute` files

A `.compute` holds kernels: GLSL functions the GPU runs once per element, declared with a local size and a
**shape** that says what they write. This package reads the file, writes each kernel's GLSL for the device,
compiles and wires it, and runs it as a compute shader: GL 4.3 and up, or a device.
**Writing and running a kernel — the format, the built-ins, the graph's compute pass, `CgGpuOps` and what is easy to
get wrong — is `docs/SHADERS.md` § *Compute*, loaded below.** This guide is the package's inside.
`crystalgraphics:shaders/example.compute` is the reference file; the graph's half is `render/graph/CLAUDE.md`.
**A pass of real work with drawing before its reader runs `async()`**: mark every one that fits (`SHADERS.md` §
*Beside the drawing*).

A kernel once also ran lowered to raster passes below compute, and as a Java body: removed in rhi X1, kept
reconnectable in `docs/LOWERED_COMPUTE.md`.

## Packages

| Package | Holds |
|---|---|
| (root) | `CgCompute` — a file: `load`, `fromSource(key, text)` for generated kernels, `kernel(name)`, `reload`, `releaseAll` (context teardown). `CgKernel` — a kernel and keyword set, one instance per set, holding its program: `program()`, `precache()` (the program started ahead of its first dispatch), `isReady()`, `glsl()`, `requireCompute()` (refuses a context without compute), and `bufferAccessors()`/`imageAccessors()` (the accessors it uses, a bit each, which a dispatch derives its access from). `CgReplayedReads` — a check's reads without waiting: requested through `CgReadback` on a first run, answered on a second |
| `source` | What a `.compute` declares, as data, no GL: `CgComputeSource`, `CgKernelDecl` (size, shape, and what its code reaches), `CgBufferDecl`, `CgImageDecl`, `CgSourcePart` (the code, cut where kernels differ), the vocabularies `CgKernelShape`, `CgBufferAccess`, `CgImageAccess`, `CgImageFormat`, `CgImageDimension`, and the accessors `CgBufferAccessor`/`CgImageAccessor` with the rule for each |
| `parse` | `CgComputeParser`, GL-free; package-private `TopLevel` (file scope, item by item), `GlslText`, `Std430`, `ConstantInt` |
| `emit` | `CgKernelEmitter` (one kernel's GLSL for a target), `CgKernelTarget` (the device's GLSL, subgroups, float atomics, limits) and `CgPropertyBlock` (where each `Properties` value sits in `CgKernelBlock`, GL-free, so a dispatch packs its values when recorded) |
| `program` | `CgKernelProgram`: a compiled, wired kernel; its direct dispatch, and `dispatchBound` for a graph that binds everything itself; `submit` links without waiting, a `Pending` that `finish()` makes the program. `CgComputeCheck`: checked mode's report slots, read back at `tickFrame` |
| `ops` | The library (C7): `CgGpuOps`, `CgGpuCount`, `CgCull` (what a cull tests against, C9b), `CgRng` (`lib/rng.glsl`'s Java twin), `CgGpuOpsCheck` (every op checked against Java on this context); kernels in `shaders/env/compute/ops/` |

The engine's own kernels are `.compute` files under `shaders/env/compute/`, beside what every kernel includes
(`kernel.glsl`, `atomic.glsl`, `subgroup.glsl`): `args.compute` writes indirect draws' commands (C4).

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
engine buffers, then **the file's code with every function and `shared` variable this kernel does not reach left
out**, the `Buffers`/`Images` blocks replaced in place by declarations and accessors for the buffers and images it
reaches (a stage holds 16 storage blocks on NVIDIA, and a file may declare more), and a `main` that calls the kernel —
returning first for an invocation past the count, in every shape but `general`.

## Bindings

Wired by name after linking, so the source carries no `binding =`:

| What | Where |
|---|---|
| Buffer `i` of `Buffers { }` | storage binding point `i` (`CgBuffer_NAME`; a float atomic's `CgBufferBits_NAME` at the same point) |
| An append buffer's count | the next point after every buffer, in order (`CgCounter_NAME`), bound from the storage-aligned offset at or below the count, `cg_CounterAt_NAME` its word there: `CgKernelProgram.counter` |
| Image `i` of `Images { }` | image unit `i`; at most 8 a file, refused at parse |
| A sampler property | texture unit, by its place among the samplers |
| `Properties` values | `CgKernelBlock` at `CgBindingPoints.MATERIAL_PROPERTIES_UBO` |
| The view and environment blocks | `CgViewBlock` at `VIEW_UBO`, `CgEnvironmentBlock` at `ENVIRONMENT_UBO`: `CgKernelProgram.frame(constants)` in a direct dispatch |
| An engine buffer (`#pragma cg_use`) | its own binding point; writable in a general kernel, read-only in the rest |
| `cg_Dispatch[6]` | base and count per dispatch, one `glUniform1iv` |
| Checked mode's slot (`CgCheck`) | the next point after the counts; each dispatch binds its own slot of `CgComputeCheck`'s buffer, so no two overlap |

## Ops, inside

- **Each op runs one algorithm.** A reduce folds sixteen at a time, a level at a time; a scan scans the block sums a
  level up and starts each block from its prefix; the sort is FidelityFX Parallel Sort's (a work group counts its run
  of 512-key blocks and sorts each 128 keys in shared memory); a histogram of at most 1024 bins counts in shared memory
  first, one of more adds per key. At a million keys and values on an RTX 4070 SUPER the sort takes 0.42 ms (0.94 with
  emulated subgroups), and a 64-bin histogram 0.01 ms. The ports' licences are in `THIRD-PARTY.md`.
- **The cull** (`cull.compute`) is one append dispatch a level, each testing every instance and keeping those at its
  level, into that level's region of the output: an append writes one buffer, so the levels' records stay in one
  buffer a multi-draw can share. `FillAt` zeroes its counters where they sit, so `counts` needs only `STORAGE`.
- **Many sets at once** (`CgCullSets`) are Range's shape: `CullKey` per instance buffer keys each instance by its
  set's row and level into one keys buffer, bound at the buffer's offset; one sort, histogram and scan over them all
  give each level's count and start; `CullGather` per instance buffer writes each level's records. A set's values are
  a row of `SETS` in place of properties, and the cull's functions take a `CullSet`, which the per-set kernels build
  from their properties.
- **Mip chains** are image kernels, one per format in `IMAGE_TYPES`.
- **`CgGpuOpsCheck`** runs every op at counts from 0 to 70,000, fixed and from the GPU, and every image op on each
  format at odd and even sizes, against Java; the harness's `gpu-ops` scene runs it, and
  `-Dcrystalgraphics.compute.selfTest=true` logs it beside the compute self-test.

## Tests

`CgComputeParserTest`, `CgKernelEmitterTest` and `ShippedKernelStagePurityTest` in core (GL-free);
`ShippedKernelSpirvTest` in `runtime/lwjgl/vulkan` compiles every shipped kernel through shaderc; the harness's
`shader-compile-audit` compiles them on the driver; `compute-seam` dispatches `harness:shaders/compute_seam.compute`
on every device against CPU-written data, subgroups native and emulated.

**`CgComputeSelfTest` is the gate**, and it ships: one kernel per shape
(`crystalgraphics:shaders/env/compute/self_test.compute`), each result worked out in Java. It runs two ways:

```bash
./gradlew :gl-debug-harness:runHarness --args="--mode=compute-tiers --device=vulkan"
# any installed client, a frame or two after its first: run once with its reads requested, then again with them
# answered (CgReplayedReads), so it never waits, Minecraft's Vulkan device included
./gradlew prodSmoke -PcgTargets=<labels> -PcgSmokeProps=crystalgraphics.compute.selfTest=true
```

---

Also loaded with this folder:

@../../../../../../../docs/SHADERS.md
