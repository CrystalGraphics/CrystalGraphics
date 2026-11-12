# CrystalGraphics — Agent Knowledge Base

**What**: a modern rendering engine — materials, meshes, framebuffers, instancing and text — designed for Vulkan
and running on OpenGL wherever Vulkan is not the backend. It runs in any application that hosts it (the GL debug
harness is one), and also inside Minecraft: **one jar** for Forge 1.7.10–26.3, NeoForge 1.20.2–26.3 and Fabric
1.14.4–26.3. **Authored in** Java 25, with a Java 8 copy of every engine module. **The parent of** CrystalGUI, which
builds every Minecraft node against this repository's node of the same version.

**Where documentation goes.** This file holds only what every session needs — rules and routing. New reference
material goes to the doc that owns the subject (`docs/`, indexed in `docs/ARCHITECTURE.md` § *Docs*), and a
package's rule to that package's `CLAUDE.md`. Never append a section here; add a routing row if a new doc needs one.

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

Commands, the rules the build will not tell you, and its caveats are all in `docs/BUILD.md`. Running Minecraft —
dev clients, `serverSmoke`, `prodSmoke` — is CrystalGUI's: this mod alone draws nothing.

| Read | When |
|---|---|
| **[`docs/BUILD.md`](docs/BUILD.md)** | **First, for anything about the build**: layout, nodes and toolchains, stub mode, commands, releasing, and adding a Minecraft version |
| **[`docs/PROFILING.md`](docs/PROFILING.md)** | **Measuring anything**. CrystalGUI adds `docs/CGUI_PROFILING.md`; the `profiling` skill is the checklist |
| **[`docs/MINECRAFT_RENDERING_CONVENTIONS.md`](docs/MINECRAFT_RENDERING_CONVENTIONS.md)** | **Before drawing into Minecraft's frame, and with every new Minecraft version** |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) § *Docs* | Every other doc — setup, `singlejar-logic`, stubs, natives, hotswap — and when to read it |

---

## Render testing — the GL debug harness

For anything that touches rendering, shaders, FBOs, text or atlases, **test in the harness, not
Minecraft**: it boots in seconds, needs no Minecraft context, and writes PNGs. It is CrystalGUI's
submodule (`gl-debug-harness/`, Java 25) and runs from CrystalGUI's root; authoring rules are its own
`AGENTS.md`.

```bash
./gradlew :gl-debug-harness:runHarness --args="--list"
./gradlew :gl-debug-harness:runHarness --args="--mode=forward-renderer"       # CgWorldRenderer through both world stages
./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-desktop --rhi=vulkan"  # any scene on the Vulkan device
# Every scene: gl-debug-harness/AGENTS.md § Quick Start. Outputs land in gl-debug-harness/harness-output/{scene}/
```

`--rhi=gl` (the default) or `vulkan` picks the device. Scenes call no raw GL; writing
and registering one is the harness's `AGENTS.md`.

---

## Module layout

`platform/` is the SPI only; `core/` is all rendering; `runtime/lwjgl/*` is tier 1 (GL, Vulkan, input per toolkit, naming no Minecraft class); `runtime/mc/*` are the hosts. Every module, its Java level and its role: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

**Rule**: `core/` and `platform/` have zero compile dependency on LWJGL, Minecraft or any loader — enforced by
each module's import guard (§ *Global Coding Rules*). A client-only class *constructed* on a server is a runtime
property the guard cannot see; `serverSmoke` catches it.

---

## Platform SPI architecture

This is the load-bearing architectural law. Read it before writing code that touches GL or a host.

```
platform/        ← the SPI: CgRHI and its devices (CgGLDevice, CgNullRHI), the services, CgService slots
    ↑
core/            ← rendering logic — records for the installed CgRHI, calls the slots' owners (CgHostResources, CgInput, ...)
    ↑
runtime/lwjgl/*  ← tier 1: the GL drivers, input devices and clipboard per toolkit; CgVulkanDevice
    ↑
runtime/mc/*     ← a host: registers a bundle over tier 1, adds what names Minecraft
```

**`CgRHI` is the seam** (Unreal's RHI): `CgGLDevice` on every GL-era host, `CgVulkanDevice` on Minecraft 26.2+'s
Vulkan and the harness's `--rhi=vulkan`, `CgNullRHI` in tests. A host starts its device in
`CgPlatformService.startDevice()`; `CgDeviceSection` brackets every host entry, and `CgHostImports` names what the
host owns. The platform guide has each.

```java
// In core/ — never a raw GL call, never an LWJGL import: record a pass, and the device draws it
CgRasterPass pass = rec.raster("preview").color(picture, CgLoad.CLEAR).camera(camera);
pass.draw(mesh, material).transform(model);
pass.end();
CgRenderer.executeNow(rec);
InputStream in = CgHostResources.open("mymod", "textures/icon.png");   // a slot, through its owner

// A host, once, from an entry point that runs on both sides: the bundle and every platform slot
PlatformServiceModern.install(ModLoaderForge.INSTANCE);     // or PlatformService1710.install(client), PlatformServiceLegacy.install(client)
```

**Registration builds no graphics**: a dedicated server has none. The graphics start calls `startDevice()` on a
client's render thread; each bundle builds its services lazily, and a client-only service (the cursor) is filled only
on a client.

**If you find yourself calling raw GL inside `core/` or importing a loader type, you are in the wrong
module.**

---

## Cross-platform feature checklist

**A new platform capability:**

1. Declare it in `platform/`: a `CgRHI` method (every device implements it), a service method, or — when its
   absence is a legitimate configuration, as for a cursor — a `CgService` slot with an absent-value.
2. Implement it in tier 1 (`runtime/lwjgl/2`, `runtime/lwjgl/3`) when it is toolkit-level.
3. The compiler then names every bundle that must answer: `PlatformService1710`, `PlatformServiceLegacy`,
   `PlatformServiceModern`, the harness's `PlatformServiceHarness`, core's `TestPlatformService`.
4. Call it through `CgPlatform` / `CgRHI` from `core/`.

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
| Write or load a `.shader` material, a vertex factory or a ui primitive | **[`docs/MATERIAL_FORMAT.md`](docs/MATERIAL_FORMAT.md)** (the `#domain` format), [`docs/SHADERS.md`](docs/SHADERS.md) for the engine side and the older `Pass { }` format, then **[`docs/PRECACHE.md`](docs/PRECACHE.md)** | `api/material/CLAUDE.md` |
| Write or run a kernel (`.compute`) | [`docs/SHADERS.md`](docs/SHADERS.md) § *Compute*, then **[`docs/PRECACHE.md`](docs/PRECACHE.md)** | `compute/CLAUDE.md` |
| Draw anything new: a material, a keyword set, a renderer pass, a kernel, a look | **[`docs/PRECACHE.md`](docs/PRECACHE.md)**: what is precached for you, and what you must precache | `render/world/CLAUDE.md` |
| Write an effect, a particle emitter or a particle module | [`docs/VFX.md`](docs/VFX.md) | `vfx/CLAUDE.md` |
| Work on the VFX rewrite toward Unreal parity (`vfx-aaa`) | `plan/crystalgraphics/vfx-aaa/aaa-rules.md` first: binding | `vfx/CLAUDE.md` § *AAA parity* |
| Build a GPU-driven pipeline: kernels writing what draws, indirect and multi-draw draws, GPU culling | **[`docs/GPU_DRIVEN_RENDERING.md`](docs/GPU_DRIVEN_RENDERING.md)** | `compute/CLAUDE.md`, `render/graph/CLAUDE.md` |
| Draw at a host's render stage, or meshes into the world | [`docs/ENGINE_API.md`](docs/ENGINE_API.md) § *Render stages*, § *CgWorldRenderer* | `render/world/CLAUDE.md` |
| Create a texture, a target of yours, a mesh or a buffer | [`docs/ENGINE_API.md`](docs/ENGINE_API.md) | `api/texture`, `api/buffer`, `api/mesh`, `gpu/resource` |
| Upload, compute or read back off the render thread or beside the frame | [`docs/ENGINE_API.md`](docs/ENGINE_API.md) § *Async* | `gpu/`, `gpu/resource`, `render/graph` |
| Know who sets GPU state, on GL and Vulkan, and what crosses to the host | **[`docs/RENDER_STATE.md`](docs/RENDER_STATE.md)** | `platform/gl/state` |
| Render text on screen | `docs/font/README.md` | `text/CLAUDE.md` |
| Build a HUD element, a widget over a point in the world (damage numbers, name plates, markers) or a subtitle's look | **[`docs/WIDGETS.md`](docs/WIDGETS.md)** | `widget/CLAUDE.md` |
| Draw 2D quads, curves or text, in the world or on the screen, below widgets | **[`docs/CANVAS.md`](docs/CANVAS.md)** | `render/canvas/CLAUDE.md` |
| Load a resource file (shader source, config, image) | [`docs/ENGINE_API.md`](docs/ENGINE_API.md) § *Resource I/O* | `util/io/CgIO` |
| Init, resize or tear down a context | [`docs/ENGINE_API.md`](docs/ENGINE_API.md) § *CgGraphicsLifecycle* | — |
| Test rendering without Minecraft | [Render testing](#render-testing--the-gl-debug-harness) | `gl-debug-harness/AGENTS.md` |
| Send between client and server, or keep state in sync on the clients that see it | [`docs/NETWORKING.md`](docs/NETWORKING.md) | `net/CLAUDE.md` |
| Add a command, or complete one, on every version | [`docs/COMMANDS.md`](docs/COMMANDS.md) | `platform/.../command` |
| Write a codec, save to a file (binary, JSON, TOML, YAML) or NBT, or decode into a live object | [`docs/SERIALIZATION.md`](docs/SERIALIZATION.md) | `serialization/CLAUDE.md` |
| Ask what a shape hits, give something a hitbox, test shapes against each other | [`docs/COLLISION.md`](docs/COLLISION.md) | `collision/CLAUDE.md`, `shape/CLAUDE.md` |
| Load, make, sample, draw into or read back a texture; a texture's settings file | [`docs/TEXTURES.md`](docs/TEXTURES.md) | `api/texture/CLAUDE.md`, `gpu/resource/CLAUDE.md` |
| Load anything (files, textures, meshes, shaders, a kit), behind the loading screen or during play | **[`docs/LOADING.md`](docs/LOADING.md)** | `loading/CLAUDE.md`, `lifecycle/CLAUDE.md` |
| Run work off the game's thread, or split it across cores | [`docs/JOBS.md`](docs/JOBS.md) | `job/CLAUDE.md` |
| Play a sound: by name, in the world, riding an entity, music, subtitles, a mix | [`docs/AUDIO.md`](docs/AUDIO.md) | `audio/CLAUDE.md` |
| Keep a product's files, a cache, or download something verified and resumable | [`docs/STORAGE.md`](docs/STORAGE.md) | `storage/CLAUDE.md` |
| Wire anything into Minecraft | [`docs/MINECRAFT_INTEGRATION.md`](docs/MINECRAFT_INTEGRATION.md) | each host's `CLAUDE.md` |
| Diagnose with a JVM flag | [`docs/DEBUG_FLAGS.md`](docs/DEBUG_FLAGS.md) | — |
| Run the game unattended, or write a test it plays: a smoke run, a probe, a capture | [`docs/GAUNTLET.md`](docs/GAUNTLET.md) | `gauntlet/` |
| Build, ship, or add a Minecraft version | [Build and run](#build-and-run) | `docs/BUILD.md` |

**Guides load themselves.** A package's guide is its `CLAUDE.md` (every one is listed in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) § *Package guide index*), so the guide — and the
doc above that covers it — enters your context the first time you Read a file there. **Read a file before you change
it, and never change one through the shell that you have not Read**: a shell edit loads nothing.

---

## Global Coding Rules

These rules apply everywhere. All agents must internalize them.

**Precache every pipeline** — every shader variant and kernel a frame uses is named to `CgPipelinePrecache` before
it draws. VFX looks behind a cue, the registry or a kit, skinned materials and the engine's passes are already. A new
material, keyword set, renderer pass or kernel is not until you add it to a collector (the renderer's own, as
`CgWorldRenderer`'s `crystalgraphics:world` and `CgPostStack`'s `crystalgraphics:post` do) or a request. Unprecached, it
draws late and counts `pipeline.missed`; check new content with `-Dcrystalgraphics.pipeline.logMisses=true`.
**[`docs/PRECACHE.md`](docs/PRECACHE.md)** has both lists.

**The shipped pipeline list goes stale with every engine shader edit** — `pipelines/recorded.txt` is keyed by each
program's source hash, so an edited `.shader`, `.glsl` or `.compute` builds its Vulkan pipelines at their draws until
the list is refreshed. Refresh it before a release (`docs/PRECACHE.md` § *The shipped pipeline list*, and
`docs/CGUI_BUILD.md` § *Releasing* in CrystalGUI); when you change a shipped shader, say so in your report.

**Public API first** — always use the highest abstraction layer available. `CgMaterial.load()` not raw GLSL; `CgMeshLoader.load()` not `GL15.glGenBuffers()`. Check package guides to find what already exists before writing raw GL.

**GL-thread rule** — all GL object creation, upload, and deletion must happen on the GL thread within an active context. This includes: a texture's storage, shader compilation. Violations produce silent garbage or driver crashes. **Meshes are data**, built and edited on any thread, and the mesh store places them on the render thread. **Textures are the exception, through `CgDeferral`** (`gpu/`): an object owns one, all its device work goes through it, and where the device may not be driven the work waits for the render thread, before the next frame executes. A new GPU object that must work off the render thread does the same rather than branching on `CgRHI.onDeviceThread()` itself.

**Vertex data via `CgVertexWriter`** — never write vertex bytes via raw `ByteBuffer.putFloat()`. All vertex packing goes through `CgVertexWriter.forBuffer()`. Index buffer `putShort()`/`putInt()` is the only exception.

**Java version by module** — `core/`, `platform/` and `runtime/lwjgl/*` are authored at Java 25 and each builds a Java 8 copy that consumers below 25 resolve. **A newer API is not checked**: jvmdg stubs what it can, and a call it cannot stub fails on the player's JVM. Never lower an abstract module's Java to suit a consumer. Per module: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) § *Java version by module*.

**Forbidden cross-module imports:**

| In module | Forbidden | Reason |
|---|---|---|
| `core/`, `platform/` | `net.minecraft.*`, `net.minecraftforge.*`, `cpw.mods.fml.*`, `org.lwjgl.*` | Loader-blind — enforced by each module's import guard |
| `runtime/mc/modern/*` | `org.lwjgl.input.Mouse`, LWJGL2 input types | LWJGL3 environment |
| `runtime/mc/1710/*` | LWJGL3 GL calls, `com.mojang.*` | LWJGL2 environment |

### Lombok

Use it in all new code: `@Getter` + `@RequiredArgsConstructor` for immutable classes, `@Value` for data carriers,
`@Builder` past four parameters, `@Slf4j` for loggers. Always `@EqualsAndHashCode(callSuper = true)` on a subclass.
