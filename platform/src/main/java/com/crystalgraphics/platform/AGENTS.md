# Platform SPI — `com.crystalgraphics.platform`

This package defines the **8-file Service Provider Interface (SPI)** that isolates the
CrystalGraphics `core/` module from any platform-specific runtime (Minecraft 1.7.10, standalone
GL harness, future ports). The `core/` module only imports from this package — never from
`net.minecraft.*`, `org.lwjgl.*`, or `cpw.mods.*`.

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
| `CgPlatform.java` | Final utility class | Central registry; wires all 6 services |
| `CgPlatformService.java` | Interface | Bundle contract; groups all 6 services into one object |

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
- This replaces all `CallFamily.OPENGLHELPER_WRAPPER` routing in the existing `CgGlState` / FBO
  code once those classes are migrated to `core/`.

**Coverage**: Framebuffers, shaders (create/compile/link/uniforms/UBO/SSBO bindings), buffers
(gen/bind/data/sub-data/range bindings), VAOs, textures (2D/3D/arrays/cubemaps), draw calls
(direct + instanced), and the full GL state surface (blend, depth, cull, viewport, scissor,
stencil, alpha, polygon mode, color mask).

---

## `CgPlatform` — Central registry

All 6 services are registered atomically via `register(CgPlatformService)`. The registry must be
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

- **`CgLifecycleService`** — `onContextInit(w, h)`, `onContextDestroy()`, `onResize(w, h)`.
  All methods fire on the GL thread.
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
`platform/` is designed to be compilable standalone on any Java 8 JDK without Minecraft.
