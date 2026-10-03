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
./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-desktop --device=vulkan"  # any scene on the Vulkan device
# Every scene: gl-debug-harness/AGENTS.md § Quick Start. Outputs land in gl-debug-harness/harness-output/{scene}/
```

`--device=gl` (the default), `tracked` or `vulkan` picks what `CgGL` runs on. Scenes call no raw GL; writing
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
| Wire anything into Minecraft, or send between client and server | [`docs/MINECRAFT_INTEGRATION.md`](docs/MINECRAFT_INTEGRATION.md) | each host's `CLAUDE.md` |
| Diagnose with a JVM flag | [`docs/DEBUG_FLAGS.md`](docs/DEBUG_FLAGS.md) | — |
| Build, ship, or add a Minecraft version | [Build and run](#build-and-run) | `docs/BUILD.md` |

**Guides load themselves.** A package's guide is its `CLAUDE.md` (every one is listed in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) § *Package guide index*), so the guide — and the
doc above that covers it — enters your context the first time you Read a file there. **Read a file before you change
it, and never change one through the shell that you have not Read**: a shell edit loads nothing.

---

## Global Coding Rules

These rules apply everywhere. All agents must internalize them.

**Public API first** — always use the highest abstraction layer available. `CgMaterial.load()` not `CgShaderFactory.fromSource()`; `CgMeshLoader.load()` not `GL15.glGenBuffers()`. Check package guides to find what already exists before writing raw GL.

**GL-thread rule** — all GL object creation, upload, and deletion must happen on the GL thread within an active context. This includes: `CgFrameBuffer.create()`, shader compilation. Violations produce silent garbage or driver crashes. **Meshes are data**, built and edited on any thread, and the mesh store places them on the render thread. **Textures are the exception, through `CgDeferral`** (`gpu/`): an object owns one, all its device work goes through it, and where the device may not be driven the work waits for the render thread, before the next frame executes. A new GPU object that must work off the render thread does the same rather than branching on `CgGL.mayIssueGl()` itself.

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
