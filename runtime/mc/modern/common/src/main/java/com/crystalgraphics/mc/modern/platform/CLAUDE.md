# runtime/mc/modern/common — Platform Package

`com.crystalgraphics.mc.modern.platform` — the modern-era platform, one source per Minecraft version
through Stonecutter directives. No Forge, NeoForge or Fabric type appears here.

## Entry points

**`PlatformServiceModern`** implements `CgPlatformService` over tier 1 (`runtime/lwjgl/3`). A loader
installs it once, with every platform slot, before the engine's phases:

```java
PlatformServiceModern.install(ModLoaderForge.INSTANCE);              // both sides: the bundle, the loader, the world slots
if (client) PlatformServiceModern.installClient();                   // the client's slots; Fabric: its client entrypoint
CgEngineLifecycle.preInit();
CgEngineLifecycle.init(channel);
```

**`ServerModern`** carries the server's start, tick and stop to `CgEngineLifecycle` (and the player query's server);
`NetworkModern` only the players and connections.

**`LifecycleModern`** is everything a loader forwards to: the opaque and transparent passes, the reload,
the shutdown. A loader decides only which event (or node mixin) reaches it.

## Classes

| Class | What it does |
|---|---|
| `gl/GLDeviceModern` | The GL device in Minecraft's context, on `Lwjgl3GLDriver` (SDL3's contexts from 26.3), its host state `HostStateModern`; the shader toolchain takes over its compiles once fetched |
| `gl/HostStateModern` | What Minecraft's `GlStateManager` cache believes, read as each host section opens and set back as it closes, plus what the cache alone cannot say: `BufferUploader`'s record dropped (1.19.2-1.21.4), 1.21.11's samplers unbound. At the first host section it turns off the `GL_DEBUG_OUTPUT` Minecraft enables at its default `glDebugVerbosity`, for the context (`-Dcrystalgraphics.gl.keepHostDebugOutput=true` keeps it): on NVIDIA it made every GL call several times dearer, and switching it per section cost the driver 1.1 ms a switch |
| `gl/GlStateManager` | A shim that spells 1.14's un-prefixed methods the later way |
| `HostStateVerifier` | `-Dcrystalgraphics.host.verify=true`: after each pass, compares the driver against `GlStateManager`'s fields and names the domain that disagrees |
| `Blaze3dTextureUnits` | how many texture units Blaze3D's table models: the units `HostStateModern` reads and a host section sets back |
| `CgMinecraftClient` | the client state Minecraft moved (26.2: the screen, the loading overlay and the HUD's hidden flag onto `mc.gui`, the main target onto the game renderer), spelled once. Use it instead of a directive at the call site |
| `ScreensModern` | `CgScreens` here: a `Screen` of its own that draws nothing, Escape its owner's, the HUD hidden under it where an owner asks (1.21.6 to 26.0), and 26.3's input method (`preeditUpdated`, the candidate list's place); `giveBack` hands a key to a screen of Minecraft's |
| `HostKeys` | CrystalGraphics' key and button numbers in this Minecraft's own: GLFW's, SDL's from 26.3 |
| `FrameHooks` | the end-of-frame resize check and `CgGraphicsLifecycle.tickFrame()` |
| `ResourceIds`, `Windows` | a `ResourceLocation` and the game window, in whichever spelling the running version has |
| `service/HostResourcesModern` | `CgHostResources` over the client's `ResourceManager`; `LifecycleModern.reload()` sends a reload to `CgAssetReloader` |

**A domain missing from `HostStateModern`'s section slots is a missing GL call, not an exception** — wrong
rendering with nothing in any log. `Blaze3dMirrorTest` pins the backend's few overrides; `HostStateVerifier` is the
runtime check.

## Lifecycle

The engine starts in `FrameHooks.endFrame` once `LifecycleModern.reload()` has seen the first resource reload
finish: on the title screen, so a world's first frame compiles almost nothing. `LifecycleModern.opaquePass`
and `transparentPass`, from the loader's world-render hooks, fire `CgRenderStage.WORLD_OPAQUE` and
`WORLD_TRANSPARENT`. At shutdown the loaders call
`CgEngineLifecycle.shutdown()` (through `LifecycleModern.shutdown`), which frees from the render thread and stops the
engine. Minecraft dispatches render stages and its camera hook for a frame or two after its shutdown signal, so
every engine entry is a no-op after it.

A GUI-only frame ends no CrystalGraphics frame today — `runtime/mc/modern/common/CLAUDE.md` § *Open*.
