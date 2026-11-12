# CrystalGraphics — modules, packages and docs

Where everything lives: each module and its Java level, every package guide, and every doc with when to read it. Moved verbatim from [`AGENTS.md`](../AGENTS.md), which keeps the rules every session needs.

---

## Module layout

Every module has one role — code in the wrong one either fails to compile or silently breaks a loader.
The build view (Java levels, toolchains, what a consumer build includes) is `docs/BUILD.md` § *Layout*.

| Module | Java | Role |
|---|---|---|
| `platform/` | 25 + 8 copy | The SPI only: `CgPlatform`, `CgPlatformService`, `CgService`, `CgRHI` and its GL and null devices, the services (`service`), the world's (`world`: levels, entities, players), each read in core through one facade (`platform/CLAUDE.md` § *The wiring standard*). No implementation — [its guide](../platform/src/main/java/com/crystalgraphics/platform/CLAUDE.md) |
| `core/` | 25 + 8 copy | All rendering: materials, meshes, the pipeline, fonts, text, atlases. Records for the installed `CgRHI`, and calls `CgPlatform` for every lifecycle operation; never imports Minecraft, a loader or LWJGL |
| `freetype-msdfgen-harfbuzz-bindings/` | 8 | JNI text shaping, with its natives |
| `runtime/lwjgl/2`, `runtime/lwjgl/3` | 25 + 8 copy | **Tier 1**: GL backend, context, input and cursor per LWJGL (`Lwjgl2*`, `Lwjgl3*`, `Glfw*`). **Name no Minecraft class** (import guard), so one copy serves every host of that LWJGL and the harness. LWJGL3 is pinned to 3.2.2, the oldest in range, so a symbol a 1.16 client lacks is a compile error |
| `runtime/lwjgl/sdl` | 25 + 8 copy | **Tier 1 for a host windowed by SDL3** (Minecraft 26.3+, which ships no GLFW): `InputSdl` (keys by scancode, modifiers, mouse buttons), `ClipboardSdl` and `SdlCursorService`, beside `platform`'s `CgSdlKeyCodes`. A 26.3 node's `PlatformServiceModern` registers these where an older one registers the `Glfw*` pair. Pinned to LWJGL 3.4.3, the oldest with SDL3 |
| `runtime/lwjgl/vulkan` | 25 + 8 copy | **Tier 1 for Vulkan** (`plan/device-vulkan.md`): `CgVulkanDevice`, a `CgRHI` over a `host.CgVulkanHost` — `host.OwnedVulkanHost` when nothing else owns the device, `host.HostedVulkanHost` when a game does and submits it; `shader.ShadercGlslCompiler`, GLSL to SPIR-V over shaderc and SPIRV-Cross, as Minecraft 26.2 compiles its own; and the device's parts in `resource`, `binding`, `command`, `memory` and `format`. Pinned to LWJGL 3.4.1, the oldest a 26.2+ client ships. Its tests run core on the devices (`EngineOnGLDeviceTest`, `DevicePipelinesTest`), since core's own tests carry LWJGL 2, and the RHI's command contract on a real device (`VulkanCommandListTest`) |
| `runtime/mc/1710/` | 25 → 8 | Forge 1.7.10 on RetroFuturaGradle: `PlatformService1710`, the `CgRenderHook`/`MixinMinecraft` mixins, Angelica's state provider |
| `runtime/mc/legacy/` | 8 | Forge 1.8–1.12.2, a Stonecutter tree (nodes 1.8.9, 1.10.2, 1.12.2) on Unimined: `PlatformServiceLegacy`, `GlStateManagerGLBackend`, SRG-named mixins MixinBooter applies |
| `runtime/mc/modern/` | each node's | Forge 1.13.2+, NeoForge 1.20.2+, Fabric 1.14.4+, a Stonecutter tree — branches `common` (**tier 2**: `PlatformServiceModern`, `GLDeviceModern`, `HostStateModern`, `HostStateVerifier`, `LifecycleModern`) and one per loader, which is registration only |
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
| [`docs/VFX.md`](VFX.md) | Writing effects: emitters, modules, events, a module of your own, and the gates for each |
| [`docs/LIGHTS.md`](LIGHTS.md) | The world's lights: adding them, how they light Minecraft (deferred, the G-buffer rebuilt from depth and colour), falloff, day and night, the light grid, lights from effects, what is planned |
| [`docs/COLLISION.md`](COLLISION.md) | Questions about space: shapes, world queries (overlap, cast, ray), hits, hitboxes over time, shields, hitboxes on every side, snapshots, exact tests, the debug view, recipes |
| [`docs/JOBS.md`](JOBS.md) | Work off the game's thread: jobs handed back at the next tick, keys and debounce, lanes, repeating simulations, `forEach` across cores, cancelling, progress, replies |
| [`docs/AUDIO.md`](AUDIO.md) | Playing sound: `CgSounds`, names and loading, classes and voices, placing and following, attenuation, mixes, subtitles, sounds in cues and sequences |
| [`docs/STORAGE.md`](STORAGE.md) | A product's files: the layout, config stores, verified cache installs, resumable downloads, and the per-product locations file that repairs dead links |
| [`docs/NETWORKING.md`](NETWORKING.md) | Sending between server and clients: messages, requests, and networked objects (state mirrored to whoever sees it, per-player data, events, commands, server behaviours, prediction, saving, the test rig) |
| [`docs/SERIALIZATION.md`](SERIALIZATION.md) | Codecs and where their values go: writing one, mutable objects, decoding into a live object, files in binary, JSON and YAML, Minecraft's NBT, changing a saved shape |
| **[`docs/GPU_DRIVEN_RENDERING.md`](GPU_DRIVEN_RENDERING.md)** | **Building a rendering pipeline on compute**: kernels writing records and counts, indirect and multi-draw draws, GPU culling and levels, and the design production engines follow |
| **[`docs/LOADING.md`](LOADING.md)** | **Before loading anything** (files, textures, meshes, shaders, kits): when each loads, the load plan behind the loading screen, keeping the screen's frames and gameplay frames smooth, the watchdog and checking a load |
| **[`docs/PRECACHE.md`](PRECACHE.md)** | **Before drawing anything new**: what the engine precaches for you (VFX looks, kits, cues, its own passes), what you precache with `CgPipelinePrecache`, checking for misses, and refreshing the shipped Vulkan pipeline list |
| **[`docs/PROFILING.md`](PROFILING.md)** | **Measuring anything**: the trace engine, the one-run rule, the harness and game runs, reading a report, what is instrumented. CrystalGUI adds `docs/CGUI_PROFILING.md`; the `profiling` skill is the checklist |
| [`docs/GAUNTLET.md`](GAUNTLET.md) | Running the game unattended: the tests a run plays (boot, play, world, collision, net, server), its report, writing a test, and `CgGameSession` |
| [`docs/DEBUG_FLAGS.md`](DEBUG_FLAGS.md) | Every engine switch, generated from the variable registry (`CgVar`, platform's `com.crystalgraphics.variable`): its sources, declaring a new one, and the table |
| [`docs/NATIVE_BUILD_PROCESS.md`](NATIVE_BUILD_PROCESS.md) | Rebuilding the FreeType/HarfBuzz/msdfgen natives |
| [`docs/HOTSWAP_SETUP.md`](HOTSWAP_SETUP.md) | Hotswapping into a running 1.7.10 client |
| [`docs/WIDGETS.md`](WIDGETS.md) | A HUD element as an asset: widget trees laid out by Slate's rules, retainer boxes, widget animations, widgets over points in the world, the UI scale |
| **[`docs/CANVAS.md`](CANVAS.md)** | **Drawing anything flat**: quads, curves and text on a HUD, the screen or in the world, through a stage's canvas or one of your own; materials, order and batching, recording a frame |
| **[`docs/RENDER_STATE.md`](RENDER_STATE.md)** | **Who owns GPU state**: pipelines on Vulkan, the GL device's emulated PSOs and state filter (and why GL 4.6 still needs one), the host hand-over at a section's edge, foreign GL inside a frame |
| **[`docs/MINECRAFT_RENDERING_CONVENTIONS.md`](MINECRAFT_RENDERING_CONVENTIONS.md)** | **Before drawing into Minecraft's frame, and with every new Minecraft version**: how each version changed that frame (26.2's reversed depth, float depth, blend and sampler state left behind) and what the engine does about each |

---

## Package guide index

Every package guide (`CLAUDE.md`) under `src/main/java/com/crystalgraphics/`. Relative paths omit the common prefix.

### Demo / Benchmarks
| Path | What it covers |
|---|---|
| `frame/CLAUDE.md` | `CgWorld`, `CgTickGroup`, `CgTickFunction` — the world's per-frame schedule |
| `demo/CLAUDE.md` | `CgRenderDemo` and its scenes — the in-game world demo, platform-agnostic |

### CrystalShader Pipeline
| Path | What it covers |
|---|---|
| `api/material/CLAUDE.md` | `CgMaterial` load/bind/keywords/attach-buffers/ownership; `CgRenderPassVariant`; `CgRenderQueue` constants |
| `render/CLAUDE.md` | `CgImmediate`, `CgFrameClock`, `CgViewFrustum` — the render package's root |
| `render/world/CLAUDE.md` | `CgWorldRenderer`, `CgDepthSnapshot`, `CgSortKey` — the world drawn under the host's camera |
| `render/post/CLAUDE.md` | `CgPostStack`, `CgPostEffect`, `CgPostPoint`, the composite and the built-in bloom — what runs after the world |
| `render/draw/CLAUDE.md` | `CgPipeline` (a CPU key), `CgBindingTable` (snapshots with handles), `CgInstanceKind`, `CgPassConstants`, `CgDrawChunk`, `CgBatcher` — what a recorded draw is made of; recording touches no GL (`render-graph`) |
| `render/graph/CLAUDE.md` | `CgRecording`, `CgFrameGraph`, `CgFrameBuilder` (order, cull, batch, pack — off the render thread), `CgExecutor`, `CgImmediate` — the frame graph |
| `material/CLAUDE.md` | `CgMaterialShader`, `CgMaterialShaderRegistry`, `CgMaterialProperties` |
| `material/parse/CLAUDE.md` | `CgShaderParser` facade, `CgParsedShader`, `CgMaterialShaderCompiler`, sub-parsers |
| `compute/CLAUDE.md` | `CgCompute`, `CgKernel`, `.compute` parsing and emission, `CgKernelProgram` — kernels |

### Shaders
| Path | What it covers |
|---|---|
| `api/shader/CLAUDE.md` | `CgShaderPreprocessor` (#include/pragma-once/cycle detection), the GLSL library |
| `material/CLAUDE.md` | `CgShader`, `CgShaderCompile` |

### Framebuffers
| Path | What it covers |
|---|---|
| `gpu/resource/CLAUDE.md` | The texture and buffer classes, the texture cache, targets of yours, readbacks |

### Textures
| Path | What it covers |
|---|---|
| `api/texture/CLAUDE.md` | `CgTexture` interface, `CgTextures`, `CgSampler` and where a texture's sampling comes from, `CgTextureType` |
| `gpu/resource/CLAUDE.md` | `CgTexture2D`, `CgTexture2DArray`, `CgTexture3D`, `CgTextureCubemap` concrete implementations |

### Mesh
| Path | What it covers |
|---|---|
| `api/mesh/CLAUDE.md` | `CgMesh` (a mesh as data), `CgMeshWriter`, `CgMeshShapes`, `CgMeshLoader`, `CgMeshLods`, `CgSubmesh`, `CgMeshChanges`, `CgMeshTopology`, `CgSkeletalMesh`, `CgCookedMesh` |
| `api/animation/CLAUDE.md` | `CgSkeleton`, `CgAnimSequence`, `CgMorphTargets`, `CgCookedAnimation`: what a skinned model moves by, as data; `CgPose`, `CgAnimSection`: sampling, blending and section time |
| `animation/CLAUDE.md` | `CgAnimInstance`, `CgAnimation`: skinned meshes posed here in `POSE`, on workers, their palettes written |

### Vertex / Instancing
| Path | What it covers |
|---|---|
| `api/vertex/CLAUDE.md` | `CgVertexFormat`, `CgVertexSemantic`, `CgAttribType` |

### Buffers
| Path | What it covers |
|---|---|
| `api/buffer/CLAUDE.md` | `CgGpuType`, `CgBufferField`, `CgBufferLayout` builder, std140/std430 alignment rules |
| `gpu/resource/CLAUDE.md` | `CgStreamBuffer` tiers (persistent ring → mapped ring), retained storage staged through the ring, the frame clock `CgFrameRing`, `CgQuadIndexBuffer` |
| `gpu/resource/CLAUDE.md` | `CgShaderBuffer` (SSBO), `CgUniformBuffer` (UBO), `CgShaderBufferRegistry`, binding point rules |
| `gpu/staging/CLAUDE.md` | `CgStagingBuffer`, `CgVertexWriter` (all vertex packing goes here), `CgBufferWriter` |

### GL State
| Path | What it covers |
|---|---|
| `api/state/CLAUDE.md` | `CgRenderState`, `CgDepthState`, `CgBlendState`, `CgCullState`, `CgStencilState`, as pipeline descriptions |

### The canvas
| Path | What it covers |
|---|---|
| `render/canvas/CLAUDE.md` | `CgCanvas`, `CgQuadBatch`, `CgCurveBatch`, `CgInstanceRun`, `CgClipTable`, `CgShapeTable` |

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

### Effects and networking
| Path | What it covers |
|---|---|
| `vfx/CLAUDE.md` | The VFX engine: effects, looks, particles on the GPU, cues; imports `docs/VFX.md` |
| `sequence/CLAUDE.md` | Sequences played (Unreal's Sequencer and a montage's sections): the sequencer and its players at the root, the compiled definition, time, and a subpackage per track kind |
| `net/CLAUDE.md` | Messages, requests, connections and networked objects; imports `docs/NETWORKING.md` |
| `cue/CLAUDE.md` | Gameplay cues (Unreal's cue notifies): `CgCueDefinition` compiled from `cue` assets, and `CgCueManager`, which plays them by tag |
| `tag/CLAUDE.md` | Gameplay tags: `CgGameplayTag`, `CgTagRegistry`, `CgTagContainer`, and the index table clients agree on (`CgTagNet`) |
| `object/CLAUDE.md` | Types declared once (Unreal's reflection): `CgType`, `CgProperty`, `CgTypes`, `CgValueType`, and `value`'s forms and quantities |
| `asset/CLAUDE.md` | An asset file's text and tree (`codec`: the one writer, the cooked form, `_v` steps) and its resolution over its parent (`tree`: `CgAssetData`, origins, the merge by id, edits, validation); the registry of every asset by kind and id (`registry`); loading to `COMPILED` on workers (`load`) |
| `asset/kind/CLAUDE.md` | The format's 20 asset kinds, their structs and dispatch families, declared against `object` |
| `serialization/CLAUDE.md` | Codecs and decoding into a live object; `format`: files in binary, JSON and YAML, and the NBT bridge (`format.file`, `format.nbt`); imports `docs/SERIALIZATION.md` |
| `job/CLAUDE.md` | Work off the caller's thread: `CgJob` handed back at its loop's drain, keyed and debounced, by lane; `CgJobLoop`; `forEach` across threads; `CgReply` answers and `CgProgress`; imports `docs/JOBS.md` |
| `storage/CLAUDE.md` | A product's own files, caches and downloads: `CgConfigStorage`, `CgCacheFiles`, `CgDownloads`, `CgDownloadLocations`; imports `docs/STORAGE.md` |
| `lifecycle/CLAUDE.md` | The engine's start on both sides: `CgEngineLifecycle`'s pre-init (mods' assets mounted), init (the network) and post-init (what was mounted loads), so a host only says when each phase is |
| `loading/CLAUDE.md` | The loading screen (Lyra's): processes and tasks it waits on, the hold, its view (the logo over a bar), and what a host does each frame |
| `collision/CLAUDE.md` | Worlds and queries, hits, hitboxes and hitbox types, the entity and block tables behind snapshots and batches, the debug show; imports `docs/COLLISION.md` |
| `shape/CLAUDE.md` | Shapes, poses, boxes and the exact tests between them (Jolt's GJK and EPA); imports `docs/COLLISION.md` |

### Platform SPI and hosts (outside `core/`)
| Path | What it covers |
|---|---|
| `platform/src/main/java/com/crystalgraphics/platform/CLAUDE.md` | `CgPlatform`, `CgPlatformService`, `CgService`, the device (`CgRHI`, host sections, host imports), the services — the contract between `core/` and every host |
| `runtime/mc/1710/src/main/java/com/crystalgraphics/mc/v1710/platform/CLAUDE.md` | The 1.7.10 bundle and its registration |
| `runtime/mc/modern/common/CLAUDE.md`, `…/common/src/main/java/com/crystalgraphics/mc/modern/platform/CLAUDE.md` | The modern tier 2: `PlatformServiceModern`, `GLDeviceModern`, `HostStateModern`, `LifecycleModern`, and the open GUI-frame tick |
| `runtime/mc/modern/{forge,neoforge,fabric}/AGENTS.md` | Each loader's versions, entry classes and render hooks |
| `freetype-msdfgen-harfbuzz-bindings/AGENTS.md` | The JNI bindings and the Zig native build |
