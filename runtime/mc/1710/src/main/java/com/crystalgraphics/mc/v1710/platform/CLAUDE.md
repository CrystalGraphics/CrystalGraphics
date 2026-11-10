# `mc/v1710/platform` — the MC 1.7.10 platform

The 1.7.10 answer to `platform/`'s SPI: what names Minecraft or Forge, and nothing else. The GL driver and input
are tier 1's (`runtime/lwjgl/2` — `Lwjgl2GLDriver`, `InputLwjgl2`, `ClipboardLwjgl2`, `Lwjgl2CursorService`), shared
with legacy Forge; the device is `gl/GLDevice1710`.

## Classes

| Class | Implements | What it does |
|---|---|---|
| `PlatformService1710` | `CgPlatformService` | The bundle: `startDevice()` adds `GLDevice1710`. `install(client)` registers it, fills every slot (the world slots and process exit on both sides; input devices, the clipboard, the resources, the cursor and the rest on a client) and registers `Ticks1710`; `onInit()` checks GL requirements and attaches the reload listener, client only |
| `gl/GLDevice1710` | `CgToolchainDevice` | The GL device in Minecraft's context, on `Lwjgl2GLDriver`, its host state read by query at each section (`GLHostState.byQuery`). Under lwjgl3ify it still runs, through lwjgl3ify's LWJGL 2 layer |
| `service/HostResources1710` | `CgHostResources.Host` | `open(namespace, path)` over `IResourceManager`, null on not-found, never throws; `listenForReloads()` sends F3+T and a pack change to `CgAssetReloader` |

## Registration

```
CrystalGraphics (the @Mod)
  onPreInit → PlatformService1710.install(client)
                ├─ CgPlatform.register(PlatformService1710.getInstance())
                ├─ the mod loader, the world slots, process exit, Ticks1710   both sides
                ├─ CgPlatform.provide(CgCursorService.SERVICE, …)   client only
  onInit    → PlatformService1710.onInit()                           client only
                ├─ CrystalGraphicsVersion.processAllRequirements()
                └─ HostResources1710.listenForReloads()
```

The frame itself is the mixins': `CgRenderHook` on `EntityRenderer` (the opaque and transparent passes
inside `renderWorld`, and the frame tick at `updateCameraAndRender` TAIL) and `MixinMinecraft` (resize, fullscreen and
shutdown, straight to `CgGraphicsLifecycle` and `CgEngineLifecycle`).

## Do not

- Create GL objects in any FML event: mod loading runs on the splash screen's shared context, where a
  VAO or FBO is named in a context nothing draws with (`docs/MINECRAFT_INTEGRATION.md` § *GL objects during mod loading*).
- Construct a client-only class on a server path — `Lwjgl2CursorService` names `org.lwjgl.input.Mouse`.
- Add a platform interface here; it belongs in `platform/`, and `core/` never imports this package.
