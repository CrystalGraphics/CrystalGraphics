# api/material — CrystalShader Material API

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md)

## What This Package Is

User-facing entry point for the CrystalShader material pipeline. Provides `CgMaterial`
for loading, compiling, and binding `.shader` files. The canonical vertex format for
spatial materials is `CgVertexFormat.SPATIAL` (defined in `api/vertex/`).

This is the top of the Wave 5–9 material stack. Implementation tasks:
- **T9** — `CgMaterial`, `CgMaterialRegistry`, `CgMaterialKey`, `package-info.java`

## Class Map

| Type | Role |
|------|------|
| `CgMaterial` | User-facing material handle. `load(path)` delegates to `CgMaterialRegistry.get().getOrCreate(path)`. `load(CgMaterialKey)` overload for typed keys. `applyBindings(Consumer<CgShaderBindings>)` writes persistent property values into the shader's bindings object. `bind()` activates shader + properties + object buffer. `unbind()` deactivates shader. `reload()` marks shader dirty (called by registry on hot-reload). `delete()` frees the backing shader. Registry-owned — not caller-owned; use `CgMaterialRegistry.get().deleteAll()` for teardown. |
| `CgMaterialRegistry` | Singleton load/reload/delete lifecycle manager. `get()` returns the singleton. `getOrCreate(String)` / `getOrCreate(CgMaterialKey)` check cache; on miss call `CgMaterial.create()`, cache, and return. `reloadAll()` calls `mat.reload()` on each cached material. `deleteAll()` deletes all cached materials. Registered for teardown in `CgGraphicsLifecycle.destroyContext()`. |
| `CgMaterialKey` | `@Desugar record` wrapping a resource-path string. `of(String)` factory. Value equality. Used as a typed alternative to raw strings. |
| `CgMaterialPipeline` | Singleton pipeline owning per-frame UBO and per-object SSBO. `frameBuffer()` returns the frame UBO for advanced wiring. `objectBuffer()` returns the shared per-object buffer. `beginFrame()` uploads frame data each frame. |
| `package-info.java` | Package-level Javadoc describing the material API and lifecycle contract. |

## Key API

### CgMaterial.applyBindings(consumer)
```java
material.applyBindings(b -> {
    b.set1f("_Alpha", 0.5f);
    b.vec4("_Color", 1f, 0f, 0f, 1f);
});
```
Passes the shader's **persistent** `CgShaderBindings` instance directly to the consumer.
Values written here survive across frames until overwritten. Returns `this` for chaining.
This is NOT `shader.applyBindings()` (which is ephemeral). The persistent bindings are
flushed during `bind()` via `shader.bindings().apply(shader)`.

### CgMaterial.load(resourcePath) / load(CgMaterialKey)
```java
CgMaterial material = CgMaterial.load("mymod:shaders/terrain.shader");
// or:
CgMaterial material = CgMaterial.load(CgMaterialKey.of("mymod:shaders/terrain.shader"));
```
Both delegate to `CgMaterialRegistry.get().getOrCreate(...)`. Returns the same cached instance on repeated calls.

Internal `CgMaterial.create(resourcePath)` steps (package-private, only called by registry):
1. `CgIO.loadSource(resourcePath)` — load `.shader` source
2. `CgShaderParser.parse(source)` — structural parse
3. `CgCapabilities.detect().preferredShaderBufferPath()` — detect SSBO/TBO/NONE
4. `CgMaterialShaderCompiler.compile(parsed, path)` — generate GLSL vert + frag strings
5. `new CgShaderPreprocessor().process(...)` — resolve `#include "cg_env.glsl"` (once)
6. `CgShaderFactory.fromSource(vert, frag, CgVertexFormat.SPATIAL)` — compile + link
7. `shader.bindings().ubo(CgMaterialPipeline.getInstance().frameBuffer())` — wire `CgFrameBlock` UBO persistently (survives hot-reload)
8. Apply property defaults from `Properties` block

Throws `IllegalStateException` if compile/link fails — never returns a broken material.

### CgMaterial.bind()

No-arg bind activated after `pipeline.beginFrame()` and after writing per-object records:
```java
material.bind();
mesh.drawDirect();
material.unbind();
```

Bind steps (in order):
1. Stage property values + TBO sampler (if TBO path) into ephemeral bindings
2. `shader.bind()` — activates GL program and flushes all bindings
3. `objectBuffer.bind(lastWrittenCount)` — binds SSBO or TBO texture

### CgMaterial.reload()
Called by `CgMaterialRegistry.reloadAll()` during hot-reload (F3+T).
Marks the backing shader dirty; recompile happens lazily on the next `bind()`.

### CgVertexFormat.SPATIAL
```java
// Attribute contract (matches cg_env.glsl):
// Location 0: cg_Position  (vec3, FLOAT)
// Location 1: cg_TexCoord0 (vec2, FLOAT)
// Location 2: cg_Normal    (vec3, FLOAT)
// Stride: 32 bytes
CgVertexFormat format = CgVertexFormat.SPATIAL;
CgMeshData data = CgMeshBuilder.unitCube(format);
CgMesh mesh = CgMesh.upload(data);
```

## Reserved Texture Unit

`CgTextureBuffer.DEFAULT_TBO_TEXTURE_UNIT` (= 7) is engine-reserved for the TBO
per-object data path. When using `applyBindings()` to set a `sampler2D` property,
pass a unit other than 7 to avoid collisions with the engine-managed TBO slot.

## Ownership Rules

- `CgMaterialRegistry` owns all `CgMaterial` instances it creates. Call `CgMaterialRegistry.get().deleteAll()` to free them.
- `CgGraphicsLifecycle.destroyContext()` calls `CgMaterialRegistry.get().deleteAll()` and `CgMaterialPipeline.destroy()` automatically.
- Callers that hold a reference to a material must not call `delete()` on it directly — the registry owns teardown.

## Guardrails (NOT in this package)

- No render-state management (blend, depth, cull)
- No variant system (DEPTH/SHADOW passes)
- No `CgRenderState.apply()` calls

## Related Packages

| Package | Relationship |
|---------|-------------|
| `gl/material/` | Provides `CgShaderParser` (parse) and `CgMaterialShaderCompiler` (generate GLSL) |
| `gl/buffer/shader/` | `CgUniformBuffer` (UBO frame data), `CgShaderBuffer` (SSBO/TBO per-object data) |
| `api/shader/` | `CgShader`, `CgShaderBindings`, `CgShaderPreprocessor` used internally |
| `gl/shader/` | `CgShaderFactory.fromSource()` used by `CgMaterial.create()` |
| `api/vertex/` | `CgVertexFormat.SPATIAL` — canonical spatial format for this pipeline; also `CgVertexSemantic`, `CgAttribType` |
| `gl/mesh/` | `CgMesh.drawDirect()` and `CgMesh.drawInstanced(N)` are the draw consumers |
