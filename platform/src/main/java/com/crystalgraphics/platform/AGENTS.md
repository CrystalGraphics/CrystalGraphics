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
| `gl/CgGlStateManager`, `gl/state/*` | — | The GL state shadow, scopes and providers (`core/.../gl/state/AGENTS.md`) |
| `gl/CENSUS.md` | Generated | Every `CgGLBackend` method and who reaches it; `python platform/tools/gl_census.py` |
| `gl/tracked/CgTrackedGLBackend` | Class | `CgGLBackend` over a `CgDevice` — [the tracked backend](#the-tracked-backend-and-the-device-d3) |
| `gl/tracked/CgTracker`, `CgTrackedStateProvider` | Classes | Passes, pipelines and bindings from GL's calls; the scopes' state provider on it |
| `device/CgDevice` and its types | Interfaces, records | What a GPU device does: buffers, textures, SPIR-V modules, pipelines, passes, frames |
| `device/CgGlslCompiler` | Interface | A GL program's GLSL to SPIR-V and its binding table |
| `device/CgRecordingDevice` | Class | A device that logs every command and throws on misuse — for tests |
| `service/CgResourceService` | Interface | `openStream(domain, path)` — `null` on not-found |
| `service/CgRenderingService` | Interface | Viewport size and the legacy single-call frame |
| `service/CgLifecycleService` | Interface | Context init, destroy, resize, and the frame tick |
| `service/CgReloadService` | Interface | `onReload()` — F3+T and resource-pack changes |
| `service/CgInputService` | Interface | Key and mouse codes, modifier and button state, **and the clipboard** |
| `service/CgSoundService` | Interface | UI sounds |
| `service/CgCursorService` | Interface + slot | Presenting a cursor image; `CgCursorService.SERVICE` |
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

A host backend overrides only what its host caches: `Blaze3dGLBackend` routes through Minecraft's
`GlStateManager`, `GlStateManagerGLBackend` through legacy Forge's. Everything else reaches the driver
from tier 1. **A missing override is a missing GL call, not an exception.**

---

## `CgPlatform` — the registry

`register(bundle)` is called **exactly once**, before any `core/` code runs. `resources()` answers `null`
before registration, so `CgIO` can fall back to the classpath during early boot; every other getter
throws. **Registration must not demand a GL backend**: a dedicated server has none, and constructing
one there is `NoClassDefFoundError` on LWJGL — every bundle builds its services lazily.

```java
CgPlatform.register(PlatformServiceModern.getInstance());                            // the bundle
CgPlatform.provide(CgCursorService.SERVICE, new GlfwCursorService(windowHandle));    // a slot, client only
```

## Hosts and recording — the device seam's additions (D2)

`CgGLBackend` has four methods for living inside a host, all trivial on GL and meaningful on the tracked
backend to come: `importHostTexture` (a host texture as a GL name; `CgTexture2D.wrap` adopts it without
owning it), `hostSectionBegin`/`hostSectionEnd` (control goes back to the host / comes back to us; nothing on
GL), and `ownedByCurrentThread`. `CgGL` fronts each.

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
`CgGL` changes. Its devices today are `CgRecordingDevice` in tests; `CgVulkanDevice` comes with the harness on
LWJGL 3 (`plan/device-vulkan.md`).

```java
CgTrackedGLBackend gl = new CgTrackedGLBackend(device, new ShadercGlslCompiler(), true);
CgGL.init(gl);
CgCapabilities.init(new CgTrackedGLContext());
CgGlState.setProvider(new CgTrackedStateProvider(gl));
CgRenderPipeline.init();            // and anything else that creates objects: after the backend is in
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
| `CgGlslCompiler` | `ShadercGlslCompiler` (`runtime/lwjgl/vulkan`) compiles as Minecraft 26.2 does: shaderc on the source as written, then SPIRV-Cross reflection with the binding and location words patched so the stages agree by name |

What is easy to get wrong:

- **An object made before `CgGL.init(tracked)` does not exist on it.** Names are the backend's own.
- **GL's semantics hold where core relies on them**: a VAO owns its element binding, an attribute pointer
  captures the buffer bound at the call, `glBufferData` gives fresh storage, an incomplete texture samples
  black, an FBO with nothing attached raises `GL_INVALID_FRAMEBUFFER_OPERATION` at the draw, a colour clear
  under a partial write mask is drawn through the mask (a device's clear writes every channel), and a fence
  waited on with `GL_SYNC_FLUSH_COMMANDS_BIT` submits its frame.
- **What GL allows and a device cannot do throws** naming it — sampler objects, texture swizzles, 8-bit
  indices, a draw buffer after `GL_NONE`.
- **A fence is its frame**: a poll answers once that frame retires; a blocking wait on the current frame
  submits it, and a hosted device refuses the wait, since its host submits.
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
| Modern (1.13+) | `FrameHooks.endFrame()`, from `LifecycleModern.frameEnd()` after the GUI — a loader frame event, or on Fabric a node mixin; see `runtime/mc/modern/common/AGENTS.md` § *The frame end* |

`CgRenderingService.onFrameBegin(partialTick)` is the legacy single-call path; the hosts drive the
opaque and transparent passes from their own hooks instead.

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
