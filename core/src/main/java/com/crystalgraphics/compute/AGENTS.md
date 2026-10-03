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

`.compute` files live under `shaders/`, beside the `.shader` that draws what they write.

## Packages

| Package | Holds |
|---|---|
| (root) | `CgCompute` — a file: `load`, `fromSource(key, text)` for generated kernels, `kernel(name)`, `reload`, `releaseAll` (context teardown). `CgKernel` — a kernel and keyword set, a value: `program()`, `glsl()` |
| `source` | What a `.compute` declares, as data, no GL: `CgComputeSource`, `CgKernelDecl` (size, shape, fallback, and what its code reaches), `CgBufferDecl`, `CgImageDecl`, `CgSourcePart` (the code, cut where kernels differ), the vocabularies `CgKernelShape`, `CgBufferAccess`, `CgImageAccess`, `CgImageFormat`, `CgImageDimension`, and the accessors `CgBufferAccessor`/`CgImageAccessor` with the rule for each |
| `parse` | `CgComputeParser`, GL-free; package-private `TopLevel` (file scope, item by item), `GlslText`, `Std430`, `ConstantInt` |
| `emit` | `CgKernelEmitter` (one kernel's GLSL for a target) and `CgKernelTarget` (the device's GLSL, subgroups, float atomics, limits) |
| `program` | `CgKernelProgram`: a compiled, wired kernel, and its direct dispatch |

The frame graph's compute pass is `render/graph`'s (gpu-compute C3); lowering below compute, the CPU tier, the
primitives and readback take `lower`, `cpu`, `ops` and `readback` here as they land.

## The path

```
text --CgComputeParser--> CgComputeSource --CgKernelEmitter(target)--> GLSL with #includes
     --CgShaderPreprocessor--> CgShaderProgram.compileCompute --> CgKernelProgram (wired)
```

**The parser sees through includes; the emitter keeps them.** Analysis runs on the expanded text, so a helper in
an included lib counts toward what a kernel reaches; the emitted source keeps the `#include` line, so `#pragma once`
still collapses a lib the engine's env files include too.

## What a kernel's source is

The header the target needs (`#version 430 core`, or `330 core` with the ARB compute extensions below GL 4.3; the
KHR subgroup extensions where they are native), the keywords asked for, `CG_COMPUTE_STAGE`, the size macros
(`CG_LOCAL_SIZE_X/Y/Z`, `CG_GROUP_SIZE`, `CG_GROUP_POW2`, `CG_DIMENSIONS`, `CG_KERNEL_<Name>`), `cg_env.glsl`,
`env/compute/kernel.glsl` and `atomic.glsl` (and `subgroup.glsl` if it uses `CG_SUBGROUP_*`), the properties, the
engine buffers, then **the file's code with every function and `shared` variable this kernel does not reach left
out**, the `Buffers`/`Images` blocks replaced in place by declarations and accessors, and a `main` that calls the
kernel — returning first for an invocation past the count, in every shape but `general`.

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
  dispatch changes.
- **Limits are the device's, checked at `program()`**: size per axis, invocations, shared memory (unreadable array
  sizes are left to the driver). A dispatch past the group count limit runs as several, each from its own base.
- `vec3` properties are refused, as a material's are: the block is std140.

## Tests

`CgComputeParserTest`, `CgKernelEmitterTest` and `ShippedKernelStagePurityTest` in core (GL-free);
`ShippedKernelSpirvTest` in `runtime/lwjgl/vulkan` compiles every shipped kernel through shaderc; the harness's
`shader-compile-audit` compiles them on the driver, and `compute-seam` dispatches `harness:shaders/compute_seam.compute`
on every device against a CPU reference, subgroups native and emulated.
