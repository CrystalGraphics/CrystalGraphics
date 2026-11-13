# The lowered compute form — removed, kept reconnectable

Kernels run as compute shaders alone: GL 4.3 and up, or a device (`ComputeTier.V`, `G43`). Until rhi X1 (2026-11-04)
a kernel also ran **lowered**, as raster passes, on GL 4.0–4.2 (`G40`, macOS's 4.1) and GL 3.3 (`G33`), and as a
**Java body** under the CPU oracle. Both were removed because the RHI's floor is GL 4.6 and Vulkan (`rhi.md` D8), where
every context has compute. This page is what to read to bring the lowered form back.

**Restore from**: CrystalGraphics `c6cc251e` (rhi branch), the last commit with it; the harness at `eb63570`. Everything
below names files as they were there. `ComputeTier.G40` and `G33`, and their detection in `CgCapabilities`, were kept:
a context still reports them, and a kernel dispatched there is refused by `CgKernel.requireCompute`.

## What it did

A kernel's **shape** (`map`, `gather`, `append`, `scatter`, `image`, `general`) said which raster passes could stand in
for it. `general` (shared memory, barriers, subgroups) could not be lowered.

- **map / gather**: each buffer the kernel writes its own element of became a render target laid out as the buffer's
  words, filled by one fragment pass over its texels; up to eight buffers of one layout shared the pass.
- **append**: a geometry stage emitted the appended elements, captured by transform feedback, and counted them into a
  1×1 float target with additive blending.
- **scatter**: points into a target laid out element to texel, blended for adds, minima and maxima.
- **image**: a fragment pass over the image.
- Buffers were read as **buffer textures** (TBO, see X2), every pass seeing what the buffers held before the dispatch.
- After the last pass a graph dispatch **held** each target as its buffer's words (`CgLoweredResources.hold`). A later
  lowered pass read them from there (`_cg_word_NAME`); the executor read them back into the buffer (`glReadPixels`
  into a pixel pack buffer) before anything else touched it, and when the execution ended.
- G40 sized an indirect dispatch's draws from the GPU's count (`glDrawArraysIndirect`); G33 read the count back, a
  stall.
- A device has no transform feedback, so a lowered tier was refused on one.

## The pieces

| Piece | Where (at `c6cc251e`) |
|---|---|
| The lowering: passes per shape, or the construct that stops it (GL-free) | `compute/lower/CgLowering` |
| Each pass's GLSL stages | `compute/lower/CgLoweredEmitter`, `shaders/env/compute/lowered.glsl` |
| Target limits per context (`GL33`, units, varyings) | `compute/lower/CgLoweredTarget` |
| Compiled passes and their dispatch; `scope()` around GL state | `compute/lower/CgLoweredKernel`, `CgLoweredPrograms` |
| Held targets, scratch, zeroed counters | `compute/lower/CgLoweredResources`, `CgTexelTarget` |
| What a dispatch below compute binds, in GL names | `compute/CgDispatchBindings` |
| The form per tier, and the every-tier check | `compute/CgKernelForm` (`choose`, `check`, `lowestGlsl`) |
| GLSL 3.30/4.00 polyfills of newer builtins | `compute/emit/CgGlslBuiltins`; `CgKernelDecl.builtins`, the parser's scan |
| The CPU form (the gates' oracle) | `compute/cpu/*`, `CgKernel.cpu`, `CgCompute.cpuBody`, `CgCapabilities.cpuOracle`, `ComputeTier.CPU`, `compute/ops/CgGpuOpsBodies` |
| Tests | `CgLoweringTest`, `CgKernelFormTest`, `CgGlslBuiltinsTest`, the lowered half of `ShippedKernelStagePurityTest`; harness `shader-compile-audit`'s `LOWER` rows, `compute-tiers` forced per tier, `-Pharness.downlevel=mac41\|gl33` |

## Where it hooked in

Bringing it back means restoring the pieces above and reconnecting each of these:

1. **The choice.** `CgKernel.form()` cached `CgKernelForm.choose(source, decl, tier, hasCpuBody)` per file generation
   and tier; `runs()` answered whether a `compute_only` kernel runs here; `lowered()` built the lowered kernel through
   `CgCompute.lowered`. `CgDispatch`'s constructor called `kernel.check()` (every tier) and `kernel.form()`.
2. **The executor** (`render/graph/CgExecutor`). `compute()` switched on `form().how()`: `COMPUTE` dispatched,
   `LOWERED` ran `lowered(frame, d, bindings)` inside `CgLoweredKernel.scope()` and re-bound the frame block, `CPU` ran
   `cpu(d)` over `CgCpuMirrors`. `bind(d)` filled a `CgDispatchBindings` per source. Held targets were landed by
   `landHeld` before every non-compute step, by `landAll` at a callback and at the execution's end, and by `land(id)`
   before a buffer inspection; `CgLoweredResources.written`/`drop`/`zeroed` followed every fill, copy, update, pool
   return and freed buffer. `runsAsync` refused a pass with any non-compute dispatch. `drawCounted` took a count the CPU
   tier wrote from `CgCpuMirrors.word` without a read.
3. **Indirect commands** (`render/graph/CgIndirectArgs`). Below compute each command was written by `args.compute`'s
   `DrawArgs` (a `map` kernel, its values as `Properties`) as its own lowered dispatch, and `writeCommands` ran inside
   `CgLoweredKernel.scope()`; on compute, `DrawArgsMany` writes them all from a row table.
4. **Fallback kernels.** `#pragma fallback General Lowerable` named the kernel a tier without compute ran instead;
   `#pragma compute_only` marked a kernel with none, which callers asked `runs()` about. They paired as:
   - `compute/ops/sort.compute`: `SortCount`… (FidelityFX) on compute; `RadixCount`, `RadixScatter`, `RadixScatterKeys` plus a
     `CgGpuOps.scanLevel` over the digit cells below it (`CgGpuOps.radixSort`'s `grouped` branch, `precacheSort`).
   - `compute/ops/histogram.compute`: `HistogramGroups` on compute for ≤1024 bins, else `Histogram` (still used above 1024).
   - `world/lights/grid.compute`: `Coarse` + `Cell` (two-level) on compute; `Count`, a `CgGpuOps.scan` into `starts`, then
     `Write` below it, with `counts`/`starts` buffers (`CgWorldLights.bin`, `precacheBin`, `kernelsBuilt`).
   - `media.compute`: `IntegrateColumns` (a thread a column) on compute; `Integrate` (a thread a froxel, dispatched
     `SLICES` deep) below it (`CgWorldMedia`'s `byColumn`).
   - `self_test.compute`: `BinLowered`, `DoubledLowered`, and `Stuck`/`Only` to check the refusals.
5. **The parser** refused buffer elements a lowered kernel's texels cannot hold (`CgBufferDecl.lowerable`, for any
   non-`general` kernel) and checked fallback pairs (`checkFallbacks`).
6. **The budget.** `CgGpuBudget.share(tier)` started a budget at 0.5 on the lowered tiers, 0.25 on the CPU tier.

## Measured, when it ran

At a million keys and values on an RTX 4070 SUPER the every-tier radix sort took 1.94 ms against FidelityFX's 0.42; a
64-bin histogram 0.27 ms against 0.01 grouped.
