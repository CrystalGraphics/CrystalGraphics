# `mc/v1710/platform` — the MC 1.7.10 platform

The 1.7.10 answer to `platform/`'s SPI: what names Minecraft or Forge, and nothing else. The GL context
and input are tier 1's (`runtime/lwjgl/2` — `Lwjgl2GLContext`, `InputLwjgl2`, `ClipboardLwjgl2`,
`Lwjgl2CursorService`), shared with legacy Forge; the GL backend is tier 1's `Lwjgl2GLBackend` extended
by `GLBackend1710`, for Angelica.

## Classes

| Class | Implements | What it does |
|---|---|---|
| `PlatformService1710` | `CgPlatformService` | The GL bundle: tier 1's backend and context, built lazily. `install(client)` registers it, fills every slot (the world slots and process exit on both sides; input devices, the clipboard, the resources, the cursor and the rest on a client) and registers `Ticks1710`; `onInit()` checks GL requirements and attaches the reload listener, client only |
| `gl/GLBackend1710` | `CgGLBackend` | Tier 1's `Lwjgl2GLBackend`, with the ten calls Angelica rewrites into methods its `GLStateManager` lacks routed through ones it has. Here and not in tier 1, because Angelica exists only on 1.7.10. Under lwjgl3ify it still runs, through lwjgl3ify's LWJGL 2 layer |
| `service/HostResources1710` | `CgHostResources.Host` | `open(namespace, path)` over `IResourceManager`, null on not-found, never throws; `listenForReloads()` sends F3+T and a pack change to `CgAssetReloader` |
| `state/AngelicaStateProvider` | `CgGlStateProvider` | Reads Angelica's GL mirror instead of `glGet`, when Angelica is present |

## Registration

```
CrystalGraphics (the @Mod)
  onPreInit → PlatformService1710.install(client)
                ├─ CgPlatform.register(PlatformService1710.getInstance())
                ├─ the mod loader, the world slots, process exit, Ticks1710   both sides
                ├─ CgPlatform.provide(CgCursorService.SERVICE, …)   client only
                └─ CgGlState.setProvider(AngelicaStateProvider)       if Angelica is present
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
