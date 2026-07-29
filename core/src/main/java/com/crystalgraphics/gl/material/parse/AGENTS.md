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
| `CgShaderParser` | `public final` | Public parse facade. `parse(String)` / `parse(String, String)` → `CgParsedShader`. `parseV2fFields(CgParsedPass)` → `List<V2fField>`. Nested `V2fField` record (public). Dispatches pass-aware parse flow (see Pipeline Flow below). |
| `CgShaderParseException` | `public` | `RuntimeException` thrown on any `.shader` format violation. All messages include a `[resourcePath]` prefix. |
| `CgParsedShader` | `public record` | Immutable material-level parse result. Fields: `shaderType`, `properties`, `featureNames`, `renderQueue`, `renderType`, `castShadows`, `passes`. `getPassByName(String)` and `getPassByLightMode(String)` are convenience lookups. Per-pass data lives on each `CgParsedPass` — not on this record. Consumed by `CgMaterialShaderCompiler`. |
| `CgParsedPass` | `public record` | Immutable per-pass parse result. Fields: `lightMode`, `name`, `renderState`, `v2fStructBody`, `globalDecls`, `vertexBody`, `fragmentBody`, `fragOutput`. Package-private constants `LIGHT_MODE_FORWARD`, `LIGHT_MODE_SHADOW_CASTER`, `LIGHT_MODE_DEPTH` — **use `CgRenderPassVariant.*.lightModeName()` from outside this package**. |
| `CgMaterialShaderCompiler` | `public final` | Generates complete GLSL vertex + fragment source strings from a `(CgParsedShader, CgParsedPass)` pair. Canonical 5-arg entry point: `compile(shader, pass, attachedBuffers, matPropsEntry, config)`. Convenience overloads delegate to the canonical via `passes().get(0)`. `compileShadowAutoGen(shader, forwardPass, ...)` auto-generates a shadow-caster program from a Forward pass. `compileDepthAutoGen(shader, forwardPass, ...)` auto-generates a depth-prepass program (three cases: (1) simple vertex → minimal `CG_MATRIX_MVP` transform; (2) complex vertex → forward body copied verbatim to preserve animation; (3) alpha-clip → forward vertex + forward fragment body so `discard` fires). `isSimpleVertex(pass)` — no user props, no `cg_Time`/`CG_OBJECT_CUSTOM0-3`, no `discard`, cull ≠ NONE. Nested `CompiledSource` record: `vertexSource`, `fragmentSource`, `vertexFormat` (resolved from `#type` at compile time). Nested `CompileConfig` record: `activeKeywords`, `DEFAULT`. **Known limitation**: `compileShadowAutoGen`/`compileDepthAutoGen` generate vertex bodies that reference `cg_Position` by literal name — only correct for SPATIAL and SPATIAL-derived formats. Custom formats with differently-named position attributes must author an explicit `ShadowCaster`/`Depth` pass. |
| `CgFragOutputParser` | package-private | Parses the `void fragment()` signature to produce a `CgFragOutputParser.FragOutput`. Handles single-output (`out vec4`) and MRT struct path (`out GBuffer o`), including `: RTN` annotation resolution, all-or-none validation, duplicate/range checks, and clean struct body construction. Owns the nested `FragOutput` public record (see below). |
| `CgFragOutputParser.FragOutput` | `public static record` (nested) | Fragment output descriptor, nested inside `CgFragOutputParser`. `isMrt()` distinguishes single-output (`mrtStructName==null`) from MRT struct path. Fields: `mrtStructName`, `outParamName`, `fieldNames`, `locations`, `mrtStructBody`. Static factory: `singleOutput(String)`. |
| `CgGlslEmitter` | package-private | Pure GLSL string generator for SSBO, TBO, UBO blocks, and vertex format attribute declarations. Package-private test seam: `emitSsbo/emitTbo/emitUbo(CgBufferFormat, String, ...)`. `emitVertexInputs(CgVertexFormat)` generates `in <glslType> <name>;` declarations for all format attributes — called only from `buildVertexSource()`, never from the fragment stage. Renamed from `CgGlslEmitter`. |
| `CgShaderKeywords` | package-private | Registry of all render-state keyword token sets (compare funcs, blend factors, blend equations, cull modes, on/off, stencil ops) and grammar descriptors. Used by `CgRenderStateParser`. |
| `TokenSet<V>` | package-private | Immutable, case-insensitive keyword → value lookup table with a fluent `Builder`. Used by `CgShaderKeywords` to define each token domain. |
| `CgStructureParser` | package-private | Structural parsing and validation: brace matching, `Pass { }` block extraction (`extractPassBlocks`), per-pass section extraction (`parsePassV2fBody`, `parsePassGlobalDecls`), shared v2f extraction (`parseSharedV2f`), pass body validation (`validatePassBody`), `#type` parsing, and all `validate*` format checks. Does **not** own fragment output or tag parsing. |
| `CgTagParser` | package-private | Tag parsing: `parseTopLevelTags(source, path)` — parses the material-level `Tags { }` block (before first `Pass`); `parseTagsBlock(body, path)` — parses a `Tags { }` block anywhere within a given text region. Both return unmodifiable `Map<String, String>` of raw key→value pairs. Private `parseKeyValuePairs` handles `"key" = "value"` extraction. Called by `CgShaderParser`. |
| `CgRenderStateParser` | package-private | Parses the `RenderState { }` block into a `CgRenderState`. Now called once per pass on the pass body. Handles `DepthTest`, `DepthWrite`, `Blend`, `BlendEquation`, `Cull`, `AlphaTest`, `ColorMask`, and the `Stencil { }` sub-block. Rejects deprecated keywords (`ZTest`, `ZWrite`, `BlendOp`) with migration hints. |
| `CgPropertiesParser` | package-private | Parses the `Properties { }` block (material-level, before first `Pass`) into `List<CgMaterialProperty>`. Supports old-style and new-style declarations. Handles `Range(min, max)` bounds, sampler default quote-stripping, and reserved-prefix validation. |
| `CgQueueParser` | package-private | Parses the `Queue = "Name"` keyword into a numeric render-queue priority. Accepts named queues (`Background`, `Geometry`, `AlphaTest`, `Transparent`, `Overlay`) and bare integer values. Rejects old-style `RenderQueue <Name>` syntax with a migration hint. |

## External-Facing Types

Callers outside this package should import only:

- `CgShaderParser` — entry point for parsing
- `CgShaderParseException` — catch for format errors
- `CgParsedShader` — parse result consumed by the compiler
- `CgParsedPass` — per-pass parse result (accessed via `parsed.passes()` or `getPassByLightMode`)
- `CgFragOutputParser.FragOutput` — fragment output descriptor (accessed via `pass.fragOutput()`)
- `CgMaterialShaderCompiler` + `CgMaterialShaderCompiler.CompiledSource` — GLSL compilation

All other classes are package-private implementation details.

## Key Design Rules

- **Zero GL calls** — all GL constants are LWJGL `static final int` references, never runtime queries.
- **`resourcePath` is mandatory** — always pass it to sub-parser methods; all `CgShaderParseException` messages include `[path]`.
- **`CgShaderParser` is the only entry point** — sub-parsers are package-private and not accessible externally.
- **At least one `Pass { }` required** — `CgShaderParser.parse()` throws `CgShaderParseException` when no `Pass` blocks are found.
- **`LIGHT_MODE_*` constants are package-private** — outside callers use `CgRenderPassVariant.*.lightModeName()` for LightMode string values.
- **Properties block is optional** — `CgPropertiesParser.parse()` returns an empty unmodifiable list if absent.
- **Comment lines stripped** — `//` comments are stripped before tokenizing `RenderState` and `Properties` blocks.
- **`#` directive passthrough** — top-level preamble `#`-lines are collected by `CgStructureParser.parsePreambleDirectives()` and prepended to each pass's `globalDecls`; the compiler emits them immediately after `#version`.
- **Auto-inject extensions** — `CgGpuType.requiredExtension()` drives automatic `#extension` emission for `INT64`/`UINT64` buffer fields.
- **Fragment output ownership** — `CgFragOutputParser` owns all fragment-output parse logic; `CgStructureParser` does not parse fragment outputs.
- **Shadow UBO contract** — auto-generated shadow shaders reference `cg_ShadowViewProjMatrix`, `cg_LightDirection`, `cg_ShadowParams` as plain identifiers from the `CgFrameBlock` UBO — never as standalone `uniform` declarations.

## Internal Pipeline Flow

```
CgShaderParser.parse(source, path)
    │
    ├── CgStructureParser.warnNonAscii()
    ├── CgStructureParser.validateNoVersionDirective()
    ├── CgStructureParser.validateNoOldRenderQueue()
    ├── CgStructureParser.parseShaderType()
    ├── CgStructureParser.parseFeaturePragmas()
    ├── CgPropertiesParser.parse()                  ← material-level Properties {}
    ├── CgQueueParser.parse()                       ← Queue = "..." (material-level)
    ├── CgTagParser.parseTopLevelTags()       → renderType, castShadows
    ├── CgStructureParser.extractPassBlocks()       → List<String> pass bodies
    │   └── throws if empty ("No Pass {} blocks found")
    ├── CgStructureParser.parseSharedV2f()          ← top-level struct v2f (optional)
    ├── CgStructureParser.parsePreambleDirectives() ← top-level # lines
    │
    └── for each passBody:
        ├── CgTagParser.parseTagsBlock()      → lightMode, name
        ├── lightMode validation/default (→ "Forward" if absent/unknown)
        ├── dedup check for ShadowCaster/Depth (warn + skip if duplicate)
        ├── CgRenderStateParser.parse(passBody)
        ├── CgStructureParser.parsePassV2fBody()    (per-pass or shared fallback)
        ├── CgStructureParser.parsePassGlobalDecls()
        ├── CgStructureParser.extractBlock() ×2    (vertex, fragment)
        ├── CgStructureParser.validateNoMainFunction() ×2
        ├── CgFragOutputParser.parse(passBody)
        ├── MRT struct normalisation in globalDecls
        ├── CgStructureParser.validatePassBody()
        └── → CgParsedPass

    → CgParsedShader(shaderType, properties, featureNames, renderQueue,
                      renderType, castShadows, passes)

CgMaterialShaderCompiler.compile(shader, pass, attachedBuffers, matPropsUbo, config)
    ├── CgShaderParser.parseV2fFields(pass)
    ├── CgVertexFormat.forShaderType(shader.shaderType())  → resolves vertex format
    ├── CgGlslEmitter.emitVertexInputs(format)             (injected in vertex source only)
    ├── CgGlslEmitter.emitSsbo/emitTbo/emitUbo()  (per attached buffer)
    └── → CompiledSource { vertexSource, fragmentSource, vertexFormat }

CgMaterialShaderCompiler.compileShadowAutoGen(shader, forwardPass, ...)
    ├── isSimpleVertex(forwardPass)  → simple (position-only) or complex (full body + override)
    └── → CompiledSource { shadowVertexSource, depthOnlyFragmentSource }
```

## `#pragma cg_feature` — Keyword Parsing

**Where it lives:** `CgStructureParser.parseFeaturePragmas(String source, String resourcePath)`

**Stop condition:** Stops at the first `Pass {` or `Pass{` — feature pragmas inside pass bodies are not collected.

**Validation rules enforced at parse time:**
- Feature name must be non-empty — throws if blank after `#pragma cg_feature`.
- Feature name must match `^[A-Za-z_][A-Za-z0-9_]*$` — throws on invalid identifier.
- Duplicate feature names throw `CgShaderParseException`.
- Maximum of **8 feature names** per shader — throws if exceeded.

**How the compiler uses `featureNames`:**
`CgMaterialShaderCompiler.CompileConfig` holds the active keyword set. During compilation,
the compiler iterates `featureNames` in declaration order and, for each name present in
`activeKeywords`, emits `#define NAME 1` immediately after `#version` in both vertex and
fragment sources. `#pragma cg_feature` lines are never forwarded to GLSL output.

## `#pragma cg_use` — Engine Buffer Opt-In

**Where it lives:** `CgStructureParser.parseUsePragmas(String source, String resourcePath)`, with
cross-checking in `CgShaderParser.validateEngineBufferUsage(...)`.

Declares that a shader reads an engine-provided shader buffer — currently only `quad`
(`CgQuadRenderer`'s per-instance buffer, unlocking `QUAD_DATA` and the `CG_QUAD_*` macros). Tokens
land in `CgParsedShader.engineBuffers()`.

**Same preamble region and stop conditions as `cg_feature`** — never collected from inside a `Pass`
body, and stripped from `parsePreambleDirectives` output so the line never reaches GLSL.

**Validation, all `CgShaderParseException` at parse time:**
- Missing token after `#pragma cg_use`.
- Token must match `^[A-Za-z_][A-Za-z0-9_]*$`.
- Duplicate token.
- Token not registered in `CgEngineBufferRegistry` — message lists the registered ones.
- **Reverse check:** source references `QUAD_DATA` or `CG_QUAD_` *without* declaring the token —
  message names the exact pragma line to add.

That reverse check is the point of the whole mechanism. The buffer used to be attached from Java at
a material's first use, so anything that compiled the shader earlier (notably
`CgMaterial.enableKeyword`, which recompiles on the spot when the shader has not been parsed yet)
produced GLSL with `QUAD_DATA` undeclared — and since a failed compile leaves the feature list
empty, it surfaced as *"Keyword 'X' is not declared as #pragma cg_feature"*, naming a pragma that was
present and correct.

**Detection is textual, against the raw `.shader` source** — deliberately not the preprocessed
output, which would contain `cg_env.glsl`'s own `CG_QUAD_*` *definitions* and therefore flag every
shader in the engine.

**How the attach happens:** `CgMaterialShader.recompile()` step 2b resolves each token through
`CgEngineBufferRegistry` and calls `attach(...)` — after parse, before compile. Covered by
`CgShaderParserUsePragmaTest`.

## Shadow Auto-Generation

`CgMaterialShaderCompiler.compileShadowAutoGen(shader, forwardPass, attachedBuffers, matPropsUbo, config)`:

- **Simple vertex path** (`isSimpleVertex(pass)` = no user props, no `discard`, cull ≠ `NONE`): emits a minimal position-only transform using `cg_ShadowViewProjMatrix` from the frame UBO.
- **Complex vertex path**: re-runs the full forward vertex body, then overrides `gl_Position` with the shadow matrix.
- **Fragment**: depth-only (empty body — rasterizer writes depth automatically).

Called by `CgMaterialShader.recompile()` when `castShadows && renderQueue < 3000` and no explicit `ShadowCaster` pass was authored.

## `#pragma cg_feature` Stop Conditions

Both `parsePreambleDirectives` and `parseFeaturePragmas` stop at:
- `Properties {` / `Properties{`
- `struct v2f {` / `struct v2f{`
- `void vertex(`
- `Pass {` / `Pass{`

This prevents preamble content inside Pass bodies from being misidentified as material-level declarations.

## `vec3` Ban in Properties

`CgPropertiesParser.resolvePropertyTypeBase()` throws `CgShaderParseException` if the resolved
property type is `vec3`. The error message explains the STD140 alignment root cause and directs
authors to use `vec4` instead. `CgMaterialProperty.fromDecl()` throws `UnsupportedOperationException`
as a defense-in-depth guard for any code path that bypasses the parser.

## Relationship to `gl/material/`

`CgMaterialProperty` and `CgMaterialProperties` remain in the parent `gl/material/` package —
`CgMaterialProperty` is used directly by callers to set property values at runtime, and
`CgMaterialProperties` is a utility that depends on it.
