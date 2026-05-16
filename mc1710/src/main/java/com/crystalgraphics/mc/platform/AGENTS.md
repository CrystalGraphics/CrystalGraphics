# `mc/platform` — MC 1.7.10 Platform Service Adapters

This package contains the MC 1.7.10 concrete implementations of all six
`platform/` SPI interfaces. These classes are the only place in the codebase
that may directly reference Minecraft/Forge types — everything in `core/` goes
through the SPI.

All classes are bootstrapped by `PlatformRegistry1710`, called from
`CrystalGraphics.onPreInit()` and `CrystalGraphics.onInit()`.

---

## Class Map

### `PlatformRegistry1710` — bootstrap coordinator
Owns the full two-phase MC 1.7.10 bootstrap sequence. Called exclusively from
`CrystalGraphics` FML event handlers. `onPreInit()` constructs and registers
`PlatformService1710` with `CgPlatform`. `onInit()` validates GL requirements and
attaches the reload listener.

### `PlatformService1710` — implements `CgPlatformService`
Composes all six mc1710 service adapters into a single bundle. Holds private instances of
`RenderingService1710`, `LifecycleService1710`, and `ReloadService1710` with package-visible accessors.
`reload()` returns the `ReloadService1710` instance.

### `ResourceService1710` — implements `CgResourceService`
Single method `openStream(domain, path)`. Delegates to
`Minecraft.getMinecraft().getResourceManager()`.
Returns `null` on not-found — never throws. Used by `CgIO.openStream()` when
the platform is registered (falls through to classpath if not yet registered).

### `RenderingService1710` — implements `CgRenderingService`
`onFrameBegin(partialTick)` is called directly by `CgRenderHook` each frame — executes
`CgRenderPipeline.getInstance().execute(partialTick)`. Viewport dimensions read from
`Minecraft.displayWidth/displayHeight`.

### `LifecycleService1710` — implements `CgLifecycleService`
Delegates directly to `CgGraphicsLifecycle`. Called by `MixinMinecraft` via
`onContextInit(w, h)`, `onContextDestroy()`, `onResize(w, h)` — no callback lists.

### `ReloadService1710` — implements `CgReloadService`
Sole implementor of `CgReloadService` in the mc1710 platform layer. `onReload()` delegates
to `CgAssetReloader.reload()`. Static method `attachToResourceManager()` hooks into
`IReloadableResourceManager` to call `CgPlatform.reload().onReload()` on reload events.

### `Lwjgl2CapabilityProbe` — implements `CgCapabilityProbe`
Reads from LWJGL2's `ContextCapabilities` (obtained via `GLContext.getCapabilities()`).
`probe()` must be called once on the GL thread after context creation; all
boolean getters throw `IllegalStateException` if called before `probe()`.

### `Lwjgl2GlDispatch` — extends `CgGlDispatch`
Delegates every abstract method to the corresponding LWJGL2 static method.
FBO methods use the Core GL30 > ARB_framebuffer_object > EXT_framebuffer_object
waterfall reading from `CgPlatform.capabilitiesOrNull()`.

`bindFramebufferCompat(int fbo)` calls `OpenGlHelper.func_153171_g(GL_FRAMEBUFFER, fbo)`
so that Minecraft's internal FBO tracking stays consistent.

---

## Registration Flow

```
CrystalGraphics.onPreInit() → PlatformRegistry1710.onPreInit()
  └─ CgPlatform.register(new PlatformService1710())
       ├─ lifecycle()  → LifecycleService1710   (calls CgGraphicsLifecycle directly)
       ├─ rendering()  → RenderingService1710   (calls CgRenderPipeline.execute directly)
        └─ reload()     → ReloadService1710   (implements CgReloadService, delegates to CgAssetReloader.reload())

CrystalGraphics.onInit() → PlatformRegistry1710.onInit()
  ├─ CrystalGraphicsVersion.processAllRequirements()
  └─ ReloadService1710.attachToResourceManager()  → hooks IReloadableResourceManager
```

---

## Do Not

- Do NOT add new platform service interfaces here — they belong in `platform/`.
- Do NOT make core/ classes import from this package — dependency flows one way.
- Do NOT use `GL*C` classes (LWJGL3 naming) — use `GL11`, `GL20`, `GL30`, etc.
