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
| `CgMaterial` | User-facing material handle backed by a shared `CgMaterialShader` asset. `load(path)` returns the cached instance per path. `newInstance(path)` creates a fresh non-cached instance. `bind()` activates the Forward pass with enabled keywords; detects hot-reload via revision check, then delegates shared bind logic to private `doBind(CgShader, CgRenderPassVariant)`. `bindForPass(CgRenderPassVariant)` activates a specific pass variant (FORWARD uses active keywords; SHADOW/DEPTH use empty keywords); also delegates to `doBind`. `doBind` (private) owns: UBO wiring, GL state save, UBO upload, sampler binding, render state apply, and `shader.bind()`. `drawChain(CgRenderPassVariant, Runnable)` executes all chained passes in authored order — use this instead of `bindForPass`+`unbind` for correct multi-pass support. `getPassRenderState(CgRenderPassVariant)` returns the render state for the given pass. `hasShadowCasterPass()` — returns `castShadows` parse flag AND `renderQueue < TRANSPARENT` (intent, not compile state). `hasDepthPass()` — true when an explicit authored `[Depth]` pass is present in the parsed result (parse-state only, does NOT include auto-generated depth variants). `hasCompiledDepthPass()` — true when a compiled Depth GL program exists in the cache (compile-state); includes both explicit and auto-generated depth variants from `recompile()`. This is the correct query for `CgDepthPrepassRenderer` to route to `bindForPass(DEPTH)`. Always false for transparent materials. `getMaterialId()` — stable per-instance integer ID from an `AtomicInteger` counter; used by `CgRenderCommandQueue.submit()` as the sort key materialId (replaces `identityHashCode` which can collide). `enableKeyword(name)` / `disableKeyword(name)` toggle feature flags; throws for undeclared names. `unbind()` deactivates shader using `lastBoundShader`. `delete()` frees only the per-instance property UBO. `attach(CgShaderBuffer, macroName)` / `detach(...)` / `attach(CgUniformBuffer)` / `detachUbo(...)` delegate to `CgMaterialShader`. |
| `CgRenderPassVariant` | Public enum bridging orchestrators to per-pass LightMode routing. `FORWARD("Forward")`, `SHADOW("ShadowCaster")`, `DEPTH("Depth")`. `lightModeName()` returns the canonical LightMode tag value. Used by `bindForPass(CgRenderPassVariant)`, `getPassRenderState(CgRenderPassVariant)`, and `hasShadowCasterPass()`/`hasDepthPass()`. |
| `CgAttachedBuffer` | Immutable descriptor for a user-attached SSBO/TBO or UBO buffer. Created via `CgAttachedBuffer.of(buffer, macroName)` (SSBO/TBO, STD430) or `CgAttachedBuffer.of(CgUniformBuffer)` (UBO, STD140). `isUbo()` returns `true` for UBO entries (macroName is null). SSBO/TBO fields: `buffer`, `macroName`, `structName` (= `format.getGlslName()`), `ssboArrayName` (`_cg_{lowerFirst}Arr`), `tboGetterName` (`_cg_get{structName}`). UBO entries: only `buffer` is set; macroName/structName/ssboArrayName/tboGetterName are all null. Block/sampler/UBO block name is always `buffer.getName()` — required for `wireShader()`. |
| `CgMaterialRegistry` | Singleton load/reload/delete lifecycle manager. `get()` returns the singleton. `getOrCreate(String)` / `getOrCreate(CgMaterialKey)` check cache; on miss call `CgMaterial.create()` (which uses `CgMaterialShaderRegistry` internally), cache, and return. `reloadAll()` delegates to `CgMaterialShaderRegistry.get().reloadAll()` — marks all shader assets dirty; materials detect revision change on next `bind()`. `deleteAll()` deletes all cached material instances (freeing per-instance UBOs), then cascades to `CgMaterialShaderRegistry.get().deleteAll()` to free GL shader programs. Registered for teardown in `CgGraphicsLifecycle.destroyContext()`. |
| `CgMaterialKey` | `@Desugar record` wrapping a resource-path string. `of(String)` factory. Value equality. Used as a typed alternative to raw strings. |
| `CgRenderQueue` | Final utility class (not an enum). Static `int` constants: `BACKGROUND` (1000), `GEOMETRY` (2000), `ALPHA_TEST` (2450), `TRANSPARENT` (3000), `OVERLAY` (4000). Threshold constants: `ALPHA_TEST_THRESHOLD` (2450), `TRANSPARENT_THRESHOLD` (2500), `OVERLAY_THRESHOLD` (4000). `fromName(String)` maps PascalCase or SCREAMING_SNAKE names to int value; throws for unknown names. |
| `package-info.java` | Package-level Javadoc describing the material API and lifecycle contract. |

**Shader compilation**: all compile pipeline logic lives in `gl/material/CgMaterialShader` + `CgMaterialShaderRegistry`. See [`gl/material/AGENTS.md`](../../../../../gl/material/AGENTS.md).

## Key API

### CgRenderPipeline — Per-Frame Setup

Per-frame orchestration is owned by `CgRenderPipeline` in `api/render/`. The material API itself
does not handle frame-level UBO or object buffer management. See [`api/render/AGENTS.md`](../render/AGENTS.md)
for the full lifecycle and usage pattern.

```java
CgRenderPipeline pipe = CgRenderPipeline.getInstance();
CgFrameData fd = pipe.getFrameData();
fd.viewMatrix.set(viewBuf); fd.projMatrix.set(projBuf);
fd.timeSecs = elapsedSecs; fd.viewportW = w; fd.viewportH = h;
fd.deriveFromViewMatrix();

CgRenderCommand cmd = pipe.acquireCommand();
cmd.modelMatrix.translation(x, y, z);
cmd.worldAabb[0] = x-r; cmd.worldAabb[3] = x+r;  // ... etc.
cmd.mesh = myMesh; cmd.material = myMaterial;
pipe.submit(cmd);
pipe.execute(partialTicks);
```

### CgMaterial.bind() and CgMaterial.bindForPass(CgRenderPassVariant)

`bind()` activates the Forward-lit pass with the current enabled keyword set. Use it for standard opaque/transparent geometry draws.

`bindForPass(CgRenderPassVariant variant)` activates a specific pass variant:
- `FORWARD` — same as `bind()` but called explicitly (uses active keywords)
- `SHADOW` — activates `ShadowCaster` pass with empty keywords (keywords don't apply to shadow passes)
- `DEPTH` — activates `Depth` pass with empty keywords

```java
// Forward draw:
material.bind();
mesh.drawInstanced(N);
material.unbind();

// Shadow map draw (from a shadow renderer):
if (material.hasShadowCasterPass()) {
    shadowMapFbo.bind();
    material.bindForPass(CgRenderPassVariant.SHADOW);
    mesh.drawInstanced(N);
    material.unbind();
    shadowMapFbo.unbind();
}
```

`getPassRenderState(CgRenderPassVariant)` returns the `CgRenderState` for the given pass, without binding. Useful for querying cull mode, depth write, etc. without activating the GL program.

`hasShadowCasterPass()` — true when `castShadows=true` (parse flag, default true) AND `renderQueue < TRANSPARENT`. Reflects declared *intent*, not compile state — returns true even before the shadow program is compiled. Transparent materials always return false. `hasDepthPass()` — true when an explicit authored `[Depth]` pass is present in the last successful parse (`hasParsedPass("Depth")`). Parse-state only — does NOT include auto-generated depth variants. `hasCompiledDepthPass()` — true when a compiled depth GL program exists in the cache (under key `"Depth"`). Compile-state — includes both explicit and auto-generated depth variants produced by `recompile()`. This is the correct query for `CgDepthPrepassRenderer` to decide whether to use `bindForPass(DEPTH)`. Always false for transparent materials (depth auto-gen is opaque-only).

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

**Safe to call before first `bind()`**: if `applyProperties()` is called before the shader
has been compiled (i.e. before the first `bind()`), the consumer is buffered and replayed
automatically once the shader compiles successfully. Values set this way are not lost.

### Shader Keywords / Variants

Shaders declare optional compile-time feature flags using `#pragma cg_feature` in their preamble.
Each enabled keyword injects `#define NAME 1` into both vertex and fragment sources.
Every unique keyword-set combination is compiled into a separate GL program and cached lazily
on the first `bind()` call with that combination.

**Declaring keywords in a `.shader` file:**
```glsl
#type spatial

#pragma cg_feature RECEIVE_SHADOWS
#pragma cg_feature FOG_ON
#pragma cg_feature NORMAL_MAP
```

**Complete example with keyword-gated code:**
```glsl
#type spatial

#pragma cg_feature RECEIVE_SHADOWS
#pragma cg_feature FOG_ON

Properties {
    _MainTex   ("Main Texture",  sampler2D)  = "white"
    _FogColor  ("Fog Color",     vec4)       = (0.7, 0.7, 0.7, 1.0)
    _FogDensity("Fog Density",   Range(0,1)) = 0.05
}

struct v2f {
    vec2 uv;
    vec3 worldPos;
};

void vertex(out v2f o) {
    gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
    o.uv        = cg_TexCoord0;
    o.worldPos  = (CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0)).xyz;
}

void fragment(in v2f i, out vec4 fragColor) {
    fragColor = texture(_MainTex, i.uv);

#ifdef RECEIVE_SHADOWS
    // shadow map sampling
#endif

#ifdef FOG_ON
    float dist    = length(i.worldPos);
    float fogFact = exp(-_FogDensity * dist);
    fragColor.rgb = mix(_FogColor.rgb, fragColor.rgb, clamp(fogFact, 0.0, 1.0));
#endif
}
```

**Enabling / disabling keywords at runtime:**
```java
material.enableKeyword("RECEIVE_SHADOWS");   // next bind() compiles + caches this variant
material.disableKeyword("FOG_ON");           // reverts to variant without FOG_ON
boolean on = material.isKeywordEnabled("FOG_ON");  // false by default
```

**Rules and limits:**
- Maximum **8 keywords** per shader. Exceeding this throws `CgShaderParseException` at parse time.
- Keyword names must match `[A-Z][A-Z0-9_]*` — all uppercase, start with a letter.
- Only names declared with `#pragma cg_feature` in that shader are accepted — `enableKeyword()`
  throws `IllegalArgumentException` for undeclared names.
- `#pragma cg_feature` lines are **never emitted** in the generated GLSL — they are stripped
  during parsing. Only the `#define NAME 1` lines (for enabled keywords) appear in compiled output.
- `enableKeyword()` is safe to call before the first `bind()` — triggers a lazy compile if needed.
- Each unique keyword combination shares the same `propStore` and UBO — only the GL program differs.

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
- No render-pass system (depth/shadow passes are out of scope)
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
