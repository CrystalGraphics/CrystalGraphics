# CrystalShader — Where We Are

> *Everything in this project exists to serve one goal: a Unity Shader Graph-equivalent for Minecraft,
> running on a real GL 3.x+ pipeline, with instancing as the default draw path from day one.*

---

## The Ladder (Manifesto Cornerstones)

| # | Cornerstone | Status |
|---|---|---|
| 0 | `cg_env.glsl` — object data contract | ✅ Complete |
| 1 | `CgShaderParser` — `.shader` file parsing | ✅ Complete |
| 2 | `CgMaterialShaderCompiler` — GLSL generation | ✅ Complete |
| 3 | `CgProgram` / shader linking + caching | ✅ Complete (waterfall: GL20 → ARB) |
| 4 | `CgMaterial` — user-facing material object | ✅ Complete |
| 5 | `CgShaderBuffer` + draw call integration | ✅ Complete (this session) |
| 6 | Variant system (`#pragma cg_feature` keyword variants) | ✅ Complete |
| 6a | Pass-based `.shader` format (`Pass {}` + `LightMode` tag) | ✅ Complete |
| 6b | Pass variant routing (`CgRenderPassVariant`, `bindForPass`) | ✅ Complete |
| 7 | Hot reload (`CgShaderHotReload`) | 🔲 Not started |
| 8 | Node graph IR + visual editor | 🔲 Far future |

---

## What's Been Built — and Why It Matters

### Foundation Layer (Cornerstones 0–3)

**`cg_env.glsl`** — The contract between engine and shader. Every `.shader` file gets this
injected automatically. Defines the SSBO/TBO dual path for per-instance data, all `CG_*`
macros (`CG_MATRIX_MVP`, `CG_OBJECT_TO_WORLD`, `CG_NORMAL_MATRIX`, `CG_OBJECT_CUSTOM0–3`),
frame uniforms (`cg_ViewMatrix`, `cg_ProjMatrix`, `cg_Time`, `cg_Resolution`), and vertex
attribute aliases (`cg_Position`, `cg_Normal`, `cg_TexCoord0`). Shader authors write to this
contract — never to raw GL binding points or magic uniforms.

**`CgShaderParser` + `CgParsedShader`** — Splits a `.shader` file into its five structural
sections: `Properties {}`, `struct v2f {}`, global declarations, `vertex()` body, `fragment()`
body. Pure Java, no GLSL grammar needed. Returns a value object consumed by the compiler.

**`CgMaterialShaderCompiler`** — Takes a `CgParsedShader` and emits two complete, compilable
GLSL source strings (vertex + fragment). Handles version gating (`#version 430 core` vs `#version
330 core`), SSBO/TBO path selection (`CG_USE_SSBO` define), Properties → `uniform` emission,
`v2f` struct → flat-packed `in/out` varying expansion, and the `main()` wrapper around the
user's `vertex()`/`fragment()` functions. This is the engine's core compilation intelligence.

**`CgShaderFactory` / `CgCoreShaderProgram` / `CgArbShaderProgram`** — The waterfall compile
pipeline: attempt GL20 first, fall back to ARB. Uniforms queried and cached by name. The shader
preprocessor (`CgShaderPreprocessor`) handles `#include`, `#pragma once`, and cycle detection
across all GLSL includes, including `cg_env.glsl`.

### Material Layer (Cornerstone 4)

**`CgMaterial`** — The single user-facing object. `CgMaterial.load("mod:shaders/foo.shader")`
is the entire API surface. Internally: parses the file, compiles via `CgMaterialShaderCompiler`,
links via `CgShaderFactory`, caches by resource path. Exposes `bind()` / `unbind()` / `applyBindings()`.
The `applyBindings()` call takes a fluent `CgShaderBindings` lambda for property uploads
(`b.set1f("_Alpha", 0.5f)`, `b.vec4("_Color", ...)`, `b.sampler2D(...)`). No variant
selection yet — single program per material in MVP.

**`CgMaterialRegistry`** — Cache keyed by `CgMaterialKey` (resource path). Prevents
redundant recompilation across frames. `deleteAll()` wired into `CgGraphicsLifecycle`.

### Buffer Data Layer (Cornerstone 5) — Completed This Session

This is the biggest unlock. Previously, per-instance data was wired manually with raw floats.
Now it's a fully typed, format-driven system.

**`CgGpuType`** — Enum of every GLSL-level compound type (`FLOAT`, `VEC2`, `VEC3`, `VEC4`,
`MAT3`, `MAT4`, `INT`, `UINT`, `BOOL`) with spec-derived std140/std430 alignment metadata baked
in. `MAT3` is correctly 48 bytes in both layouts (3 × 16-byte vec4-aligned columns).

**`CgBufferField` + `CgBufferFormat`** — Typed format descriptor for a GPU buffer record.
`CgBufferFormat.builder("name", STD430).mat4("modelMatrix").mat4("normalMatrix").vec4("custom0").build()`
auto-computes byte offsets with correct inter-field alignment padding. Value-equal by field
list + layout — the buffer-domain analogue of `CgVertexFormat`.

**`CgBufferWriter`** — Format-aware write head. `beginRecord()` pre-zeros the entire record
slot. Named writes (`w.mat4("modelMatrix", m)`, `w.vec4("custom0", r, g, b, 1f)`) write to
the correct offset by field name lookup — no manual offset tracking, no positional float
counting, no silent field misalignment. Unwritten fields stay zero automatically.

**`CgShaderBuffer`** — The SSBO/TBO GPU buffer. `CgBufferFormat` is mandatory — no raw
`floatPerRecord` int paths remain. Starts at capacity=1, grows automatically on `beginWrite(N)`.
`endRecord()` is the unified finalization call for both SSBOs and UBOs — no exception-throwing
override. Binding point is `final` (immutable after construction). `bind()` takes no args.

**`CgUniformBuffer`** — Single-block UBO (the `CgFrameBlock`). Format-aware. Write cycle:
`writer().reset()` → `beginRecord()` → named writes → `endRecord()` → `upload()`.

**`CgShaderBufferRegistry`** — Singleton registry for user-created shader buffers. Enforces
`bindingPoint >= CgBindingPoints.USER_START` (engine-reserved points 0 and 1 bypass it).
`deleteAll()` wired into `CgGraphicsLifecycle`.

**`CgRenderPipeline`** — The singleton orchestrator replacing `CgMaterialPipeline`. Owns the engine's two reserved buffers:
- `OBJECT_FORMAT` (STD430, 48 floats: `modelMatrix` + `normalMatrix` + `custom0–3`) — the
  per-object SSBO/TBO pre-populated before every draw batch
- `FRAME_BLOCK_FORMAT` (STD140, 38 floats: view + proj + time + resolution) — the per-frame UBO

`pipeline.getFrameData()` returns the `CgFrameData` mutable holder (replacing `CgFrameUniforms`).
Set its fields, call `pipeline.execute(partialTicks)` — zero allocation per frame.

### Wave 2 — Material System Completions (Cornerstones 1–6)

**Render State in `.shader` files** — The `RenderState {}` block is now a first-class section of the `.shader` format. `CgRenderStateParser` reads blend modes (with per-MRT index support), depth test/write, cull, stencil, alpha-test, and color mask. `CgMaterial.bind()` applies the compiled `CgRenderState` automatically; `unbind()` restores the previous state via `CgGlScope`. Shader authors no longer set GL state manually — it lives in the file.

```glsl
RenderState {
    Blend SrcAlpha OneMinusSrcAlpha
    ZTest LEqual
    ZWrite Off
    Cull Back
}
```

**MRT fragment outputs (`Outputs {}` block)** — Fragment shaders can now declare multiple render targets. The `Outputs {}` block in the `.shader` file names each target and its type. The compiler emits `layout(location=N) out vec4 name` declarations and adjusts the `fragment()` signature accordingly. This enables G-buffer passes and any multi-target rendering.

```glsl
Outputs {
    fragColor : vec4   // location 0
    fragNormal : vec4  // location 1
}
```

**`#pragma cg_feature` keyword variant system (Cornerstone 6)** — The full shader-feature-equivalent variant system is implemented and tested. Declaring, enabling, compiling, and caching keyword variants all work:

```glsl
// In .shader preamble:
#pragma cg_feature FOG
#pragma cg_feature ALPHA_TEST

// In body:
#ifdef FOG
    color = applyFog(color, depth);
#endif
```

```java
CgMaterial mat = CgMaterial.load("demo:shaders/terrain.shader");
mat.enableKeyword("FOG");      // next bind() compiles & caches the FOG variant
mat.disableKeyword("ALPHA_TEST");
mat.bind();                     // uses cached FOG-only program
```

- `CgStructureParser.parseFeaturePragmas()` — preamble-only scan, validates identifiers, max 8, strips pragmas from emitted GLSL
- `CompileConfig(Set<String> activeKeywords)` — compiler accepts keyword set, injects `#define NAME 1` in declaration order
- `ProgramKey(Set<String> keywords)` — Set-equality, order-independent variant cache key
- `CgMaterialShader` — shared shader asset object that owns `Map<ProgramKey, CgShader> programCache`; multiple `CgMaterial` instances pointing to the same `.shader` path share one cache
- `CgMaterialShaderRegistry` — path → `CgMaterialShader` deduplication; mirrors Unity's per-shader-asset model
- Lazy compile: variant is compiled on first `bind()` with that keyword set; `enableKeyword()` triggers recompile if parsed state is null
- Stale-keyword cleanup: `recompile()` filters `enabledKeywords` to only names still declared in the new source

**Tests**: 48 new tests across three test classes — `CgMaterialVariantTest`, `CgMaterialShaderCompilerVariantTest`, `CgShaderParserFeatureTest`. All passing, zero regressions.



### Wave 3 — Pass-Based Architecture + Parser Cleanup

This wave made the `.shader` format first-class multi-pass, aligned the compiler pipeline with Unity URP's per-pass model, and cleaned up four identified code smells.

**Pass-based `.shader` format** — `.shader` files now declare one or more `Pass {}` blocks, each with a `Tags { "LightMode" = "..." }` declaration. The `LightMode` tag is the sole declaration mechanism for pass routing (no boolean flags, no `[Shadow]` block syntax). The three canonical values are `"Forward"` (main color pass), `"ShadowCaster"` (shadow depth pass), and `"Depth"` (depth prepass, v2). This exactly mirrors Unity URP's `ShaderTagId` model and Godot's `PassMode` enum.

```glsl
Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        Blend SrcAlpha OneMinusSrcAlpha
        ZWrite Off
    }

    struct v2f { vec2 uv; };

    void vertex(out v2f o) {
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
        o.uv = cg_TexCoord0;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = texture(_MainTex, i.uv);
    }
}
```

**`CgParsedPass`** — The new per-pass record. Holds `passName`, `lightMode`, `v2fStructBody`, `globalDecls`, `vertexBody`, `fragmentBody`, `renderState`, and `fragOutput`. It is the compile unit for `CgMaterialShaderCompiler` — not `CgParsedShader`.

**`CgParsedShader` schema overhaul** — The single-pass flat schema (`vertexBody`, `fragmentBody`, etc.) is replaced: the record now carries `passes (List<CgParsedPass>)`, `castShadows (boolean)` (parsed from material-level `Tags { "CastShadows" = "Off" }`), `renderQueue`, `renderType`, `properties`, and `featureNames`. `getPassByLightMode(String)` and `getPassByName(String)` are the access methods.

**`CgShaderParser` rewritten** as a pass-aware dispatcher. Iterates `Pass {}` blocks, delegates per-pass structural parsing to `CgStructureParser`, and accumulates `CgParsedPass` instances. Material-level tags parsed by the new `CgTagParser`.

**`CgTagParser`** (package-private) — New class that owns `parseTopLevelTags`, `parseTagsBlock`, and `parseKeyValuePairs`. Extracted from `CgStructureParser` to give it a single responsibility (tag block parsing). Called by `CgShaderParser`.

**`CgMaterialShaderCompiler` updated** — `compile(CgParsedShader, CgParsedPass, Set<String>)` is the new signature: a pass is the compile unit. `compileShadowAutoGen(CgParsedShader, CgParsedPass forwardPass, ...)` generates the ShadowCaster program for materials without an explicit ShadowCaster pass. `isSimpleVertex(CgParsedPass)` detects position-only vertex bodies (no custom attributes, no `discard`) for the auto-gen decision.

**`CgMaterialShader.ProgramKey(passName, keywords)`** — The flat cache key replaces the old `VariantKey(keywords)`. A `ProgramKey("ShadowCaster", emptySet)` for auto-generated shadow programs is stored and retrieved through the exact same `getOrCompile(passName, keywords)` path as the Forward pass — no special-casing.

**Shadow auto-generation 4-step resolution ladder** (in `CgMaterialShader.recompile()`):

1. `castShadows == false` → no ShadowCaster program at all
2. Explicit `Pass { "LightMode" = "ShadowCaster" }` authored → compile it normally
3. No explicit ShadowCaster + `isSimpleVertex == true` → auto-generate: position-only light-space transform + normal bias + pancaking
4. No explicit ShadowCaster + `isSimpleVertex == false` → auto-generate: forward vertex body reused + shadow preamble injected

**`CgRenderPassVariant`** — New enum in `api/material/`. Values: `FORWARD("Forward")`, `SHADOW("ShadowCaster")`, `DEPTH("Depth")`. The `lightModeName()` accessor is the bridge to `.shader` LightMode tag values. `DEPTH` is included but silently no-ops in MVP; it avoids a breaking change when depth prepass is implemented.

**`CgMaterial.bindForPass(CgRenderPassVariant)`** — Routes to the correct compiled program. Keyword policy: `FORWARD` passes `enabledKeywords`; `SHADOW` and `DEPTH` pass `Collections.emptySet()`. Returns silently (no GL touch) when no program exists for the requested variant — matching Unity `DrawRenderers(ShaderTagId("ShadowCaster"))` skip semantics.

**`CgMaterial.getPassRenderState(CgRenderPassVariant)`** — Pure accessor returning the `CgRenderState` for the named pass. Callers (renderers) own applying and clearing the state — `bindForPass` binds shader program and UBO only, never touches GL render state.

**`CgMaterial.hasShadowCasterPass()` / `hasDepthPass()`** — Structural parse checks used at submit time to set pass-participation flags on render commands. `hasShadowCasterPass()` checks `castShadows` (covers both explicit and auto-gen cases); `hasDepthPass()` checks for an authored `"Depth"` pass specifically.

**Code cleanup (material-parse-cleanup plan)**:
- `CgMaterial.bind()` and `bindForPass()` both delegate to a private `doBind(CgShader, CgPassVariant)` — ~40 lines of duplicated bind logic removed
- `CgMaterialShader.recompile()` step 6 extracted into `attemptShadowAutoGen(...)` — ~40 inline lines condensed to one call, returns `false` to signal abort
- `CgTagParser` created (package-private); `CgStructureParser` stripped of `parseTopLevelTags`, `parseTagsBlock`, `parseKeyValuePairs`, and the two companion patterns
- All AGENTS.md docs updated for `api/material/`, `gl/material/`, `gl/material/parse/`

**Tests**: All existing tests pass (BUILD SUCCESSFUL, `:test`). The pass-based architecture is covered by the existing variant + compiler test suite.



Everything above runs on infrastructure that's been in place for a while:

- **GL abstraction layer** — FBO waterfall (Core/ARB/EXT), capability detection, external GL
  state capture via ASM coremod transformer (intercepts other mods' GL calls).
- **VAO/VBO framebufferPath** — streaming VBO strategies (sync ring / orphan / subdata waterfall),
  shared quad IBO, vertex array registry.
- **Mesh system** — `CgMesh` (static GPU upload + `drawDirect()` + `drawInstanced(N)`),
  `CgMeshBuilder` (procedural), `CgObjLoader` / `CgGltfLoader`.
- **Shader preprocessor** — `#include`, `#pragma once`, cycle detection. Used by both the
  raw shader API and the material compiler.
- **Batch render layer** — `CgBatchRenderer` + `CgBufferSource` + `CgRenderLayer` for
  UI/text quads. Separate from the material system.
- **Font/text system** — MSDF glyph generation, atlas packing, async cache, render path.
- **GLSL standard library** — `math.glsl`, `vector.glsl`, `color.glsl`, `uv.glsl`, `noise.glsl`.

---

## The Current Capability in One Picture

A `.shader` file today:

```glsl
#type spatial

Properties {
    _Color : vec4 = (1.0, 1.0, 1.0, 1.0)
}

struct v2f {
    vec3 worldPos;
    vec2 uv;
};

void vertex(out v2f o) {
    o.worldPos = (CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0)).xyz;
    o.uv = cg_TexCoord0;
    gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
}

void fragment(in v2f i, out vec4 fragColor) {
    fragColor = _Color * CG_OBJECT_CUSTOM0;  // per-instance tint from custom slot
}
```

Java side — same material, both draw paths:

```java
CgRenderPipeline pipeline = CgRenderPipeline.getInstance();
CgMaterial material = CgMaterial.load("demo:shaders/tinted.shader");

// --- Every frame — populate frame data ---
CgFrameData fd = pipeline.getFrameData();
fd.viewMatrix.set(view); fd.projMatrix.set(proj);
fd.timeSecs = elapsed; fd.viewportW = w; fd.viewportH = h;
fd.deriveFromViewMatrix();

// Submit N render commands
for (int i = 0; i < N; i++) {
    CgRenderCommand cmd = pipeline.acquireCommand();
    cmd.mesh = mesh; cmd.material = material;
    cmd.modelMatrix.set(transforms[i]);
    cmd.custom0.set(tints[i]);
    cmd.worldAabb[0] = ...; // set AABB
    pipeline.submit(cmd);
}

// Execute (called by CgRenderHook per frame)
pipeline.execute(partialTicks);
```

This is Cornerstone 5 of the manifesto working. **No `#ifdef INSTANCED`. No variant authored. One shader file.**

---

## What's Not Sharp Yet — Known Rough Edges

These aren't blockers, but they're areas that will bite us as the system grows:

1. **`CgInstanceRenderer` uses the old VBO divisor path.** The manifesto explicitly
   rejects this approach (burns vertex attribute slots, can't be read from fragment shaders,
   forces VAO rebind on mode switch). It should be updated to drive off `CgMaterialPipeline.objectBuffer()`
   instead. Until then, the batch render layer and the material system are two parallel instancing
   stacks that don't compose.

2. **`CgMaterial.bind()` doesn't automatically bind the pipeline's object buffer and frame UBO.**
   The harness manually calls `pipeline.beginFrame()` and then `material.bind()` as two separate
   steps. Eventually `material.bind()` should assert that the pipeline is active (or auto-activate
   it) so the contract is airtight.

3. **No `writeSingle(Matrix4f)` convenience on the object buffer.** The format spec shows
   `objectBuffer.writeSingle(modelMatrix)` for non-instanced draws. Right now a caller must do
   `beginWrite(1)` → `beginRecord()` → named writes → `endRecord()` → `endWrite()` even for one
   object. A convenience wrapper would clean up the most common case.

4. **`CgMaterialPipeline` is a global singleton accessed by static `getInstance()`.** Fine for
   now, but worth watching as multiple rendering contexts (e.g. world + UI overlay) could need
   different frame state. Not a problem today.

5. **The TBO path (`cg_FetchObjectData`) reads 12 texels per instance.** This is correct for
   the current `OBJECT_FORMAT` (48 floats = 12 vec4 texels). But the TBO binding logic in
   `CgTextureBuffer` just got fixed (was defaulting to binding 0, conflicting with texture unit
   0). Needs integration testing on hardware that hits the TBO path (GL < 4.3).

6. **Shadow auto-gen compiles one program per material** even when their generated GLSL is
   identical (simple-vertex case). The Godot shared shadow program optimization is documented
   in `render-pipeline-curation.md` Section 4 but deferred to v2. The GPU driver's shader cache
   provides the practical deduplication in the meantime.

---

## Next Steps — Ordered by Value

These are ordered by how much they unlock for the grand goal. Not all need to happen before
the next, but the critical path runs top to bottom.

### Sharpen First (Before Adding More)

**A. `CgMaterial.bind()` pipeline integration** *(1–2 days)*
Make `material.bind()` the single call that activates everything: frame UBO, object buffer
binding. Callers shouldn't need to manually orchestrate pipeline + material separately. This
is the "renderer.draw(mesh, mat, transforms)" API from the manifesto.

**B. `writeSingle(Matrix4f)` convenience** *(hours)*
`pipeline.objectBuffer().writeSingle(modelMatrix)` for the non-instanced case. Under the
hood it's just `beginWrite(1)` → `beginRecord()` → `mat4("modelMatrix", m)` → `endRecord()` →
`endWrite()`. Small addition, but cleans up the most common usage significantly.

**C. `CgInstanceRenderer` migration to SSBO/TBO path** *(1–3 days)*
Replace the VBO divisor instancing with `CgRenderPipeline.objectBuffer()`. This is the
architectural reconciliation that makes the batch render layer and the material system the same
system. Existing `CgRenderLayer` consumers (UI, text layers) are not affected — they use
`CgBatchRenderer`, not `CgInstanceRenderer`.

### Advance the Material System

**D. ~~Render state in `.shader` files~~** ✅ Done (Wave 2)

**E. ~~`#pragma cg_feature` keyword variant system~~** ✅ Done (Wave 2, Cornerstone 6)

**F. ~~MRT fragment outputs~~** ✅ Done (Wave 2)

**G. ~~Pass-based `.shader` format + `CgRenderPassVariant`~~** ✅ Done (Wave 3)

**H. Material property defaults applied at load time** *(hours)*
`Properties { _Color : vec4 = (1.0, 1.0, 1.0, 1.0) }` — the default value is currently
parsed and stored but not applied. At `CgMaterial.load()` time, defaults should be uploaded
automatically so a freshly loaded material is visually correct without any `applyBindings()` call.

**I. ~~Render command queue + forward renderer~~** ✅ Done (Wave 4)
`CgRenderCommand`, `CgRenderCommandPool`, `CgSortKey`, `CgRenderCommandQueue`, `CgForwardRenderer`,
`CgTransparentRenderer`, `CgRenderPipeline` — the submission + sort + auto-instancing loop.
`CgMaterialPipeline`/`CgFrameUniforms` replaced by `CgRenderPipeline`/`CgFrameData`.

**J. Shadow pass** *(3–5 days, depends on I)* ✅ Depends on I, which is now done.
`CgShadowRenderer`, shadow fields in `CgFrameData`, `CgRenderPipeline.FRAME_BLOCK_FORMAT`
extensions (`cg_ShadowViewProjMatrix`, `cg_LightDirection`, `cg_ShadowParams`),
`CgBindingPoints.SHADOW_MAP_UNIT`. Shadow auto-gen in `CgMaterialShader.recompile()` is
already complete — only the renderer orchestration and frame UBO wiring remain.

**K. CgRenderHook wiring** *(hours)* — `CgRenderHook` Mixin that drives `executeOpaquePass` /
`executeTransparentPass` / `endFrame` from `RenderWorldLastEvent`. Needs wiring into the mixin
JSON. `CgFrameData.worldTimeSampler` requires the MC `theWorld.getTotalWorldTime()` lambda.

**L. Hot reload (`CgShaderHotReload`)** *(2–3 days, dev-mode only)*
File watcher on `.shader` assets. On change: re-parse → re-compile → swap `CgShader` in the
`programCache` in-place, without reconstructing the material object. Essential for the
shader authoring workflow that the node graph will depend on. Dev-mode only — no prod cost.

### The Endgame

**M. Node graph IR** *(weeks)*
`CgShaderNode` (inputs, outputs, namespaced GLSL body), `CgShaderGraph` (DAG),
`CgGraphCompiler` (topological sort → GLSL snippet emission → `.shader` file output).
Every node is a GLSL function. The graph compiler wires outputs to inputs and emits a complete
`.shader` file. The visual editor is a frontend to this IR — it can be built independently
once the IR is stable.

The `.shader` format we have today IS the node graph's output format. A hand-written `.shader`
is a single-node graph. The node graph just produces the same format automatically.

---

## Where We Stand in One Sentence

> The engine speaks `.shader` with first-class `Pass {}` blocks. The pipeline speaks `CgBufferFormat`.
> `CgRenderPipeline` owns the command queue, frame UBO, object buffer, and pass renderers.
> Submit commands via `acquireCommand()` + `submit()` + `execute(partialTicks)`.
> The forward renderer auto-instances same-material+mesh runs. Shadow programs are auto-generated.
> Next: wire `CgRenderHook`, then tackle the shadow pass.
