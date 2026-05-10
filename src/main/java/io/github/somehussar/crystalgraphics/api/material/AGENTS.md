# api/material — CrystalShader Material API

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md)

## What This Package Is

User-facing entry point for the CrystalShader material pipeline. Provides `CgMaterial`
for loading, compiling, and binding `.shader` files. The canonical vertex format for
spatial materials is `CgVertexFormat.SPATIAL` (defined in `api/vertex/`).

This is the top of the CrystalShader material stack.

## Class Map

| Type | Role |
|------|------|
| `CgMaterial` | User-facing material handle. `load(path)` delegates to `CgMaterialRegistry.get().getOrCreate(path)`. `load(CgMaterialKey)` overload for typed keys. `applyProperties(Consumer<CgShaderBindings>)` writes persistent property values by name — routes through the material's `CgMaterialProperties` instance (which implements `CgShaderBindings`). `bind()` activates shader + properties + object buffer. `unbind()` deactivates shader. `reload()` marks shader dirty (called by registry on hot-reload). `delete()` frees the backing shader. `attach(CgShaderBuffer, macroName)` / `detach(macroName)` for SSBO/TBO auto-injection. `attach(CgUniformBuffer)` / `detachUbo(blockName)` for UBO flat-block injection. Registry-owned — not caller-owned; use `CgMaterialRegistry.get().deleteAll()` for teardown. |
| `CgAttachedBuffer` | Immutable descriptor for a user-attached SSBO/TBO or UBO buffer. Created via `CgAttachedBuffer.of(buffer, macroName)` (SSBO/TBO, STD430) or `CgAttachedBuffer.of(CgUniformBuffer)` (UBO, STD140). `isUbo()` returns `true` for UBO entries (macroName is null). SSBO/TBO fields: `buffer`, `macroName`, `structName` (= `format.getGlslName()`), `ssboArrayName` (`_cg_{lowerFirst}Arr`), `tboGetterName` (`_cg_get{structName}`). UBO entries: only `buffer` is set; macroName/structName/ssboArrayName/tboGetterName are all null. Block/sampler/UBO block name is always `buffer.getName()` — required for `wireShader()`. |
| `CgMaterialRegistry` | Singleton load/reload/delete lifecycle manager. `get()` returns the singleton. `getOrCreate(String)` / `getOrCreate(CgMaterialKey)` check cache; on miss call `CgMaterial.create()`, cache, and return. `reloadAll()` calls `mat.reload()` on each cached material. `deleteAll()` deletes all cached materials. Registered for teardown in `CgGraphicsLifecycle.destroyContext()`. |
| `CgMaterialKey` | `@Desugar record` wrapping a resource-path string. `of(String)` factory. Value equality. Used as a typed alternative to raw strings. |
| `CgMaterialPipeline` | Singleton pipeline owning per-frame UBO and per-object SSBO/TBO. `OBJECT_FORMAT` (std430, 48 floats) and `FRAME_BLOCK_FORMAT` (std140, 38 floats) are the engine-canonical typed buffer format descriptors. `frameBuffer()` returns the frame UBO for advanced wiring. `objectBuffer()` returns the shared per-object buffer. `getFrameUniforms()` returns the pipeline-owned mutable `CgFrameUniforms` holder. `beginFrame()` (no-arg) reads from the owned holder and uploads frame data each frame via named writes. `init()` / `destroy()` manage the singleton lifecycle. |
| `CgFrameUniforms` | `@Data` mutable holder for per-frame uniform data. Fields: `Matrix4f view`, `Matrix4f proj`, `float timeSecs`, `int viewportW`, `int viewportH`. Owned by `CgMaterialPipeline` — mutate via `pipeline.getFrameUniforms().setX(...)` then call `pipeline.beginFrame()`. Adding a new frame global: add field here + to `FRAME_BLOCK_FORMAT` + one named write line in `beginFrame()`. |
| `CgMaterialRegistry` | Singleton load/reload/delete lifecycle manager. `get()` returns the singleton. `getOrCreate(String)` / `getOrCreate(CgMaterialKey)` check cache; on miss call `CgMaterial.create()`, cache, and return. `reloadAll()` calls `mat.reload()` on each cached material. `deleteAll()` deletes all cached materials. Registered for teardown in `CgGraphicsLifecycle.destroyContext()`. |
| `CgMaterialKey` | `@Desugar record` wrapping a resource-path string. `of(String)` factory. Value equality. Used as a typed alternative to raw strings. |
| `package-info.java` | Package-level Javadoc describing the material API and lifecycle contract. |

## Key API

### CgMaterialPipeline — Per-Frame Setup

```java
// On GL context creation:
CgMaterialPipeline.init();

// Per-frame:
CgMaterialPipeline pipeline = CgMaterialPipeline.getInstance();
CgFrameUniforms fu = pipeline.getFrameUniforms();
fu.view(viewMatrix);
  .proj(projMatrix);
  .timeSecs(elapsedSeconds);
  .viewportW(width);
  .viewportH(height);
pipeline.beginFrame();

// Write per-object records — fields from OBJECT_FORMAT:
CgShaderBuffer buf = pipeline.objectBuffer();
CgBufferWriter w = buf.beginWrite(N);
for (MyObject obj : objects) {
    w.beginRecord()
     .mat4("modelMatrix", obj.getModel())
     .mat4("normalMatrix", obj.getNormal());
     // custom0-3 auto-zeroed
    buf.endRecord();
}
buf.endWrite();

// Draw:
material.bind();
mesh.drawInstanced(N);
material.unbind();

// On GL context destroy:
CgMaterialPipeline.destroy();
```

Available named fields in `OBJECT_FORMAT` (STD430, 48 floats):
- `modelMatrix` — mat4, floats 0–15
- `normalMatrix` — mat4, floats 16–31 (pass mat3 as full mat4; shader reads upper-left 3×3)
- `custom0`–`custom3` — vec4, floats 32–47

Available named fields in `FRAME_BLOCK_FORMAT` (STD140, 38 floats):
- `cg_ViewMatrix` — mat4
- `cg_ProjMatrix` — mat4
- `cg_Time` — vec4: `t/20, t, t*2, t*3`
- `cg_Resolution` — vec2: viewport width, height

### CgMaterial.applyProperties(consumer)
```java
material.applyProperties(b -> {
    b.set1f("_Alpha", 0.5f);
    b.vec4("_Color", 1f, 0f, 0f, 1f);
});
```
Routes writes through the material's `CgMaterialProperties` instance (which implements
`CgShaderBindings`). Values are stored persistently on the property objects and survive
across frames until overwritten. Returns `this` for chaining. Unknown names are silently
ignored. Non-property operations (`mat4`, `ubo`, etc.) throw `UnsupportedOperationException`.

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
7. `mat.setResourcePath(resourcePath)` — store path for hot-reload
8. `CgMaterialPipeline.getInstance().frameBuffer().bind(shader)` — wire `CgFrameBlock` UBO block index
9. Apply property defaults from `Properties` block

Throws `IllegalStateException` if compile/link fails — never returns a broken material.

### CgMaterial.bind()

No-arg bind activated after `pipeline.beginFrame()` and after writing per-object records:
```java
material.bind();
mesh.drawDirect();
material.unbind();
```

Bind steps (in order):
1. If `dirty`, calls `recompile()` — hot-reload from `resourcePath`
2. Stage property values into ephemeral bindings
3. `shader.bind()` — activates GL program and flushes all bindings
4. `objectBuffer.bind(shader)` — binds SSBO/TBO and wires it (TBO: sets samplerBuffer uniform)

### CgMaterial.reload()
Called by `CgMaterialRegistry.reloadAll()` during hot-reload (F3+T).
Sets `dirty = true`; the full load→parse→compile→relink pipeline runs lazily on the next `bind()`.

### CgMaterial.recompile()
Full hot-reload pipeline (called lazily from `bind()` when `dirty`):
1. `CgIO.loadSource(resourcePath)` — re-read `.shader` file
2. `CgShaderParser.parse()` — re-parse
3. `CgMaterialShaderCompiler.compile()` — regenerate GLSL
4. `shader.setSource(vert, frag)` + `shader.recompile()` — relink existing GL program in-place
5. On success: `frameBuffer.bind(shader)` re-wires UBO block index for new program
6. On failure: old program stays active, error logged

No-op if `resourcePath == null` (shader-graph / programmatic materials).

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

The engine object buffer TBO uses texture unit 0 (`CgBindingPoints.TBO_ENGINE_UNIT`). When using
`applyBindings()` to set a `sampler2D` property, avoid texture unit 0 to prevent collision with
the engine-managed TBO slot. The old `DEFAULT_TBO_TEXTURE_UNIT = 7` constant was removed — the
texture unit is now derived from `bindingLocation` directly.

## Ownership Rules

- `CgMaterialRegistry` owns all `CgMaterial` instances it creates. Call `CgMaterialRegistry.get().deleteAll()` to free them.
- `CgGraphicsLifecycle.destroyContext()` calls `CgMaterialRegistry.get().deleteAll()` and `CgMaterialPipeline.destroy()` automatically.
- Callers that hold a reference to a material must not call `delete()` on it directly — the registry owns teardown.

## User-Attached Buffer API

### SSBO/TBO path — `attach(CgShaderBuffer, macroName)` / `detach(macroName)`

```java
// Define format for per-glyph metrics
CgBufferFormat glyphFmt = CgBufferFormat.builder("GlyphMetrics", STD430)
    .vec4("bbox")
    .vec2("uv0").vec2("uv1")
    .float_("advance").float_("bearing").float_("descent").float_("pad")
    .build();
CgShaderBuffer glyphBuf = CgShaderBuffer.create("GlyphMetricsBuffer", glyphFmt, 0);

// Attach: GLSL struct + SSBO/TBO block auto-injected on next compile
material.attach(glyphBuf, "GLYPH_DATA");

// In shader: GLYPH_DATA(n).advance
// Detach:
material.detach("GLYPH_DATA");
```

- `macroName` must match `^[A-Z][A-Z0-9_]*$` — validated by `CgAttachedBuffer.of()`.
- `format.getMemoryLayout()` must be STD430 — throws for STD140.
- Duplicate macroName or duplicate `format.getGlslName()` (struct name) throws.
- TBO-path constraints (field types, stride % 16) validated at compile time, not attach time.
- `attach()` calls `markDirty()` — next `bind()` triggers recompile.
- **Ownership warning**: `CgMaterial.load(path)` returns a shared cached instance. Only call `attach()` on materials you exclusively own.
- **Runtime binding**: `buffer.bind()` is your responsibility before each draw call — `attach()` registers for GLSL injection and `wireShader()` wiring only.

### UBO path — `attach(CgUniformBuffer)` / `detachUbo(blockName)`

```java
CgBufferFormat sceneFmt = CgBufferFormat.builder("SceneParams", STD140)
    .vec4("ambientColor").float_("exposure").build();
CgUniformBuffer sceneUbo = CgUniformBuffer.create(sceneFmt, "SceneParams", 0);

material.attach(sceneUbo);  // emits: layout(std140) uniform SceneParams { vec4 ambientColor; float exposure; };
// In shader: ambientColor  (direct scope — no block prefix, no macro)
material.detachUbo("SceneParams");
```

- `format.getMemoryLayout()` must be STD140 — throws for STD430.
- Duplicate block name (`buffer.getName()`) throws.
- **UBO flat-scope semantics**: `emitUbo()` emits no struct, no instance name, no macro. All fields land in direct shader scope — write `fieldName`, not `BlockName.fieldName`.
- `attach(CgUniformBuffer)` takes no `macroName` — UBO data is a single instance, not an indexed array.

### What NOT to pass

Do NOT pass engine pipeline buffers (`CgMaterialPipeline.objectBuffer()`, `frameBuffer()`) — those are declared in `cg_env.glsl` and wired automatically by the engine. Passing them here causes duplicate GLSL declarations that fail to compile.

### GLSL symbol naming (SSBO/TBO)

Given `buffer.getName() = "FontMetricsBuffer"`, `format.getGlslName() = "FontMetrics"`, `macroName = "FONT_METRICS"`:

| Symbol | Value | Visibility |
|--------|-------|------------|
| Struct type | `FontMetrics` | User-visible |
| SSBO block interface name | `FontMetricsBuffer` | Internal — must match for `wireShader()` |
| SSBO array instance name | `_cg_fontMetricsArr` | Internal |
| TBO sampler uniform name | `FontMetricsBuffer` | Internal — must match for `wireShader()` |
| TBO getter function | `_cg_getFontMetrics` | Internal |
| User macro | `FONT_METRICS(n)` | User-facing entry point |

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
