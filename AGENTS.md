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

**Project type**: Multi-loader Minecraft graphics library | **Targets**: MC 1.7.10 (Forge/LWJGL2) · MC 1.20.1 (Forge, Fabric) · MC 1.20.4 (NeoForge/LWJGL3) | **Core authored in**: Java 17 (`core/` and `platform/`, toolchain 17) | **Build**: Gradle multi-project

## TO BUILD
```bash
./gradlew.bat compileJava   # ~60 seconds — DO NOT kill early, DO NOT run in parallel
./gradlew.bat test          # pure-logic unit tests (no GL context required)
```

## Dev Runs

```bash
# Core
./gradlew :core:compileJava

# MC 1.7.10
./gradlew :mc1710:runClient
./gradlew :mc1710:compileJava

# MC 1.20.1 Forge
./gradlew :mc1201:forge:runClient
./gradlew :mc1201:forge:compileJava

# MC 1.20.4 NeoForge
./gradlew :mc1201:neoforge:runClient
./gradlew :mc1201:neoforge:compileJava

# MC 1.20.1 Fabric
./gradlew :mc1201:fabric:runClient
./gradlew :mc1201:fabric:compileJava
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
./gradlew :gl-debug-harness:runHarness --args="--mode=shader-compile-audit"  # every shipped .shader + keyword variant, one report

# Outputs land in gl-debug-harness/harness-output/{scene}/
```

**Key rules when authoring harness scenes** (from `gl-debug-harness/AGENTS.md`):
- Never call raw GL — use `CgVertexArray`, `CgStreamBuffer`, `CgTexture`, `CgFrameBuffer`, etc.
- Implement `HarnessSceneLifecycle` (managed, single frame) or `InteractiveSceneLifecycle` (loop + camera).
- Register the new scene in `SceneRegistry.createDefault()`.
- Use `ArtifactService.requestCapture("suffix")` for interactive captures; `ScreenshotUtil` for managed ones.
- GL state cleanup after `render()` is automatic — don't do it yourself.

---

## Multi-Loader Project Structure

The repository is a Gradle multi-project build. Every subproject has a distinct role — put code in the wrong one and it will either fail to compile or silently break a loader.

| Subproject | Java | GL library | Role |
|---|---|---|---|
| `platform/` | 25 → 8 | none | SPI interfaces only — `CgGlDispatch`, `CgPlatform`, `CgLifecycleService`, etc. No implementation. |
| `core/` | 25 → 8 | none | All rendering logic — `CgMaterial`, `CgMesh`, `CgRenderPipeline`, font, text, atlas. Calls `CgPlatform.*()` for every GL or lifecycle operation. Never imports MC or LWJGL types. |
| `freetype-msdfgen-harfbuzz-bindings/` | 25 → 8 | none | JNI bindings for FreeType/HarfBuzz text shaping. Bundled in every loader JAR. |
| `gl-debug-harness/` | 17 | LWJGL3 | Standalone GL test harness — no Minecraft, boots in seconds. Use for all rendering work. |
| `mc1710/` | 25 → 8 | LWJGL2 | MC 1.7.10 / Forge. Registers `PlatformRegistry1710` which implements all SPI interfaces against LWJGL2. |
| `mc1201/common/` | 17 | LWJGL3 | Shared MC 1.20.x platform service implementation (`PlatformService1201`, `Mc120xGLBackend`) and mixins (`MixinGameRenderer`, `MixinMinecraftShutdown`). No loader-specific types. |
| `mc1201/forge/` | 17 | LWJGL3 | MC 1.20.1 / MinecraftForge 47.x. Thin bootstrap: registers events on the Forge bus, calls `CgPlatform.register()`. |
| `mc1201/neoforge/` | 17 | LWJGL3 | MC 1.20.4 / NeoForge. Same pattern as forge. Despite living under `mc1201/`, targets MC 1.20.4. |
| `mc1201/fabric/` | 17 | LWJGL3 | MC 1.20.1 / Fabric. Same pattern, uses Fabric API callbacks + GLFW for inputs with no Fabric API equivalent. |

**Rule**: `core/` and `platform/` have zero compile dependency on LWJGL, MC, or any loader.

> ⚠️ **Not currently enforced here.** The import-guard `doLast` in `core/build.gradle.kts` is commented
> out (inside a disabled dual-pipeline experiment), so a stray `import net.minecraft.*` in `core/` would
> compile. CrystalGUI's equivalent guard *is* active — do not assume this one is by analogy. Either
> re-enable it or keep this warning; silently claiming enforcement that does not exist is worse than
> having none. (The `cgStateWriteGuard` task once cited here as the working pattern no longer exists — the
> V2 state rewrite made the rule it enforced unnecessary. Copy CrystalGUI's guard instead.)

---

## Platform SPI Architecture

This is the load-bearing architectural law. Read it before writing any code that touches GL or MC lifecycle.

```
platform/       ← SPI interfaces (CgGlDispatch, CgLifecycleService, CgReloadService, ...)
    ↑
core/           ← rendering logic — calls CgPlatform.gl(), CgPlatform.lifecycle(), etc.
    ↑
mc*/loader      ← registers a concrete implementation via CgPlatform.register(service)
```

`CgPlatform` is the runtime dispatch singleton. All GL calls flow through it:

```java
// In core/ — never a raw GL call, never an LWJGL import:
CgPlatform.gl().bindFramebuffer(target, id);
CgPlatform.lifecycle().onContextCreated(w, h);
CgPlatform.reload().onReload();
```

Each loader bootstraps by registering its implementation:

```java
// mc1710 — in PlatformRegistry1710.onPreInit():
CgPlatform.register(new PlatformService1710(...));

// mc1201 — in CrystalGraphics1201Forge / Fabric / NeoForge constructor:
CgPlatform.register(PlatformService1201.getInstance());
```

**If you find yourself calling raw GL inside `core/` or importing a loader type, you are in the wrong module.** Add a method to the appropriate SPI interface in `platform/`, implement it in each loader's platform service, then call it via `CgPlatform`.

---

## Cross-Platform Feature Checklist

Use this when adding anything that touches GL, lifecycle, or loader-specific events across all targets.

**Adding a new abstraction to the platform layer:**

1. Define the method or interface in `platform/` SPI (`CgGlDispatch`, `CgLifecycleService`, etc.)
2. Implement in `mc1710/`'s `Lwjgl2GlDispatch` / `LifecycleService1710` (LWJGL2 path)
3. Implement in `mc1201/common/`'s `Mc120xGLBackend` / `LifecycleService1201` (LWJGL3 path)
4. Call via `CgPlatform.*()` in `core/` — never call the implementation directly

**Adding a new render hook or input event to mc1201 loaders:**

5. Implement the logic in `core/` or `mc1201/common/` (loader-blind)
6. Wire in `mc1201/forge/`: subscribe on the **Forge** event bus (`Mod.EventBusSubscriber.Bus.FORGE`)
7. Wire in `mc1201/neoforge/`: subscribe on the **NeoForge** event bus (`NeoForge.EVENT_BUS.addListener`)
8. Wire in `mc1201/fabric/`: use Fabric API callbacks (`HudRenderCallback`, `ClientLifecycleEvents`) — if no Fabric API event exists, chain a GLFW callback; **mixins are last resort**

**Whenever you add a new subproject dependency to mc1201 loaders:**

> ⚠️ **mc1201 Forge/NeoForge classpath rule**
>
> `runtimeOnly` Gradle deps are **invisible** to ModDevGradle dev runs. The `mods{}` block is the only source ModDevGradle reads for the Forge/NeoForge run classpath.
> Every module bundled in the JAR (`platform/`, `core/`, `mc1201:common`, `freetype-msdfgen-harfbuzz-bindings`) must appear in **two places** in each Forge/NeoForge loader's `build.gradle.kts`:
> 1. As `sourceSet(project(":foo").extensions.getByType<SourceSetContainer>()["main"])` inside the `mods { create("crystalgraphics") { ... } }` block
> 2. As `from(zipTree(...jar...))` inside the `shadowJar` task
>
> **Fabric is NOT exempt** — `runtimeOnly` deps are on the JVM system classpath but Knot classloader
> does NOT delegate `com.crystalgraphics.*` to the system classloader. Fabric must bundle all
> sub-project classes into `tasks.jar` (same as Forge/NeoForge) AND add `from(zipTree(...))` inside
> `tasks.jar` and `shadowJar`. Do NOT use `loom.mods { sourceSet(crossProject) }` — Loom 1.16.2
> tries to apply `fabric-loom-companion` to the cross-project, which fails for non-Loom projects.
> See `mc1201/fabric/AGENTS.md` for full details.

9. Add `compileOnly` + `runtimeOnly` in `cg-mc1201-loader.gradle.kts`
10. Add `sourceSet(project(":foo")...)` to `mods{}` in `mc1201/forge/build.gradle.kts` and `mc1201/neoforge/build.gradle.kts`
11. Add `from(zipTree(...))` to **both** `tasks.jar` and `shadowJar` in **all three** loader `build.gradle.kts` files (including Fabric — see note above)

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

**Java version by module** — `core/` and `platform/` are **Java 17 source and target, toolchain 17**, and produce Java 17 bytecode. Modern syntax (`var`, records, sealed classes, pattern matching, streams) is fully permitted in both.

> ⚠️ **The Jabel + jvmDowngrader dual pipeline is written but commented out.** `core/build.gradle.kts` still carries it — `compileJabel`, `downgradeClasses`, the java8/java17 variants — behind comments, and both modules keep `jabel-javac-plugin` and `jvmdowngrader-java-api` as `compileOnly` so an `import ...Desugar` still compiles. Neither has an `annotationProcessor(jabel)`, so **nothing is desugaring or downgrading today**. Earlier revisions of this file described the Java 25 → Java 8 pipeline as live; it is not. Do not rely on Java 8 bytecode being produced, and do not add a Java 8-only constraint on the belief that it is.

`platform/` was genuinely Java 8 source until 2026-07-30, which is a different thing from the pipeline above and was simply out of step with `core/`. The two are consumed together and shadowed into the same loader jar, so they now match. `mc1710/` gets modern Java through the GTNH convention plugin (`enableModernJavaSyntax = jvmDowngrader` in `mc1710/gradle.properties`), which is a separate mechanism from the commented-out one here. `mc1201/` modules target Java 17 with no downgrade.

| Module | Authored in | Compiled to | Notes |
|---|---|---|---|
| `core/`, `platform/` | Java 17 | Java 17 bytecode | Toolchain 17. Modern syntax OK. Jabel/jvmDowngrader present but inactive — see the warning above |
| `freetype-msdfgen-harfbuzz-bindings/` | Java 8 | Java 8 bytecode | Genuinely Java 8 source; JNI bindings, deliberately minimal |
| `mc1710/` | Modern (GTNH convention) | Java 8 bytecode | `enableModernJavaSyntax = jvmDowngrader` in `mc1710/gradle.properties` — the convention plugin's mechanism, not this repo's |
| `mc1201/common/`, `mc1201/forge/`, `mc1201/neoforge/`, `mc1201/fabric/` | Java 17 | Java 17 | Full Java 17 API available |

**Forbidden cross-module imports:**

| In module | Forbidden | Reason |
|---|---|---|
| `core/`, `platform/` | `net.minecraft.*`, `net.minecraftforge.*`, `org.lwjgl.*` | Loader-blind — **guard currently disabled**, see the warning above |
| `mc1201/*` | `org.lwjgl.input.Mouse`, LWJGL2 input types | LWJGL3 environment |
| `mc1710/*` | LWJGL3 GL calls, `com.mojang.*` | LWJGL2 environment |

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

**`#type <name>`** selects the vertex format by its registered `key`. The compiler resolves the name against `CgVertexFormat.REGISTRY` at parse time and injects the format's vertex attribute declarations (`in <glslType> <name>;`) into the generated vertex GLSL immediately after the `cg_env.glsl` include. Unknown names throw `CgShaderParseException` at parse time listing all registered types.

Built-in types: `spatial` (`CgVertexFormat.SPATIAL` — pos3/uv2/normal3), `pos3_uv2_col4ub`, `pos2_uv2_col4ub`. Custom formats self-register on `CgVertexFormat.build()` under their `debugName` and become immediately usable as a `#type`.

```glsl
#type spatial
#pragma cg_feature RECEIVE_SHADOWS    // compile-time keyword; max 8 per shader
#pragma cg_feature FOG_ON
#pragma cg_use quad                   // opt into an engine buffer (see below); omit if unused

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

**Scene samplers** — auto-bound by the engine before every material draw; do not declare or bind these yourself:

| GLSL name | Type | Unit | Content |
|---|---|---|---|
| `cg_DepthBuffer` | `uniform sampler2D` | `CgBindingPoints.DEPTH_TEXTURE_UNIT` | Scene depth snapshot (DEPTH24_STENCIL8) captured just before the opaque pass via one `glBlitFramebuffer` from MC's main render target. Valid in both vertex and fragment stages of all passes. **Do not bind user Properties samplers to `CgBindingPoints.DEPTH_TEXTURE_UNIT`.** |

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

### Stage defines — `CG_VERTEX_STAGE` / `CG_FRAGMENT_STAGE`

One `.shader` file becomes **two** GLSL programs, and `partitionGlobalDecls` hoists **every** `#`-line
from a material or pass preamble — `#include` very much included — into **both** of them. There is no
stage filtering, by design: a shader author writes one preamble, not two.

The consequence is the rule:

> **A lib included at material scope is compiled into the vertex stage too. If any of it is
> fragment-only, it must guard itself.**

The compiler emits exactly one of these into each generated source, **before** the user directive
block, so an included lib can see it:

| Define | Present in |
|---|---|
| `CG_VERTEX_STAGE 1` | generated vertex source only |
| `CG_FRAGMENT_STAGE 1` | generated fragment source only |

```glsl
#ifndef CG_VERTEX_STAGE
float sdf_coverage(float dist) { return 1.0 - smoothstep(-fwidth(dist), fwidth(dist), dist); }
#endif
```

**Use `#ifndef CG_VERTEX_STAGE`, not `#ifdef CG_FRAGMENT_STAGE`.** Raw `.vert`/`.frag` files go
through `CgShaderPreprocessor` with *no* stage defines at all, so an `#ifdef` silently deletes the
function from every one of them. `#ifndef` includes it everywhere except the one stage that cannot
have it.

> **Both facts here were learned the expensive way.** `sdf.glsl`'s `fwidth` reached the vertex stage
> and an AMD tester could not launch the UI gallery at all, while it ran flawlessly on NVIDIA —
> NVIDIA accepts derivative builtins in a vertex shader, AMD correctly refuses. And the define used
> to be emitted *after* the user `#include`s, which meant every guard evaluated identically in both
> stages and did nothing at all. Ordering is what makes the guard mechanism exist.

Fragment-only, i.e. never legal in a vertex shader: `fwidth`/`dFdx`/`dFdy` and their `Fine`/`Coarse`
variants, `discard`, `gl_FragCoord`, `gl_FrontFacing`, `gl_PointCoord`, `gl_FragDepth`,
`interpolateAt*`, `gl_SampleID`/`gl_SamplePosition`/`gl_SampleMask`.

Two things enforce this so it cannot regress silently:

- **`ShippedShaderStagePurityTest`** (in both CrystalGraphics and CrystalGUI) compiles every shipped
  `.shader` and asserts no such identifier is *reachable* in the generated vertex source. It resolves
  the stage conditionals itself, because `CgShaderPreprocessor` expands `#include` but leaves
  `#ifdef` to the driver. GL-free, so it catches this on any machine.
- **`--mode=shader-compile-audit`** puts the real driver over every shipped shader and keyword
  variant, collecting failures instead of crashing on the first. Run it on any GPU that disagrees.

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

## Engine Buffers — `#pragma cg_use`

`CgFrameBlock` and `CgObjectDataBuffer` are declared unconditionally in `cg_env.glsl` because
essentially every shader wants them. Buffers that only a minority of shaders need are **opt-in**:

```glsl
#type pos2_uv2_col4ub
#pragma cg_use quad
```

| Token | Provides | Needed by |
|---|---|---|
| `quad` | `QUAD_DATA(n)` + `CG_QUAD_WORLD_POS` / `CG_QUAD_UV` / `CG_QUAD_COLOR` / `CG_QUAD_NORMAL` / `CG_QUAD_ATLAS_LAYER` | Any shader drawn through `CgQuadRenderer` — UI quads, text glyphs, SDF rects |
| `curve` | `CURVE_DATA(n)` + `CG_CURVE_WORLD_POS` / `CG_CURVE_P0`–`P2` / `CG_CURVE_COLOR0`–`1` / `CG_CURVE_WIDTHS` / `CG_CURVE_FEATHER` / `CG_CURVE_FLAGS` | Any shader drawn through `CgVectorRenderer` — Bézier strokes, graph wires, connectors |

> **`curve` is the one engine buffer read from the fragment stage as well as the vertex stage.** A
> stroke is an analytic SDF evaluated per pixel, so the fragment needs the control points themselves;
> it re-reads `CURVE_DATA(CG_INSTANCE_ID)` rather than receiving them as varyings, because the
> `.shader` v2f DSL has no `flat` qualifier to offer (`cg_InstanceId` is the only compiler-generated
> flat varying). This needs no compiler change — `appendAttachedBuffers` already runs for the fragment
> source too — but it does mean the TBO fallback occupies that texture unit in both stages.

The buffer's GLSL declaration is injected **during parsing, before anything can compile**, so the
symbols exist no matter what triggers the first compile. Register a new token with
`CgEngineBufferRegistry.register(...)` — providers hold a `Supplier`, so registration never forces
the provider class's static init (which would allocate against `CgBindingPoints` too early).

**Both directions are hard parse errors:**

| Mistake | Result |
|---|---|
| Uses `CG_QUAD_*`/`QUAD_DATA` without `#pragma cg_use quad` | `CgShaderParseException` naming the line to add |
| Declares an unregistered token | `CgShaderParseException` listing registered tokens |
| Duplicate or malformed token | `CgShaderParseException` |

> **Do not attach these from Java.** A `CgQuadRenderer.attachTo(material)` helper used to exist and
> was removed. Attaching at first use loses to anything that compiles the shader earlier — notably
> `CgMaterial.enableKeyword`, which recompiles on the spot when the shader has not been parsed yet.
> The result was GLSL built with `QUAD_DATA` undeclared, and because a failed compile leaves the
> parsed feature list empty, it surfaced as **"Keyword 'X' is not declared as `#pragma cg_feature`"**
> — naming a pragma that was present and correct. Declaring the dependency in the shader removes the
> ordering question entirely.

Like `cg_feature`, `cg_use` lines are stripped and never reach generated GLSL, and are only read
from the material-level preamble (never from inside a `Pass` body).

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

// 4. Execute — MC loaders call the split API; harness uses the convenience wrapper:
pipe.executeOpaquePass(partialTicks, sourceFboId); // after MC entity render; blits depth snapshot
pipe.executeTransparentPass();                     // after MC water/translucent render
pipe.endFrame();
// pipe.execute(partialTicks) is a convenience wrapper (harness / single-hook paths only)

// 5. Teardown (CgGraphicsLifecycle.destroyContext() calls this automatically)
CgRenderPipeline.destroy();
```

**Execute sequence**: depth snapshot blit (one `glBlitFramebuffer` from MC's main FBO before first opaque call) → sort (opaque front-to-back, transparent back-to-front) → UBO upload → depth prepass → opaque forward pass → transparent pass. All GL state is saved/restored via `CgGlState.saveAll()` around each pass block.

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
| `sdf.glsl` | `sdf_rounded_box` (uniform / per-corner / elliptical), `sdf_segment`, `sdf_bezier` (exact quadratic, with a straight-line fallback), `sdf_coverage` (**fragment-only, guarded**) |
| `stroke.glsl` | `stroke_coverage(p, p0,p1,p2, widths, feather, cap, out t)` — the whole shared body of every `CgVectorRenderer` consumer: taper, caps, feathered edge |

> **`stroke.glsl` exists so there is exactly one copy of the cap logic.** `curve.shader` and
> CrystalGUI's `gui_curve.shader` must differ in render state (`LEQUAL` vs `ALWAYS`) and in one
> `_LayerOpacity` multiply, and a Pass's `RenderState` cannot vary per keyword variant — so they are
> genuinely two materials. They are not two implementations. The cap handling was wrong three times in
> a row and every version rendered something plausible rather than failing, which is precisely the
> situation where a duplicated body gets fixed in one file only.

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

> Imports come from **`com.crystalgraphics.platform.gl.state`** (`CgGlState`, `CgGlScope`, `CgGlSlot`); `CgGlStateManager` is in `platform.gl`.

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

For UI, 2D overlays, and non-material draw paths (not the CrystalShader material pipeline). `CgBatchRenderer` + `CgRenderLayer` — layer-based immediate-mode quad/triangle batching. `CgBufferSource` — per-context owner, not a singleton. `CgTextLayers`/`CgDynamicTextureRenderLayer` still exist but are **no longer used by `CgTextRenderer`** — as of the batch-ownership migration (see `text/render/AGENTS.md`), `CgTextRenderer` owns its own private `CgBatchRenderer` directly instead of going through a caller-provided layer.

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
| 0 | `CgTextRendererRegistry.get().deleteAll()` | Any `CgTextRenderer` still alive (backstop — individual owners should already have called `delete()`); runs first so each renderer's owned `CgBatchRenderer` (VAO/VBO) releases individually before the bulk sweep below |
| 1 | `CgVertexArrayRegistry.get().deleteAll()` | All VAOs — instanced first (they reference both VBOs), then non-instanced |
| 2 | `CgMeshRegistry.get().deleteAll()` | Static mesh VBOs + IBOs + per-mesh VAOs |
| 3 | `CgVertexBufferRegistry.get().deleteAll()` | All streaming VBOs (base + instance) |
| 4 | `CgQuadIndexBuffer.freeAll()` | Shared quad IBO |
| 5 | `CgTextureManager.get().freeAll()` | All cached textures + fallback |
| 5c | `CgFontRegistry.get().releaseAll()` | Glyph atlas textures + background generation executor, reset in place (reusable immediately) |
| 6 | `CgMaterialRegistry.get().deleteAll()` | Material instances + GL shader programs |
| 7 | `CgShaderBufferRegistry.get().deleteAll()` | User SSBO/TBO/UBO resources |
| 8 | `CgRenderPipeline.destroy()` | Frame UBO + object SSBO + command queue; nulls depth snapshot FBO reference (GL object freed by step 9) |
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
| `CgFontRegistry` | `CgFontRegistry.get()` | Glyph atlas textures (bitmap/MSDF/MTSDF) + background generation executor | `releaseAll()` on teardown (via lifecycle); parameterized constructors remain public for harness testing of custom atlas sizes/configs — see `text/cache/AGENTS.md` |
| `CgTextRendererRegistry` | `CgTextRendererRegistry.get()` | Tracks every `CgTextRenderer` for teardown; auto-resizes screen-sized ones (`create()`, the default — opt out via `createManualSized()`) on `onResize()` | Does not own renderer *lifecycle* the way other registries do — owners still call `delete()` themselves; `deleteAll()` on teardown is a backstop, not the primary path — see `text/render/AGENTS.md` |


---

# Package AGENTS.md Index

All 35 package guides under `src/main/java/com/crystalgraphics/`. Relative paths omit the common prefix.

### Demo / Benchmarks
| Path | What it covers |
|---|---|
| `demo/AGENTS.md` | `CgFontDemo` — platform-agnostic font benchmark and atlas diagnostic viewer |

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
| `api/state/AGENTS.md` | `CgRenderState`, `CgDepthState`, `CgBlendState`, `CgCullState`, `CgStencilState`, `CgTextureState` (`CgGlSlot` moved to `platform.gl.state`) |
| `gl/state/AGENTS.md` | Nothing — the package is empty. Kept as a signpost to the state framework in `platform.gl` / `platform.gl.state`, and a record of what was removed |

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
| `text/render/AGENTS.md` | Draw-time orchestration |

### Debug
| Path | What it covers |
|---|---|
| `gl/debug/AGENTS.md` | `CgDebugBlit` — fullscreen texture blit, covering-triangle, no VBO |

### Platform SPI (platform subproject)
| Path | What it covers |
|---|---|
| `platform/src/main/java/com/crystalgraphics/platform/AGENTS.md` | `CgGlDispatch`, `CgCapabilityProbe`, `CgResourceService`, `CgRenderingService`, `CgLifecycleService`, `CgReloadService`, `CgFrameCallback`, `CgPlatform` — the SPI contract between `core/` and all four loaders (`mc1710/`, `mc1201/forge`, `mc1201/neoforge`, `mc1201/fabric`) |

---

# Minecraft Integration Glue

This section covers the layer that wires CrystalGraphics into the Minecraft/Forge runtime. Understanding it matters when debugging frame-timing issues, hot-reload failures, or GL state conflicts between CG and other mods.

## `CrystalGraphics.java` — Forge Mod Container

The root `@Mod` class (`modid = "crystalgraphics"`). Intentionally performs **no GL work** — it is purely a Forge dependency anchor and lifecycle logger. All rendering logic lives in `mixins/`. **There is no coremod** — `mc/coremod/` and its GL-redirect layer were deleted on 2026-07-31; see [GL state](#gl-state--cgglstatemanager). Other mods declare `required-after:crystalgraphics` in their `mcmod.info` to depend on this mod.

## Render Loop Hook — `CgRenderHook`

`mixins/early/impl/client/CgRenderHook` is a SpongePowered Mixin on `EntityRenderer.renderWorld` with two `@Inject` methods:

- **`onBeforeTranslucentBlocks`** — fires immediately before `sortAndRender(pass=1)` (MC line ~1367). Calls `CgGraphicsLifecycle.onOpaquePass(partialTicks, w, h, mc.framebufferMc.framebufferObject)`: blits the depth snapshot from MC's main FBO, then runs depth prepass + opaque forward pass. At this point MC has finished opaque world geometry so the depth buffer is fully populated.
- **`onAfterTranslucentContent`** — fires immediately before `ForgeHooksClient.dispatchRenderLast` (MC line ~1430). Calls `CgGraphicsLifecycle.onTransparentPass()`: transparent back-to-front pass. MC then continues with its own translucent terrain, naturally depth-interleaving with CG geometry.

This hook is why you never call `pipe.executeOpaquePass/executeTransparentPass` manually in game code — they are called once per frame at the correct moments.

## Hot-Reload Hook — `CgAssetReloader`

`core/mc/CgAssetReloader` (now in `core/`) wires reload callbacks via the platform SPI (`CgReloadService`). It fires on **F3+T** and on resource pack changes via `ReloadService1710`. On reload it calls, in order:

1. `CgTextureManager.get().reloadAll()` — re-uploads all textures
2. `CgShaderManager.reloadAll()` — marks all raw `CgShader` instances dirty (recompile on next `bind()`)
3. `CgMaterialRegistry.get().reloadAll()` + `CgMaterialShaderRegistry.get().reloadAll()` — marks all materials dirty

Failures in each step are isolated and logged — a broken shader does not prevent textures from reloading.

## Platform Service Adapters — `mc/platform/`

`mc1710/src/main/java/com/crystalgraphics/mc/platform/` contains the MC 1.7.10 concrete implementations of all six platform SPI interfaces. These are the **only classes** that may reference MC/Forge types. Bootstrap is owned by `PlatformRegistry1710`, called from `CrystalGraphics` event handlers — not from `CrystalGraphics` directly.

**Package guide**: `mc1710/src/main/java/com/crystalgraphics/mc/platform/AGENTS.md`

| Class | Implements | Key role |
|---|---|---|
| `PlatformRegistry1710` | — | Two-phase bootstrap: `onPreInit` registers services, `onInit` attaches reload listener |
| `Lwjgl2GlDispatch` | `CgGlDispatch` | All raw LWJGL2 GL calls; FBO waterfall; `bindFramebufferCompat` → `OpenGlHelper` |
| `Lwjgl2CapabilityProbe` | `CgCapabilityProbe` | Reads `ContextCapabilities` from LWJGL2 |
| `ResourceService1710` | `CgResourceService` | Delegates to `IResourceManager` — single `openStream` method |
| `RenderingService1710` | `CgRenderingService` | Legacy `onFrameBegin` path — superseded by `CgRenderHook`'s two-inject split for the opaque/transparent pass separation |
| `LifecycleService1710` | `CgLifecycleService` | Delegates `CgGraphicsLifecycle` init/destroy/resize directly |
| `ReloadService1710` | bridge utility | `attachToResourceManager()` wires `IReloadableResourceManager` → `CgPlatform.reload().onReload()` |

## GL state — `CgGlStateManager`

> **Superseded 2026-07-30, finished 2026-07-31.** `GLStateMirror`, `CgGlStates` and the entire ASM coremod
> (`mc/coremod/` — coremod, transformer, redirects, coverage matrix) are **deleted**. Earlier revisions
> of this file described the mirror as what made `CgGlState.save/restore` reliable — it never could be.

**Why the mirror was abandoned.** It depended on an ASM transformer rewriting GL call sites process-wide.
That cannot be made reliable: our redirector is modelled on Angelica's, targets the same call sites, and has
been observed with **Angelica redirecting ours into its own**. Two transformers competing for the same
bytecode cannot both be authoritative, and any third mod doing raw GL ends the guarantee regardless. It was
also only ever fed on 1.7.10 — on the other three targets it was inert.

**What replaced it.** `gl/state/CgGlStateManager` keeps a CPU-side shadow, eliminates redundant GL calls,
and takes truth from a `CgGlStateProvider` at declared boundaries rather than by observing other code.
`CgGlState` / `CgGlScope` / `CgGlSlot` kept their signatures, so no call site changed.

Measured on the `text-3d` harness scene: `doBind.stateSave` went from **1,599 ms over 838 frames** to
**0.00 ms**, and the worst single frame from **346.8 ms** to a whole-`doBind` max of **2.16 ms**.

Package guide: `core/src/main/java/com/crystalgraphics/gl/state/AGENTS.md`.
Design record and eight implementation corrections: `docs_research/CGGLSTATEMANAGER_PLAN.md`.

**Four rules worth knowing before touching rendering code:**

1. **Raw `CgGL` is fine.** Tracking lives *inside* `CgGL`'s setters, so there is no way to write GL state
   without the shadow seeing it. The typed records (`CgBlendState`, `CgDepthState`, …) are a convenience,
   not a safety requirement — the `cgStateWriteGuard` task that used to police this was deleted along with
   the problem it policed.
2. Any code that resets GL state wholesale **with raw GL that bypasses `CgGL`** — the harness's
   `GlStateResetHelper`, a foreign mod — must call `CgGlState.invalidateAllIfPresent()`. Only what goes
   around `CgGL` is invisible.
3. **Only genuinely global state may be deduplicated.** Anything an object binding implicitly swaps must be
   invalidated when that object changes — `GL_ELEMENT_ARRAY_BUFFER` is per-VAO state, and treating it as
   global elided a required bind and killed every indexed draw through the affected VAO.
4. A wrong decision here produces a **missing GL call** — wrong rendering, no exception. Diagnose with
   `-Dcrystalgraphics.state.verify=true`, which names the offending domain, or
   `-Dcrystalgraphics.state.noDedup=true` to rule the manager out entirely.

---

## mc1201 Integration Glue (Forge · NeoForge · Fabric)

mc1201 uses a different integration model from mc1710. There is no ASM coremod, no GL state redirect, and no LWJGL2 polling. The shared implementation lives in `mc1201/common/`; each loader subproject (`forge/`, `neoforge/`, `fabric/`) is a thin bootstrap that only registers events and calls `CgPlatform.register()`.

### `mc1201/common/` — Where Shared Code Lives

All platform service logic and mixins that apply to all three mc1201 loaders live here. When adding a new mc1201 platform feature, implement it in `mc1201/common/` first, then wire the event in each loader.

| Class | Role |
|---|---|
| `PlatformService1201` | Compositor — implements all SPI interfaces, registers as the single `CgPlatformService` |
| `Mc120xGLBackend` | GL dispatch — routes to `RenderSystem` → `GlStateManager` → raw LWJGL3 GL in that order |
| `MixinGameRenderer` | Injects after `renderLevel()` to drive `CgGraphicsLifecycle.onOpaquePass` + `onTransparentPass` split; also owns first-frame lazy init via `onRenderFrame` |
| `MixinMinecraftShutdown` | Injects into MC shutdown to call `CgGraphicsLifecycle.destroyContext()` |

### Loader Bootstrap Pattern

Each loader's mod entrypoint does exactly two things: register the platform service and subscribe events. No GL work in constructors.

```java
// Forge — CrystalGraphics1201Forge constructor
CgPlatform.register(PlatformService1201.getInstance());
// events via @Mod.EventBusSubscriber(bus = Bus.FORGE)

// NeoForge — CrystalGraphics1201NeoForge constructor
CgPlatform.register(PlatformService1201.getInstance());
NeoForge.EVENT_BUS.addListener(CrystalGraphics1201NeoForge::onRenderGui);
NeoForge.EVENT_BUS.addListener(CrystalGraphics1201NeoForge::onMouseScroll);

// Fabric — CrystalGraphics1201Fabric.onInitializeClient()
CgPlatform.register(PlatformService1201.getInstance());
HudRenderCallback.EVENT.register(...);
ClientLifecycleEvents.CLIENT_STARTED.register(...);
```

### mc1201 Render Stage Events

The pipeline is driven by two per-frame events across all three mc1201 loaders:

| Stage | Forge | NeoForge | Fabric | When |
|---|---|---|---|---|
| Opaque | `RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES` | same | `WorldRenderEvents.AFTER_ENTITIES` | After block entities, before `renderChunkLayer(translucent)` |
| Transparent | `RenderLevelStageEvent.Stage.AFTER_PARTICLES` | same | `WorldRenderEvents.AFTER_TRANSLUCENT` | After translucent terrain + tripwire + particles |

Each handler calls `mc.getMainRenderTarget().bindWrite(false)` before the CG lifecycle call to guard against Fabulous OIT leaving a non-main FBO bound.

**Iris/Oculus**: when a shader pack is active, CG geometry renders into the main FBO **outside** Iris's deferred GBuffer chain and will appear unlit under deferred pipelines. `cg_DepthBuffer` remains valid — the depth texture is shared. Use `CgRenderPipeline.isIrisActive()` (delegates to `CgIrisCompat` in `core/mc/compat/`) for detection.

### mc1201 Input Event Patterns

mc1710 uses LWJGL2 polling (`Mouse.getDWheel()`). mc1201 is event-driven:

| Input | Forge | NeoForge | Fabric |
|---|---|---|---|
| Mouse scroll | `InputEvent.MouseScrollingEvent` (Forge bus) | `InputEvent.MouseScrollingEvent` (NeoForge bus) | GLFW callback chain via `glfwSetScrollCallback` |
| Keyboard | `InputEvent.Key` (Forge bus) | `InputEvent.Key` (NeoForge bus) | Fabric API keyboard events |

Fabric API has **no global mouse scroll event**. The pattern for GLFW callback chaining that preserves MC's own handler:

```java
ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
    long window = client.getWindow().getWindow();
    final GLFWScrollCallback[] prev = { GLFW.glfwSetScrollCallback(window, null) };
    GLFW.glfwSetScrollCallback(window, (win, dx, dy) -> {
        // your handler
        if (prev[0] != null) prev[0].invoke(win, dx, dy);  // preserve MC's handler
    });
});
```

**Mixin policy**: Mixins are last resort. Always prefer native loader events or GLFW callbacks. A Mixin is justified only when no event exists and the GLFW callback approach is also unavailable.

### ⚠️ mc1201 Forge/NeoForge Dev-Run Classpath Rule

> `runtimeOnly` Gradle deps are **invisible** to ModDevGradle dev runs. The `mods{}` block is the only source ModDevGradle reads for the Forge/NeoForge run classpath.
>
> Every module bundled in the final JAR (`platform/`, `core/`, `mc1201:common`, `freetype-msdfgen-harfbuzz-bindings`) must appear in **two places** in each Forge/NeoForge loader's `build.gradle.kts`:
> 1. `sourceSet(project(":foo").extensions.getByType<SourceSetContainer>()["main"])` inside `mods { create("crystalgraphics") { ... } }`
> 2. `from(zipTree(...jar...))` inside the `shadowJar` task
>
> **Fabric is NOT exempt** — `runtimeOnly` deps are on the JVM system classpath but Knot classloader
> does NOT delegate `com.crystalgraphics.*` to the system classloader. Fabric must also use JAR
> bundling — add `from(zipTree(...jar...))` inside `tasks.jar` AND `shadowJar`.
> Do NOT use `loom.mods { sourceSet(crossProject) }` — Loom 1.16.2 tries to apply
> `fabric-loom-companion` to the cross-project, which fails for non-Loom projects.
> See `mc1201/fabric/AGENTS.md` for full details.

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

# Minecraft Source Code Location (mc1201)

Decompiled, Parchment-mapped sources for each mc1201 loader module.
Run once after checkout (or when toolchain versions change):

```bash
./gradlew extractAllMcSources
```

Sources and resources land at `build/mc-src/` within each loader subproject:

| Module | Java sources | Resources | Notes |
|---|---|---|---|
| `mc1201/neoforge/` | `build/mc-src/java/` | `build/mc-src/resources/` | Targets MC **1.20.4** / NeoForge 20.4.x — NeoForge never published a stable 1.20.1 series |
| `mc1201/forge/` | `build/mc-src/java/` | `build/mc-src/resources/` | MC 1.20.1 / MinecraftForge 47.x |
| `mc1201/fabric/` | `build/mc-src/java/` | `build/mc-src/resources/` | MC 1.20.1 / Fabric — sources generated by Loom's Vineflower decompiler |

Per-module details: [`mc1201/neoforge/AGENTS.md`](mc1201/neoforge/AGENTS.md) ·
[`mc1201/forge/AGENTS.md`](mc1201/forge/AGENTS.md) ·
[`mc1201/fabric/AGENTS.md`](mc1201/fabric/AGENTS.md)

---

# External References

**LWJGL 2.9 Javadoc**: https://javadoc.lwjgl.org/  
**OpenGL Registry**: https://www.khronos.org/registry/OpenGL/  
**GTNH Build Plugin**: https://github.com/GTNewHorizons/

---

# Debug / JVM Flags

```bash
# Hotswap (dev only) — re-applies the LaunchWrapper transformer chain, Mixins included,
# to classes HotswapAgent redefines. The redirector.* flags are gone with the coremod.
-Dcrystalgraphics.hotswap.verbose=true               # log each class transformed

# GL state manager (see gl/state/AGENTS.md)
-Dcrystalgraphics.state.verify=true                  # verify the shadow against the driver before
                                                     # eliminating any call; logs the offending domain
                                                     # with tracked-vs-actual. Very slow — diagnosis only.
-Dcrystalgraphics.state.noDedup=true                 # never eliminate a call; distinguishes "the shadow
                                                     # is lying" from a semantic regression in one run

# Shader
-Dcrystalgraphics.shader.devmode=true                # emit #line directives in preprocessed output
-Dcrystalgraphics.shader.resourceOverrideDir=path    # filesystem override dir for shader sources
```
