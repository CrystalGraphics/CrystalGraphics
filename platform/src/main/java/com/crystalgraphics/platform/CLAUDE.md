# Platform SPI — `com.crystalgraphics.platform`

The seam between CrystalGraphics' `core/` and whatever runs it — a Minecraft loader, the harness, an
application. `core/` imports only this package, never `net.minecraft.*`, `org.lwjgl.*` or a loader.

**It also serves CrystalGUI**, which has no platform registry of its own: its input, sound, clipboard
and cursor seams are here too — see [UI-facing services](#ui-facing-services).

---

## File index

| File | Kind | What it owns |
|---|---|---|
| `CgPlatform` | Final class | The registry, both halves: `register(CgPlatformService)` and the `CgService` slots |
| `CgPlatformService` | Interface | The CLOSED bundle a platform registers — eight methods, no defaults |
| `CgService` | Class | An OPEN slot: a consumer declares it, a platform fills it, it carries its own absent-value |
| `gl/CgGLBackend` | Abstract class | Every raw GL call — the seam an implementation answers |
| `gl/CgGL` | Final class | The static facade `core/` calls; normalises names and forwards to the backend |
| `gl/CgGLContext` | Interface | Capability detection, probed once on the GL thread |
| `gl/CgCapabilities` | Class | The immutable capability snapshot built from the context |
| `gl/CgGlStateManager`, `gl/state/*` | — | The GL state shadow, scopes and providers (`core/.../gl/state/CLAUDE.md`) |
| `gl/CENSUS.md` | Generated | Every `CgGLBackend` method and who reaches it; `python platform/tools/gl_census.py` |
| `gl/tracked/CgTrackedGLBackend`, `CgTrackedGLContext`, `CgTrackedStateProvider` | Classes | `CgGLBackend` over a `CgDevice` — [the tracked backend](#the-tracked-backend-and-the-device-d3) — its capabilities, and the scopes' state provider on it |
| `gl/tracked/tracker/` | Classes | `CgTracker`: passes, pipelines and bindings from GL's calls, with the draw state and target it compares |
| `gl/tracked/gl/` | Classes | The GL objects the backend emulates — buffers, textures, programs, framebuffers, VAOs, render state, errors. Public for the backend only |
| `gl/tracked/memory/` | Classes | Buffer storage: slab allocation and the renamed allocations behind one GL buffer |
| `device/CgDevice`, `CgDeviceInfo`, `CgDeviceObject` | Interfaces, records | What a GPU device does: buffers, textures, SPIR-V modules, pipelines, passes, frames |
| `device/format/`, `resource/`, `pipeline/`, `command/`, `shader/` | Interfaces, records | Its types, by what they describe: formats; buffers, textures, samplers, views, timers; pipelines and bindings; encoders and passes; modules and `CgGlslCompiler` |
| `device/recording/CgRecordingDevice` | Class | A device that logs every command and throws on misuse — for tests |
| `service/CgResourceService` | Interface | `openStream(domain, path)` — `null` on not-found |
| `service/CgRenderingService` | Interface | Viewport size and the legacy single-call frame |
| `service/CgLifecycleService` | Interface | Context init, destroy, resize, and the frame tick |
| `service/CgReloadService` | Interface | `onReload()` — F3+T and resource-pack changes |
| `service/CgInputService` | Interface | Key and mouse codes, modifier and button state, **and the clipboard** |
| `service/CgSoundService` | Interface | UI sounds |
| `service/CgCursorService` | Interface + slot | Presenting a cursor image; `CgCursorService.SERVICE` |
| `service/CgWorldQuery` | Interface + slot | The world at a block or column, client only: collision and its boxes, fluid, light, what a block is made of, tint and biome colours, heightmaps, precipitation, the particle sprite. Primitives; core composes scans and the raycast (`com.crystalgraphics.world.CgWorldQueries`) |
| `service/CgEntityQuery` | Interface + slot | Entities by int id, client only: interpolated pose, flags, kind, and a box walk handing ids to a visitor. Core computes hands, head and feet (`CgEntityAttachments`) |
| `service/CgHostCamera` | Interface + slot | The offset and FOV scale the host adds to its camera each frame; core sums every shake into it (`CgCameraShake`) |
| `service/CgWorldSound` | Interface + slot | A sound at a point in the world, by resource location |
| `service/CgGameDirectory` | Interface + slot | The client's game directory, where `config/` lives: what CrystalGraphics' settings file (`com.crystalgraphics.settings`) is kept under. Absent: the working directory |
| `service/CgCacheDirectory` | Final class | `of(name)`: a folder under `<game>/crystalgraphics/cache/` for what can be rebuilt — the SPIR-V (`spirv`) and pipeline (`vulkan`) caches a host passes its compiler and device. Null where `-Dcrystalgraphics.cache=false` or it cannot be made; `-Dcrystalgraphics.cache.dir` moves it |
| `service/CgWorldEvents` | Final class | What the client learns happens in the world (explosion, block broken, entity hurt or killed, lightning), pushed by hosts to listeners, on the render thread |
| `service/CgNetworkChannel` | Interface + slot | Carrying one frame to the server or a player, and its ceiling: the whole platform side of networking. `CgNetworkChannel.SERVICE`; everything above it is core's `net` |
| `service/CgServerPlayers` | Interface + slot | The server's players, server thread: a level's, entity's or player's dimension id, an entity's position, a player's profile id, who has a chunk or an entity loaded, and where the world saves. What core's `CgAudience` composes; `NONE` reaches nobody |
| `input/CgSystemInput` | Interface | The raw event sink and its two event types |
| `input/CgKeyCodes`, `CgGlfwKeyCodes`, `CgMouseCodes`, `CgModifiers` | Constants | Code tables with no LWJGL import |

**Who implements the bundle**: `PlatformService1710`, `PlatformServiceLegacy`, `PlatformServiceModern`,
the harness's `PlatformServiceHarness` and core's `TestPlatformService`. Their GL backend, context and
input come from tier 1 (`runtime/lwjgl/2`, `runtime/lwjgl/3`); a Minecraft host adds only what names
Minecraft.

---

## `CgGLBackend` and `CgGL`

`core/` calls `CgGL`; `CgGL` calls `CgGLBackend.get()`. The backend's FBO methods carry no `gl` prefix
(`bindFramebuffer`), and `CgGL` spells everything `glXxx`. Every backend is core GL 3.3 — the ARB and EXT
fallbacks went with D1.

**A backend's `glShaderSource` gets ASCII only**: `CgGL.glShaderSource` turns every character above 127 into a space,
includes and all, since LWJGL 2 narrows a `char` to a byte and a comment's `─` ended the source as a NUL.

A host backend overrides only what its host caches: `Blaze3dGLBackend` routes through Minecraft's
`GlStateManager`, `GlStateManagerGLBackend` through legacy Forge's. Everything else reaches the driver
from tier 1. **A missing override is a missing GL call, not an exception.**

---

## `CgPlatform` — the registry

`register(bundle)` is called **exactly once**, before any `core/` code runs. `resources()` answers `null`
before registration, so `CgIO` can fall back to the classpath during early boot; every other getter
throws. **Registration builds no graphics**: a dedicated server has none, and constructing a backend
there is `NoClassDefFoundError` on LWJGL. `CgGL` takes `gl()` at the first `CgGL.fromHost()` or
`CgCapabilities.detect()`, and `CgCapabilities` takes `capabilities()` at its first probe; only a client
reaches either, on its render thread. `serverSmoke` fails if a backend was installed.

```java
CgPlatform.register(PlatformServiceModern.getInstance());                            // the bundle
CgPlatform.provide(CgCursorService.SERVICE, new GlfwCursorService(windowHandle));    // a slot, client only

// A harness or test using CgGL before any host section installs the backend itself:
CgPlatform.register(platform);
CgGL.init(platform.gl());
```

## Hosts and recording — the device seam's additions (D2)

`CgGLBackend` has four methods for living inside a host, all trivial on GL: `importHostTexture` (a host
texture as a GL name; `CgTexture2D.wrap` adopts it without owning it), `fromHost`/`toHost`, and
`ownedByCurrentThread`. `CgGL` fronts each.

**Host sections.** A host section is a stretch of our work inside the host's frame: the host has control
between them, and each one is bracketed where the host hands control to us and takes it back:

```java
CgGL.fromHost();       // the host hands us its frame
try {
    paint();
} finally {
    CgGL.toHost();     // and gets it back
}
```

They exist for a host that owns a Vulkan device, Minecraft 26.2 first. It records its frame as a series of its
own render passes, and its images have to be where its tracking left them when it resumes. A section records
into command buffers of our own, from the host's pool, which run in the host's submit after everything it
recorded before the section — never into the command buffer the host records into. `fromHost` is where a
device-backed backend starts recording against the host's current target, and `toHost` is where it ends our
pass, leaves the host's images as it expects, and hands our command buffers to its submit
(`HostedVulkanHost`). `CgGL.fromHost`'s javadoc has the full account.

- **Every host entry brackets itself**: `CgGraphicsLifecycle`'s `initContext`, `onResize` and `tickFrame`,
  and `CgRenderStage.fire`; the modern tree's world passes in `LifecycleModern`, around the target
  and depth convention they set; and CrystalGUI's `HostSession.PaintHost.enter`/`leave` on every loader.
- **Brackets nest**, and only the outermost pair reaches the backend, so a host's bracket may wrap a lifecycle
  entry. A close with none open throws.
- **Nothing on GL**, where the host's own repairs (`CgUiHostGl.leave`, the invalidations) do the work. On the
  tracked backend the close ends our pass; a hosted device hands the host its command buffer and images there.
- **A host's repair of its own state goes after the close**: it is the host's work, not ours.
- **Never inside a recording**: `CgGlRecordingBackend` refuses both, so a recording holds no bracket and is
  replayed inside one.

**`CgGlRecording`** records a `CgGL` stream and replays it through `CgGL` later:

```java
recording.begin();
try { paint(); } finally { recording.end(); }
recording.replay();
```

While it records, `CgGL` runs against `CgGlRecordingBackend` and the live `CgGlStateManager` with
deduplication off; scopes, `hostForeign` and invalidations become operations run on replay against the live
manager, and the live shadow is set aside and put back untouched. A query answers what the recording set, and
anything else from the live context. Creating objects, compiling and reading pixels are refused by name.
Foreign drawing survives a recording only through `CgGlState.hostForeign(Runnable, slots…)`, whose body is
recorded and run on replay in order. Owner thread only; `-Dcrystalgraphics.recording.debugScopes=true` names
where a scope left open at `end()` was opened.

## The tracked backend and the device (D3)

`CgTrackedGLBackend` answers every `CgGLBackend` call on a `CgDevice` instead of a GL driver, so nothing above
`CgGL` changes. Its devices are `CgRecordingDevice` in tests and `CgVulkanDevice` (`runtime/lwjgl/vulkan`,
`plan/device-vulkan.md`), which the harness runs with `--device=vulkan`.

```java
CgTrackedGLBackend gl = new CgTrackedGLBackend(device, new ShadercGlslCompiler(), true);
CgGL.init(gl);
CgCapabilities.init(new CgTrackedGLContext());
CgGlState.setProvider(new CgTrackedStateProvider(gl));
CgGraphicsLifecycle.initContext(w, h);   // and anything else that creates objects: after the backend is in
// ... the engine draws through CgGL, as on GL ...
gl.endFrame();                      // once a frame: ends the open pass and the device's frame
```

A test that wants the device's view reads it back:

```java
CgRecordingDevice device = new CgRecordingDevice(64, 64);
// ... draw ...
gl.endFrame();
device.draws();        // draws the device accepted -- it throws on a draw it cannot record
device.passes();       // each pass's targets and load/store ops
gl.stats();            // passes, pass breaks, clears folded into load ops, pipeline misses, renames
```

| Layer | Job |
|---|---|
| `CgDevice` | Buffers, textures, samplers, SPIR-V modules, pipelines by description, dynamic-rendering passes with load and store ops, one push-descriptor set per draw, frames in flight with release deferred to retirement |
| `CgTracker` | GL's implicit passes made explicit: a clear before the first draw is the pass's `CLEAR` load op; a copy, blit, readback or texture upload ends the pass and the next draw resumes with `LOAD`; a pipeline per state key; buffer memory renamed when an unretired frame used it |
| `CgTrackedGLBackend` | GL's objects and selector state over the tracker, one `Tracked*` class per domain in the census's order |
| `CgGlslCompiler` | `ShadercGlslCompiler` (`runtime/lwjgl/vulkan`) compiles as Minecraft 26.2 does: shaderc on the source as written, then SPIRV-Cross reflection with the binding and location words patched so the stages agree by name. Given a folder it keeps shaderc's output there by the source's hash, so a second launch runs shaderc on nothing it ran it on before; `CgVulkanDevice` keeps its pipeline cache the same way, one file per driver, written at `close()` |

What is easy to get wrong:

- **An object made before `CgGL.init(tracked)` does not exist on it.** Names are the backend's own.
- **GL's semantics hold where core relies on them**: a VAO owns its element binding, an attribute pointer
  captures the buffer bound at the call, `glBufferData` gives fresh storage, an incomplete texture samples
  black, an FBO with nothing attached raises `GL_INVALID_FRAMEBUFFER_OPERATION` at the draw, a colour clear
  under a partial write mask is drawn through the mask (a device's clear writes every channel), and a fence
  waited on with `GL_SYNC_FLUSH_COMMANDS_BIT` submits its frame.
- **What GL allows and a device cannot do throws** naming it — sampler objects, texture swizzles, 8-bit
  indices, a draw buffer after `GL_NONE`, `glReadPixels` into a pack buffer in a layout other than the
  texture's own.
- **A copy from or into device-local storage is a device copy at the call**, ending the pass like an upload;
  between host-visible buffers the CPU copies, through the destination's rename, unless a frame in flight uses the
  source: a kernel writing it has not run yet, so that is a device copy too. Mapping a host-visible buffer
  to read waits for the frame that last used it: nothing once it retired (`CgReadback` polls its fence first),
  `finish()` if it is the current one, which a hosted device refuses. A buffer with a `READ` usage hint (or
  `glBufferStorage` with `GL_MAP_READ_BIT`) is in memory the CPU caches (`CgGpuBuffer.Desc.hostReads`), from a pool of
  its own; any other host-visible buffer may be write-combined, where reading 4 MB pixel by pixel took a second.
  Persistently mapped storage the CPU only writes (the frame ring, upload leases) and the uniform arena are
  `streamed` (`CgGpuBuffer.Desc.streamed`): system memory, as GL drivers place it, since through a discrete GPU's
  BAR the CPU writes three times slower. Retained buffers stay where VMA puts them.
- **A texture upload from an unpack buffer** (`glTexSubImage*` with an offset: how `CgUploads`' leases land) is a
  device copy from the buffer itself (`copyBufferToTexture`) where the bytes are the texture's texels as they are,
  tightly packed; one that converts is unpacked on the CPU from the buffer's memory. A client-memory upload that
  needs no conversion is staged as it is, with no unpacking copy.
- **A fence is its frame**: a poll answers once that frame retires; a blocking wait on the current frame
  submits it, and a hosted device refuses the wait, since its host submits.
- **`cgBeginAsync`/`cgEndAsync`/`cgWaitAsync` bracket async compute**: dispatches, barriers and copies between the
  first two go to the device's compute queue, and a draw, a host section's end or the frame's end inside them throws.
  `OwnedVulkanHost` takes a compute-only queue family (buffers and images then shared by both, and barriers on it kept
  to the stages it has), else a second queue of the frame's family, and orders them with two timeline semaphores. A
  hosted device runs it on a compute queue its host made and never submits to (`HostedVulkanHost.computeQueue`:
  Minecraft 26.2 and 26.3's own), each async stretch submitted at once and waiting on a value the host's submit
  signals later, and an async pass there refuses the host's images; else in order, as GL records it
  (`asyncCompute()` false). On NVIDIA a second queue of the graphics family
  ran 0.3-0.6 ms slower than in order where the compute family saved 0.5 ms (`--mode=async-compute`):
  `-Dcrystalgraphics.vulkan.asyncCompute=graphics` forces it, `false` turns async off.
- **A copy into an image only the transfer queue has used runs there**, on Vulkan devices with a family that only
  copies (owned: family 1 on NVIDIA; hosted: Minecraft 26.2 and 26.3's own transfer queue). A new image stays
  `UNDEFINED` until its first copy or its first sampling, which lays what is left on the setup buffer; any use on
  another queue keeps it off the transfer queue for good. So does a buffer copy or write between `cgBeginTransfer` and
  `cgEndTransfer`, the mesh store's uploads, whose caller promises it reads nothing the frame's queue writes and writes
  nothing a frame in flight reads; one bracket's copies are unordered, the next bracket's come after them. The batch is
  submitted, and the frame's queue waits for it, at the first work there touching such an image or buffer, at a pass,
  or at the frame's end; mipmaps asked of it meanwhile are generated after that wait.
  `-Dcrystalgraphics.vulkan.transfer=false` keeps every copy on the frame's queue.
- **An upload replacing a whole texture the frame has used, 256 KB or more, goes to a new image** (`TrackedTextures`'
  rename, asked of `CgCommandEncoder.writeWaitsForFrame`), so it takes the transfer queue rather than waiting behind
  the frame: level 0 whole, with no other level specified, since those would be lost. The old image retires with the
  frames reading it. A smaller or partial upload stays on the frame's queue, where copies one after another share one
  barrier pair, and one call where they share a source and a texture, until anything else is recorded
  (`VulkanEncoder.endCopies`).
- **`compileInBackground()` makes a link return at once**, shaderc running on a worker (`crystalgraphics-shaderc`),
  as a driver with `KHR_parallel_shader_compile` does: `GL_COMPLETION_STATUS_KHR` says when it is done, and anything
  else asked of the program waits for it (`shader.spirvWait`) and makes its modules here. Hosts turn it on; tests
  leave links finished at the call. A relink of the program in use stays synchronous, since draws read it directly.
- **A draw, a dispatch or a barrier allocates nothing.** Uniform uploads come from `CgFrameArena` (`frameAllocate`
  answers a page and `frameOffset()` the place in it; a page is taken again once its frame retired), and the Vulkan
  device fills what it records per call in `VulkanScratch`, through a `ByteBuffer`, for LWJGL's raw `n` functions:
  on Java 25 LWJGL 3.4's struct setters write through FFM, and each write C2 does not inline builds a
  `MemorySegment`. The desktop on `vulkan` took 27% less CPU a frame for it. A struct recorded per call does the same;
  one made once may use LWJGL's classes.
- **A program's pipeline is built at its first draw**, where a Vulkan driver compiles it. `buildPipeline(mode)`
  builds it ahead, for the current program and state with nothing it reads bound — what the shader audit
  runs on a device, in both clip conventions.
- **`debug`** refuses sampling a texture the open pass renders to, and a vertex input no attribute array
  feeds. GL errors are kept for `glGetError` and logged once each.

`EngineOnTrackedBackendTest` (`runtime/lwjgl/vulkan`) is the whole stack headless: every shipped shader linked
in every variant, and a frame through the pipeline, quad and vector renderers.

---

## `CgLifecycleService` — the frame tick

`onContextInit(w, h)`, `onContextDestroy()`, `onResize(w, h)`, `onFrameRendered()`, all on the GL
thread. **`onFrameRendered()` is the only sanctioned per-frame tick** for engine singletons
(`CgGraphicsLifecycle.tickFrame()` → `CgFontRegistry.tickFrame()`); feature code never calls
`tickFrame()` itself. It must fire once per rendered frame, GUI-only frames included:

| Host | Where it fires |
|---|---|
| 1.7.10, Forge 1.8–1.12.2 | `updateCameraAndRender` TAIL — no early return, covers the world, a GUI with no world, and skip-render-world alike |
| Modern (1.13+) | `FrameHooks.endFrame()`, from `LifecycleModern.frameEnd()` after the GUI — a loader frame event, or on Fabric a node mixin; see `runtime/mc/modern/common/CLAUDE.md` § *The frame end* |

The hosts fire the world's render stages from their own hooks (`CgRenderStage.WORLD_OPAQUE`,
`WORLD_TRANSPARENT`); `CgRenderingService` answers the viewport only.

## `CgReloadService`

One method, `onReload()`, called directly — no callback registration. Each host bridges its own reload
event to it (`ReloadService1710.attachToResourceManager()` on 1.7.10, a reload listener on the others).

---

## Dependencies

`platform/` imports no LWJGL, Minecraft or loader type. log4j-api is `compileOnly` at 1.7.10's version:
exporting it would put that version on every consumer, and NeoForge requires `log4j-api` strictly 2.19.0.
Every host supplies its own.

---

## UI-facing services

Three services exist for CrystalGUI rather than for CrystalGraphics' own rendering. They sit here, in the
parent project's SPI, because CrystalGraphics is always present wherever CrystalGUI is — so a second
registry in the child project bought nothing and cost a failure mode: a loader could register one and not
the other, coming up with a working GL backend and a dead keyboard, with nothing to report it.

| Reached via | Provides |
|---|---|
| `CgPlatform.input()` | `translateKeyboardCodes`, `translateMouseCodes`, `getCurrentModifiers`, `isKeyDown`, `isMouseDown`, `howManyMouseButtons`, `getClipboard`, `setClipboard` |
| `CgPlatform.sound()` | `play(String soundId)` |
| `CgPlatform.get(CgCursorService.SERVICE)` | presenting a cursor picture; CrystalGUI decides which |
| `CgPlatform.get(CgWorldQuery.SERVICE)`, `CgEntityQuery.SERVICE` | the client world's blocks and entities; `NONE` with no level, on a server and in the harness |
| `CgPlatform.get(CgHostCamera.SERVICE)`, `CgWorldSound.SERVICE` | writes a host opts into: a camera offset and a world sound; `NONE` ignores them |

**The clipboard is on `CgInputService`, not a service of its own.** It is not conceptually input, but it
is reached the same way — one loader-owned handle, needed by exactly the code that handles keys — and
every implementation that provides one already provides the rest of the interface. Two methods do not earn
a registration slot.

### No defaults in the bundle

**Every method on `CgPlatformService` and `CgInputService` is abstract, and `CgSoundService` ships no
`NOOP` constant.** This is a deliberate rule, not an oversight:

> A default is an answer chosen on behalf of someone who never saw the question. A new platform compiles
> cleanly while silently inheriting "no sound, no clipboard", and nothing reports it — inheriting a no-op
> is indistinguishable from deciding on one. The same applies when a service is *added* here later: with
> defaults, every existing bundle keeps compiling and quietly does without the new capability.

Abstract methods make the compiler the reminder. **A platform with nothing to offer is still free to say
so** — an empty `play`, a `getClipboard` returning `""` are both correct answers. They just have to be
written in that platform's own source, where a reader can see the decision was made.
`translateMouseCodes` is the subtlest: the identity mapping is right on every platform seen so far, which
is precisely why inheriting it without looking would be a mistake on the first platform where it isn't.

**A `CgService` slot is the deliberate opposite, and that is the whole point of having two halves.** Its
absent-value is mandatory rather than forbidden, because a slot exists for a capability whose absence is a
*legitimate configuration* rather than an oversight — the cursor is the worked example: an unpresented
cursor is cosmetic, and a dedicated server, a headless test and a windowless fixture must not have to
register a stub to stay quiet. `CgService.get()` still announces the absence once, on first read, so
"nobody provided it" and "somebody chose the no-op" stay distinguishable in a log.

### Java level

An abstract module, like `:core`: Java 25 source, plus a Java 8 copy (`downgradedJar`) that every
consumer below 25 resolves (`cgbuildlogic.abstractModule`). Modern syntax is free; **a newer API is not
checked** — jvmdg stubs what it can, and a call it cannot stub fails on a Java 8 instance. The shipped
jar is downgraded whole, once.
