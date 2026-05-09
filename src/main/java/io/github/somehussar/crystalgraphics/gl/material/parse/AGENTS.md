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
| `CgShaderParser` | `public final` | Public parse facade. `parse(String)` / `parse(String, String)` → `CgParsedShader`. `parseV2fFields(CgParsedShader)` → `List<V2fField>`. Nested `V2fField` record (public). Delegates to the four package-private sub-parsers below. |
| `CgShaderParseException` | `public` | `RuntimeException` thrown on any `.shader` format violation. All messages include a `[resourcePath]` prefix. |
| `CgParsedShader` | `public record` | Immutable parse result. Fields: `shaderType`, `properties`, `v2fStructBody`, `globalDecls`, `vertexBody`, `fragmentBody`, `renderState`, `renderQueue`. Consumed by `CgMaterialShaderCompiler`. |
| `CgMaterialShaderCompiler` | `public final` | Generates complete GLSL vertex + fragment source strings from a `CgParsedShader`. Nested `CompiledSource` record (public): `vertexSource`, `fragmentSource`. |
| `CgBufferGlslEmitter` | package-private | Pure GLSL string generator for SSBO, TBO, and UBO blocks. Package-private test seam: `emitSsbo/emitTbo/emitUbo(CgBufferFormat, String, ...)`. |
| `CgShaderKeywords` | package-private | Registry of all render-state keyword token sets (compare funcs, blend factors, blend equations, cull modes, on/off, stencil ops) and grammar descriptors. Used by `CgRenderStateParser`. |
| `TokenSet<V>` | package-private | Immutable, case-insensitive keyword → value lookup table with a fluent `Builder`. Used by `CgShaderKeywords` to define each token domain. |
| `CgStructureParser` | package-private | Structural parsing and validation: brace matching, section extraction (`v2f` body, `globalDecls`, vertex/fragment blocks), preamble directive passthrough, `#type` parsing, and all `validate*` format checks (no `#version`, no `main()`, reserved-prefix enforcement, section ordering, duplicate-section detection). |
| `CgRenderStateParser` | package-private | Parses the `RenderState { }` block into a `CgRenderState`. Handles `DepthTest`, `DepthWrite`, `Blend`, `BlendEquation`, `Cull`, `AlphaTest`, `ColorMask`, and the `Stencil { }` sub-block. Rejects deprecated keywords (`ZTest`, `ZWrite`, `BlendOp`) with migration hints. |
| `CgPropertiesParser` | package-private | Parses the `Properties { }` block into `List<CgMaterialProperty>`. Supports old-style (`_Name : type [= default]`) and new-style (`_Name ("Display Name", type) [= default]`) declarations. Handles `Range(min, max)` bounds, sampler default quote-stripping, and reserved-prefix validation. |
| `CgQueueParser` | package-private | Parses the `Queue = "Name"` keyword into a numeric render-queue priority. Accepts named queues (`Background`, `Geometry`, `AlphaTest`, `Transparent`, `Overlay`) and bare integer values. Rejects old-style `RenderQueue <Name>` syntax with a migration hint. |

## External-Facing Types

Callers outside this package should import only:

- `CgShaderParser` — entry point for parsing
- `CgShaderParseException` — catch for format errors
- `CgParsedShader` — parse result consumed by the compiler
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
    ├── CgRenderStateParser.parse()
    └── CgQueueParser.parse()
    → CgParsedShader

CgMaterialShaderCompiler.compile(parsed, bufferPath, attachedBuffers)
    ├── CgBufferGlslEmitter.emitSsbo/emitTbo/emitUbo()  (per attached buffer)
    └── → CompiledSource { vertexSource, fragmentSource }
```

## Relationship to `gl/material/`

`CgMaterialProperty` and `CgMaterialProperties` remain in the parent `gl/material/` package —
`CgMaterialProperty` is used directly by callers to set property values at runtime, and
`CgMaterialProperties` is a utility that depends on it.
