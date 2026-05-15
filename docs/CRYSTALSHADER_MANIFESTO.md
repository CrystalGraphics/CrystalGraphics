# The CrystalShader Manifesto
### A Grounded Architecture for a Node-Based Shader Graph Targeting Minecraft Cross-Version

---

## I. The Vision, Stated Precisely

We are building a **shader authoring system** with three layers that must coexist seamlessly:

1. **The surface layer** — A `.shader` file format that a human writes: combined vertex + fragment in one file, Properties as typed uniforms, v2f structs, pure GLSL inside. Easy to port from MC 1.20.1 or any GLSL-based game.

2. **The engine layer** — A compiler pipeline (`CgShaderCompiler`) that reads that file, injects engine-provided context (matrices, per-instance data, GL version guards), and produces one or more compiled `CgProgram` variants for instanced and non-instanced draw calls.

3. **The graph layer** — A future node graph editor whose nodes compile down to `.shader` files (or fragments thereof) that feed into layer 2. The surface layer IS the graph's output format.

Every architectural decision must serve all three layers simultaneously.

---

## II. Prior Art and What We Learn From Each

### Unity ShaderLab
**Why it's good:** Single file ownership model. Properties block is the canonical "material interface." `#pragma multi_compile_instancing` + `UNITY_INSTANCING_BUFFER_*` macros give you one source file that the compiler expands into two variants (instanced / non-instanced). The `v2f` struct is idiomatic C-struct interpolant passing.

**Why it's not quite right for us:** It wraps GLSL in CG/HLSL, adds heavy semantic annotations (`SV_POSITION`, `TEXCOORD0`), and the macro system (`UNITY_SETUP_INSTANCE_ID`) is opaque boilerplate that obscures what's happening. We want engineers to be able to read their shader and understand exactly what the GPU is executing.

**What we steal:** The file structure. The Properties ownership model. The single-file-two-stage paradigm. The `v2f` naming convention.

### Godot Shading Language
**Why it's good:** `shader_type spatial;` + `void vertex() {}` + `void fragment() {}` in one file. Output is assigned to named built-ins (`VERTEX`, `ALBEDO`, `NORMAL`) — no return types, no semantics. `varying` keyword for vert→frag passing. Extremely close to GLSL. The per-instance uniform system is transparent — the engine handles it, the author just declares `instance uniform vec4 _Color;`. Has `#include` and preprocessor directives.

**Why it's not quite right for us:** The output-to-builtin model (`ALBEDO = ...`) works for PBR materials but doesn't generalize to arbitrary shader programs (we need general-purpose shaders, not just surface shaders). We are not building a renderer abstraction — we are building a shader system.

**What we steal:** The `varying` keyword syntax. The `#include` / preprocessor model. The `shader_type` declaration. The clean one-file mental model.

### Google Filament Material System
**Why it's the best real-world reference:** Filament solves exactly our core problem at production scale. One `.mat` file → `matc` compiler → multiple platform/variant binary packages. The user writes `void material(inout MaterialInputs m) {}` and `void materialVertex(inout MaterialVertexInputs m) {}`. The engine generates everything else — frame uniforms, object uniforms, instancing UBO arrays, variant explosion. Most importantly: **instance data is always fetched by `gl_InstanceID` from an `objectUniforms` array** even in non-instanced draws (it just reads index 0). This means zero branching — the same GLSL source works for both.

**What we steal:** The `matc` compilation model. The per-instance data buffer indexed by `gl_InstanceID`. The `variables` (interpolants) as declared fields in the material header, not free-floating `varying` declarations. The concept of engine-provided includes (`cg_env.glsl`).

### Three.js / ShaderFrog Hybrid Graph
**Why it matters for our node graph goal:** ShaderFrog parses GLSL source into ASTs via a PEG grammar, then resolves node connections by renaming and merging variable scopes across multiple GLSL snippets. This is the correct architecture for a node graph compiler — not string concatenation, not template expansion, but **AST manipulation and scope merging**. Each graph node is a GLSL snippet with named inputs and outputs. The compiler topologically sorts nodes and stitches their code together.

**What we steal:** The AST-based composition model. The insight that every node is a GLSL function, and the graph compiler just wires function calls.

---

## III. The Core Principles (Non-Negotiable)

**1. One file, two stage functions, shared interpolants.**
A `.shader` file contains a `Properties` block and two GLSL functions: `vertex()` and `fragment()`. Data flows from vertex to fragment through a user-declared `v2f` struct. This is the entire programming model.

**2. GLSL inside, always.**
Inside `vertex()` and `fragment()`, it is standard GLSL. No CG semantics. No HLSL intrinsics. The engine's contributions (matrices, instance data) are injected as a standard `#include` at the top. Port a shader from MC 1.20.1? Change the include and fix the uniform names. Done.

**3. One shader per material. Zero variants authored.**
The author writes one shader. The `CgShaderCompiler` produces as many variants as needed (instanced, non-instanced, shadow pass, depth-only, etc.) by injecting different preambles. The author never writes `#ifdef INSTANCED`.

**4. Per-instance data via SSBO (GL 4.3+) with TBO fallback (GL 3.1+).**
This is the "straight from the get go" decision. Instancing is not a feature added on top — it is the default draw path. Every draw call is potentially instanced. The instance data buffer is always bound. Non-instanced draws are just instanced draws with N=1. This eliminates every shader variant and every "is this instanced?" branch in the engine.

**5. Scope creep is death.**
We are NOT building a PBR material system. We are NOT building automatic lighting. We are building: (a) a shader file format, (b) a compiler that produces GL programs from those files, (c) a runtime that binds the right data. That's it for V1.

---

## IV. The File Format

Directly inspired by Godot + Unity, stripped of everything non-essential:

```glsl
// terrain.shader

#type spatial

Properties {
    _MainTex  : sampler2D
    _Color    : vec4      = (1.0, 1.0, 1.0, 1.0)
    _Roughness: float     = 0.5
}

// Vertex-to-fragment interpolants
struct v2f {
    vec2 uv;
    vec3 worldNormal;
};

void vertex(out v2f o) {
    // CG_MATRIX_MVP, CG_OBJECT_TO_WORLD injected by engine include
    gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
    o.uv        = cg_TexCoord0;
    o.worldNormal = mat3(CG_OBJECT_TO_WORLD) * cg_Normal;
}

void fragment(in v2f i, out vec4 fragColor) {
    vec4 tex = texture(_MainTex, i.uv);
    fragColor = tex * _Color;
}
```

**Key decisions visible here:**
- `#type spatial` — the shader type declaration (spatial = 3D geometry, future: canvas = 2D, compute = compute)
- `Properties {}` — typed, named, optionally default-valued. These become both the GLSL uniform declarations AND the material inspector interface.
- `v2f` — plain GLSL struct. The compiler knows to declare it as interpolants between stages.
- `vertex()` and `fragment()` — not `main()`. The compiler wraps these in real `main()` functions with the correct preamble.
- `cg_Position`, `cg_Normal`, `cg_TexCoord0` — engine-provided vertex attribute aliases (injected via `cg_env.glsl`). Maps to whatever attrib location CrystalGraphics uses.
- `CG_MATRIX_MVP` etc. — engine-provided uniforms from the frame/object buffer. Not hidden, just declared in the include.
- **No `#ifdef INSTANCED` anywhere.** The engine handles it.

---

## V. The Instancing Architecture (The Core Technical Decision)

This is where we diverge from every "add instancing later" approach.

### The Problem
We want `mat4 modelMatrix` to work in a shader whether you draw 1 object or 10,000 objects. Unity solves this with macro-expanded UBO arrays + `gl_InstanceID`. Godot solves it with per-instance uniforms backed by a UBO. Filament solves it with `objectUniforms.a[gl_InstanceID]`.

### Our Solution: SSBO-First, TBO Fallback

Every shader receives per-object data via a struct in a storage buffer:

```glsl
// Injected by engine into every shader's preamble (cg_env.glsl)

struct CgObjectData {
    mat4 modelMatrix;
    mat4 normalMatrix;
    // + 8 vec4 slots for per-instance material overrides
};

#if CG_GL_VERSION >= 430
// SSBO path (GL 4.3+)
layout(std430, binding = 0) readonly buffer CgShaderBuffer {
    CgObjectData cg_Objects[];
};
#define CG_OBJECT_DATA cg_Objects[gl_InstanceID]
#else
// TBO path (GL 3.1+)
uniform samplerBuffer cg_ObjectTBO;
CgObjectData cg_fetchObject(int id) { /* texelFetch 4x for mat4 */ }
#define CG_OBJECT_DATA cg_fetchObject(gl_InstanceID)
#endif

// Convenience macros used in shaders
#define CG_OBJECT_TO_WORLD   CG_OBJECT_DATA.modelMatrix
#define CG_NORMAL_MATRIX     CG_OBJECT_DATA.normalMatrix
#define CG_MATRIX_MVP        (CG_FRAME_PROJ * CG_FRAME_VIEW * CG_OBJECT_TO_WORLD)
```

**Result:** A shader that writes `CG_MATRIX_MVP * vec4(cg_Position, 1.0)` works for:
- `glDrawArrays()` — single draw, `gl_InstanceID = 0`, reads slot 0
- `glDrawArraysInstanced(N)` — N instances, each reads its own slot
- Indirect draws — same

The `CgShaderBuffer` is pre-populated by the engine before each draw call batch. For non-instanced draws, the buffer has 1 element. For instanced draws, it has N. **The shader never knows which case it is.**

### Why not UBO arrays?
UBOs have a guaranteed minimum of only 16KB — that's ~100 mat4s. TBOs are available from GL 3.1 and have a minimum of 64KB (in practice, hundreds of MB). SSBOs from GL 4.3 are bound only by available VRAM. Given we're targeting 1.7.10 with a goal of GL 3.x+ capability, TBO is our floor and SSBO is our ceiling.

### Why not instanced vertex attributes (VBO divisor)?
Because it requires changing the VAO every time you switch between instanced and non-instanced rendering. It also burns vertex attribute slots (max 16 on most hardware, and a mat4 takes 4). More importantly, it cannot be accessed by index from a fragment shader or future compute shader. SSBO/TBO can.

---

## VI. The Compilation Pipeline

```
terrain.shader
      │
      ▼
┌─────────────────────┐
│   CgShaderParser    │  Splits file into: Properties block, v2f struct,
│                     │  vertex() body, fragment() body
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│  CgShaderCompiler   │  For each requested variant:
│                     │  1. Emit GLSL version header (#version 430 or 310 es)
│                     │  2. #include "cg_env.glsl"  (engine-provided context)
│                     │  3. Emit Property uniforms
│                     │  4. Emit v2f struct as in/out variables
│                     │  5. Emit vertex main() wrapping user vertex()
│                     │  6. Emit fragment main() wrapping user fragment()
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│   CgProgram         │  A compiled GL program (vertex + fragment linked)
│   (vertex + frag    │  Knows its uniform locations.
│    linked, cached)  │  Knows its Properties layout.
└─────────────────────┘
```

**Variants generated automatically (not authored):**
- `VARIANT_DEPTH` — depth-only pass, fragment replaced with trivial output
- `VARIANT_SHADOW` — shadow pass
- Future: `VARIANT_PICKING`, `VARIANT_WIREFRAME`

The author never sees variants. The `CgMaterial` object holds one `CgProgram` per variant and selects the right one per render pass.

---

## VII. The Material Runtime

```java
CgMaterial mat = CgMaterial.load("terrain.shader");

// Set properties (typed, named)
mat.setTexture("_MainTex", myTexture);
mat.setVec4("_Color", 1f, 0.8f, 0.6f, 1f);
mat.setFloat("_Roughness", 0.3f);

// Render — same call regardless of instancing
renderer.draw(mesh, mat, transforms);            // 1 object
renderer.drawInstanced(mesh, mat, transforms, 1000);  // 1000 objects
```

The `renderer.draw()` and `renderer.drawInstanced()` paths differ only in how they populate `CgShaderBuffer` and what `glDraw*` call they issue. The material and shader are identical.

---

## VIII. The Node Graph Connection

The node graph is a **visual editor for the `.shader` file format**. Not an abstraction layer on top of it — a visual representation of it. Every node compiles to a GLSL function. The graph compiler:

1. Topologically sorts nodes (dependency graph)
2. For each node, emits its GLSL function body with a namespaced prefix to avoid collisions
3. Wires output variables of upstream nodes to input parameters of downstream nodes
4. The final "Output" node is the `fragment()` / `vertex()` function body
5. Properties declared in nodes bubble up to the `.shader` Properties block

```
┌──────────────┐     ┌──────────────┐     ┌────────────────┐
│  SampleTex   │────▶│  Multiply    │────▶│  Output Node   │
│  _MainTex    │     │  × _Color    │     │  fragColor =   │
│  → vec4 col  │     │  → vec4 out  │     │  multiply_out  │
└──────────────┘     └──────────────┘     └────────────────┘
```

Compiles to:
```glsl
// node_sampleTex
vec4 node_sampleTex_col = texture(_MainTex, i.uv);

// node_multiply
vec4 node_multiply_out = node_sampleTex_col * _Color;

// output
fragColor = node_multiply_out;
```

The graph has **two domains**: vertex and fragment. Nodes exist in one domain. A `Varying` node explicitly passes data from vertex to fragment domain, generating an entry in the `v2f` struct. This maps 1:1 to how the text-based format works.

**Critical consequence:** The text-based `.shader` format is not a "lower-level alternative" to the node graph. It IS the node graph's serialization format, just written by hand. A `.shader` file you author can be loaded into the node graph editor and displayed as nodes. Nodes you connect get saved as a `.shader` file. No separate formats.

---

## IX. The Roadmap — Ordered Cornerstones

### Cornerstone 0: `cg_env.glsl` and the Object Data Contract *(foundation for everything)*
Define `CgObjectData` struct, the SSBO/TBO dual-path, all `CG_*` matrix macros, and vertex attribute aliases (`cg_Position`, `cg_Normal`, etc.). This single include file is the contract between engine and shader. **Nothing else can be built without this.**

### Cornerstone 1: `CgShaderParser`
A parser that splits a `.shader` file into its constituent parts: Properties list, v2f struct source, vertex body, fragment body. No GLSL parsing needed at this stage — just structural extraction by delimiter scanning. Pure Java, no dependencies.

### Cornerstone 2: `CgShaderCompiler`
Takes parser output + a target variant enum + GL version → emits two complete GLSL source strings (vertex, fragment). Handles:
- Emitting Properties as `uniform` declarations
- Expanding v2f into `out` (vertex) and `in` (fragment) declarations
- Wrapping vertex/fragment bodies in `void main()` with the right preamble
- Version gating (SSBO vs TBO path selection)

### Cornerstone 3: `CgProgram` and `CgProgramCache`
Compiles and links the two GLSL strings into a GL program. Caches programs by content hash — recompilation only on actual source change. Queries and caches uniform locations. On hot reload (dev mode), recompiles and swaps transparently.

### Cornerstone 4: `CgMaterial`
The user-facing object. Holds a `CgProgram` per variant. Owns a typed property value store. Implements `bind(variant)` which sets the active program and uploads all property uniforms. This is the "one shader, any draw call" user API.

### Cornerstone 5: `CgShaderBuffer` and draw call integration
The SSBO/TBO object data buffer management. Integrates with `CgInstanceRenderer` to populate buffer slots before each draw. Defines the engine-side contract for how `CG_MATRIX_MVP` gets its data.

### Cornerstone 6: The Variant System
Formal definition of `CgShaderVariant` enum (STANDARD, DEPTH, SHADOW). The compiler's variant-specific injections (e.g., DEPTH variant's fragment is always `fragColor = vec4(gl_FragCoord.z)`). `CgMaterial` selects variant based on current render pass.

### Cornerstone 7: `CgShaderHotReload` (dev mode only)
File watcher on `.shader` files. On change: re-parse → re-compile → swap `CgProgram` in `CgMaterial` in-place. Essential for shader authoring workflow.

### Cornerstone 8: Node Graph IR and Compiler *(the endgame)*
Define `CgShaderNode` (inputs, outputs, GLSL body template), `CgShaderGraph` (directed acyclic graph), `CgGraphCompiler` (topological sort → GLSL snippet emission → `.shader` file generation). The visual editor is a frontend to this IR.

---

## X. What We Are NOT Building (Scope Guard)

To be explicit, because scope creep will kill this project:

- ❌ **Automatic PBR lighting model** — We provide matrices and instance data. Lighting math is the shader author's job.
- ❌ **SPIR-V / Vulkan compilation** — Not now. The architecture doesn't preclude it, but it's not on this roadmap.
- ❌ **Material inspector UI** — The Properties block defines the interface; the UI is a future concern.
- ❌ **Cross-compilation to HLSL** — Same reasoning as SPIR-V.
- ❌ **Automatic shader LOD** — Not in scope.

---

## XI. The MC 1.7.10 ↔ 1.20.1 Portability Contract

A shader from MC 1.20.1's core shader system (`.vsh` / `.fsh`) should be portable to our format in under 10 minutes. The delta:

| MC 1.20.1 `.vsh` | Our `.shader` |
|---|---|
| `uniform mat4 ModelViewMat;` | `CG_MATRIX_MVP` (engine-provided) |
| `in vec3 Position;` | `cg_Position` (engine alias) |
| `in vec2 UV0;` | `cg_TexCoord0` (engine alias) |
| `out vec2 texCoord0;` | field in `v2f` struct |
| `void main() { ... }` | `void vertex(out v2f o) { ... }` |
| Separate `.fsh` file | `void fragment(in v2f i, ...) { ... }` below vertex |

The MC shader's actual math (noise functions, transformations, color operations) stays 100% unchanged. You are just changing the wiring, not the logic.

---

*This manifesto defines the project. Every implementation decision can be evaluated against it: does it serve the three layers? Does it keep GLSL visible? Does it treat instancing as the default, not the exception? Does it keep the node graph path open? The first commit that matters is `cg_env.glsl`. Everything else is built on top of it.*
