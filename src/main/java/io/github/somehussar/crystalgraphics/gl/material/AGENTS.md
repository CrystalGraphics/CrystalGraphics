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
| `CgShaderParser` | Static parser: `parse(String)` → `CgParsedShader`; `parse(String, String resourcePath)` — includes path in all exception messages. Splits `.shader` source into structural sections. Skips `//` comment lines in `v2f` body and `Properties` block. Properties block is optional (returns empty list if absent). Also provides `parseV2fFields(CgParsedShader)` → `List<CgShaderParser.V2fField>` for the compiler. Nested: `V2fField` (see below). |
| `CgShaderParser.V2fField` | `@Value` nested class for one v2f struct field: `type`, `name`. Moved from standalone `V2fField.java`. |
| `CgParsedShader` | `@Value` immutable result of `CgShaderParser.parse()`. Fields: `shaderType`, `properties` (List<CgMaterialProperty>), `v2fStructBody` (raw body text), `globalDecls` (raw text between v2f and vertex()), `vertexBody`, `fragmentBody`. |
| `CgMaterialProperty` | Mutable runtime property object — replaces the former `PropertyDecl` + `CgPropertyParser` split. Holds the declaration (`name`, `type`, `rawDefault`), current float/sampler value, and self-binding logic. `fromDecl(name, glslType, rawDefault)` creates and eagerly parses the default. `applyTo(CgShaderBindings)` flushes the current value to shader uniforms. `set(...)` overloads for float/vec2/vec3/vec4; `setTexture(unit, CgTexture)` for sampler2D. `resetToDefault()` restores the parsed default. Inner enum `Type` maps GLSL names to component counts. |
| `CgShaderParseException` | `RuntimeException` thrown for any format violation. Messages include `[resourcePath]` prefix. |
| `CgMaterialShaderCompiler` | Static compiler: `compile(CgParsedShader, ShaderBufferPath)` → `CgMaterialShaderCompiler.CompiledSource`. Injects `CG_OBJECT_BUFFER_BINDING`/`CG_FRAME_BLOCK_BINDING` defines. Emits v2f interface blocks. No `ENV_TYPE` field. No GL calls. Nested: `CompiledSource` (see below). |
| `CgMaterialShaderCompiler.CompiledSource` | `@Value` nested class: `vertexSource`, `fragmentSource`. Both strings are complete, ready for `CgShaderPreprocessor.process()` followed by `CgShaderFactory.fromSource()`. Moved from standalone `CompiledMaterialSource.java`. |

## Compiler Output Format

### Vertex shader generation sequence (steps 1–11)

1. `#version 430 core` (SSBO) or `#version 330 core` (TBO)
2. `#define CG_VERTEX_STAGE 1`
3. `#define CG_USE_SSBO 1` (SSBO path only)
4. `#define CG_OBJECT_BUFFER_BINDING <CgBindingPoints.OBJECT_DATA>` + `#define CG_FRAME_BLOCK_BINDING <CgBindingPoints.FRAME_DATA>` — injected before include
5. `#include "crystalgraphics:shaders/env/cg_env.glsl"`
6. Property uniform declarations (`uniform <type> <name>;`)
7. `struct v2f { <v2fStructBody> };`
8. `flat out int cg_InstanceId;` — compiler-wired instance ID varying
9. v2f interface block (`out _CgV2fBlock { <fields> } _cg_v2f;`)
10. Global declarations (verbatim `globalDecls` from parsed shader)
11. User vertex function (`void vertex(out v2f o) { <vertexBody> }`)
12. Generated `void main()` — assigns `cg_InstanceId`, calls `vertex(_v2f_local)`, copies fields to `_cg_v2f.<name>`

### Fragment shader generation sequence (steps 1–10)

1. `#version 430 core` / `#version 330 core`
2. `#define CG_USE_SSBO 1` (SSBO path; no CG_VERTEX_STAGE)
3. `#define CG_OBJECT_BUFFER_BINDING` + `#define CG_FRAME_BLOCK_BINDING`
4. `#include "crystalgraphics:shaders/env/cg_env.glsl"`
5. Property uniform declarations (same as vertex; unused uniforms compiled out)
6. `struct v2f { ... };`
7. v2f interface block (`in _CgV2fBlock { <fields> } _cg_v2f;`)
8. Global declarations
9. `out vec4 _cg_fragColor;`
10. User fragment function (`void fragment(in v2f i, out vec4 fragColor) { <fragmentBody> }`)
11. Generated `void main()` — reconstructs `v2f _v2f_local` from `_cg_v2f.<name>`, calls `fragment(_v2f_local, _cg_fragColor)`

## Key Design Rules

- **No GL calls** in parser or compiler — pure string transforms.
- **`CgShaderParser.parse(source, resourcePath)`** — always pass resourcePath; all exceptions include `[path]` prefix.
- **Properties block is optional** — `parseProperties()` returns empty list if absent.
- **Comment lines skipped** in `v2f` body and `Properties` block.
- **Binding defines injected before include** — `cg_env.glsl` uses `CG_FRAME_BLOCK_BINDING` / `CG_OBJECT_BUFFER_BINDING` macros.
- **v2f interface block** (`_CgV2fBlock` / `_cg_v2f`) replaces individual flat varyings.
- **`ENV_TYPE` removed** — no debug level switching; env include is always `crystalgraphics:shaders/env/cg_env.glsl`.

## Relationship to Other Packages

| Package | Relationship |
|---------|-------------|
| `api/material/` | `CgMaterial.create()` (package-private, called by registry) calls `CgShaderParser.parse()` then `CgMaterialShaderCompiler.compile()` |
| `api/shader/` | `CgShaderPreprocessor` processes the compiler output before GL compilation |
| `gl/shader/` | `CgShaderFactory.fromSource()` receives the preprocessed output |
| `api/CgCapabilities` | `ShaderBufferPath` enum drives `#version` selection and `CG_USE_SSBO` emit |
