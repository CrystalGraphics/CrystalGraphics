# gl/material — CrystalShader Parser and Compiler

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md)

## What This Package Is

The structural parser and GLSL code generator for the CrystalShader `.shader` file format.
Pure string processing — zero GL calls, zero LWJGL imports (except in the compiler which
references `CgCapabilities.ShaderBufferPath` for `#version` selection).

Implementation tasks:
- **T7** — `CgShaderParser` (with nested `V2fField`), `CgParsedShader`, `CgMaterialProperty`, `CgShaderParseException`
- **T8** — `CgMaterialShaderCompiler` (with nested `CompiledSource`), `CgParsedShader`

## Class Map

| Type | Role |
|------|------|
| `CgShaderParser` | Static parser: `parse(String)` → `CgParsedShader`; `parse(String, String resourcePath)` — includes path in all exception messages. Splits `.shader` source into structural sections. Skips `//` comment lines in `v2f` body and `Properties` block. Properties block is optional (returns empty list if absent). Also provides `parseV2fFields(CgParsedShader)` → `List<CgShaderParser.V2fField>` for the compiler. **Preamble directive passthrough**: `parsePreambleDirectives()` collects any `#`-prefixed lines between `#type` and the first structural section marker; these are prepended to `globalDecls` so they flow through to the compiler. Nested: `V2fField` (see below). |
| `CgShaderParser.V2fField` | `@Value` nested class for one v2f struct field: `type`, `name`. Moved from standalone `V2fField.java`. |
| `CgParsedShader` | `@Desugar record` result of `CgShaderParser.parse()`. Fields: `shaderType`, `properties` (List<CgMaterialProperty>), `v2fStructBody` (raw body text), `globalDecls` (preamble `#` directives prepended, then raw text between v2f and vertex()), `vertexBody`, `fragmentBody`. |
| `CgMaterialProperty` | Mutable runtime property object — replaces the former `PropertyDecl` + `CgPropertyParser` split. Holds the declaration (`name`, `type`, `rawDefault`), current float/sampler value, and self-binding logic. `fromDecl(name, glslType, rawDefault)` creates and eagerly parses the default. `applyTo(CgShaderBindings)` flushes the current value to shader uniforms. `set(...)` overloads for float/vec2/vec3/vec4; `setTexture(unit, CgTexture)` for sampler2D. `resetToDefault()` restores the parsed default. Inner enum `Type` maps GLSL names to component counts. |
| `CgShaderParseException` | `RuntimeException` thrown for any format violation. Messages include `[resourcePath]` prefix. |
| `CgMaterialShaderCompiler` | Static compiler: `compile(CgParsedShader, ShaderBufferPath, List<CgAttachedBuffer>)` → `CgMaterialShaderCompiler.CompiledSource`. The single `attachedBuffers` list holds both SSBO/TBO and UBO entries; `CgAttachedBuffer.isUbo()` distinguishes them. Partitions `globalDecls` into `#`-directive lines and code lines via `partitionGlobalDecls()`. Directive lines are emitted immediately after `#version` (GLSL requires extensions/pragmas before other declarations). Code lines are emitted at the normal `globalDecls` position. Auto-required extensions (from `CgGpuType.requiredExtension()` on all attached buffer fields, including UBOs) are also emitted after `#version`, deduplicated against the existing directive lines. Throws `CgPreprocessorException` if a required extension is unsupported (e.g. `GL_ARB_gpu_shader_int64` for INT64/UINT64 fields on a GL < 4.0 context). |
| `CgBufferGlslEmitter` | Pure GLSL string generator for user-attached buffers. Package-private — no GL calls, no instance state. **Core methods** (package-private test seam): `emitSsbo(CgBufferFormat, String bufferName, String macroName)`, `emitTbo(CgBufferFormat, String bufferName, String macroName)` (throws `CgPreprocessorException` for TBO-incompatible types or stride not multiple of 16), `emitUbo(CgBufferFormat, String blockName)`. **Public facade methods**: `emitSsbo(CgAttachedBuffer)`, `emitTbo(CgAttachedBuffer)`, `emitUbo(CgUniformBuffer)`. |
| `CgMaterialShaderCompiler.CompiledSource` | `@Value` nested class: `vertexSource`, `fragmentSource`. Both strings are complete, ready for `CgShaderPreprocessor.process()` followed by `CgShaderFactory.fromSource()`. Moved from standalone `CompiledMaterialSource.java`. |

## Compiler Output Format

### Vertex shader generation sequence (steps 1–11)

1. `#version 430 core` (SSBO) or `#version 330 core` (TBO)
2. `#define CG_VERTEX_STAGE 1`
3. `#define CG_USE_SSBO 1` (SSBO path only)
4. `#define CG_OBJECT_BUFFER_BINDING <CgBindingPoints.OBJECT_DATA>` + `#define CG_FRAME_BLOCK_BINDING <CgBindingPoints.FRAME_DATA>` — injected before include
5. `#include "crystalgraphics:shaders/env/cg_env.glsl"`
6. Property uniform declarations (`uniform <type> <name>;`)
7. **User-attached SSBO/TBO buffers** (emitted by `CgBufferGlslEmitter.emitSsbo/emitTbo`) — injected here
8. **User-attached UBO blocks** (emitted by `CgBufferGlslEmitter.emitUbo`) — injected here (path-independent)
9. `struct v2f { <v2fStructBody> };`
10. `flat out int cg_InstanceId;` — compiler-wired instance ID varying
11. v2f interface block (`out _CgV2fBlock { <fields> } _cg_v2f;`)
12. Global declarations (verbatim `globalDecls` from parsed shader)
13. User vertex function (`void vertex(out v2f o) { <vertexBody> }`)
14. Generated `void main()` — assigns `cg_InstanceId`, calls `vertex(_v2f_local)`, copies fields to `_cg_v2f.<name>`

### Fragment shader generation sequence (steps 1–10)

1. `#version 430 core` / `#version 330 core`
2. `#define CG_USE_SSBO 1` (SSBO path; no CG_VERTEX_STAGE)
3. `#define CG_OBJECT_BUFFER_BINDING` + `#define CG_FRAME_BLOCK_BINDING`
4. `#include "crystalgraphics:shaders/env/cg_env.glsl"`
5. Property uniform declarations (same as vertex; unused uniforms compiled out)
6. **User-attached SSBO/TBO buffers** — same injection as vertex
7. **User-attached UBO blocks** — same injection as vertex
8. `struct v2f { ... };`
9. v2f interface block (`in _CgV2fBlock { <fields> } _cg_v2f;`)
10. Global declarations
11. `out vec4 _cg_fragColor;`
12. User fragment function (`void fragment(in v2f i, out vec4 fragColor) { <fragmentBody> }`)
13. Generated `void main()` — reconstructs `v2f _v2f_local` from `_cg_v2f.<name>`, calls `fragment(_v2f_local, _cg_fragColor)`

## CgBufferGlslEmitter — GLSL Generation Details

### `emitSsbo(format, bufferName, macroName)` output shape

```glsl
struct StructName {
    type field;
};

layout(std430) readonly buffer BufferName {
    StructName _cg_structNameArr[];
};

#define MACRO_NAME(n) _cg_structNameArr[n]
```

Block interface name = `bufferName` exactly (required for `wireShader()` to call `glGetProgramResourceIndex`).

### `emitTbo(format, bufferName, macroName)` output shape

```glsl
struct StructName { ... };

uniform samplerBuffer BufferName;

StructName _cg_getStructName(int n) {
    int _base = n * <stride/16>;
    StructName _r;
    _r.field = texelFetch(BufferName, _base + <texelIndex>)<.swizzle>;
    return _r;
}

#define MACRO_NAME(n) _cg_getStructName(n)
```

TBO texel arithmetic: texel = 16 bytes = 4 float32s.
`texelIndex = byteOffset / 16`, `componentOffset = (byteOffset % 16) / 4`.

| Type | Swizzle |
|------|---------|
| VEC4 | none (full texel) |
| VEC3 | `.xyz` (always at component 0 due to alignment 16) |
| VEC2 | `.xy` if componentOffset=0, `.zw` if componentOffset=2 |
| FLOAT | `.x`/`.y`/`.z`/`.w` by componentOffset |
| MAT4 | 4 full-texel column assignments |
| MAT3 | 3 `.xyz` column assignments |

Duplicate texelFetch indices (FLOAT/VEC2 packing) are CSE-eliminated by GLSL drivers.

Throws `CgPreprocessorException` if:
- Any field type has `isTboCompatible() == false` (INT, UINT, BOOL, IVEC*, UVEC*, INT64, UINT64)
- `format.getStride() % 16 != 0`

### `emitUbo(format, blockName)` output shape

```glsl
layout(std140) uniform BlockName {
    type field;
};
```

No struct, no instance name, no macro. All fields in direct scope. Block name = `blockName` parameter (= `buffer.getName()`). UBO declarations are path-independent (identical on SSBO and TBO paths).

## Key Design Rules

- **No GL calls** in parser or compiler — pure string transforms.
- **`CgShaderParser.parse(source, resourcePath)`** — always pass resourcePath; all exceptions include `[path]` prefix.
- **Properties block is optional** — `parseProperties()` returns empty list if absent.
- **Comment lines skipped** in `v2f` body and `Properties` block.
- **Binding defines injected before include** — `cg_env.glsl` uses `CG_FRAME_BLOCK_BINDING` / `CG_OBJECT_BUFFER_BINDING` macros.
- **v2f interface block** (`_CgV2fBlock` / `_cg_v2f`) replaces individual flat varyings.
- **`ENV_TYPE` removed** — no debug level switching; env include is always `crystalgraphics:shaders/env/cg_env.glsl`.
- **`#` directive passthrough**: any `#`-prefixed line in the shader preamble (between `#type` and the first structural section marker) is collected by `parsePreambleDirectives()` and prepended to `globalDecls`. The compiler's `partitionGlobalDecls()` then splits `globalDecls` into directive lines (emitted right after `#version`) and code lines (emitted in the normal globalDecls position). `#extension`, `#define`, and any other `#` directives placed in the preamble flow through correctly.
- **Auto-inject extensions for special GPU types**: `CgGpuType.requiredExtension()` returns the required extension string for types that need one (`INT64`/`UINT64` → `"GL_ARB_gpu_shader_int64"`; others → `null`). The compiler collects these from all attached buffer fields, checks `CgCapabilities.detect().isGpuShaderInt64()`, throws `CgPreprocessorException` if unsupported, and auto-emits them after `#version` if not already in the directive lines.

## Relationship to Other Packages

| Package | Relationship |
|---------|-------------|
| `api/material/` | `CgMaterial.create()` (package-private, called by registry) calls `CgShaderParser.parse()` then `CgMaterialShaderCompiler.compile()` |
| `api/shader/` | `CgShaderPreprocessor` processes the compiler output before GL compilation |
| `gl/shader/` | `CgShaderFactory.fromSource()` receives the preprocessed output |
| `api/CgCapabilities` | `ShaderBufferPath` enum drives `#version` selection and `CG_USE_SSBO` emit; `isGpuShaderInt64()` gates INT64/UINT64 buffer fields |
| `api/buffer/CgGpuType` | `requiredExtension()` returns required GLSL extension per field type |
