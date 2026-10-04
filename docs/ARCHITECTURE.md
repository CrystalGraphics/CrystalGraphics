# CrystalGraphics — modules, packages and docs

Where everything lives: each module and its Java level, every package guide, and every doc with when to read it. Moved verbatim from [`AGENTS.md`](../AGENTS.md), which keeps the rules every session needs.

---

## Module layout

Every module has one role — code in the wrong one either fails to compile or silently breaks a loader.
The build view (Java levels, toolchains, what a consumer build includes) is `docs/BUILD.md` § *Layout*.

| Module | Java | Role |
|---|---|---|
| `platform/` | 25 + 8 copy | The SPI only: `CgPlatform`, `CgPlatformService`, `CgService`, `CgGLBackend`, `CgGL`, the services. No implementation — [its guide](../platform/src/main/java/com/crystalgraphics/platform/CLAUDE.md) |
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

## Java version by module

**Java version by module** — `core/`, `platform/` and `runtime/lwjgl/*` are **abstract modules**: authored and compiled at Java 25 (`dep.jdk.compiler`), and each also builds a Java 8 copy, `downgradedJar`, published as a second variant at `TargetJvmVersion` 8. Gradle hands that copy to every consumer below 25 — `cgbuildlogic.abstractModule` in `singlejar-logic`, whose javadoc has the usage and the two configurations that need telling. Modern syntax is permitted; **a newer API is not checked** — jvmdg stubs what it can, and a call it cannot stub fails on the player's JVM. That is a convention, policed in review. Never lower an abstract module's Java to suit a consumer.

> The Jabel dual pipeline commented out in `core/build.gradle.kts` is dead, and `jabel-javac-plugin` is `compileOnly` only so an `import ...Desugar` still compiles. jvmdg is what downgrades: once per merge for the shipped jars, and per module for the copies.

| Module | Authored in | Compiled to | Notes |
|---|---|---|---|
| `core/`, `platform/`, `runtime/lwjgl/*` | Java 25 | Java 25, plus a Java 8 copy | Abstract modules; a consumer below 25 resolves the copy |
| `freetype-msdfgen-harfbuzz-bindings/` | Java 8 | Java 8 bytecode | Genuinely Java 8 source; JNI bindings, deliberately minimal |
| `runtime/mc/1710/` | Modern (GTNH convention) | Java 8 bytecode | `enableModernJavaSyntax = jvmDowngrader` in `runtime/mc/1710/gradle.properties` — the convention plugin's mechanism, not this repo's |
| `runtime/mc/modern/*`, `runtime/mc/legacy/*` nodes | their Minecraft's Java | the same | Compile and run against the abstract modules' Java 8 copies |

---

## Docs, and when to read each

| Read | When |
|---|---|
| **[`docs/BUILD.md`](BUILD.md)** | **First, for anything about the build**: layout, nodes and toolchains, stub mode, commands, releasing, and adding a Minecraft version |
| [`docs/SETUP.md`](SETUP.md) | Setting up a mod on CrystalGraphics — one version, or one jar across many |
| [`singlejar-logic/README.md`](../singlejar-logic/README.md) | How one jar serves every loader, and the guide another project follows. Before touching `singlejar-logic/`, `cg-single-jar`, relocation, remapping or the class-major ceiling |
| [`singlejar-logic/STUBS.md`](../singlejar-logic/STUBS.md) | Before adding a node, changing its pins, or touching a branch script's toolchain |
| [`runtime/mc/modern/README.md`](../runtime/mc/modern/README.md) | Before touching a modern node; each branch has its own `CLAUDE.md` |
| CrystalGUI's `docs/CGUI_CROSS_VERSION.md` | Code that must run on every version |
| **[`docs/PROFILING.md`](PROFILING.md)** | **Measuring anything**: the trace engine, the one-run rule, the harness and game runs, reading a report, what is instrumented. CrystalGUI adds `docs/CGUI_PROFILING.md`; the `profiling` skill is the checklist |
| [`docs/NATIVE_BUILD_PROCESS.md`](NATIVE_BUILD_PROCESS.md) | Rebuilding the FreeType/HarfBuzz/msdfgen natives |
| [`docs/HOTSWAP_SETUP.md`](HOTSWAP_SETUP.md) | Hotswapping into a running 1.7.10 client |
| **[`docs/MINECRAFT_RENDERING_CONVENTIONS.md`](MINECRAFT_RENDERING_CONVENTIONS.md)** | **Before drawing into Minecraft's frame, and with every new Minecraft version**: how each version changed that frame (26.2's reversed depth, float depth, blend and sampler state left behind) and what the engine does about each |

---

## Package guide index

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
| `render/post/CLAUDE.md` | `CgPostStack`, `CgPostEffect`, `CgPostPoint`, the composite and the built-in bloom — what runs after the world |
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
