# `mc/v1710/platform` — the MC 1.7.10 platform

The 1.7.10 answer to `platform/`'s SPI: what names Minecraft or Forge, and nothing else. The GL backend,
the GL context and input are tier 1's (`runtime/lwjgl/2` — `Lwjgl2GLBackend`, `Lwjgl2GLContext`,
`Lwjgl2InputService`, `Lwjgl2CursorService`), shared with legacy Forge and the harness.

## Classes

| Class | Implements | What it does |
|---|---|---|
| `PlatformService1710` | `CgPlatformService` | The bundle: tier 1's backend, context and input, plus the services below, each built lazily. `onPreInit()` registers it and fills the cursor slot on a client; `onInit()` checks GL requirements and attaches the reload listener, client only |
| `service/ResourceService1710` | `CgResourceService` | `openStream(domain, path)` over `IResourceManager`; `null` on not-found, never throws |
| `service/RenderingService1710` | `CgRenderingService` | The legacy single-call path; the frame is driven by `CgRenderHook` instead |
| `service/LifecycleService1710` | `CgLifecycleService` | Straight to `CgGraphicsLifecycle`; called by `MixinMinecraft` (resize, fullscreen, shutdown) |
| `service/ReloadService1710` | `CgReloadService` | `CgAssetReloader.reload()`; `attachToResourceManager()` hooks `IReloadableResourceManager` |
| `service/SoundService1710` | `CgSoundService` | Minecraft's sound handler |
| `state/AngelicaStateProvider` | `CgGlStateProvider` | Reads Angelica's GL mirror instead of `glGet`, when Angelica is present |

## Registration

```
CrystalGraphics (the @Mod)
  onPreInit → PlatformService1710.onPreInit()
                ├─ CgPlatform.register(PlatformService1710.getInstance())
                ├─ CgPlatform.provide(CgCursorService.SERVICE, …)   client only
                └─ CgGlState.setProvider(AngelicaStateProvider)       if Angelica is present
  onInit    → PlatformService1710.onInit()                           client only
                ├─ CrystalGraphicsVersion.processAllRequirements()
                └─ ReloadService1710.attachToResourceManager()
```

The frame itself is the mixins': `CgRenderHook` on `EntityRenderer` (the opaque and transparent passes
inside `renderWorld`, and the frame tick at `updateCameraAndRender` TAIL) and `MixinMinecraft`.

## Do not

- Create GL objects in any FML event: mod loading runs on the splash screen's shared context, where a
  VAO or FBO is named in a context nothing draws with (`AGENTS.md` § *GL-thread rule*).
- Construct a client-only class on a server path — `Lwjgl2CursorService` names `org.lwjgl.input.Mouse`.
- Add a platform interface here; it belongs in `platform/`, and `core/` never imports this package.
