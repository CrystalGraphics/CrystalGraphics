---

# THE GRAND GOAL — READ THIS FIRST
> **Every line of code in this repository exists to serve one end goal:**
> A **node-based shader graph for Minecraft (cross-version: 1.7.10 and 1.20.1)** — like Unity's Shader Graph, but staying true to GLSL, running on a modern GL 3.x+ pipeline, with instancing as the default draw path from day one.
>
> The full architecture, principles, file format, instancing strategy, compilation pipeline, and ordered roadmap are defined in the manifesto. **Read it before making any rendering or shader-related decision.**
>
> 📄 **[CrystalShader Manifesto](docs/CRYSTALSHADER_MANIFESTO.md)**

---

# CrystalGraphics — Agent Knowledge Base

**Project type**: Java 8 library | **Target**: Minecraft 1.7.10 Forge mods | **Graphics**: OpenGL via LWJGL 2.9.3 | **Build**: Gradle + GTNH convention plugin

## TO BUILD
```bash
./gradlew.bat compileJava   # ~60 seconds — DO NOT kill early, DO NOT run in parallel
./gradlew.bat test          # pure-logic unit tests (no GL context required)
```

## Render Testing (GL Debug Harness)

For any work that touches rendering, shaders, FBOs, text, or atlas generation — **do not test via Minecraft**. Use the standalone GL debug harness instead. It boots in seconds, requires no Minecraft context, and produces PNG artifacts for visual verification.

**Location**: `gl-debug-harness/` (subproject of this repo, or sibling at `../gl-debug-harness/`)  
**Full docs**: `gl-debug-harness/AGENTS.md`

```bash
# List all available scene modes
./gradlew :gl-debug-harness:runHarness --args="--list"

# Most common scenes
./gradlew :gl-debug-harness:runHarness --args="--mode=forward-renderer"       # CgRenderPipeline end-to-end
./gradlew :gl-debug-harness:runHarness --args="--mode=material-dual-path"     # CgMaterial shader compilation
./gradlew :gl-debug-harness:runHarness --args="--mode=instancing-test"        # Instanced draw
./gradlew :gl-debug-harness:runHarness --args="--mode=attached-buffer-stress" # SSBO/TBO attach
./gradlew :gl-debug-harness:runHarness --args="--mode=mesh-test"              # CgMeshLoader
./gradlew :gl-debug-harness:runHarness --args="--mode=atlas-dump"             # Glyph atlas
./gradlew :gl-debug-harness:runHarness --args="--mode=text-3d"                # Full text pipeline
./gradlew :gl-debug-harness:runHarness --args="--mode=capability-report"      # GL capability probe

# Outputs land in gl-debug-harness/harness-output/{scene}/
```

**Key rules when authoring harness scenes** (from `gl-debug-harness/AGENTS.md`):
- Never call raw GL — use `CgVertexArray`, `CgStreamBuffer`, `CgTexture`, `CgFrameBuffer`, etc.
- Implement `HarnessSceneLifecycle` (managed, single frame) or `InteractiveSceneLifecycle` (loop + camera).
- Register the new scene in `SceneRegistry.createDefault()`.
- Use `ArtifactService.requestCapture("suffix")` for interactive captures; `ScreenshotUtil` for managed ones.
- GL state cleanup after `render()` is automatic — don't do it yourself.

---

## Start Here By Task

| I need to… | Jump to section | Primary package guide |
|---|---|---|
| Write or load a `.shader` material | [CrystalShader Pipeline](#crystalshader-material-pipeline) | `api/material/AGENTS.md` |
| Submit geometry to the render pipeline | [CgRenderPipeline Usage](#cgrenderPipeline-per-frame-usage) | `api/render/AGENTS.md` |
| Create a framebuffer (FBO) for post-processing | [Framebuffers](#framebuffers) | `api/framebuffer/AGENTS.md` |
| Load or build a 3D mesh | [Meshes](#meshes) | `gl/mesh/AGENTS.md` |
| Create or load a texture | [Textures](#textures) | `api/texture/AGENTS.md` |
| Bind GPU buffers (SSBO/TBO/UBO) to a material | [Shader Buffers](#shader-buffers) | `gl/buffer/shader/AGENTS.md` |
| Save and restore GL state across a pass | [GL State Save/Restore](#gl-state-saverestore) | `gl/state/AGENTS.md` |
| Render text on screen | [Font/Text System](#fonttext-system) | `docs/font/README.md` |
| Work on batch/UI/2D layer rendering | [Batch Render Layer](#batch-render-layer-system) | `gl/render/AGENTS.md` |
| Load a resource file (shader source, config, image) | [Resource I/O](#resource-io--cgio-and-cgtextureio) | `util/io/CgIO` |
| Test rendering without Minecraft | [Render Testing](#render-testing-gl-debug-harness) | `gl-debug-harness/AGENTS.md` |

---

## Project Philosophy

- **Fail Fast**: throw exceptions for unsupported capabilities; never silently degrade
- **Multi-Mod First**: other mods will mutate GL state; design for cooperation, not control
- **Hardware Fragmentation**: three incompatible GL families exist on real hardware — Core GL30, ARB, EXT; each requires a different method signature (see `gl/framebuffer/AGENTS.md`)
- **Waterfall Fallback**: capability selection always follows Core GL30 > ARB > EXT
- **Angelica Coexistence**: when Angelica shader mod is present, CrystalGraphics runs in gap-only redirect mode

---

## Global Coding Rules

These rules apply everywhere. All agents must internalize them.

**Public API first** — always use the highest abstraction layer available. `CgMaterial.load()` not `CgShaderFactory.fromSource()`; `CgMeshLoader.load()` not `GL15.glGenBuffers()`. Check package guides to find what already exists before writing raw GL.

**GL-thread rule** — all GL object creation, upload, and deletion must happen on the GL thread within an active context. This includes: `CgFrameBuffer.create()`, `CgMesh.upload()`, `CgTexture2D.create()`, shader compilation. Violations produce silent garbage or driver crashes.

**Vertex data via `CgVertexWriter`** — never write vertex bytes via raw `ByteBuffer.putFloat()`. All vertex packing goes through `CgVertexWriter.forBuffer()`. Index buffer `putShort()`/`putInt()` is the only exception.

**Java 8 only** — lambdas and method references are encouraged. No `var`, no modules, no records.

### Lombok (use in all new code)

| Annotation | When to use |
|---|---|
| `@Data` | Simple POJOs with all fields in equals/hashCode/toString |
| `@Getter` + `@RequiredArgsConstructor` | Immutable classes — constructor for all `final` fields, no setters |
| `@Builder` | Classes with 4+ parameters or many optional fields |
| `@Value` | Fully immutable data carriers (final class, all fields private final) |
| `@Slf4j` | Logger field generation |
| `@EqualsAndHashCode(callSuper = true)` | Always on subclasses |

---

# CrystalShader Material Pipeline

The primary authoring surface of this repo. Most feature work touches these systems.

## The `.shader` File Format

**Reference file**: `src/main/resources/assets/crystalgraphics/shaders/example.shader`
→ Read this file first. Every feature is documented inline with comments.

Structural skeleton (all sections are optional except `#type` and at least one `Pass`):

```glsl
#type spatial
#pragma cg_feature RECEIVE_SHADOWS    // compile-time keyword; max 8 per shader
#pragma cg_feature FOG_ON

Tags { "RenderType" = "Opaque" }      // controls shadow auto-generation
Queue = "Geometry"                    // Background|Geometry|AlphaTest|Transparent|Overlay

Properties {
    _MainTex   ("Main Texture", sampler2D) = "white"
    _Color     ("Tint Color",   color)     = (1, 1, 1, 1)
    _Roughness ("Roughness",    float)     = 0.5
}

struct v2f { vec2 uv; vec3 worldPos; };

Pass {
    Tags { "LightMode" = "Forward" }  // Forward (default) | ShadowCaster | Depth
    RenderState {
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA
        DepthTest LEQUAL
        DepthWrite ON
        Cull BACK
    }
    void vertex(out v2f o) { ... }
    void fragment(in v2f i, out vec4 fragColor) { ... }
}
// ShadowCaster pass is auto-generated for Opaque + castShadows=true + queue < 3000
```

## cg_env.glsl — The Backbone

`src/main/resources/assets/crystalgraphics/shaders/env/cg_env.glsl` is the foundation every `.shader` file stands on. The material compiler **automatically `#include`s it** into every generated vertex and fragment stage — you never include it manually in `.shader` files. Raw `CgShader` users must `#include "crystalgraphics:shaders/env/cg_env.glsl"` explicitly if they need its symbols.

It provides two distinct layers: per-frame global data and per-instance object data.

### Frame Data — `CgFrameBlock`

A `layout(std140) uniform CgFrameBlock` wired post-link by the engine. Available in every stage, every pass:

| GLSL name | Type | Content |
|---|---|---|
| `cg_ViewMatrix` | `mat4` | Camera view matrix |
| `cg_ProjMatrix` | `mat4` | Projection matrix |
| `cg_Time` | `vec4` | `(t/20, t, t×2, t×3)` — seconds |
| `cg_Resolution` | `vec2` | Viewport size in pixels |

Convenience macros over the frame block:

| Macro | Expands to | Use |
|---|---|---|
| `CG_TIME` | `cg_Time.y` | Raw seconds — the one you want 99% of the time |
| `CG_TIME_VEC4` | `cg_Time` | Full 4-component time vector |
| `CG_RESOLUTION` | `cg_Resolution` | Viewport dimensions in pixels |
| `CG_MATRIX_MVP` | `cg_ProjMatrix * cg_ViewMatrix * CG_OBJECT_TO_WORLD` | Standard MVP transform |

### Per-Instance Object Data — SSBO / TBO Dual Path

Every draw uses instancing. Per-object data lives in an engine-managed buffer. At startup, `CgCapabilities.detect().preferredShaderBufferPath()` picks one of two hardware paths:

| Path | Condition | Backing |
|---|---|---|
| **SSBO** (`CG_USE_SSBO` defined) | Core GL 4.3+ or ARB | `layout(std430) readonly buffer CgObjectDataBuffer { CgObjectData cg_Objects[]; }` |
| **TBO** (fallback) | GL 3.3 baseline | `uniform samplerBuffer CgObjectDataBuffer` + `cg_FetchObjectData(int)` helper |

The struct is the same on both paths:

```glsl
struct CgObjectData {
    mat4 modelMatrix;
    mat4 normalMatrix;
    vec4 custom0;
    vec4 custom1;
    vec4 custom2;
    vec4 custom3;
};
```

Shaders never branch on the path — the macro surface is identical regardless:

| Macro | Expands to | Notes |
|---|---|---|
| `CG_OBJECT_TO_WORLD` | `CG_OBJECT_DATA.modelMatrix` | Model → world transform |
| `CG_NORMAL_MATRIX` | `mat3(CG_OBJECT_DATA.normalMatrix)` | Upper-left 3×3 — use for transforming normals |
| `CG_OBJECT_CUSTOM0`–`CG_OBJECT_CUSTOM3` | `CG_OBJECT_DATA.custom0` … `.custom3` | Per-instance `vec4` slots — written via `cmd.custom0`…`cmd.custom3` on `CgRenderCommand` |
| `CG_INSTANCE_ID` | `gl_InstanceID` (vertex) / `cg_InstanceId` (fragment) | Instance index; bridged as `flat in int cg_InstanceId` varying so it's accessible in fragment |

### Vertex Attribute Aliases

Available in the vertex stage only. Locations are bound by `CgShaderFactory` before link — no `layout(location=N)` needed in shader code:

| Alias | Type | Location |
|---|---|---|
| `cg_Position` | `vec3` | 0 |
| `cg_TexCoord0` | `vec2` | 1 |
| `cg_Normal` | `vec3` | 2 |

### Why objectBuffer and frameBuffer Must NOT Be Attached

`cg_env.glsl` already declares `CgObjectDataBuffer` and `CgFrameBlock`. The engine wires them automatically post-link. **Never call `material.attach()` with `CgMaterialPipeline.objectBuffer()` or `frameBuffer()`** — it produces duplicate GLSL declarations and a compile failure. Only user-owned buffers belong in `attach()`.

---

## Keyword Variants

Each `#pragma cg_feature` declares a compile-time flag. Enabling a keyword at runtime injects `#define NAME 1` into both vertex and fragment. **Every unique combination of enabled keywords is a separately compiled and cached GL program** — zero runtime branch cost.

```glsl
#pragma cg_feature RECEIVE_SHADOWS
#pragma cg_feature FOG_ON
#pragma cg_feature NORMAL_MAP   // max 8 per shader
```

| Rule | Detail |
|---|---|
| Max 8 per shader | Exceeding throws `CgShaderParseException` at parse time |
| Uppercase only | Names must match `[A-Z][A-Z0-9_]*` |
| Declared names only | `enableKeyword()` throws `IllegalArgumentException` for undeclared names |
| Lazy compilation | Variant compiled on first `bind()` with that keyword set, then cached under a flat `ProgramKey` |
| OFF by default | No `#define` is injected unless explicitly enabled |
| Lines stripped | `#pragma cg_feature` lines never appear in generated GLSL output |

```java
material.enableKeyword("RECEIVE_SHADOWS");  // compiled + cached on next bind()
material.disableKeyword("FOG_ON");
boolean on = material.isKeywordEnabled("NORMAL_MAP"); // false by default
```

---

## Passes, LightMode Routing, and MRTs

### LightMode Routing

Each `Pass { }` carries a `Tags { "LightMode" = "..." }` that routes it to the correct rendering stage:

| LightMode | Stage | Notes |
|---|---|---|
| `Forward` | Standard forward-lit draw | Default when `LightMode` is absent |
| `ShadowCaster` | Depth-from-light pass | Auto-generated for `RenderType=Opaque`, `castShadows=true`, `queue < 3000` |
| `Depth` | Early depth pre-pass | Auto-generated for opaque materials |

The `"Name"` tag sets the pass key dimension for the `ProgramKey` variant cache. Auto-assigned as `Pass0`, `Pass1`, … when absent.

### Pass Types vs. Multi-Draw Chains — Two Orthogonal Axes

The `.shader` format handles **pass type routing** via `LightMode` tags — one `Forward` pass, optionally a `ShadowCaster`, optionally a `Depth`. These are different rendering stages, not sequential draws of the same mesh.

`CgMaterial.nextPass` handles the **orthogonal axis**: the same mesh drawn twice (or more) for decorative effects — outline, additive glow, stencil fill. Each entry in the chain is a fully independent `CgMaterial` with its own shader, render state, and properties. This is NOT Unity built-in multi-pass (multiple `Pass` blocks in one shader file). It is analogous to Godot's `next_pass` but with deterministic immediate ordering instead of Godot's sort-queue execution, which has known ordering bugs.

`drawChain` traverses the chain. The pipeline renderers (`CgForwardRenderer`, `CgTransparentRenderer`) call it automatically — no manual traversal needed when using `CgRenderPipeline.submit()`.

```java
// Outline effect: chain a second material that draws enlarged with inverted normals
CgMaterial base    = CgMaterial.load("mymod:shaders/base.shader");
CgMaterial outline = CgMaterial.load("mymod:shaders/outline.shader");
base.setNextPass(outline);

// Manual draw (outside the pipeline):
base.drawChain(CgRenderPassVariant.FORWARD, () -> mesh.drawInstanced(N));
// ^ draws base first, then outline immediately after with the same N instances
```

`CgPreDrawHook` on a `CgRenderCommand` fires **once before `drawChain`**, not inside it. It does NOT re-fire for `nextPass` chain links.

For explicit single-pass control (no chain):

```java
material.bind();                                     // activates Forward pass, current keywords
mesh.drawInstanced(N);
material.unbind();

// Shadow pass (no keywords applied — shadow passes always use empty keyword set):
if (material.hasShadowCasterPass()) {
    shadowMapFbo.bind();
    material.bindForPass(CgRenderPassVariant.SHADOW);
    mesh.drawInstanced(N);
    material.unbind();
    shadowMapFbo.unbind();
}
```

### MRT (Multiple Render Targets)

For G-buffer or deferred passes, declare a named output struct instead of `out vec4 fragColor`. Annotate each field with `: RT0`, `: RT1`, etc. to map to color attachment slots. The compiler expands each to `layout(location=N) out vec4`:

```glsl
// Declare above the Pass block or at material scope
struct GBuffer {
    vec4 albedo  : RT0;   // → layout(location=0) out vec4 → GL_COLOR_ATTACHMENT0
    vec4 normal  : RT1;   // → layout(location=1) out vec4 → GL_COLOR_ATTACHMENT1
    vec4 pbr     : RT2;   // → layout(location=2) out vec4 → GL_COLOR_ATTACHMENT2
};

Pass {
    Tags { "LightMode" = "Forward" "Name" = "GBufferFill" }
    void vertex(out v2f o) { /* ... */ }
    void fragment(in v2f i, out GBuffer o) {
        o.albedo = texture(_MainTex, i.uv) * _Color;
        o.normal = vec4(normalize(i.normalWs) * 0.5 + 0.5, 1.0);
        o.pbr    = vec4(_Roughness, _Metallic, 0.0, 1.0);
    }
}
```

---

## Loading and Using a Material

```java
CgMaterial mat = CgMaterial.load("mymod:shaders/terrain.shader"); // cached per path
// CgMaterial.newInstance("...") creates a fresh non-cached instance

// Properties (persist across frames until overwritten):
mat.applyProperties(b -> {
    b.set1f("_Roughness", 0.8f);
    b.vec4("_Color", 1f, 0f, 0f, 1f);
});
// Safe to call before first bind() — buffered and replayed after compile

// Keywords:
mat.enableKeyword("RECEIVE_SHADOWS");  // compiles+caches variant lazily on next bind()
mat.disableKeyword("FOG_ON");
boolean on = mat.isKeywordEnabled("FOG_ON");  // false by default
// Throws IllegalArgumentException for undeclared names
```

## CgRenderPipeline — Per-Frame Usage

```java
// 1. Init once (on GL context creation)
CgRenderPipeline.init();
CgMaterial mat = CgMaterial.load("mymod:shaders/terrain.shader");
CgMesh mesh = CgMesh.upload(CgMeshBuilder.unitCube(CgVertexFormat.SPATIAL));

// 2. Per-frame — populate frame data
CgRenderPipeline pipe = CgRenderPipeline.getInstance();
CgFrameData fd = pipe.getFrameData();
fd.viewMatrix.set(viewBuf);
fd.projMatrix.set(projBuf);
fd.timeSecs = elapsedSeconds;
fd.viewportW = width;  fd.viewportH = height;
fd.deriveFromViewMatrix();   // derives cameraPos, cameraForward from viewMatrix

// 3. Submit render commands
CgRenderCommand cmd = pipe.acquireCommand();
cmd.mesh = mesh;  cmd.material = mat;
cmd.modelMatrix.translation(x, y, z);
cmd.worldAabb[0] = x-r; cmd.worldAabb[1] = y-r; cmd.worldAabb[2] = z-r;
cmd.worldAabb[3] = x+r; cmd.worldAabb[4] = y+r; cmd.worldAabb[5] = z+r;
pipe.submit(cmd);

// 4. Execute (CgRenderHook calls this automatically each MC frame)
pipe.execute(partialTicks);

// 5. Teardown (CgGraphicsLifecycle.destroyContext() calls this automatically)
CgRenderPipeline.destroy();
```

**Execute sequence**: sort (opaque front-to-back, transparent back-to-front) → UBO upload → depth prepass → opaque forward pass → transparent pass. All GL state is saved/restored via `CgGlState.saveAll()` around the full execute block.

**Per-object buffer layout** (`CgRenderPipeline.OBJECT_FORMAT`, STD430, 48 floats):

| Field | Type | Float offset |
|---|---|---|
| `modelMatrix` | mat4 | 0–15 |
| `normalMatrix` | mat4 | 16–31 (shader reads upper-left 3×3 as mat3) |
| `custom0`–`custom3` | vec4 | 32–47 |

## GLSL Standard Library

Located at `src/main/resources/assets/crystalgraphics/shaders/lib/`. All files use `#pragma once`.

| File | Key functions |
|---|---|
| `math.glsl` | `saturate`, `remap`, `remap01`, `sq`/`cb`, `positive_pow`, `safe_pow`, `sign_pow`, `smootherstep`, `deg_to_rad`/`rad_to_deg` |
| `vector.glsl` | `safe_normalize`, `fresnel` (Schlick), `rotate_axis` (Rodrigues), `orthonormalize`, `project_onto`/`reject_from`, `angle_between` |
| `color.glsl` | `luminance` (BT.709), `srgb_to_linear`/`linear_to_srgb`, `fast_*` variants (Chilliant), `rgb_to_hsv`/`hsv_to_rgb` (branchless), `rotate_hue`, `desaturate` |
| `uv.glsl` | `rotate_uv`, `scale_uv`, `tile_uv`, `pan_uv`, `flip_uv_x/y`, `cartesian_to_polar_uv` |
| `noise.glsl` | `hash12`/`hash22`/`hash13` (sin-free), `value_noise`, `fbm4`/`fbm6`, `fbm(p, octaves)`, `fbm_ridged` |

Use with `#include "crystalgraphics:shaders/lib/color.glsl"` etc. (`#pragma once` prevents double-expansion when multiple files include `math.glsl`.)

**Package guides for this layer**: `api/material/AGENTS.md` · `api/render/AGENTS.md` · `render/AGENTS.md` · `render/pipeline/AGENTS.md` · `gl/material/AGENTS.md` · `gl/material/parse/AGENTS.md`

---

# Core Framework Systems

## Framebuffers

`CgFrameBuffer` is the unified FBO abstraction. Create it via `CgFrameBufferFormat` builder — the format describes all attachments, the FBO handles Core/ARB/EXT dispatch internally.

```java
CgFrameBufferFormat fmt = CgFrameBufferFormat.builder("my_fbo")
        .color(0, CgTextureType.RGBA8)            // slot 0 → texture attachment
        .color(1, CgTextureType.RGBA16F)           // slot 1 → texture attachment
        .depth(CgTextureType.DEPTH24_STENCIL8)     // depth → texture attachment
        .depthRbo(CgTextureType.DEPTH24_STENCIL8)  // (or: depth → renderbuffer)
        .build();
CgFrameBuffer fbo = CgFrameBuffer.create("my_fbo", width, height, fmt); // or CgFrameBuffer.createScreenSized("my_fbo", fmt);
fbo.bind();     // ... render ...
fbo.unbind();
fbo.delete();   // only valid on owned FBOs; CgFrameBuffer.wrap() creates non-owned
```

`CgFrameBufferRegistry` — cache for screen-sized FBOs that auto-resize on window resize.

**Package guides**: `api/framebuffer/AGENTS.md` · `gl/framebuffer/AGENTS.md`

## Textures

```java
CgTexture2D tex    = CgTexture2D.create("mymod:textures/foo.png");
CgTexture2D hdr    = CgTexture2D.createEmpty(512, 512, CgTextureSpec.RGBA16F_LINEAR);
CgTexture2D shadow = CgTexture2D.createEmpty(512, 512, CgTextureSpec.DEPTH24_SHADOW);  // PCF
tex.bind(0);   // bind to texture unit 0
tex.delete();
```

`CgTextureType` — typed enum of ~42 GL format constants; single source of truth for (internalFormat, baseFormat, type). `CgTextureSpec` — immutable `@Builder` describing format + filter + wrap + optional shadow compare. `CgMipmapConfig` — `NONE` / `TRILINEAR` / `NEAREST`. Concrete impls: `CgTexture2D`, `CgTexture2DArray`, `CgTexture3D`, `CgTextureCubemap`.

**Package guides**: `api/texture/AGENTS.md` · `gl/texture/AGENTS.md`

## Meshes

```java
// Procedural (unitCube, quad2D, plane, uvSphere, icosahedron)
CgMeshData data = CgMeshBuilder.unitCube(CgVertexFormat.SPATIAL);
CgMesh mesh = CgMesh.upload(data);      // GL thread only — uses GL_STATIC_DRAW
mesh.drawInstanced(N);                   // N instances via engine SSBO/TBO
mesh.drawDirect();                       // non-instanced draw

// From file (auto-detects .obj / .gltf / .glb by extension)
CgMeshData loaded = CgMeshLoader.load("mymod:models/thing.obj", CgVertexFormat.SPATIAL);
CgMesh mesh2 = CgMesh.upload(loaded);
mesh2.delete();                          // idempotent — frees VBO + IBO + VAO
```

**Package guides**: `api/mesh/AGENTS.md` · `gl/mesh/AGENTS.md`

## Vertex Formats + Instancing

`CgVertexFormat.SPATIAL` — the canonical format for spatial materials: `cg_Position` (vec3) + `cg_TexCoord0` (vec2) + `cg_Normal` (vec3), stride 32 bytes. This is the format `CgMeshBuilder` and `CgMeshLoader` target by default.

`CgVertexFormat` is the registry key for `CgVertexArrayRegistry` — two formats with identical attribute lists are value-equal and share the same cached VAO/VBO.

`CgInstanceFormat` — per-instance attribute layout. `mat4` fields expand to 4 physical `vec4` attributes. Pre-built: `CgInstanceFormat.TRANSFORM_COLOR_CUSTOM` (mat4 model + ubyte4 color + vec4 custom, 84 bytes, 6 attributes). Only divisor=1 is supported.

**Package guides**: `api/vertex/AGENTS.md` · `gl/vertex/AGENTS.md`

## Shader Buffers

Attach user-owned SSBO/TBO or UBO blocks to a material. The engine injects GLSL declarations automatically on the next compile.

```java
// SSBO/TBO — access via macro in shader: GLYPH_DATA(n).advance
CgBufferFormat fmt = CgBufferFormat.builder("GlyphMetrics", STD430)
        .vec4("bbox").vec2("uv0").float_("advance").build();
CgShaderBuffer buf = CgShaderBuffer.create("GlyphMetricsBuffer", fmt, 0);
material.attach(buf, "GLYPH_DATA");     // macroName must be ^[A-Z][A-Z0-9_]*$
buf.bind();                              // caller's responsibility before each draw
material.detach("GLYPH_DATA");

// UBO — flat scope, direct field name access in shader: ambientColor (no prefix)
CgBufferFormat sceneFmt = CgBufferFormat.builder("SceneParams", STD140)
        .vec4("ambientColor").float_("exposure").build();
CgUniformBuffer ubo = CgUniformBuffer.create(sceneFmt, "SceneParams", 0);
material.attach(ubo);                   // no macroName — UBO is a single instance
material.detachUbo("SceneParams");
```

Do NOT pass engine pipeline buffers (`CgMaterialPipeline.objectBuffer()`, `frameBuffer()`) — declared in `cg_env.glsl`, wired automatically. Duplicate declarations cause compile failure.

**Package guides**: `api/buffer/AGENTS.md` · `gl/buffer/shader/AGENTS.md`

## Raw Shaders

**Use `CgMaterial.load()` for all shader authoring work.** Raw `CgShader` is for infrastructure-level GL work only — fullscreen blits, post-processing passes, debug utilities, or standalone procedural draws that do not fit the `.shader` material model.

```java
CgShader shader = CgShaderFactory.load(
        "mymod:shaders/blit.vert",
        "mymod:shaders/blit.frag");
```

### cg_env.glsl is NOT auto-included

Unlike `.shader` materials, raw shader files do **not** get `cg_env.glsl` automatically. If you need the frame UBO, per-instance macros, or vertex attribute aliases, include it explicitly at the top of your `.vert` / `.frag`:

```glsl
#version 330 core
#include "crystalgraphics:shaders/env/cg_env.glsl"
// Now CG_MATRIX_MVP, cg_Time, CG_OBJECT_TO_WORLD, etc. are available
```

### Binding uniforms

`CgShader` has two binding containers with different lifetimes:

| Method | Lifetime | Typical use |
|---|---|---|
| `shader.applyBindings(b -> { … })` | **Ephemeral** — flushed once on next `bind()`, then auto-cleared | Per-frame values (time, matrices, texture slots) |
| `shader.bindings().set1f(…)` etc. | **Persistent** — survives across frames until explicitly overwritten | Static material parameters |

```java
// Ephemeral (cleared after each bind):
shader.applyBindings(b -> {
    b.set1f("u_time", elapsedSeconds);
    b.sampler2D("u_tex", 0, myTexture);
    b.mat4("u_mvp", mvpMatrix);
});

// Persistent (stays set):
shader.bindings().set1f("u_exposure", 1.0f);
```

> `applyBindings` (raw `CgShader` uniform setters) is a different API from `applyProperties` (`CgMaterial` property block values). Do not confuse them.

### Scoped bind

`bindScoped()` is a try-with-resources helper that restores the previously bound GL program on exit:

```java
try (CgShaderScope scope = shader.bindScoped()) {
    shader.applyBindings(b -> {
        b.set1f("u_time", elapsed);
        b.sampler2D("u_tex", 0, myTexture);
    });
    mesh.drawDirect();
}  // prior program restored automatically
```

### GLSL library files

Raw shaders can use the same standard library as `.shader` materials. Include any lib file explicitly:

```glsl
#include "crystalgraphics:shaders/lib/color.glsl"
#include "crystalgraphics:shaders/lib/noise.glsl"
```

`#pragma once` in each lib file prevents double-expansion regardless of include order.

**Package guides**: `api/shader/AGENTS.md` · `gl/shader/AGENTS.md` · `mc/shader/AGENTS.md`

---

# Infrastructure

## GL State Save/Restore

Wrap any block of GL work in a `CgGlScope` to guarantee state restoration on exit — even on exceptions. Always save only the slots you intend to modify.

```java
// Save specific slots:
try (CgGlScope scope = CgGlState.save(CgGlSlot.FBO, CgGlSlot.PROGRAM)) {
    fbo.bind();
    shader.bind();
    // draw
}  // FBO and PROGRAM restored automatically on close

// Convenience shorthands:
CgGlState.saveProgram()   // → save(PROGRAM)
CgGlState.saveFull()      // → save(FBO, PROGRAM, TEXTURES, VERTEX_INPUT)
CgGlState.saveAll()       // → all 16 slots (used by CgRenderPipeline.execute())
```

`CgGlSlot` constants: `FBO` · `PROGRAM` · `TEXTURES` · `VERTEX_INPUT` · `BLEND` · `DEPTH` · `CULL` · `STENCIL` · `COLOR_MASK` · `VIEWPORT` · `SCISSOR` · `POLYGON_OFFSET` · `ALPHA_TEST` · `LINE_WIDTH` · `POLYGON_MODE` · `POINT_SIZE`

**Package guides**: `api/state/AGENTS.md` · `gl/state/AGENTS.md`

## Capabilities

`CgCapabilities.detect()` — queries LWJGL `ContextCapabilities`; result is cached per context. Key checks: `isCoreFbo()`, `isArbFbo()`, `isExtFbo()`, `isCore()`, `isArbSync()`, `isVaoSupported()`, `preferredShaderBufferPath()` (returns SSBO → TBO → NONE based on hardware).

Waterfall: Core GL30 > ARB > EXT. Factory methods on `CgFrameBuffer`, `CgVertexArray`, and `CgStreamBuffer` all follow this order internally — callers do not need to check capabilities manually for normal API usage.

## Render State

Pre-defined constants cover the common cases:

```java
CgRenderState.DEFAULT          // blend OFF, depth TEST_WRITE, cull BACK, stencil DISABLED
CgDepthState.TEST_WRITE        // depth test LEQUAL + depth write ON
CgDepthState.TEST_ONLY         // depth test LEQUAL + depth write OFF
CgBlendState.ALPHA             // SRC_ALPHA / ONE_MINUS_SRC_ALPHA
CgBlendState.ADDITIVE          // ONE / ONE
CgCullState.BACK               // GL_BACK face culling
```

`state.apply()` / `state.clear()` — always bracket render work to prevent GL state leaks.

**Package guide**: `api/state/AGENTS.md`

## Batch Render Layer System

For UI, 2D overlays, and non-material draw paths (not the CrystalShader material pipeline). `CgBatchRenderer` + `CgRenderLayer` — layer-based immediate-mode quad/triangle batching. `CgBufferSource` — per-context owner, not a singleton. `CgTextLayers` — factory for MSDF/bitmap text layers used by the text renderer.

**Package guides**: `gl/render/AGENTS.md` · `gl/buffer/staging/AGENTS.md`

## Font/Text System

- **Canonical docs**: `docs/font/README.md` (entry point) · `docs/font/api-guide.md` (usage) · `docs/font/architecture.md` (package boundaries)
- **Public entry points**: `api/font/` · `api/text/`
- **Internal**: `text/layout/` · `text/cache/` · `text/atlas/` · `text/msdf/` · `text/render/`

Do not rely on older font/text notes outside the `docs/font/` set — that is the current source of truth.

---

## Resource I/O — `CgIO` and `CgTextureIO`

### `CgIO` — Universal Resource Loader

`util/io/CgIO` is the single entry point for loading any text-based asset (shaders, config). Every part of the engine that reads a file calls it. It resolves paths through a waterfall:

| Priority | Strategy | Condition |
|---|---|---|
| 0 | Absolute filesystem path | Path is an absolute file |
| 1 | Filesystem override dir | `-Dcrystalgraphics.shader.resourceOverrideDir` is set |
| 2 | Minecraft resource manager | `IResourceManager` available (in-game) |
| 3 | Classpath | Final fallback (always works in harness + tests) |

**Accepted path formats** — all normalized to `/assets/{domain}/{rest}` internally:

```
mymod:shaders/terrain.shader         → /assets/mymod/shaders/terrain.shader
crystalgraphics:shaders/lib/math.glsl → /assets/crystalgraphics/shaders/lib/math.glsl
/assets/mymod/shaders/foo.vert       → unchanged
shader/foo.vert                      → /assets/crystalgraphics/shader/foo.vert  (default domain)
```

Key methods:
- `CgIO.loadSource(path)` — returns UTF-8 string or `null` on failure (used by shader preprocessor, material loader)
- `CgIO.openStream(path)` — returns raw `InputStream` or `null`
- `CgIO.normalizePath(path)` — path normalization only, no I/O
- `CgIO.toResourceLocation(path)` — converts any supported format to MC `ResourceLocation`

### `CgTextureIO` — Image Loader

`util/io/CgTextureIO` decodes image files into direct `ByteBuffer`s ready for GL upload. Path resolution delegates to `CgIO.openStream()`. Returns `CgImageData(pixels, width, height, channels)` or `null` on failure — never throws. Channel count (1/3/4) is preserved from the source image so callers can derive the correct `GL_RED`/`GL_RGB`/`GL_RGBA` upload format. Pixels are bottom-left row order (GL convention).

Also owns `CgTextureIO.createFallback()` — generates the purple/black 8×8 checkerboard texture used when a texture fails to load.

---

# Lifecycle & Registries

## CgGraphicsLifecycle

The single coordination point for GL context init and teardown. **Call these and nothing else** — do not free individual registries manually.

```java
// On GL context creation (GL thread):
CgGraphicsLifecycle.initContext(viewportWidth, viewportHeight);
// Initialises: CgRenderPipeline, CgFrameBufferRegistry, CgFallbackTextures

// On window resize (GL thread):
CgGraphicsLifecycle.onResize(newWidth, newHeight);
// Triggers recreation of all screen-sized FBOs in CgFrameBufferRegistry

// On GL context destruction (GL thread):
CgGraphicsLifecycle.destroyContext();
// Runs the canonical teardown sequence (see below)
```

**Canonical teardown order** (enforced inside `destroyContext()`):

| Step | What | Why |
|------|------|-----|
| 1 | `CgVertexArrayRegistry.get().deleteAll()` | All VAOs — instanced first (they reference both VBOs), then non-instanced |
| 2 | `CgMeshRegistry.get().deleteAll()` | Static mesh VBOs + IBOs + per-mesh VAOs |
| 3 | `CgVertexBufferRegistry.get().deleteAll()` | All streaming VBOs (base + instance) |
| 4 | `CgQuadIndexBuffer.freeAll()` | Shared quad IBO |
| 5 | `CgTextureManager.get().freeAll()` | All cached textures + fallback |
| 6 | `CgMaterialRegistry.get().deleteAll()` | Material instances + GL shader programs |
| 7 | `CgShaderBufferRegistry.get().deleteAll()` | User SSBO/TBO/UBO resources |
| 8 | `CgRenderPipeline.destroy()` | Frame UBO + object SSBO + command queue |
| 9 | `CgFrameBufferRegistry.get().deleteAll()` | All owned FBOs |
| 10 | `CgDebugBlit.dispose()` | Debug blit utility (no-op if never used) |

> VAOs must be deleted **before** VBOs — this is why steps 1-3 are strictly ordered.  
> Violating the order produces stale GPU state and silent corruption.

## Registry Overview

All registries are **singletons accessed via `.get()`**. You normally interact with them through the high-level API (e.g. `CgMaterial.load()`), not directly. Know they exist for debugging and teardown.

| Registry | Singleton | What it owns | When to call directly |
|----------|-----------|-------------|----------------------|
| `CgMaterialRegistry` | `CgMaterialRegistry.get()` | All `CgMaterial` instances (per-instance UBOs) | `reloadAll()` on hot-reload; `deleteAll()` on teardown (via lifecycle) |
| `CgMaterialShaderRegistry` | `CgMaterialShaderRegistry.get()` | Shared `CgMaterialShader` GL program assets | Internal — managed by `CgMaterialRegistry` |
| `CgMeshRegistry` | `CgMeshRegistry.get()` | All static `CgMesh` GPU objects | `getOrCreate(key, supplier)` for caching procedural meshes |
| `CgTextureManager` | `CgTextureManager.get()` | All `CgTexture` instances (2D, array, 3D, cubemap) | `getOrCreate(path)` for cached texture load; `reloadAll()` on F3+T |
| `CgFrameBufferRegistry` | `CgFrameBufferRegistry.get()` | Screen-sized FBOs that auto-resize | `getOrCreate(name, format)` for screen-sized FBOs |
| `CgVertexArrayRegistry` | `CgVertexArrayRegistry.get()` | All VAOs (non-instanced + instanced) | Internal — do not create VAOs manually |
| `CgVertexBufferRegistry` | `CgVertexBufferRegistry.get()` | All streaming VBOs (base + instance) | Internal — do not create VBOs manually |
| `CgShaderBufferRegistry` | `CgShaderBufferRegistry.get()` | User-attached SSBO/TBO/UBO objects | `deleteAll()` on teardown (via lifecycle) |


---

# Package AGENTS.md Index

All 34 package guides under `src/main/java/io/github/somehussar/crystalgraphics/`. Relative paths omit the common prefix.

### CrystalShader Pipeline
| Path | What it covers |
|---|---|
| `api/material/AGENTS.md` | `CgMaterial` load/bind/keywords/attach-buffers/ownership; `CgRenderPassVariant`; `CgRenderQueue` constants |
| `api/render/AGENTS.md` | `CgRenderPipeline`, `CgRenderCommand`, `CgFrameData`, `CgSortKey`, `CgPreDrawHook`, `CgRenderCommandPool` |
| `render/AGENTS.md` | `CgRenderPipeline` singleton orchestrator, execute sequence, anaglyph guard, lifecycle |
| `render/pipeline/AGENTS.md` | `CgDepthPrepassRenderer`, `CgForwardRenderer`, `CgTransparentRenderer` — internal pass renderers |
| `gl/material/AGENTS.md` | `CgMaterialShader`, `CgMaterialShaderRegistry`, `CgMaterialProperties` |
| `gl/material/parse/AGENTS.md` | `CgShaderParser` facade, `CgParsedShader`, `CgMaterialShaderCompiler`, sub-parsers |

### Shaders
| Path | What it covers |
|---|---|
| `api/shader/AGENTS.md` | `CgShader` lifecycle, `CgShaderPreprocessor` (#include/pragma-once/cycle detection), `CgShaderBindings` fluent API, `CgActiveUniform` |
| `gl/shader/AGENTS.md` | `CgShaderFactory` waterfall, `CgCoreShaderProgram` (GL20), `CgArbShaderProgram` (ARB), `StandaloneCgShader` |
| `mc/shader/AGENTS.md` | `CgShaderImpl` hot-reload flow, `CgShaderManagerImpl` cache, `CgShaderReloadHook` (F3+T), `CgSystemUniformRegistry` |

### Framebuffers
| Path | What it covers |
|---|---|
| `api/framebuffer/AGENTS.md` | `CgFrameBufferFormat` builder API, validation rules, equality |
| `gl/framebuffer/AGENTS.md` | `CgFrameBuffer` dispatch architecture, `CgFrameBufferRegistry`, EXT quirks, ownership model |

### Textures
| Path | What it covers |
|---|---|
| `api/texture/AGENTS.md` | `CgTexture` interface, `CgTextureType` (~42 format constants), `CgTextureSpec` (builder + presets), `CgMipmapConfig` |
| `gl/texture/AGENTS.md` | `CgTexture2D`, `CgTexture2DArray`, `CgTexture3D`, `CgTextureCubemap` concrete implementations |

### Mesh
| Path | What it covers |
|---|---|
| `api/mesh/AGENTS.md` | `CgMeshTopology` (GL draw mode enum), `CgMeshData` (CPU data holder) |
| `gl/mesh/AGENTS.md` | `CgMeshBuilder` (procedural), `CgObjLoader`, `CgGltfLoader`, `CgMeshLoader` facade, `CgMesh` (GPU upload + draw) |

### Vertex / Instancing
| Path | What it covers |
|---|---|
| `api/vertex/AGENTS.md` | `CgVertexFormat`, `CgInstanceFormat` (mat4 expansion, `TRANSFORM_COLOR_CUSTOM`), `CgVertexSemantic`, `CgAttribType` |
| `gl/vertex/AGENTS.md` | `CgVertexArray`, `CgVertexArrayBinding`, `CgInstanceVertexArrayBinding`, `CgVertexArrayRegistry` |

### Buffers
| Path | What it covers |
|---|---|
| `api/buffer/AGENTS.md` | `CgGpuType`, `CgBufferField`, `CgBufferFormat` builder, std140/std430 alignment rules |
| `gl/buffer/AGENTS.md` | `CgStreamBuffer` waterfall (sync ring → orphan → subdata), `CgQuadIndexBuffer` |
| `gl/buffer/shader/AGENTS.md` | `CgShaderBuffer` (SSBO/TBO), `CgUniformBuffer` (UBO), `CgShaderBufferRegistry`, binding point rules |
| `gl/buffer/staging/AGENTS.md` | `CgStagingBuffer`, `CgVertexWriter` (all vertex packing goes here), `CgInstanceWriter` |

### GL State
| Path | What it covers |
|---|---|
| `api/state/AGENTS.md` | `CgRenderState`, `CgDepthState`, `CgBlendState`, `CgCullState`, `CgStencilState`, `CgTextureState`, `CgGlSlot` |
| `gl/state/AGENTS.md` | `CgGlState` / `CgGlScope` (save/restore), `GLStateMirror`, `CallFamily` |

### Batch Render Layer
| Path | What it covers |
|---|---|
| `gl/render/AGENTS.md` | `CgBatchRenderer`, `CgRenderLayer`, `CgInstanceRenderer`, `CgBufferSource`, `CgTextLayers` |

### Font / Text
| Path | What it covers |
|---|---|
| `api/font/AGENTS.md` | Public font-domain API + layout bridge |
| `api/text/AGENTS.md` | Public text-domain value types |
| `text/AGENTS.md` | Top-level text package coordination |
| `text/layout/AGENTS.md` | Layout algorithm |
| `text/cache/AGENTS.md` | Glyph supply, async generation, cache |
| `text/atlas/AGENTS.md` | Atlas storage |
| `text/atlas/packing/AGENTS.md` | Packing algorithms |
| `text/msdf/AGENTS.md` | Distance-field generation logic |
| `text/render/AGENTS.md` | Draw-time orchestration, `CgTextQuadSink` |

### Debug
| Path | What it covers |
|---|---|
| `gl/debug/AGENTS.md` | `CgDebugBlit` — fullscreen texture blit, covering-triangle, no VBO |

---

# Minecraft Integration Glue

This section covers the layer that wires CrystalGraphics into the Minecraft/Forge runtime. Understanding it matters when debugging frame-timing issues, hot-reload failures, or GL state conflicts between CG and other mods.

## `CrystalGraphics.java` — Forge Mod Container

The root `@Mod` class (`modid = "crystalgraphics"`). Intentionally performs **no GL work** — it is purely a Forge dependency anchor and lifecycle logger. All rendering and interception logic lives in `mc/coremod/` and `mixins/`. Other mods declare `required-after:crystalgraphics` in their `mcmod.info` to depend on this mod.

## Render Loop Hook — `CgRenderHook`

`mixins/early/impl/client/CgRenderHook` is a SpongePowered Mixin on `EntityRenderer.renderWorld`. It fires `CgRenderPipeline.getInstance().execute(partialTicks)` **immediately before** MC renders its translucent terrain pass.

At this hook point:
- MC has already drawn all opaque world geometry into FBO 0
- The depth buffer is fully populated — CG geometry depth-tests correctly against the world
- CG runs its full sequence: depth prepass → opaque forward → transparent
- MC then continues with translucent terrain, naturally depth-interleaving with CG geometry

This hook is why you never call `pipe.execute()` manually in game code — it is already called once per frame at the right moment.

## Hot-Reload Hook — `CgAssetReloader`

`mc/CgAssetReloader` registers an `IResourceManagerReloadListener` with Minecraft's resource manager. It fires on **F3+T** and on resource pack changes. On reload it calls, in order:

1. `CgTextureManager.get().reloadAll()` — re-uploads all textures
2. `CgShaderManager.reloadAll()` — marks all raw `CgShader` instances dirty (recompile on next `bind()`)
3. `CgMaterialRegistry.get().reloadAll()` + `CgMaterialShaderRegistry.get().reloadAll()` — marks all materials dirty

Failures in each step are isolated and logged — a broken shader does not prevent textures from reloading.

## GL State Mirror — `CrystalGLRedirects` + `CrystalGraphicsTransformer`

`mc/coremod/CrystalGraphicsTransformer` is an ASM coremod that rewrites GL call sites across the **entire Minecraft process** — including vanilla MC and other loaded mods — redirecting them to `CrystalGLRedirects`. Each redirect:

1. Checks the recursion guard (`GLStateMirror.isInRedirect()`) to prevent infinite recursion
2. Updates `GLStateMirror` with the new GL state **before** the actual GL call
3. Calls the original LWJGL / `OpenGlHelper` method inside a `try/finally`

This is what makes `CgGlState.save/restore` reliable in a multi-mod environment — CG knows the true GL state even when other mods make GL calls outside CG's control.

The JVM flags for this layer are listed in the [Debug / JVM Flags](#debug--jvm-flags) section.

---

# Minecraft Source Code Location

**CRITICAL**: Minecraft 1.7.10 and Forge source is decompiled and deobfuscated at `build/rfg/minecraft-src/java/`. Key files:

- `net/minecraft/client/Minecraft.java` — main game class, owns `framebufferMc`
- `net/minecraft/client/renderer/EntityRenderer.java` — render pipeline, shader integration
- `net/minecraft/client/renderer/OpenGlHelper.java` — GL extension detection (THE reference impl)
- `net/minecraft/client/shader/Framebuffer.java` — vanilla FBO wrapper
- `net/minecraft/client/shader/ShaderGroup.java` — post-processing pipeline
- `net/minecraft/client/shader/ShaderManager.java` — GLSL program management
- `cpw/mods/fml/client/FMLClientHandler.java` · `cpw/mods/fml/common/gameevent/TickEvent.java`

**Analysis documents**:
- `docs/MINECRAFT_FBO_ANALYSIS.md` — complete trace of vanilla FBO system
- `docs/MINECRAFT_SHADER_ANALYSIS.md` — vanilla shader architecture
- `docs/CRITICAL_GOTCHAS.md` — hidden vanilla behaviors
- `docs/INTEGRATION_STRATEGY.md` — integration patterns

---

# External References

**LWJGL 2.9 Javadoc**: https://javadoc.lwjgl.org/  
**OpenGL Registry**: https://www.khronos.org/registry/OpenGL/  
**GTNH Build Plugin**: https://github.com/GTNewHorizons/

---

# Debug / JVM Flags

```bash
# Coremod / ASM transformer
-Dcrystalgraphics.redirector.disable=true            # disable all transforms
-Dcrystalgraphics.redirector.verbose=true            # enable verbose logging
-Dcrystalgraphics.redirector.verbosePrefix=net.minecraft.  # limit verbose to prefix
-Dcrystalgraphics.redirector.forceAngelica=true|false  # override Angelica detection

# State boundary
-Dcrystalgraphics.boundary.forceGlGet=true           # force glGet* capture even if mirror valid

# Shader
-Dcrystalgraphics.shader.devmode=true                # emit #line directives in preprocessed output
-Dcrystalgraphics.shader.resourceOverrideDir=path    # filesystem override dir for shader sources
```
