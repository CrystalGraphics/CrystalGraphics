# CrystalGraphics — Agent Knowledge Base

**What**: a modern rendering engine — materials, meshes, framebuffers, instancing and text — designed for Vulkan
and running on OpenGL wherever Vulkan is not the backend. It runs in any application that hosts it (the GL debug
harness is one), and also inside Minecraft: **one jar** for Forge 1.7.10–26.3, NeoForge 1.20.2–26.3 and Fabric
1.14.4–26.3. **Authored in** Java 25, with a Java 8 copy of every engine module. **The parent of** CrystalGUI, which
builds every Minecraft node against this repository's node of the same version.

---

## Project philosophy

The principles every design here answers to; the sections below assume them.

- **Vulkan first; OpenGL keeps up through waterfalls.** The engine is designed for the Vulkan device: compute
  passes, indirect draws and everything GL 4.6 or later offers are the design, used ungated there. OpenGL stays
  supported from a 3.3 floor. Where an older context cannot do what the design does (macOS's GL stops at 4.1), a
  waterfall tier provides it, each tier forceable for a driver that misbehaves, and Minecraft before 26 runs on those
  tiers. The modern approach is never dropped to suit the old one, and a new GPU subsystem gets its own research
  plan before any code.
- **Fail fast; a tier is never a silent downgrade.** A capability no tier can provide throws, naming what is
  missing. A tier reaches the same result by another route; anything that quietly draws less, or something else,
  is a bug.
- **One engine, every version; hosts only wire it.** `core/` and `platform/` name no Minecraft, loader or LWJGL
  type, and a host says how its version spells a thing and decides nothing. A version, a node or a platform
  capability lands here first, then in CrystalGUI.
- **Draws are data, recorded, then executed once.** A frame is recorded into a graph on any thread, built off the
  render thread (ordered by what reads and writes what, culled, batched) and executed in one place. Meshes are data
  the same way: built and edited anywhere, their GPU copy the engine's business.
- **The frame time is the budget.** Generation (glyphs, shader variants, meshes) runs on workers and the frame draws
  what is ready; hot paths do not allocate; a frame waits on one fence at its start, never in the middle.
- **Cooperate with the host; never assume control.** Minecraft and other mods change GL state behind the engine,
  so the state shadow takes its truth at host boundaries (host sections), and every scope restores what it changed.
- **Port what is solved; measure what is claimed.** Proven designs (Skia, Godot, Bevy, Dolphin, Unity's formats)
  are ported or followed with attribution rather than re-derived. A performance claim comes from a trace, before and
  after, in one run (`docs/PROFILING.md`).

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
| **[`docs/PROFILING.md`](docs/PROFILING.md)** | **Measuring anything**: the trace engine, the one-run rule, the harness and game runs, reading a report, what is instrumented. CrystalGUI adds `docs/CGUI_PROFILING.md`; the `profiling` skill is the checklist |
| [`docs/NATIVE_BUILD_PROCESS.md`](docs/NATIVE_BUILD_PROCESS.md) | Rebuilding the FreeType/HarfBuzz/msdfgen natives |
| [`docs/HOTSWAP_SETUP.md`](docs/HOTSWAP_SETUP.md) | Hotswapping into a running 1.7.10 client |
| **[`docs/MINECRAFT_RENDERING_CONVENTIONS.md`](docs/MINECRAFT_RENDERING_CONVENTIONS.md)** | **Before drawing into Minecraft's frame, and with every new Minecraft version**: how each version changed that frame (26.2's reversed depth, float depth, blend and sampler state left behind) and what the engine does about each |

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
./gradlew :gl-debug-harness:runHarness --args="--mode=forward-renderer"       # CgWorldRenderer through both world stages
./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-spheres"            # the VFX showcase: sixteen effect spheres (CgVfxShowcase)
./gradlew :gl-debug-harness:runHarness --args="--mode=material-dual-path"     # CgMaterial shader compilation
./gradlew :gl-debug-harness:runHarness --args="--mode=attached-buffer-stress" # SSBO/TBO attach
./gradlew :gl-debug-harness:runHarness --args="--mode=mesh-test"              # CgMeshShapes, CgMeshLoader, a part per material
./gradlew :gl-debug-harness:runHarness --args="--mode=atlas-dump"             # Glyph atlas
./gradlew :gl-debug-harness:runHarness --args="--mode=text-3d"                # Full text pipeline
./gradlew :gl-debug-harness:runHarness --args="--mode=capability-report"      # GL capability probe
./gradlew :gl-debug-harness:runHarness --args="--mode=shader-compile-audit"   # every shipped .shader + keyword variant
./gradlew :gl-debug-harness:runHarness --args="--mode=graph-executor-test"    # frame graph: three paths, identical PNGs
./gradlew :gl-debug-harness:runHarness --args="--mode=text-threaded"          # text recorded on a worker beside render-thread text; prints PASS/FAIL lines
./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-desktop --device=vulkan"  # any scene on the Vulkan device
# Outputs land in gl-debug-harness/harness-output/{scene}/
```

- The harness is LWJGL 3 and GLFW. `--device=gl` (the default) is `Lwjgl3GLBackend`; `--device=tracked` the
  tracked backend over a recording device; `--device=vulkan` over `CgVulkanDevice` and `OwnedVulkanHost`, with
  validation on unless `-Dcrystalgraphics.harness.vulkanValidation=false`. Its `AGENTS.md` has the rest.
- Never call raw GL — use `CgMesh`, `CgStreamBuffer`, `CgTexture`, `CgFrameBuffer`, etc.
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
| `platform/` | 25 + 8 copy | The SPI only: `CgPlatform`, `CgPlatformService`, `CgService`, `CgGLBackend`, `CgGL`, the services. No implementation — [its guide](platform/src/main/java/com/crystalgraphics/platform/CLAUDE.md) |
| `core/` | 25 + 8 copy | All rendering: materials, meshes, the pipeline, fonts, text, atlases. Calls `CgPlatform`/`CgGL` for every GL or lifecycle operation; never imports Minecraft, a loader or LWJGL |
| `freetype-msdfgen-harfbuzz-bindings/` | 8 | JNI text shaping, with its natives |
| `runtime/lwjgl/2`, `runtime/lwjgl/3` | 25 + 8 copy | **Tier 1**: GL backend, context, input and cursor per LWJGL (`Lwjgl2*`, `Lwjgl3*`, `Glfw*`). **Name no Minecraft class** (import guard), so one copy serves every host of that LWJGL and the harness. LWJGL3 is pinned to 3.2.2, the oldest in range, so a symbol a 1.16 client lacks is a compile error |
| `runtime/lwjgl/sdl` | 25 + 8 copy | **Tier 1 for a host windowed by SDL3** (Minecraft 26.3+, which ships no GLFW): `SdlInputService` (keys by scancode, modifiers, mouse buttons, the clipboard) and `SdlCursorService`, beside `platform`'s `CgSdlKeyCodes`. A 26.3 node's `PlatformServiceModern` registers these where an older one registers the `Glfw*` pair. Pinned to LWJGL 3.4.3, the oldest with SDL3 |
| `runtime/lwjgl/vulkan` | 25 + 8 copy | **Tier 1 for Vulkan** (`plan/device-vulkan.md`): `CgVulkanDevice`, a `CgDevice` over a `CgVulkanHost` — `host.OwnedVulkanHost` when nothing else owns the device, `host.HostedVulkanHost` when a game does and submits it; `shader.ShadercGlslCompiler`, the tracked backend's GLSL to SPIR-V over shaderc and SPIRV-Cross, as Minecraft 26.2 compiles its own; and the device's parts in `resource`, `command` and `format`. Pinned to LWJGL 3.4.1, the oldest a 26.2+ client ships. Its tests run core on the tracked backend (`EngineOnTrackedBackendTest`), since core's own tests carry LWJGL 2 |
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
platform/        ← the SPI: CgGLBackend, CgGLContext, the services, CgService slots;
                   and CgTrackedGLBackend, GL's semantics over a CgDevice
    ↑
core/            ← rendering logic — calls CgGL and CgPlatform.lifecycle() etc.
    ↑
runtime/lwjgl/*  ← tier 1: the GL backend, context and input per LWJGL; CgVulkanDevice
    ↑
runtime/mc/*     ← a host: registers a bundle over tier 1, adds what names Minecraft
```

**`CgGLBackend` is the seam, and `CgGL`'s 132 methods keep GL's semantics on every backend.** A GL backend
calls the driver; `CgTrackedGLBackend` answers the same calls on a `CgDevice` — `CgVulkanDevice` in the
harness's `--device=vulkan`, a recording device in tests. Three additions live inside a host on either:
`importHostTexture`, `fromHost`/`toHost` (the bracket around every host entry — nothing on GL), and
`ownedByCurrentThread`. The platform guide has each, and the tracked backend's rules.

```java
// In core/ — never a raw GL call, never an LWJGL import:
CgGL.glBindFramebuffer(target, id);
CgPlatform.reload().onReload();

// A host, once, from an entry point that runs on both sides:
CgPlatform.register(PlatformServiceModern.getInstance());   // or PlatformService1710, PlatformServiceLegacy
```

**Registration builds no graphics**: a dedicated server has none. `CgGL` takes the bundle's backend at the
first host section or capability probe, on a client's render thread; each bundle builds its services lazily,
and a client-only service (the cursor) is filled only on a client.

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
`runtime/mc/modern/fabric/CLAUDE.md`.

---

## Start Here By Task

| I need to… | Read | Primary package guide |
|---|---|---|
| Write or load a `.shader` material | [`docs/SHADERS.md`](docs/SHADERS.md) | `api/material/CLAUDE.md` |
| Write or run a kernel (`.compute`) | [`docs/SHADERS.md`](docs/SHADERS.md) § *Compute* | `compute/CLAUDE.md` |
| Draw at a host's render stage, or meshes into the world | [`docs/ENGINE_API.md`](docs/ENGINE_API.md) § *Render stages*, § *CgWorldRenderer* | `render/world/CLAUDE.md` |
| Create a framebuffer, texture, mesh or shader buffer | [`docs/ENGINE_API.md`](docs/ENGINE_API.md) | `api/framebuffer`, `api/texture`, `api/mesh`, `gl/buffer/shader` |
| Save and restore GL state across a pass | [`docs/ENGINE_API.md`](docs/ENGINE_API.md) § *GL State Save/Restore* | `gl/state/CLAUDE.md` |
| Render text on screen | `docs/font/README.md` | `text/CLAUDE.md` |
| Draw 2D quads, curves or text through the renderers | [`docs/ENGINE_API.md`](docs/ENGINE_API.md) § *Instanced renderers* | `gl/render/CLAUDE.md` |
| Load a resource file (shader source, config, image) | [`docs/ENGINE_API.md`](docs/ENGINE_API.md) § *Resource I/O* | `util/io/CgIO` |
| Init, resize or tear down a context | [`docs/ENGINE_API.md`](docs/ENGINE_API.md) § *CgGraphicsLifecycle* | — |
| Test rendering without Minecraft | [Render testing](#render-testing--the-gl-debug-harness) | `gl-debug-harness/AGENTS.md` |
| Wire anything into Minecraft, or send between client and server | [`docs/MINECRAFT_INTEGRATION.md`](docs/MINECRAFT_INTEGRATION.md) | each host's `AGENTS.md` |
| Diagnose with a JVM flag | [`docs/DEBUG_FLAGS.md`](docs/DEBUG_FLAGS.md) | — |
| Build, ship, or add a Minecraft version | [Build and run](#build-and-run) | `docs/BUILD.md` |

**Guides load themselves.** A package's guide is its `CLAUDE.md`, so the guide — and the
doc above that covers it — enters your context the first time you Read a file there. **Read a file before you change
it, and never change one through the shell that you have not Read**: a shell edit loads nothing.

---

## Global Coding Rules

These rules apply everywhere. All agents must internalize them.

**Public API first** — always use the highest abstraction layer available. `CgMaterial.load()` not `CgShaderFactory.fromSource()`; `CgMeshLoader.load()` not `GL15.glGenBuffers()`. Check package guides to find what already exists before writing raw GL.

**GL-thread rule** — all GL object creation, upload, and deletion must happen on the GL thread within an active context. This includes: `CgFrameBuffer.create()`, shader compilation. Violations produce silent garbage or driver crashes. **Meshes are data**, built and edited on any thread, and the mesh store places them on the render thread. **Textures are the exception, through `CgDeferral`** (`gpu/`): an object owns one, all its device work goes through it, and where the device may not be driven the work waits for the render thread, before the next frame executes. A new GPU object that must work off the render thread does the same rather than branching on `CgGL.mayIssueGl()` itself.

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
> **So `runtime/mc/1710`'s `@Mod` class creates no GL objects at all**; the first render stage a host fires
> initialises lazily, on a frame that genuinely owns the render context. A dev run cannot show the
> failure (no splash in the way), so it appears only in an installed client. `CgMeshPool` warns
> (`[cg-vao]`) when the driver returns a vertex array name this process still owns — the one cheap signal that two
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

# Package guide index

Every package guide (`CLAUDE.md`) under `src/main/java/com/crystalgraphics/`. Relative paths omit the common prefix.

### Demo / Benchmarks
| Path | What it covers |
|---|---|
| `demo/CLAUDE.md` | `CgFontDemo` — platform-agnostic font benchmark and atlas diagnostic viewer |

### CrystalShader Pipeline
| Path | What it covers |
|---|---|
| `api/material/CLAUDE.md` | `CgMaterial` load/bind/keywords/attach-buffers/ownership; `CgRenderPassVariant`; `CgRenderQueue` constants |
| `render/CLAUDE.md` | `CgImmediate`, `CgFrameClock`, `CgViewFrustum` — the render package's root |
| `render/world/CLAUDE.md` | `CgWorldRenderer`, `CgDepthSnapshot`, `CgSortKey` — the world drawn under the host's camera |
| `render/draw/CLAUDE.md` | `CgPipeline` (a CPU key), `CgBindingTable` (snapshots with handles), `CgInstanceKind`, `CgPassConstants`, `CgDrawChunk`, `CgBatcher` — what a recorded draw is made of; recording touches no GL (`render-graph`) |
| `render/graph/CLAUDE.md` | `CgRecording`, `CgFrameGraph`, `CgFrameBuilder` (order, cull, batch, pack — off the render thread), `CgExecutor`, `CgImmediate` — the frame graph |
| `gl/material/CLAUDE.md` | `CgMaterialShader`, `CgMaterialShaderRegistry`, `CgMaterialProperties` |
| `gl/material/parse/CLAUDE.md` | `CgShaderParser` facade, `CgParsedShader`, `CgMaterialShaderCompiler`, sub-parsers |
| `compute/CLAUDE.md` | `CgCompute`, `CgKernel`, `.compute` parsing and emission, `CgKernelProgram` — kernels |

### Shaders
| Path | What it covers |
|---|---|
| `api/shader/CLAUDE.md` | `CgShader` lifecycle, `CgShaderPreprocessor` (#include/pragma-once/cycle detection), `CgShaderBindings` fluent API, `CgActiveUniform` |
| `gl/shader/CLAUDE.md` | `CgShaderFactory`, `StandaloneCgShader` |
| `mc/shader/CLAUDE.md` | `CgShaderImpl` hot-reload flow, `CgShaderManagerImpl` cache, `CgShaderReloadHook` (F3+T), `CgSystemUniformRegistry` |

### Framebuffers
| Path | What it covers |
|---|---|
| `api/framebuffer/CLAUDE.md` | `CgFrameBufferFormat` builder API, validation rules, equality |
| `gl/framebuffer/CLAUDE.md` | `CgFrameBuffer` dispatch architecture, `CgFrameBufferRegistry`, ownership model |

### Textures
| Path | What it covers |
|---|---|
| `api/texture/CLAUDE.md` | `CgTexture` interface, `CgTextureType` (~42 format constants), `CgTextureSpec` (builder + presets), `CgMipmapConfig` |
| `gl/texture/CLAUDE.md` | `CgTexture2D`, `CgTexture2DArray`, `CgTexture3D`, `CgTextureCubemap` concrete implementations |

### Mesh
| Path | What it covers |
|---|---|
| `api/mesh/CLAUDE.md` | `CgMesh` (a mesh as data), `CgMeshWriter`, `CgMeshShapes`, `CgMeshLoader`, `CgMeshLods`, `CgSubmesh`, `CgMeshChanges`, `CgMeshTopology` |

### Vertex / Instancing
| Path | What it covers |
|---|---|
| `api/vertex/CLAUDE.md` | `CgVertexFormat`, `CgVertexSemantic`, `CgAttribType` |

### Buffers
| Path | What it covers |
|---|---|
| `api/buffer/CLAUDE.md` | `CgGpuType`, `CgBufferField`, `CgBufferFormat` builder, std140/std430 alignment rules |
| `gl/buffer/CLAUDE.md` | `CgStreamBuffer` tier waterfall (persistent ring → mapped ring → orphan → subdata), the frame clock `CgFrameRing`, `CgQuadIndexBuffer` |
| `gl/buffer/shader/CLAUDE.md` | `CgShaderBuffer` (SSBO/TBO), `CgUniformBuffer` (UBO), `CgShaderBufferRegistry`, binding point rules |
| `gl/buffer/staging/CLAUDE.md` | `CgStagingBuffer`, `CgVertexWriter` (all vertex packing goes here), `CgBufferWriter` |

### GL State
| Path | What it covers |
|---|---|
| `api/state/CLAUDE.md` | `CgRenderState`, `CgDepthState`, `CgBlendState`, `CgCullState`, `CgStencilState`, `CgTextureState` (`CgGlSlot` moved to `platform.gl.state`) |
| `gl/state/CLAUDE.md` | Nothing — the package is empty. Kept as a signpost to the state framework in `platform.gl` / `platform.gl.state`, and a record of what was removed |

### Instanced renderers
| Path | What it covers |
|---|---|
| `gl/render/CLAUDE.md` | `CgQuadRenderer`, `CgVectorRenderer`, `CgInstanceRun`, `CgClipTable`, `CgShapeTable` |

### Font / Text
| Path | What it covers |
|---|---|
| `api/font/CLAUDE.md` | Public font-domain API + layout bridge |
| `api/text/CLAUDE.md` | Public text-domain value types |
| `text/CLAUDE.md` | Top-level text package coordination |
| `text/layout/CLAUDE.md` | Layout algorithm |
| `text/cache/CLAUDE.md` | Glyph supply, async generation, cache |
| `text/atlas/CLAUDE.md` | Atlas storage |
| `text/atlas/packing/CLAUDE.md` | Packing algorithms |
| `text/msdf/CLAUDE.md` | Distance-field generation logic |
| `text/render/CLAUDE.md` | Draw-time orchestration |

### Platform SPI and hosts (outside `core/`)
| Path | What it covers |
|---|---|
| `platform/src/main/java/com/crystalgraphics/platform/CLAUDE.md` | `CgPlatform`, `CgPlatformService`, `CgService`, `CgGLBackend`/`CgGL`, the services — the contract between `core/` and every host |
| `runtime/mc/1710/src/main/java/com/crystalgraphics/mc/v1710/platform/CLAUDE.md` | The 1.7.10 bundle and its registration |
| `runtime/mc/modern/common/CLAUDE.md`, `…/common/src/main/java/com/crystalgraphics/mc/modern/platform/CLAUDE.md` | The modern tier 2: `PlatformServiceModern`, `Blaze3dGLBackend`, `LifecycleModern`, and the open GUI-frame tick |
| `runtime/mc/modern/{forge,neoforge,fabric}/AGENTS.md` | Each loader's versions, entry classes and render hooks |
| `freetype-msdfgen-harfbuzz-bindings/AGENTS.md` | The JNI bindings and the Zig native build |

---
