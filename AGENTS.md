# CrystalGraphics — Agent Knowledge Base

**What**: a modern OpenGL rendering engine for Minecraft mods — materials, meshes, framebuffers,
instancing and text — shipped as **one jar** for Forge 1.7.10–26.2, NeoForge 1.20.2–26.2 and
Fabric 1.14.4–26.2. **Authored in** Java 25, with a Java 8 copy of every engine module. **The parent
of** CrystalGUI, which builds every node against this repository's node of the same version.

> **The goal every line serves**: a node-based shader graph for Minecraft on every version the jar
> supports — Unity's Shader Graph, true to GLSL, on a modern GL 3.x+ pipeline with instancing as the
> default draw path. It shipped in CrystalGUI (`com.crystalgui.app.shadergraph`). **Read
> the manifesto (`plan/crystalgraphics/archive/CRYSTALSHADER_MANIFESTO.md`, private) before any rendering or shader decision.**

---

## Build and run

```bash
./gradlew :core:compileJava
./gradlew :core:test --tests "<Class>"          # NEVER unscoped: the full suite hangs
./gradlew checkAllTargets                        # every Minecraft node compiles -- before every commit
./gradlew singleJar checkSingleJar               # the shipped jar, build/libs/crystalgraphics-<v>.jar
python singlejar-logic/mcapi.py <Class> [member] # any Minecraft or loader API, on every node's version
```

**Running Minecraft is CrystalGUI's.** Dev clients, `serverSmoke`, `prodSmoke` and the GL debug harness
are all driven from the CrystalGUI checkout this repository sits in — this mod alone draws nothing.
Their commands are CrystalGUI's `AGENTS.md` § *Build and run*.

| Read | When |
|---|---|
| **[`docs/BUILD.md`](docs/BUILD.md)** | **First, for anything about the build**: layout, nodes and toolchains, stub mode, commands, releasing, and adding a Minecraft version |
| [`docs/SETUP.md`](docs/SETUP.md) | Setting up a mod on CrystalGraphics — one version, or one jar across many |
| [`singlejar-logic/README.md`](singlejar-logic/README.md) | How one jar serves every loader, and the guide another project follows. Before touching `singlejar-logic/`, `cg-single-jar`, relocation, remapping or the class-major ceiling |
| [`singlejar-logic/STUBS.md`](singlejar-logic/STUBS.md) | Before adding a node, changing its pins, or touching a branch script's toolchain |
| [`runtime/mc/modern/README.md`](runtime/mc/modern/README.md) | Before touching a modern node; each branch has its own `AGENTS.md` |
| CrystalGUI's `docs/CGUI_CROSS_VERSION.md` | Code that must run on every version |
| [`docs/NATIVE_BUILD_PROCESS.md`](docs/NATIVE_BUILD_PROCESS.md) | Rebuilding the FreeType/HarfBuzz/msdfgen natives |
| [`docs/HOTSWAP_SETUP.md`](docs/HOTSWAP_SETUP.md) | Hotswapping into a running 1.7.10 client |

**Five rules the build will not tell you:**

1. **A Minecraft version is added here first**, then in CrystalGUI (`docs/BUILD.md` § *Adding a Minecraft
   version*).
2. **Nodes compile from `singlejar-logic/stubs.zip` by default.** Changing code never touches it; adding a
   node or changing its pins means regenerating it. A run task makes its node real.
3. **One compiler, JDK 25.** `core`, `platform` and `runtime/lwjgl/*` are Java 25 and publish a Java 8
   copy every consumer below 25 resolves. Never lower their Java to suit a consumer — and javac does not
   check the API: a Java 9+ call fails on a Java 8 instance unless jvmdg stubs it.
4. **Switching the active Stonecutter node rewrites `src/` in place.** Switch back before committing.
5. **Configuration cache stays off** (ModDevGradle).

---

## Render testing — the GL debug harness

For anything that touches rendering, shaders, FBOs, text or atlases, **test in the harness, not
Minecraft**: it boots in seconds, needs no Minecraft context, and writes PNGs. It is CrystalGUI's
submodule (`gl-debug-harness/`, Java 25) and runs from CrystalGUI's root; authoring rules are its own
`AGENTS.md`.

```bash
./gradlew :gl-debug-harness:runHarness --args="--list"
./gradlew :gl-debug-harness:runHarness --args="--mode=forward-renderer"       # CgRenderPipeline end-to-end
./gradlew :gl-debug-harness:runHarness --args="--mode=material-dual-path"     # CgMaterial shader compilation
./gradlew :gl-debug-harness:runHarness --args="--mode=instancing-test"        # Instanced draw
./gradlew :gl-debug-harness:runHarness --args="--mode=attached-buffer-stress" # SSBO/TBO attach
./gradlew :gl-debug-harness:runHarness --args="--mode=mesh-test"              # CgMeshLoader
./gradlew :gl-debug-harness:runHarness --args="--mode=atlas-dump"             # Glyph atlas
./gradlew :gl-debug-harness:runHarness --args="--mode=text-3d"                # Full text pipeline
./gradlew :gl-debug-harness:runHarness --args="--mode=capability-report"      # GL capability probe
./gradlew :gl-debug-harness:runHarness --args="--mode=shader-compile-audit"   # every shipped .shader + keyword variant
# Outputs land in gl-debug-harness/harness-output/{scene}/
```

- Never call raw GL — use `CgVertexArray`, `CgStreamBuffer`, `CgTexture`, `CgFrameBuffer`, etc.
- Implement `HarnessSceneLifecycle` (managed, single frame) or `InteractiveSceneLifecycle` (loop + camera),
  and register the scene in `SceneRegistry.createDefault()` — or, for a project on top of CrystalGraphics,
  in its own `HarnessExtension`. The harness names no such project.
- `ArtifactService.requestCapture("suffix")` for interactive captures; `ScreenshotUtil` for managed ones.
- GL state cleanup after `render()` is automatic.

---

## Module layout

Every module has one role — code in the wrong one either fails to compile or silently breaks a loader.
The build view (Java levels, toolchains, what a consumer build includes) is `docs/BUILD.md` § *Layout*.

| Module | Java | Role |
|---|---|---|
| `platform/` | 25 + 8 copy | The SPI only: `CgPlatform`, `CgPlatformService`, `CgService`, `CgGLBackend`, `CgGL`, the services. No implementation — [its guide](platform/src/main/java/com/crystalgraphics/platform/AGENTS.md) |
| `core/` | 25 + 8 copy | All rendering: materials, meshes, the pipeline, fonts, text, atlases. Calls `CgPlatform`/`CgGL` for every GL or lifecycle operation; never imports Minecraft, a loader or LWJGL |
| `freetype-msdfgen-harfbuzz-bindings/` | 8 | JNI text shaping, with its natives |
| `runtime/lwjgl/2`, `runtime/lwjgl/3` | 25 + 8 copy | **Tier 1**: GL backend, context, input and cursor per LWJGL (`Lwjgl2*`, `Lwjgl3*`, `Glfw*`). **Name no Minecraft class** (import guard), so one copy serves every host of that LWJGL and the harness. LWJGL3 is pinned to 3.2.2, the oldest in range, so a symbol a 1.16 client lacks is a compile error |
| `runtime/lwjgl/vulkan` | 25 + 8 copy | **Tier 1 for Vulkan** (`plan/device-vulkan.md`): `CgVulkanDevice`, a `CgDevice` over a `CgVulkanHost` — `host.OwnedVulkanHost` when nothing else owns the device; `shader.ShadercGlslCompiler`, the tracked backend's GLSL to SPIR-V over shaderc and SPIRV-Cross, as Minecraft 26.2 compiles its own; and the device's parts in `resource`, `command` and `format`. Pinned to LWJGL 3.4.1, the oldest a 26.2+ client ships. Its tests run core on the tracked backend (`EngineOnTrackedBackendTest`), since core's own tests carry LWJGL 2 |
| `runtime/mc/1710/` | 25 → 8 | Forge 1.7.10 on RetroFuturaGradle: `PlatformService1710`, the `CgRenderHook`/`MixinMinecraft` mixins, Angelica's state provider |
| `runtime/mc/legacy/` | 8 | Forge 1.8–1.12.2, a Stonecutter tree (nodes 1.8.9, 1.10.2, 1.12.2) on Unimined: `PlatformServiceLegacy`, `GlStateManagerGLBackend`, SRG-named mixins MixinBooter applies |
| `runtime/mc/modern/` | each node's | Forge 1.13.2+, NeoForge 1.20.2+, Fabric 1.14.4+, a Stonecutter tree — branches `common` (**tier 2**: `PlatformServiceModern`, `Blaze3dGLBackend`, `HostStateVerifier`, `LifecycleModern`) and one per loader, which is registration only |
| `runtime/mc/shared/` | 8 | **The variant selector** every mod built from these repositories reads (`com.crystalgraphics:mc-shared`): `Variants`, `VariantEntry`, `VariantBootstrap`, `LoaderProbe`, `CrashVariant`. Java 8 because FML 1.7.10 refuses any class above major 52 |
| `runtime/mc/forge-bootstrap/`, `forge-stubs/` | 8 | The one `@Mod` class for every Forge from 1.8, compiled against a union annotation |
| `singlejar-logic/` | — | The shared build logic (merge, tree, stubs, catalog) and `stubs.zip`; CrystalGUI uses it too |
| `runtime/mc/modern/build-logic/` | — | This repository's convention plugins |

**Rule**: `core/` and `platform/` have zero compile dependency on LWJGL, Minecraft or any loader.

> **Enforced**: `compileJava` in both `core/build.gradle.kts` and `platform/build.gradle.kts` fails on an
> import of `net.minecraft`, `cpw.mods.fml`, `net.minecraftforge` or `org.lwjgl`. What it cannot see is a
> client-only class *constructed* on a server — that is a runtime property, and `serverSmoke` is what
> catches it.

---

## Platform SPI architecture

This is the load-bearing architectural law. Read it before writing code that touches GL or a host.

```
platform/        ← the SPI: CgGLBackend, CgGLContext, the services, CgService slots
    ↑
core/            ← rendering logic — calls CgGL and CgPlatform.lifecycle() etc.
    ↑
runtime/lwjgl/*  ← tier 1: the GL backend, context and input per LWJGL
    ↑
runtime/mc/*     ← a host: registers a bundle over tier 1, adds what names Minecraft
```

```java
// In core/ — never a raw GL call, never an LWJGL import:
CgGL.glBindFramebuffer(target, id);
CgPlatform.reload().onReload();

// A host, once, from an entry point that runs on both sides:
CgPlatform.register(PlatformServiceModern.getInstance());   // or PlatformService1710, PlatformServiceLegacy
```

**Registration must not demand a GL backend**: a dedicated server has none. Each bundle builds its services
lazily, and a client-only service (the cursor) is filled only on a client.

**If you find yourself calling raw GL inside `core/` or importing a loader type, you are in the wrong
module.**

---

## Cross-platform feature checklist

**A new platform capability:**

1. Declare it in `platform/`: a `CgGLBackend` method, a service method, or — when its absence is a
   legitimate configuration, as for a cursor — a `CgService` slot with an absent-value.
2. Implement it in tier 1 (`runtime/lwjgl/2`, `runtime/lwjgl/3`) when it is toolkit-level.
3. The compiler then names every bundle that must answer: `PlatformService1710`, `PlatformServiceLegacy`,
   `PlatformServiceModern`, the harness's `PlatformServiceHarness`, core's `TestPlatformService`.
4. Call it through `CgPlatform` / `CgGL` from `core/`.

**A new render or lifecycle hook:**

1. The logic goes in `core/` or the modern `common` branch (`LifecycleModern`) — loader-blind.
2. Each loader node only decides *what reaches it*: a loader event where one exists, a node mixin where
   none does (Forge 1.21.3+ world passes, Fabric 1.14.4–1.15.2). 1.7.10 and legacy Forge are mixins.
3. `python singlejar-logic/mcapi.py <EventClass>` says which versions have an event before you choose it.

**A module the shipped jar or a dev run must carry:** the merge takes each loader's thin jar plus modules
named once in `cg-single-jar.gradle.kts` (`singlejar-logic/README.md` § *The shape of a build*). A dev run is
separate: ModDevGradle reads only `mods {}` (`devRunSourceSet`, in `cgbuildlogic.AbstractModule`), and
Fabric's dev mod is `tasks.jar` bundling each module's `downgradedJar` —
`runtime/mc/modern/fabric/AGENTS.md`.

---

## Start Here By Task

| I need to… | Jump to section | Primary package guide |
|---|---|---|
| Write or load a `.shader` material | [CrystalShader Pipeline](#crystalshader-material-pipeline) | `api/material/AGENTS.md` |
| Submit geometry to the render pipeline | [CgRenderPipeline Usage](#cgrenderpipeline--per-frame-usage) | `api/render/AGENTS.md` |
| Create a framebuffer (FBO) for post-processing | [Framebuffers](#framebuffers) | `api/framebuffer/AGENTS.md` |
| Load or build a 3D mesh | [Meshes](#meshes) | `gl/mesh/AGENTS.md` |
| Create or load a texture | [Textures](#textures) | `api/texture/AGENTS.md` |
| Bind GPU buffers (SSBO/TBO/UBO) to a material | [Shader Buffers](#shader-buffers) | `gl/buffer/shader/AGENTS.md` |
| Save and restore GL state across a pass | [GL State Save/Restore](#gl-state-saverestore) | `gl/state/AGENTS.md` |
| Render text on screen | [Font/Text System](#fonttext-system) | `docs/font/README.md` |
| Work on batch/UI/2D layer rendering | [Batch Render Layer](#batch-render-layer-system) | `gl/render/AGENTS.md` |
| Load a resource file (shader source, config, image) | [Resource I/O](#resource-io--cgio-and-cgtextureio) | `util/io/CgIO` |
| Test rendering without Minecraft | [Render testing](#render-testing--the-gl-debug-harness) | `gl-debug-harness/AGENTS.md` |
| Build, ship, or add a Minecraft version | [Build and run](#build-and-run) | `docs/BUILD.md` |

---

## Project Philosophy

- **Fail Fast**: throw exceptions for unsupported capabilities; never silently degrade
- **Multi-Mod First**: other mods will mutate GL state; design for cooperation, not control
- **A GL 3.3 floor, and gates above it**: `CgCapabilities.detect()` throws below OpenGL 3.3, so nothing core in 3.3 has an ARB or EXT fallback; what is above it (SSBO, `glCopyImageSubData`) keeps its gate and its fallback. **A 3.2 context with 3.3's extensions passes**: vanilla 1.17–1.21.4 asks for 3.2 core and NVIDIA returns exactly that, so Fabric and pre-early-window Forge run on one. On it LWJGL 3 loads no 3.3 entry point, which is why `Lwjgl3GLBackend.glVertexAttribDivisor` falls back to the ARB name
- **Angelica Coexistence**: on 1.7.10 with Angelica present, the GL state shadow reads Angelica's mirror instead of the driver (`AngelicaStateProvider`)

---

## Global Coding Rules

These rules apply everywhere. All agents must internalize them.

**Public API first** — always use the highest abstraction layer available. `CgMaterial.load()` not `CgShaderFactory.fromSource()`; `CgMeshLoader.load()` not `GL15.glGenBuffers()`. Check package guides to find what already exists before writing raw GL.

**GL-thread rule** — all GL object creation, upload, and deletion must happen on the GL thread within an active context. This includes: `CgFrameBuffer.create()`, `CgMesh.upload()`, `CgTexture2D.create()`, shader compilation. Violations produce silent garbage or driver crashes.

> **The right thread is not the right CONTEXT, and on 1.7.10 that distinction is load-bearing.** FML's
> splash screen runs mod loading with a second, *shared* context of its own — so `FMLInitializationEvent`
> is on the client thread and still not on the renderer's context. Buffers and textures are shared
> between GL contexts; **container objects (VAO, FBO) are not.** A VAO built during mod loading is
> therefore named in a context nothing will ever draw with, while the VBO and IBO it points at stay
> valid, so the object reads as healthy from Java. `glGenVertexArrays` hands the same id to the next
> caller on the first real frame: one VAO, two owners, and the second one's attribute pointers replace
> the first's. Nothing errors — a mesh drawing another mesh's attributes at the wrong stride is
> degenerate geometry, which rasterises nothing.
>
> **So `runtime/mc/1710`'s `@Mod` class creates no GL objects at all**; `CgGraphicsLifecycle.onOpaquePass`
> initialises lazily on a frame that genuinely owns the render context. A dev run cannot show the
> failure (no splash in the way), so it appears only in an installed client. `CgVertexArray.gen()`
> warns when the driver returns a name this process still owns — the one cheap signal that two
> contexts are in play. See `CrystalGUI/docs/CGUI_INVARIANTS.md` § *Rendering, GL and shaders*.

**Vertex data via `CgVertexWriter`** — never write vertex bytes via raw `ByteBuffer.putFloat()`. All vertex packing goes through `CgVertexWriter.forBuffer()`. Index buffer `putShort()`/`putInt()` is the only exception.

**Java version by module** — `core/`, `platform/` and `runtime/lwjgl/*` are **abstract modules**: authored and compiled at Java 25 (`dep.jdk.compiler`), and each also builds a Java 8 copy, `downgradedJar`, published as a second variant at `TargetJvmVersion` 8. Gradle hands that copy to every consumer below 25 — `cgbuildlogic.abstractModule` in `singlejar-logic`, whose javadoc has the usage and the two configurations that need telling. Modern syntax is permitted; **a newer API is not checked** — jvmdg stubs what it can, and a call it cannot stub fails on the player's JVM. That is a convention, policed in review. Never lower an abstract module's Java to suit a consumer.

> The Jabel dual pipeline commented out in `core/build.gradle.kts` is dead, and `jabel-javac-plugin` is `compileOnly` only so an `import ...Desugar` still compiles. jvmdg is what downgrades: once per merge for the shipped jars, and per module for the copies.

| Module | Authored in | Compiled to | Notes |
|---|---|---|---|
| `core/`, `platform/`, `runtime/lwjgl/*` | Java 25 | Java 25, plus a Java 8 copy | Abstract modules; a consumer below 25 resolves the copy |
| `freetype-msdfgen-harfbuzz-bindings/` | Java 8 | Java 8 bytecode | Genuinely Java 8 source; JNI bindings, deliberately minimal |
| `runtime/mc/1710/` | Modern (GTNH convention) | Java 8 bytecode | `enableModernJavaSyntax = jvmDowngrader` in `runtime/mc/1710/gradle.properties` — the convention plugin's mechanism, not this repo's |
| `runtime/mc/modern/*`, `runtime/mc/legacy/*` nodes | their Minecraft's Java | the same | Compile and run against the abstract modules' Java 8 copies |

**One compiler**: every module compiles with JDK 25 (`dep.jdk.compiler`, a rule in the root build); the
toolchains are launchers, and `--release`/source-target still decides each module's bytecode.

**Forbidden cross-module imports:**

| In module | Forbidden | Reason |
|---|---|---|
| `core/`, `platform/` | `net.minecraft.*`, `net.minecraftforge.*`, `cpw.mods.fml.*`, `org.lwjgl.*` | Loader-blind — enforced by each module's import guard |
| `runtime/mc/modern/*` | `org.lwjgl.input.Mouse`, LWJGL2 input types | LWJGL3 environment |
| `runtime/mc/1710/*` | LWJGL3 GL calls, `com.mojang.*` | LWJGL2 environment |

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

`src/main/resources/assets/crystalgraphics/shaders/env/cg_env.glsl` is the foundation every `.shader` file stands on: the frame block, the per-instance object data with its SSBO/TBO dual path, the instance-id bridge, the scene samplers and the convenience macros over them. **A renderer's own macros are not in it** — `CG_QUAD_*` lives in `env/buffer/quad.glsl` and `CG_CURVE_*` in `env/buffer/curve.glsl`, injected only by `#pragma cg_use`; three quarters of this file used to be those two, paid for by every shader including the ones that draw neither. The material compiler **automatically `#include`s `cg_env.glsl`** into every generated vertex and fragment stage — you never include it manually in `.shader` files. Raw `CgShader` users must `#include "crystalgraphics:shaders/env/cg_env.glsl"` explicitly if they need its symbols.

It provides two distinct layers: per-frame global data and per-instance object data.

### Frame Data — `CgFrameBlock`

A `layout(std140) uniform CgFrameBlock` wired post-link by the engine. Available in every stage, every pass:

| GLSL name | Type | Content |
|---|---|---|
| `cg_ViewMatrix` | `mat4` | Camera view matrix |
| `cg_ProjMatrix` | `mat4` | Projection matrix |
| `cg_Time` | `vec4` | `(t/20, t, t×2, t×3)` — seconds |
| `cg_Resolution` | `vec2` | Viewport size in pixels |
| `cg_DepthParams` | `vec4` | `x` 1 when the pass's depth is reversed (Minecraft 26.2's world), `y` 1 when its clip depth runs 0..1. Read through `cg_LinearEyeDepth`, not directly |

**Scene samplers** — auto-bound by the engine before every material draw; do not declare or bind these yourself:

| GLSL name | Type | Unit | Content |
|---|---|---|---|
| `cg_DepthBuffer` | `uniform sampler2D` | `CgBindingPoints.DEPTH_TEXTURE_UNIT` | Scene depth snapshot, in the main target's own depth format, captured just before the opaque pass via one `glBlitFramebuffer` from MC's main render target. **Raw values are the host's convention** — reversed-Z on 26.2 — so compare depths as eye distances: `CG_SCENE_EYE_DEPTH(uv)` against `cg_LinearEyeDepth(gl_FragCoord.z)`. Valid in both vertex and fragment stages of all passes. **Do not bind user Properties samplers to `CgBindingPoints.DEPTH_TEXTURE_UNIT`.** |

Convenience macros over the frame block:

| Macro | Expands to | Use |
|---|---|---|
| `CG_TIME` | `cg_Time.y` | Raw seconds — the one you want 99% of the time |
| `CG_TIME_VEC4` | `cg_Time` | Full 4-component time vector |
| `CG_RESOLUTION` | `cg_Resolution` | Viewport dimensions in pixels |
| `CG_SCENE_EYE_DEPTH(uv)` | `cg_LinearEyeDepth(texture(cg_DepthBuffer, uv).r)` | Scene distance from the camera at `uv`, in eye units, under any depth convention |
| `CG_MATRIX_MVP` | `cg_ProjMatrix * cg_ViewMatrix * CG_OBJECT_TO_WORLD` | Standard MVP transform |

### Per-Instance Object Data — SSBO / TBO Dual Path

Every draw uses instancing. Per-object data lives in an engine-managed buffer. At startup, `CgCapabilities.detect().shaderBufferPath()` picks one of two hardware paths:

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
| `quad` | `QUAD_DATA(n)` + `CG_QUAD_WORLD_POS` / `CG_QUAD_UV` / `CG_QUAD_COLOR` / `CG_QUAD_NORMAL` / `CG_QUAD_ATLAS_LAYER` / `CG_QUAD_CUSTOM0`-`CG_QUAD_CUSTOM1` (two free vec4s per instance, written with `Quad.custom0`/`custom1` — `CG_OBJECT_CUSTOM*`'s contract at quad granularity, and where a per-quad parameter belongs rather than in a property that breaks the batch), and for screen-space materials the edge and texel antialiasing below | Any shader drawn through `CgQuadRenderer` — UI quads, text glyphs, SDF rects |
| `curve` | `CURVE_DATA(n)` + `CG_CURVE_WORLD_POS` / `CG_CURVE_P0`–`P2` / `CG_CURVE_COLOR0`–`1` / `CG_CURVE_WIDTHS` / `CG_CURVE_FEATHER` / `CG_CURVE_FLAGS` | Any shader drawn through `CgVectorRenderer` — Bézier strokes, graph wires, connectors |

> **A screen-space quad material antialiases its own edges — without MSAA.** `env/buffer/quad.glsl`
> (injected by `#pragma cg_use quad`) provides
> `CG_QUAD_EDGE_PARAM` (the vertex's parameter, grown by half a pixel when the instance is rotated or
> sheared in device space), `CG_QUAD_EDGE_WORLD_POS(param)`, `CG_QUAD_EDGE_UV(param)` (clamped, so the pad
> never samples past the rect) and `CG_QUAD_EDGE_COVERAGE(param)` — the exact area a straight edge leaves of
> the pixel, per edge, combined per opposite pair. Axis-aligned instances are left exactly alone: the
> rasteriser snaps those, and two abutting quads softened on a fractional boundary would seam. **Every edge
> of a rotated quad is soft**: there was a per-edge opt-out for a quad abutting another, and nothing tiles
> with separate quads any more — a nine-slice is one draw remapping its regions per pixel, so the seams are
> inside a fragment shader rather than between quads. `CG_QUAD_EDGE_ROTATED` gates anything else a material wants to do only when
> rotated: `cg_texel_aa_sample` (the pixel-art filter: nearest everywhere, one screen pixel of blend at a
> texel boundary, four taps held inside `CG_QUAD_UV_RECT`) — which is not a quad thing at all and lives in `lib/texel.glsl`, `#include`d where wanted, since it reads no instance buffer and `sdf_coverage(dist, rampPx)` with
> `CG_QUAD_EDGE_FILTER` — the reconstruction width, 1.5 px, the one knob. Adoption is three lines per
> material; every CrystalGUI quad material has it, `text.shader`'s bitmap path has the texel filter, and a
> 3D quad material must not use any of it (half a pixel means nothing under a perspective projection).

> **`curve` is the one engine buffer read from the fragment stage as well as the vertex stage.** A
> stroke is an analytic SDF evaluated per pixel, so the fragment needs the control points themselves;
> it re-reads `CURVE_DATA(CG_INSTANCE_ID)` rather than receiving them as varyings, because the
> `.shader` v2f DSL has no `flat` qualifier to offer (`cg_InstanceId` is the only compiler-generated
> flat varying). This needs no compiler change — `appendAttachedBuffers` already runs for the fragment
> source too — but it does mean the TBO fallback occupies that texture unit in both stages.

The buffer's GLSL declaration is injected **during parsing, before anything can compile**, so the
symbols exist no matter what triggers the first compile. **A token may also carry an env file** —
GLSL macros written against the buffer, the way `CG_QUAD_*` is written against `QUAD_DATA` — which
`CgMaterialShaderCompiler` includes immediately after the declaration, so the macros always have the
struct to read. Convention is `shaders/env/buffer/<token>.glsl`; a mod's own buffer gets the same
treatment by passing its own path. Register a new token with
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
| `texel.glsl` | `cg_texel_aa_sample(tex, uv, uvRect, filterPx)` — pixel-art filtering: nearest everywhere, one screen pixel of blend at a texel boundary. **Fragment-only, guarded.** Takes the reconstruction width as an argument rather than reading a constant, which is what keeps it free of any engine buffer |
| `stroke.glsl` | `stroke_coverage(p, p0,p1,p2, widths, feather, cap, out t)` — the whole shared body of every `CgVectorRenderer` consumer: taper, caps, feathered edge |
| `rect_blur.glsl` | `rect_shadow_outer`, `rect_shadow_inset`, `rect_blur_coverage` — Skia Graphite's analytic Gaussian rect blur with `erf` in place of its integral table; what a text decoration's shadow is. **Fragment-only, guarded** |
| `text_gamma.glsl` | `text_gamma_terms`, `text_gamma_coverage` — Skia's text gamma and contrast (`SkMaskGamma`), per fragment rather than a table; what `CgTextGamma` drives in `text.shader` |

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

`CgFrameBuffer` is the unified FBO abstraction. Create it via `CgFrameBufferFormat` builder — the format describes all attachments, the FBO dispatches through `CgGL`.

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

`CgCapabilities.detect()` — cached per context; **throws below OpenGL 3.3**. Above the floor it answers `shaderBufferPath()` (SSBO → TBO), `vertexStreamTier()` / `shaderStreamTier()` (the stream-buffer waterfall, see `gl/buffer/AGENTS.md`), `isCopyImageSubDataSupported()`, the limits (`getMaxDrawBuffers()`, `getMaxTextureUnits()`, …) and `isCoreProfile()`.

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
- **Internal**: `text/layout/` · `text/cache/` · `text/atlas/` · `text/msdf/` · `text/render/` · `text/font/` (font files without natives: `.ttc` faces, names, coverage; the per-script fallback tables)
- **This jar ships no fonts.** Text a caller's own fonts cannot draw falls back to the installed ones through `CgSystemFonts` / `CgFontFamily.withFallback`; tests read `core/src/test/resources/fonts/`.

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

// About to draw OUTSIDE a world pass — a menu, a title screen, any GUI (GL thread):
CgGraphicsLifecycle.ensureContext(width, height);
// Initialises once, resizes if the viewport moved, no-ops after destroyContext()
```

> **`ensureContext` exists because the engine initialises on the first WORLD render**, which is right
> for anything drawn in a world and wrong for everything else. On a title screen no world pass ever
> runs, so `isInitialized()` stayed false for the life of the process and a UI that politely checked
> before painting drew nothing at all — and because Minecraft only clears the colour buffer when it
> renders a level, the frame still held the previous screen. A screenshot then came back showing the
> main menu, which is indistinguishable from a working UI that was simply not asked to draw. Any
> `Screen` that paints through this engine calls it first; `onOpaquePass` calls the same method, so
> there is one definition rather than two.

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
| 8b | `CgPreviewPool.deleteAll()` | Shader-graph preview targets. **Context-owned, not renderer-owned** — they are `createOwned`, so no registry below reaches them, and release used to depend on every `CgPreviewRenderer`'s owner remembering to call `delete()`. Before step 9, since a target holds framebuffers |
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
| `gl/shader/AGENTS.md` | `CgShaderFactory`, `CgCoreShaderProgram`, `StandaloneCgShader` |
| `mc/shader/AGENTS.md` | `CgShaderImpl` hot-reload flow, `CgShaderManagerImpl` cache, `CgShaderReloadHook` (F3+T), `CgSystemUniformRegistry` |

### Framebuffers
| Path | What it covers |
|---|---|
| `api/framebuffer/AGENTS.md` | `CgFrameBufferFormat` builder API, validation rules, equality |
| `gl/framebuffer/AGENTS.md` | `CgFrameBuffer` dispatch architecture, `CgFrameBufferRegistry`, ownership model |

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
| `gl/buffer/AGENTS.md` | `CgStreamBuffer` tier waterfall (persistent ring → mapped ring → orphan → subdata), the frame clock `CgFrameRing`, `CgQuadIndexBuffer` |
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

### Platform SPI and hosts (outside `core/`)
| Path | What it covers |
|---|---|
| `platform/src/main/java/com/crystalgraphics/platform/AGENTS.md` | `CgPlatform`, `CgPlatformService`, `CgService`, `CgGLBackend`/`CgGL`, the services — the contract between `core/` and every host |
| `runtime/mc/1710/src/main/java/com/crystalgraphics/mc/v1710/platform/AGENTS.md` | The 1.7.10 bundle and its registration |
| `runtime/mc/modern/common/AGENTS.md`, `…/common/src/main/java/com/crystalgraphics/mc/modern/platform/AGENTS.md` | The modern tier 2: `PlatformServiceModern`, `Blaze3dGLBackend`, `LifecycleModern`, and the open GUI-frame tick |
| `runtime/mc/modern/{forge,neoforge,fabric}/AGENTS.md` | Each loader's versions, entry classes and render hooks |
| `freetype-msdfgen-harfbuzz-bindings/AGENTS.md` | The JNI bindings and the Zig native build |

---

# Minecraft Integration Glue

The layer that wires CrystalGraphics into each Minecraft. Read it when debugging frame timing, hot
reload, or GL state shared with Minecraft and other mods. Each host's classes: its own `AGENTS.md`.

## 1.7.10 — `runtime/mc/1710`

`CrystalGraphics` is the `@Mod` class (`modid = "crystalgraphics"`) and does **no GL work**: mod loading
runs on the splash screen's shared context (see the GL-thread rule). It registers the platform
(`PlatformService1710.onPreInit`/`onInit`) and the crash-report variant line. Other mods declare
`required-after:crystalgraphics`. **There is no coremod** — the GL-redirect layer was deleted on
2026-07-31; see [GL state](#gl-state--cgglstatemanager).

`CgRenderHook` is a Mixin on `EntityRenderer` with three injections:

- **Before `sortAndRender(pass=1)`** in `renderWorld` — `CgGraphicsLifecycle.onOpaquePass(partialTicks, w, h,
  mc.framebufferMc.framebufferObject)`: the depth snapshot from Minecraft's main FBO, then the depth
  prepass and the opaque forward pass, with the opaque world already in the depth buffer.
- **Before `ForgeHooksClient.dispatchRenderLast`** — `onTransparentPass()`, back to front; Minecraft's own
  translucent terrain then interleaves by depth.
- **`updateCameraAndRender` TAIL** — the frame tick, on every frame including a GUI with no world.

`MixinMinecraft` covers resize, fullscreen, resource reload and shutdown. Package guide:
`runtime/mc/1710/src/main/java/com/crystalgraphics/mc/v1710/platform/AGENTS.md`.

**Never call `executeOpaquePass`/`executeTransparentPass` from game code** on any host — each host's hooks
call them once per frame at the right moment.

## Forge 1.8–1.12.2 — `runtime/mc/legacy`

1.7.10's shape, ported: `PlatformServiceLegacy` over tier 1, `GlStateManagerGLBackend` (Minecraft's GL
state cache kept in step, eight texture units), and the render, frame, resize and shutdown hooks as
SRG-named mixins that MixinBooter applies. `singlejar-logic/README.md` § *Forge 1.8 to 1.12.2*.

## Modern — `runtime/mc/modern` (Forge 1.13.2+, NeoForge 1.20.2+, Fabric 1.14.4+)

The `common` branch holds everything a loader does not decide: `PlatformServiceModern` (over tier 1),
`Blaze3dGLBackend` (tier 1 plus routing what Minecraft's `GlStateManager` caches through it),
`HostStateVerifier`, and `LifecycleModern` — the one class a loader talks to. Each loader node is
registration only, reached through one bootstrapper per loader (`ForgeBootstrap`, `NeoForgeBootstrap`,
`FabricBootstrap`) that picks the node by the running version.

**The world passes**, by era — each handler rebinds `mc.getMainRenderTarget()` first, since Fabulous
leaves a non-main FBO bound:

| Loader | Opaque | Transparent |
|---|---|---|
| Forge 1.13.2–1.17.1 | `RenderWorldLastEvent` — both passes at the end of the level | the same |
| Forge 1.18–1.19.2 | `RenderLevelStageEvent` `AFTER_CUTOUT_BLOCKS` (ahead of entities); 1.18–1.18.1 fall back to `RenderLevelLastEvent` at runtime | `AFTER_PARTICLES` |
| Forge 1.19.3–1.21.1 | `AFTER_BLOCK_ENTITIES` | `AFTER_PARTICLES` |
| Forge 1.21.3+ | node mixin `OpaquePassHook` | node mixin `TransparentPassHook` |
| NeoForge 1.20.2–1.21.3 | `RenderLevelStageEvent` `AFTER_BLOCK_ENTITIES` | `AFTER_PARTICLES` |
| NeoForge 1.21.4–1.21.8 · 1.21.9–1.21.11 | `RenderLevelStageEvent.AfterBlockEntities` · `.AfterEntities` | `.AfterParticles` |
| NeoForge 26.1+ | `RenderLevelStageEvent.AfterOpaqueFeatures` | `.AfterTranslucentParticles` |
| Fabric 1.14.4–1.15.2 | node mixin `WorldPassHook` | the same |
| Fabric 1.16.5–1.21.8 · 1.21.9–1.21.11 | `WorldRenderEvents.AFTER_ENTITIES` · `BEFORE_TRANSLUCENT` | `AFTER_TRANSLUCENT` · `END_MAIN` |
| Fabric 26.1+ | `LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN` | `END_MAIN` |

The exact version splits are in each loader branch's `AGENTS.md`. **The frame ends after the GUI**, from
a loader frame event or, on Fabric, a mixin — `runtime/mc/modern/common/AGENTS.md` § *The frame end*,
which also covers 26.1's own main-target framebuffer and 26.2's stand-down under Vulkan.

**Iris/Oculus**: with a shader pack active, CrystalGraphics geometry renders into the main FBO **outside**
Iris's deferred GBuffer chain and appears unlit under deferred pipelines; `cg_DepthBuffer` stays valid.
`CgIrisCompat.isShaderPackActive()` detects it, and `CgRenderPipeline` warns once.

**Mixin policy**: prefer a native loader event, or a GLFW callback for input; a mixin only where neither
exists.

> ⚠️ **Loader events are not the stable surface for the render hook** — measured 2026-09-11. Across MC
> 1.17.1 / 1.18.2 / 1.19.2 / 1.20.x / 1.21.4, Forge's event API moved four ways — a package renamed
> (1.17), classes that did not exist yet (1.18), a constant added late (`AFTER_BLOCK_ENTITIES`, 1.20.1),
> and `RenderLevelStageEvent` gone by 1.21.3 — while `LevelRenderer.renderLevel` underneath never moved.
> The policy stands for input, lifecycle and anything with a real event; for the world passes a mixin on
> the Minecraft method is the more stable choice, and is what 1.7.10 and legacy Forge have always done.
> Forge 1.21.3+ is where it had to happen: those node mixins run Mojang names, so they need no refmap.

## Hot reload — `CgAssetReloader`

`core/src/main/java/com/crystalgraphics/mc/CgAssetReloader`, reached through each host's
`CgReloadService` on **F3+T** and on resource-pack changes. In order, each step isolated so one failure
does not stop the next:

1. `CgTextureManager.get().reloadAll()` — re-uploads every texture
2. `CgShaderManager.reloadAll()` — marks every raw `CgShader` dirty (recompiled on next `bind()`)
3. `CgMaterialRegistry.get().reloadAll()` + `CgMaterialShaderRegistry.get().reloadAll()` — marks every material dirty

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
Design record and eight implementation corrections: `plan/gl-state-manager.md`.

**Four rules worth knowing before touching rendering code:**

1. **Raw `CgGL` is fine.** Tracking lives *inside* `CgGL`'s setters, so there is no way to write GL state
   without the shadow seeing it. The typed records (`CgBlendState`, `CgDepthState`, …) are a convenience,
   not a safety requirement — the `cgStateWriteGuard` task that used to police this was deleted along with
   the problem it policed.
2. Any code that resets GL state wholesale **with raw GL that bypasses `CgGL`** — a foreign mod — must
   call `CgGlState.invalidateAllIfPresent()`. Only what goes around `CgGL` is invisible.
3. **Only genuinely global state may be deduplicated.** Anything an object binding implicitly swaps must be
   invalidated when that object changes — `GL_ELEMENT_ARRAY_BUFFER` is per-VAO state, and treating it as
   global elided a required bind and killed every indexed draw through the affected VAO.
4. A wrong decision here produces a **missing GL call** — wrong rendering, no exception. Diagnose with
   `-Dcrystalgraphics.state.verify=true`, which names the offending domain, or
   `-Dcrystalgraphics.state.noDedup=true` to rule the manager out entirely.

---

# Minecraft Source Code Location

| Want | Where |
|---|---|
| **Any node's API, with no setup** | `python singlejar-logic/mcapi.py <Class> [member]` — which versions have it, and its signature on each |
| A modern node's decompiled sources | `./gradlew :runtime:mc:modern:<branch>:<version>:extractMcSources` → `runtime/mc/modern/<branch>/versions/<version>/build/mc-src/{java,resources}` (makes that node real; minutes the first time) |
| 1.7.10's | `runtime/mc/1710/build/rfg/minecraft-src/java/`, after a build of that module |

1.7.10 files worth knowing:

- `net/minecraft/client/Minecraft.java` — main game class, owns `framebufferMc`
- `net/minecraft/client/renderer/EntityRenderer.java` — render pipeline, shader integration
- `net/minecraft/client/renderer/OpenGlHelper.java` — GL extension detection (THE reference impl)
- `net/minecraft/client/shader/Framebuffer.java` — vanilla FBO wrapper
- `net/minecraft/client/shader/ShaderGroup.java` — post-processing pipeline
- `net/minecraft/client/shader/ShaderManager.java` — GLSL program management
- `cpw/mods/fml/client/FMLClientHandler.java` · `cpw/mods/fml/common/gameevent/TickEvent.java`

The early 1.7.10 research — the vanilla FBO and shader traces, the integration gotchas and strategy — is
archived in the private plan repository, `plan/crystalgraphics/archive/`.

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
-Dcrystalgraphics.state.roundTrip=true               # read every scope's domains on open and after it
                                                     # restores, name any that differ; on 1.7.10 with
                                                     # Angelica also against the raw driver; and report
                                                     # every write no open scope declares (a leak, with its
                                                     # stack; CgGlState.handOver declares one meant to
                                                     # stay). Totals every 1000 scopes, via log4j; any
                                                     # count but 0 is a bug. Fits prodSmoke's 120 s run
                                                     # alone; four at a time, a client can miss it

# Minecraft's own GL state cache (modern nodes)
-Dcrystalgraphics.host.verify=true                   # after each pass, compare the driver against the host's
                                                     # GlStateManager and name the domain that disagrees

# GL errors (LWJGL3 hosts, needs a debug context -- every dev client has one)
-Dcrystalgraphics.gl.debugStacks=true                # log the Java stack of the first 5 GL errors, so a
                                                     # debug message names the call; .limit=N for more

# Shader
-Dcrystalgraphics.shader.devmode=true                # emit #line directives in preprocessed output
-Dcrystalgraphics.shader.resourceOverrideDir=path    # filesystem override dir for shader sources
```
