# `gl/shader` — the engine's linked programs (internal)

What a material's pass and keyword set, or a kernel, compiles into until S4 makes them `CgRHI` pipelines (GL3). Not
API: a consumer's shader is a material (`CgMaterial`) or a kernel (`CgKernel`); there is no public way to compile GLSL
text or set a uniform by name (rhi SH9, Unreal's material and global shaders).

## Class map

| Class | Job |
|---|---|
| `CgShader` | A linked program from generated sources: `isCompiled`, `isReady` (a submitted link done), `bind`/`bindScoped`, `getUniformLocation`, `getLastCompileError`, `delete`. A failure is logged and kept, never thrown |
| `CgShaderFactory` | `fromSource(vertex, fragment, format)` compiles now (preprocessed); `submit(...)` links on the driver's threads, the sources as given |
| `CgProgram` | The GL program object under a `CgShader` or a kernel: compile/link, `submitLink`/`finishLink`, `compileCompute`, uniform setters by location, `labelled(...)` for debug names |
| `CgLinkWorker` | Links on a worker context where the driver has none of its own |
| `CgProgramBinaryCache` | Program binaries kept across launches |
| `CgGlslStrip` | Strips comments and blank lines from a source before the driver sees it |

## Invariants

- `format` names the vertex inputs for `glBindAttribLocation` before linking; null where the source places them.
- The owner of a `CgShader` deletes it: a material's map, a kernel program.

---

Also loaded with this folder:

@../../../../../../../../docs/SHADERS.md
