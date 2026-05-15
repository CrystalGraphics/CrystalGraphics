# Platform SPI — `com.crystalgraphics.platform`

This package defines the **8-file Service Provider Interface (SPI)** that isolates the
CrystalGraphics `core/` module from any platform-specific runtime (Minecraft 1.7.10, standalone
GL harness, future ports). The `core/` module only imports from this package — never from
`net.minecraft.*`, `org.lwjgl.*`, or `cpw.mods.*`.

---

## File Index

| File | Kind | What it owns |
|---|---|---|
| `CgFrameCallback.java` | `@FunctionalInterface` | Per-frame callback type; fired before CG render pass |
| `CgResourceService.java` | Interface | Asset loading (text + raw streams) |
| `CgRenderingService.java` | Interface | Viewport dimensions + frame callback registration |
| `CgLifecycleService.java` | Interface | GL context init / destroy / resize callbacks |
| `CgReloadService.java` | Interface | Hot-reload (F3+T) callback registration |
| `CgCapabilityProbe.java` | Interface | GL capability detection |
| `CgGlDispatch.java` | Abstract class (singleton) | All raw GL calls |
| `CgPlatformRegistry.java` | Final utility class | Central registry; wires all 6 services |

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

## `CgPlatformRegistry` — Central registry

All 6 services are registered atomically via `register(...)`. The registry must be called
**exactly once** during platform init before any `core/` code runs.

### `orNull()` vs strict getters

Several services expose **two getter variants**:

| Getter | Behaviour | Use case |
|---|---|---|
| `glOrNull()` | Returns `null` pre-registration | Early-boot classpath paths in `CgIO.openStream` |
| `gl()` | Throws `IllegalStateException` | Any post-init code path |
| `capabilitiesOrNull()` | Returns `null` pre-registration | `CgCapabilities` early bootstrap |
| `capabilities()` | Throws `IllegalStateException` | Post-init capability checks |
| `resourcesOrNull()` | Returns `null` pre-registration | `CgIO.loadSource` fallback before platform init |
| `resources()` | Throws `IllegalStateException` | Post-init resource loads |

`rendering()`, `lifecycle()`, `reload()` only have strict getters — those services are never
needed before platform registration.

### Why NOT `ServiceLoader`

FML's classloader in MC 1.7.10 makes `ServiceLoader` unreliable: provider JARs are not always
on the correct classloader at the time `ServiceLoader.load()` is called during FML preInit. The
direct argument pattern avoids classloader races entirely and is explicit about registration order.

---

## `CgCapabilityProbe`

Replaces the LWJGL2-coupled `CgCapabilities.detect()` in `core/`. The probe is called once via
`probe()` after context creation, then `core/` queries it through `CgPlatformRegistry.capabilities()`.

Capability surface mirrors the existing `CgCapabilities` query surface:
`isCoreFboSupported()`, `isArbFboSupported()`, `isExtFboSupported()`, `isVaoSupported()`,
`isSSBOSupported()`, `isTBOSupported()`, `isOpenGL40()`, `isOpenGL43()`, `isARBSync()`.

---

## `CgResourceService`

Replaces `IResourceManager` coupling in `CgIO`. The null-returning `resourcesOrNull()` path
allows `CgIO.loadSource` to fall back to classpath loading during early boot (e.g., before
Minecraft's resource manager is initialised). Methods return `null` on not-found — never throw.

---

## `CgLifecycleService` and `CgRenderingService`

- **`CgLifecycleService`** — replaces `CgGraphicsLifecycle`'s direct Forge/Mixin coupling.
  All callbacks fire on the GL thread. Context init fires after capabilities have been probed;
  destroy fires before the context is torn down; resize fires on fullscreen toggle.
- **`CgRenderingService`** — replaces `CgRenderHook`'s Mixin coupling. The `registerFrameCallback`
  method is the canonical way for `core/` to hook into the per-frame draw cycle.

---

## Dependency and No-Forbidden-Import Rule

`platform/` has **zero** LWJGL, Minecraft, or Forge imports. The only runtime dependencies are:
- `org.apache.logging.log4j:log4j-api` (logging — used by implementations, available to all)
- Lombok `compileOnly` (annotation processor for future concrete helper classes)

`core/` depends on `platform/` but NOT on the root project's `gtnhconvention` classpath.
`platform/` is designed to be compilable standalone on any Java 8 JDK without Minecraft.
