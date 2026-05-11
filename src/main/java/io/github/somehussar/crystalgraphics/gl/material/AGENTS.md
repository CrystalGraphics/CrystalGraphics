# gl/material — CrystalShader Material Surface

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md)  
> Internal parse/compile implementations: [`parse/AGENTS.md`](parse/AGENTS.md)

## What This Package Is

The thin public surface of the CrystalShader `.shader` file format pipeline, plus the
runtime property value object used by `CgMaterial` and callers.

The bulk of the parse, compile, and GLSL-generation implementation lives in
`gl/material/parse/`. See [`parse/AGENTS.md`](parse/AGENTS.md) for the full class map.

Implementation tasks:
- **T7** — `CgShaderParser` (with nested `V2fField`), `CgParsedShader`, `CgMaterialProperty`, `CgShaderParseException`
- **T8** — `CgMaterialShaderCompiler` (with nested `CompiledSource`), `CgParsedShader`

## Class Map — This Package (`gl/material/`)

| Type | Role |
|------|------|
| `CgMaterialShader` | Shared shader asset compiled from a `.shader` file. Owned by `CgMaterialShaderRegistry`. Owns the full compile pipeline: source load → parse → GLSL generation → preprocess → GL link → `wireShader()` for pipeline + attached buffers. Fields: `resourcePath`, `lastParsed`, `shader`, `matPropsUbo` (template UBO for GLSL emission), `renderState`, `renderQueue`, `attachedBuffers`, `dirty`, `revisionNumber`. `recompile()` increments `revisionNumber` on each success; `markDirty()` triggers a lazy recompile on next bind. `attach(CgShaderBuffer, macroName)` / `detach(...)` / `attach(CgUniformBuffer)` / `detachUbo(...)` mirror the `CgMaterial` attach API. `delete()` frees GL programs; called by registry only. |
| `CgMaterialShaderRegistry` | Singleton path→asset cache. `getOrCreate(path)` returns the shared `CgMaterialShader` instance, creating it on miss (marked dirty). `reloadAll()` marks all assets dirty — materials detect revision change on next `bind()`. `deleteAll()` deletes all assets and clears cache; cascaded from `CgMaterialRegistry.deleteAll()`. `resetForTest()` is a package-private reset for unit tests. |
| `CgMaterialProperty` | Mutable runtime property object. Holds the declaration (`name`, `displayName`, `type`, `rawDefault`), the current float/int/sampler value, and self-binding logic. `fromDecl(name, displayName, typeName, rawDefault)` creates and eagerly parses the default. `copyWithDefaults()` returns a fresh independent clone with the default value applied — used by `CgMaterial.onShaderRecompiled()` to initialise per-instance property stores. `applyToSampler(CgShaderBindings)` flushes sampler value to shader uniforms (sampler-only). `writeToUbo(CgBufferWriter)` writes non-sampler value into the material UBO. `addToFormatBuilder(CgBufferFormat.Builder)` registers the field in the UBO layout. `set(...)` overloads for float/vec2/vec3/vec4; `setInt(int)` for INT; `setTexture(unit, CgTexture)` for sampler types. `resetToDefault()` restores the parsed default. Inner enum `Type` maps property type names to GLSL names and component counts; `isSampler()` is the canonical discriminator. |
| `CgMaterialProperties` | Partitioned view of a material's full property list — splits into UBO-eligible (non-sampler) and sampler properties at construction. Implements `CgShaderBindings` for name-based property writes from `CgMaterial.applyProperties()`. `propsByName` HashMap built during construction/rebuild for O(1) name lookup. `rebuild(List<CgMaterialProperty>)` repopulates all internal state in-place — called on hot-reload to avoid reallocation. `buildUboFormat()` builds the `CgBufferFormat` for `CgMaterialBlock`. `writeUboProps(CgBufferWriter)` uploads all non-sampler values. `applySamplerProps(CgShaderBindings)` binds all sampler values. `EMPTY` sentinel for contexts needing a non-null default. |
| `CgMaterialBindingsAdapter` | **Deprecated stub** — logic fully absorbed into `CgMaterialProperties`. |

## Class Map — `parse/` Subpackage

See [`parse/AGENTS.md`](parse/AGENTS.md) for the full class map. Key external-facing types:

| Type | Role |
|------|------|
| `CgShaderParser` | Public parse facade. `parse(String)` / `parse(String, String)` → `CgParsedShader`. `parseV2fFields(CgParsedShader)` → `List<V2fField>`. Nested `V2fField` record. |
| `CgShaderParseException` | `RuntimeException` thrown on any `.shader` format violation. Messages include `[resourcePath]` prefix. |
| `CgParsedShader` | `@Desugar record` result of `CgShaderParser.parse()`. Fields: `shaderType`, `properties`, `v2fStructBody`, `globalDecls`, `vertexBody`, `fragmentBody`, `renderState`, `renderQueue`. |
| `CgMaterialShaderCompiler` | Static compiler. `compile(CgParsedShader, ShaderBufferPath, List<CgAttachedBuffer>)` → `CompiledSource`. |
| `CgMaterialShaderCompiler.CompiledSource` | `@Desugar record`: `vertexSource`, `fragmentSource` — both complete, ready for `CgShaderPreprocessor` then `CgShaderFactory`. |

## Compiler Output Format

### Vertex shader generation sequence (steps 1–14)

1. `#version 430 core` (SSBO) or `#version 330 core` (TBO)
2. `#define CG_VERTEX_STAGE 1`
3. `#define CG_USE_SSBO 1` (SSBO path only)
4. `#define CG_OBJECT_BUFFER_BINDING <CgBindingPoints.OBJECT_DATA>` + `#define CG_FRAME_BLOCK_BINDING <CgBindingPoints.FRAME_DATA>`
5. `#include "crystalgraphics:shaders/env/cg_env.glsl"`
6. `CgMaterialBlock` UBO block (emitted via `CgBufferGlslEmitter.emitUbo()` when non-sampler properties exist; no `binding=` qualifier — wired post-link by `wireShader()` at slot `CgBindingPoints.MATERIAL_PROPERTIES_UBO`)
7. Sampler property uniform declarations (`uniform sampler2D/sampler2DArray/sampler3D/samplerCube <name>;` — one per sampler property)
8. User-attached SSBO/TBO buffers (emitted by `CgBufferGlslEmitter`)
8. User-attached UBO blocks (emitted by `CgBufferGlslEmitter`) — path-independent
9. `struct v2f { <v2fStructBody> };`
10. `flat out int cg_InstanceId;`
11. v2f interface block (`out _CgV2fBlock { <fields> } _cg_v2f;`)
12. Global declarations (verbatim `globalDecls` from parsed shader)
13. User vertex function (`void vertex(out v2f o) { <vertexBody> }`)
14. Generated `void main()` — assigns `cg_InstanceId`, calls `vertex(_v2f_local)`, copies fields to `_cg_v2f.<name>`

### Fragment shader generation sequence (steps 1–13)

1. `#version 430 core` / `#version 330 core`
2. `#define CG_USE_SSBO 1` (SSBO path; no `CG_VERTEX_STAGE`)
3. `#define CG_OBJECT_BUFFER_BINDING` + `#define CG_FRAME_BLOCK_BINDING`
4. `#include "crystalgraphics:shaders/env/cg_env.glsl"`
5. `CgMaterialBlock` UBO block (when non-sampler properties exist)
6. Sampler property uniform declarations
7. User-attached SSBO/TBO buffers
7. User-attached UBO blocks
8. `struct v2f { ... };`
9. v2f interface block (`in _CgV2fBlock { <fields> } _cg_v2f;`)
10. Global declarations
11. `out vec4 _cg_fragColor;`
12. User fragment function (`void fragment(in v2f i, out vec4 fragColor) { <fragmentBody> }`)
13. Generated `void main()` — reconstructs `v2f _v2f_local`, calls `fragment(_v2f_local, _cg_fragColor)`

## `CgBufferGlslEmitter` — GLSL Generation Details

### SSBO output shape

```glsl
struct StructName { type field; };
layout(std430) readonly buffer BufferName { StructName _cg_structNameArr[]; };
#define MACRO_NAME(n) _cg_structNameArr[n]
```

### TBO output shape

```glsl
struct StructName { ... };
uniform samplerBuffer BufferName;
StructName _cg_getStructName(int n) { ... texelFetch ... }
#define MACRO_NAME(n) _cg_getStructName(n)
```

TBO texel arithmetic: texel = 16 bytes = 4 floats. `texelIndex = byteOffset / 16`.  
Throws `CgPreprocessorException` if any field is TBO-incompatible or `stride % 16 != 0`.

### UBO output shape

```glsl
layout(std140) uniform BlockName { type field; };
```

No struct, no instance name, no macro. Block name = `buffer.getName()`. Path-independent.

## Key Design Rules

- **No GL calls** in parser or compiler — pure string transforms.
- **Always pass `resourcePath`** — all `CgShaderParseException` messages include `[path]` prefix.
- **Properties block is optional** — `CgPropertiesParser.parse()` returns an empty list if absent.
- **Comment lines skipped** in `v2f` body and `Properties` block.
- **`#` directive passthrough** — preamble `#`-lines are collected by `CgStructureParser.parsePreambleDirectives()` and prepended to `globalDecls`; the compiler's `partitionGlobalDecls()` emits them right after `#version`.
- **Auto-inject extensions** — `CgGpuType.requiredExtension()` drives automatic `#extension` emission for `INT64`/`UINT64` fields; throws `CgPreprocessorException` if the capability is absent.

## Relationship to Other Packages

| Package | Relationship |
|---------|-------------|
| `api/material/` | `CgMaterial` delegates compilation to `CgMaterialShader`; `CgMaterialRegistry` delegates reload/delete to `CgMaterialShaderRegistry` |
| `api/shader/` | `CgShaderPreprocessor` processes compiler output before GL compilation |
| `gl/shader/` | `CgShaderFactory.fromSource()` receives the preprocessed output |
| `api/CgCapabilities` | `ShaderBufferPath` drives `#version` selection; `isGpuShaderInt64()` gates INT64/UINT64 |
| `api/buffer/CgGpuType` | `requiredExtension()` returns required GLSL extension per field type |
