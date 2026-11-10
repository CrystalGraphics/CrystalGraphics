# `gl/shader` — the engine's compiled programs (internal)

What a material's pass and keyword set, or a kernel, compiles into: the program as written
(`CgGlslCompiler.compileExplicit`) that its device pipelines are built from. Not API: a consumer's shader is a material
(`CgMaterial`) or a kernel (`CgKernel`); there is no public way to compile GLSL text or set a uniform by name (rhi SH9,
Unreal's material and global shaders).

## Class map

| Class | Job |
|---|---|
| `CgShader` | A material program from generated sources: `compile` (now) or `submit` (on a worker), `isReady`, `isCompiled`, `getLastCompileError`, `explicit()`, `sceneReads()`, its modules on a device (`deviceModule`), `delete`. A failure is kept, never thrown, but by `explicit()` |
| `CgShaderCompile` | A compile under way: on a job worker where the installed compiler compiles on any thread (shaderc), else run by the first thread that asks (the native compiler, which needs the device's context) |

## Invariants

- `format` places the vertex inputs: attribute `i` at location `i`; null where the source places them.
- The owner of a `CgShader` deletes it: a material's map. A compile still running when it is deleted finishes unread.
- What a program samples of the scene is its own layout's answer (`sceneReads`), so a keyword variant answers for
  itself.

---

Also loaded with this folder:

@../../../../../../../../docs/SHADERS.md
