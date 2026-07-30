# Platform SPI — `com.crystalgraphics.platform`

This package defines the Service Provider Interface (SPI) that isolates the CrystalGraphics `core/`
module from any platform-specific runtime (Minecraft 1.7.10, standalone GL harness, future ports). The
`core/` module only imports from this package — never from `net.minecraft.*`, `org.lwjgl.*`, or
`cpw.mods.*`.

**It also serves CrystalGUI**, which is a separate project built on top of CrystalGraphics and has no
platform registry of its own. Its input, sound, clipboard and cursor seams are the last three services
below — see [UI-facing services](#ui-facing-services).

---

## File Index

| File | Kind | What it owns |
|---|---|---|
| `CgResourceService.java` | Interface | Asset loading — single `openStream` method |
| `CgRenderingService.java` | Interface | Viewport dimensions + direct-call frame hook (`onFrameBegin`) |
| `CgLifecycleService.java` | Interface | GL context init / destroy / resize — direct call contract |
| `CgReloadService.java` | Interface | Hot-reload (F3+T) — direct call contract (`onReload`) |
| `CgCapabilityProbe.java` | Interface | GL capability detection |
| `CgGlDispatch.java` | Abstract class (singleton) | All raw GL calls |
| `CgPlatform.java` | Final utility class | Central registry; wires every service |
| `CgPlatformService.java` | Interface | Bundle contract; groups every service into one object |
| `service/CgInputService.java` | Interface | Key/mouse code translation, modifier and button state, **and the clipboard** |
| `service/CgSoundService.java` | Interface | UI sounds |
| `service/CgCursorService.java` | Interface | Presenting a mouse cursor |
| `input/CgSystemInput.java` | Interface | Raw mouse/keyboard event sink + the two event types |
| `input/CgKeyCodes.java`, `CgMouseCodes.java`, `CgModifiers.java` | Constants | LWJGL2-shaped, with no LWJGL import |
| `input/CgCursor.java` | Enum | The cursor keyword set (CSS UI 4's, because it is the complete one) |
| `input/CgCursorBitmaps.java` | Utility | Procedural 32×32 ARGB cursor artwork, for platforms with no system cursors |

---

## `CgGlDispatch` — The GL call abstraction layer

`CgGlDispatch` is an **abstract class** (not an interface) for two reasons:
1. Future utility methods can be added without breaking existing implementations.
2. The static singleton pattern (`get()` / `setInstance()`) cannot live on an interface cleanly.

**Singleton access**:
```java
CgGlDispatch.get().glUseProgram(programId);
```

**`bindFramebufferCompat(int fbo)`** is the key FBO compatibility method:
- MC 1.7.10 implementation delegates to `OpenGlHelper.func_153171_g(GL_FRAMEBUFFER, fbo)` so
  that Minecraft's internal FBO tracking remains consistent.
- Standalone / harness implementations call `glBindFramebuffer(GL_FRAMEBUFFER, fbo)` directly.
- This replaces the former `CallFamily.OPENGLHELPER_WRAPPER` routing (that enum is deleted) in the FBO
  code once those classes are migrated to `core/`.

**Coverage**: Framebuffers, shaders (create/compile/link/uniforms/UBO/SSBO bindings), buffers
(gen/bind/data/sub-data/range bindings), VAOs, textures (2D/3D/arrays/cubemaps), draw calls
(direct + instanced), and the full GL state surface (blend, depth, cull, viewport, scissor,
stencil, alpha, polygon mode, color mask).

---

## `CgPlatform` — Central registry

All services are registered atomically via `register(CgPlatformService)`. The registry must be
called **exactly once** during platform init before any `core/` code runs.

The preferred registration path is `CgPlatform.register(new PlatformService1710())` — one
object, compiler-enforced completeness. The old 6-arg `register(gl, caps, res, rendering,
lifecycle, reload)` overload still exists but is `@Deprecated`; it delegates to the bundle path.

### Getter variants

`resources()` returns `null` pre-registration (safe for `CgIO.openStream` classpath fallback).
All other getters (`gl()`, `capabilities()`, `rendering()`, `lifecycle()`, `reload()`) throw
`IllegalStateException` before registration.

---

## `CgCapabilityProbe`

Replaces the LWJGL2-coupled `CgCapabilities.detect()` in `core/`. The probe is called once via
`probe()` after context creation, then `core/` queries it through `CgPlatform.capabilities()`.

Capability surface mirrors the existing `CgCapabilities` query surface:
`isCoreFboSupported()`, `isArbFboSupported()`, `isExtFboSupported()`, `isVaoSupported()`,
`isSSBOSupported()`, `isTBOSupported()`, `isOpenGL40()`, `isOpenGL43()`, `isARBSync()`.

---

## `CgResourceService`

Single method: `openStream(String domain, String path)`. Returns `null` on not-found — never
throws. Replaces `IResourceManager` coupling in `CgIO`. The null-returning `resources()` path
allows `CgIO.openStream` to fall back to classpath loading during early boot (e.g., before
Minecraft's resource manager is initialised).

---

## `CgLifecycleService` and `CgRenderingService`

Both use a **direct call contract** — the platform implementation calls the methods directly,
no `register*Callback` indirection.

- **`CgLifecycleService`** — `onContextInit(w, h)`, `onContextDestroy()`, `onResize(w, h)`,
  `onFrameRendered()`. All methods fire on the GL thread.
  `onFrameRendered()` is the canonical, and *only sanctioned*, per-frame tick point for
  engine-owned singletons that need per-frame bookkeeping (currently
  `CgFontRegistry.tickFrame()`, called via `CgGraphicsLifecycle.tickFrame()`). Feature-level
  code (`CgUiPaintContext`, demo overlays, etc.) must never call
  `CgGraphicsLifecycle.tickFrame()` directly — only `CgLifecycleService` implementations
  should, wired to whatever native hook reliably fires exactly when a frame is actually
  rendered. For MC 1.7.10 this is `CgRenderHook`'s dedicated mixin on
  `EntityRenderer.updateCameraAndRender` at `@At("TAIL")` — **not**
  `Minecraft.runGameLoop()` (the general tick-and-maybe-render dispatch that can complete
  an iteration with zero actual rendering) and **not** `renderWorld` alone (never fires
  without a loaded world). `updateCameraAndRender`'s body was verified to have no early
  returns: it is one straight-line sequence gated by a single outer
  `if (!this.mc.skipRenderWorld)` that wraps both the world-render branch and the
  no-world overlay-setup branch, followed by the unconditional GUI screen draw — so TAIL
  fires exactly once per call and covers the in-world case, the no-world-with-GUI case
  (main menu, etc.), and the skip-render-world case uniformly. No known gap remains.
- **`CgRenderingService`** — `onFrameBegin(partialTick)` called each frame; `getViewportWidth()`
  and `getViewportHeight()` for viewport dimensions.

## `CgReloadService`

Direct call contract — single method `onReload()`. No callback registration. The platform
bridge (`ReloadService1710.attachToResourceManager()`) wires MC's reload event to call
`CgPlatform.reload().onReload()` directly.

---

## Dependency and No-Forbidden-Import Rule

`platform/` has **zero** LWJGL, Minecraft, or Forge imports. The only runtime dependencies are:
- `org.apache.logging.log4j:log4j-api` (logging — used by implementations, available to all)
- Lombok `compileOnly` (annotation processor for future concrete helper classes)

`core/` depends on `platform/` but NOT on the root project's `gtnhconvention` classpath.
`platform/` is designed to be compilable standalone on a Java 17 JDK without Minecraft.


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
| `CgPlatform.cursor()` | `setCursor(CgCursor)` |

**The clipboard is on `CgInputService`, not a service of its own.** It is not conceptually input, but it
is reached the same way — one loader-owned handle, needed by exactly the code that handles keys — and
every implementation that provides one already provides the rest of the interface. Two methods do not earn
a registration slot. Both default to a no-op pair.

### No defaults, anywhere in this SPI

**Every method on `CgPlatformService` and `CgInputService` is abstract, and neither `CgSoundService` nor
`CgCursorService` ships a `NOOP` constant.** This is a deliberate rule, not an oversight:

> A default is an answer chosen on behalf of someone who never saw the question. A new platform compiles
> cleanly while silently inheriting "no sound, no cursor, no clipboard", and nothing reports it — inheriting
> a no-op is indistinguishable from deciding on one. The same applies when a service is *added* here later:
> with defaults, every existing bundle keeps compiling and quietly does without the new capability.

Abstract methods make the compiler the reminder. **A platform with nothing to offer is still free to say
so** — an empty `play`, an empty `setCursor`, a `getClipboard` returning `""` are all correct answers. They
just have to be written in that platform's own source, where a reader can see the decision was made.

`mc1201`'s three UI services are exactly this case today: written out as visible stubs with a note on what
a real implementation needs, rather than inherited silently. `translateMouseCodes` is the subtlest one —
the identity mapping is right on every platform seen so far, which is precisely why inheriting it without
looking would be a mistake on the first platform where it isn't.

### Java level

This module mirrors `:core` exactly — toolchain 17, source/target 17, with Jabel and the jvmDowngrader
API as `compileOnly` (so an `import ...Desugar` compiles) and no annotation processor, because the dual
compile pipeline in `core/build.gradle.kts` is commented out in both. Records, `var`, switch expressions
and pattern matching are all available.

> It was Java 8 source until 2026-07-30, which meant this module could not use records while `core/`
> could — `CgSystemInput`'s two event types had to be hand-written classes with record-shaped accessors.
> The two modules are consumed together and shadowed into the same loader jar, so there was never a
> reason for them to disagree.
