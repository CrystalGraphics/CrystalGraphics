# gl/material/parse — Internal Parse and Compile Implementations

> Parent package: [`gl/material/AGENTS.md`](../AGENTS.md)  
> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../../AGENTS.md)

## What This Package Is

Internal parse, compile, and GLSL-generation implementation for the CrystalShader material
pipeline. **Not for direct external use** — callers should import only the types listed
under "External-facing types" below.

This package owns the entire pipeline from raw `.shader` source string to complete, ready-to-compile
GLSL strings. Zero GL calls anywhere in this package — all GL constants come from LWJGL
static final fields.

## Class Map

| Class | Access | Role |
|-------|--------|------|
| `CgShaderParser` | `public final` | Public parse facade. `parse(String)` / `parse(String, String)` → `CgParsedShader`. `parseV2fFields(CgParsedShader)` → `List<V2fField>`. Nested `V2fField` record (public). Delegates to the package-private sub-parsers below. |
| `CgShaderParseException` | `public` | `RuntimeException` thrown on any `.shader` format violation. All messages include a `[resourcePath]` prefix. |
| `CgParsedShader` | `public record` | Immutable parse result. Fields: `shaderType`, `properties`, `v2fStructBody`, `globalDecls`, `vertexBody`, `fragmentBody`, `renderState`, `renderQueue`, `fragOutput`, `featureNames`. Consumed by `CgMaterialShaderCompiler`. |
| `CgMaterialShaderCompiler` | `public final` | Generates complete GLSL vertex + fragment source strings from a `CgParsedShader`. Nested `CompiledSource` record (public): `vertexSource`, `fragmentSource`. Nested `CompileConfig` record (public): `activeKeywords` (normalised unmodifiable `Set<String>`), `DEFAULT` constant (empty keywords). `compile(parsed, bufferPath, attachedBuffers)` and `compile(parsed, bufferPath, attachedBuffers, matPropsEntry)` delegate to the 5-arg `compile(..., CompileConfig)` with `CompileConfig.DEFAULT`. Active keywords are injected as `#define NAME 1` lines (in declaration order from `featureNames`) immediately after `#version`. `#pragma cg_feature` lines are never emitted in output GLSL. |
| `CgFragOutputParser` | package-private | Parses the `void fragment()` signature to produce a `CgFragOutputParser.FragOutput`. Handles single-output (`out vec4`) and MRT struct path (`out GBuffer o`), including `: RTN` annotation resolution, all-or-none validation, duplicate/range checks, and clean struct body construction. Owns the nested `FragOutput` public record (see below). |
| `CgFragOutputParser.FragOutput` | `public static record` (nested) | Fragment output descriptor, nested inside `CgFragOutputParser`. `isMrt()` distinguishes single-output (`mrtStructName==null`) from MRT struct path. Fields: `mrtStructName`, `outParamName`, `fieldNames`, `locations`, `mrtStructBody`. Static factory: `singleOutput(String)`. |
| `CgBufferGlslEmitter` | package-private | Pure GLSL string generator for SSBO, TBO, and UBO blocks. Package-private test seam: `emitSsbo/emitTbo/emitUbo(CgBufferFormat, String, ...)`. |
| `CgShaderKeywords` | package-private | Registry of all render-state keyword token sets (compare funcs, blend factors, blend equations, cull modes, on/off, stencil ops) and grammar descriptors. Used by `CgRenderStateParser`. |
| `TokenSet<V>` | package-private | Immutable, case-insensitive keyword → value lookup table with a fluent `Builder`. Used by `CgShaderKeywords` to define each token domain. |
| `CgStructureParser` | package-private | Structural parsing and validation: brace matching, section extraction (`v2f` body, `globalDecls`, vertex/fragment blocks), preamble directive passthrough, `#type` parsing, and all `validate*` format checks (no `#version`, no `main()`, reserved-prefix enforcement, section ordering, duplicate-section detection). Does **not** own fragment output parsing — that belongs to `CgFragOutputParser`. |
| `CgRenderStateParser` | package-private | Parses the `RenderState { }` block into a `CgRenderState`. Handles `DepthTest`, `DepthWrite`, `Blend`, `BlendEquation`, `Cull`, `AlphaTest`, `ColorMask`, and the `Stencil { }` sub-block. Rejects deprecated keywords (`ZTest`, `ZWrite`, `BlendOp`) with migration hints. |
| `CgPropertiesParser` | package-private | Parses the `Properties { }` block into `List<CgMaterialProperty>`. Supports old-style (`_Name : type [= default]`) and new-style (`_Name ("Display Name", type) [= default]`) declarations. Handles `Range(min, max)` bounds, sampler default quote-stripping, and reserved-prefix validation. |
| `CgQueueParser` | package-private | Parses the `Queue = "Name"` keyword into a numeric render-queue priority. Accepts named queues (`Background`, `Geometry`, `AlphaTest`, `Transparent`, `Overlay`) and bare integer values. Rejects old-style `RenderQueue <Name>` syntax with a migration hint. |

## External-Facing Types

Callers outside this package should import only:

- `CgShaderParser` — entry point for parsing
- `CgShaderParseException` — catch for format errors
- `CgParsedShader` — parse result consumed by the compiler
- `CgFragOutputParser.FragOutput` — fragment output descriptor (accessed via `parsed.fragOutput()`; nested inside `CgFragOutputParser`)
- `CgMaterialShaderCompiler` + `CgMaterialShaderCompiler.CompiledSource` — GLSL compilation

All other classes are package-private implementation details.

## Key Design Rules

- **Zero GL calls** — all GL constants are LWJGL `static final int` references, never runtime queries.
- **`resourcePath` is mandatory** — always pass it to sub-parser methods; all `CgShaderParseException` messages include `[path]`.
- **`CgShaderParser` is the only entry point** — sub-parsers are package-private and not accessible externally.
- **Properties block is optional** — `CgPropertiesParser.parse()` returns an empty unmodifiable list if absent.
- **Comment lines stripped** — `//` comments are stripped before tokenizing `RenderState` and `Properties` blocks.
- **`#` directive passthrough** — preamble `#`-lines (between `#type` and first structural section) are collected by `CgStructureParser.parsePreambleDirectives()` and prepended to `globalDecls`; `CgMaterialShaderCompiler.partitionGlobalDecls()` emits them immediately after `#version`.
- **Auto-inject extensions** — `CgGpuType.requiredExtension()` drives automatic `#extension` emission for `INT64`/`UINT64` buffer fields; throws `CgPreprocessorException` if the capability is absent on the current context.
- **Fragment output ownership** — `CgFragOutputParser` owns all fragment-output parse logic; `CgStructureParser` does not parse fragment outputs.

## Internal Pipeline Flow

```
CgShaderParser.parse(source, path)
    │
    ├── CgStructureParser.validateNoVersionDirective()
    ├── CgStructureParser.validateNoOldRenderQueue()
    ├── CgStructureParser.validateSectionStructure()
    ├── CgStructureParser.parseShaderType()
    ├── CgPropertiesParser.parse()
    ├── CgStructureParser.parseV2fBody() / findV2fEnd()
    ├── CgStructureParser.parseGlobalDecls()
    ├── CgStructureParser.extractBlock()  ×2  (vertex, fragment)
    ├── CgStructureParser.validateNoMainFunction()  ×2
    ├── CgStructureParser.parsePreambleDirectives()
    ├── CgFragOutputParser.parse()          ← MRT output descriptor
    ├── CgRenderStateParser.parse()
    └── CgQueueParser.parse()
    → CgParsedShader

CgMaterialShaderCompiler.compile(parsed, bufferPath, attachedBuffers)
    ├── CgBufferGlslEmitter.emitSsbo/emitTbo/emitUbo()  (per attached buffer)
    └── → CompiledSource { vertexSource, fragmentSource }
```

## `#pragma cg_feature` — Keyword Parsing

**Where it lives:** `CgStructureParser.parseFeaturePragmas(String source, String resourcePath)`

**What it does:**
- Scans the source preamble (lines before the first structural section such as `Properties {`,
  `struct v2f`, `void vertex(`) for lines matching `#pragma cg_feature <NAME>`.
- Each found name is validated and added to an ordered list.
- The result is stored as `CgParsedShader.featureNames()` — a `List<String>` in declaration order.
- `#pragma cg_feature` lines that appear **inside** a function body are ignored (not parsed as features).
- `#pragma once` lines in the preamble are silently skipped (not confused with `cg_feature`).

**Validation rules enforced at parse time:**
- Feature name must be non-empty — throws if blank after `#pragma cg_feature`.
- Feature name must match `^[A-Z][A-Z0-9_]*$` (uppercase identifier) — throws with message.
- Duplicate feature names throw `CgShaderParseException`.
- Maximum of **8 feature names** per shader — throws if exceeded.

**How the compiler uses `featureNames`:**
`CgMaterialShaderCompiler.CompileConfig` holds the active keyword set. During compilation,
the compiler iterates `featureNames` in declaration order and, for each name present in
`activeKeywords`, emits `#define NAME 1` immediately after `#version` in both vertex and
fragment sources. `#pragma cg_feature` lines are never forwarded to GLSL output — they are
consumed entirely at parse time.

**Output example** for `activeKeywords = {"RECEIVE_SHADOWS"}`:
```glsl
#version 430 core
#define RECEIVE_SHADOWS 1
// ... rest of generated GLSL
```

**vec3 ban in Properties:**
`CgPropertiesParser.resolvePropertyTypeBase()` throws `CgShaderParseException` if the resolved
property type is `vec3`. The error message explains the STD140 alignment root cause and directs
authors to use `vec4` instead. `CgMaterialProperty.fromDecl()` throws `UnsupportedOperationException`
as a defense-in-depth guard for any code path that bypasses the parser.

## Relationship to `gl/material/`

`CgMaterialProperty` and `CgMaterialProperties` remain in the parent `gl/material/` package —
`CgMaterialProperty` is used directly by callers to set property values at runtime, and
`CgMaterialProperties` is a utility that depends on it.
