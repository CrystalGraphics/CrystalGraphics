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
(`bindFramebuffer`), and `CgGL` spells everything `glXxx`. `bindFramebuffer` carries the Core GL30 > ARB >
EXT waterfall, chosen per call from `CgPlatform.capabilities()` — there is no second, host-delegating
bind.

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

---

## `CgLifecycleService` — the frame tick

`onContextInit(w, h)`, `onContextDestroy()`, `onResize(w, h)`, `onFrameRendered()`, all on the GL
thread. **`onFrameRendered()` is the only sanctioned per-frame tick** for engine singletons
(`CgGraphicsLifecycle.tickFrame()` → `CgFontRegistry.tickFrame()`); feature code never calls
`tickFrame()` itself. It must fire once per rendered frame, GUI-only frames included:

| Host | Where it fires |
|---|---|
| 1.7.10, Forge 1.8–1.12.2 | `updateCameraAndRender` TAIL — no early return, covers the world, a GUI with no world, and skip-render-world alike |
| Modern (1.13+) | `FrameHooks.endFrame()`, at the end of the transparent pass — **world frames only**; see `runtime/mc/modern/common/AGENTS.md` § *Open* |

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
