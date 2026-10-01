# runtime/mc/modern/common — Platform Package

`com.crystalgraphics.mc.modern.platform` — the modern-era platform, one source per Minecraft version
through Stonecutter directives. No Forge, NeoForge or Fabric type appears here.

## Entry points

**`PlatformServiceModern`** implements `CgPlatformService` over tier 1 (`runtime/lwjgl/3`). A loader
registers it once, from an entry point that runs on both sides:

```java
CgPlatform.register(PlatformServiceModern.getInstance());
```

**`LifecycleModern`** is everything a loader forwards to: the opaque and transparent passes, the reload,
the shutdown. A loader decides only which event (or node mixin) reaches it.

## Classes

| Class | What it does |
|---|---|
| `gl/Blaze3dGLBackend` | Tier 1's `Lwjgl3GLBackend`, plus routing every call whose state Minecraft's `GlStateManager` caches through `GlStateManager`'s own `_` methods, so its cache stays true. Everything else reaches the driver from tier 1 |
| `gl/GlStateManager` | A shim that spells 1.14's un-prefixed methods the later way |
| `HostStateVerifier` | `-Dcrystalgraphics.host.verify=true`: after each pass, compares the driver against `GlStateManager`'s fields and names the domain that disagrees |
| `Blaze3dTextureUnits` | how many texture units Blaze3D models — binding above it corrupts unit 0 for the next sampler |
| `FrameHooks` | the end-of-frame resize check and `CgGraphicsLifecycle.tickFrame()` |
| `ResourceIds`, `Windows` | a `ResourceLocation` and the game window, in whichever spelling the running version has |
| `service/*` | `CgLifecycleService`, `CgReloadService`, `CgResourceService`, `CgRenderingService`, `CgSoundService` over Minecraft |

**A missing `Blaze3dGLBackend` override is a missing GL call, not an exception** — wrong rendering with
nothing in any log. `Blaze3dMirrorTest` pins the list; `HostStateVerifier` is the runtime check.

## Lifecycle

Initialisation is lazy, on the first frame that owns the render context. `LifecycleModern.opaquePass`
and `transparentPass`, from the loader's world-render hooks, fire `CgRenderStage.WORLD_OPAQUE` and
`WORLD_TRANSPARENT`. At shutdown the loaders call
`CgGraphicsLifecycle.shutdown()`, which stops the engine and frees nothing: Minecraft dispatches render
stages for a frame or two after its shutdown signal. `destroyContext()` is for a host where rendering
has definitively stopped.

A GUI-only frame ends no CrystalGraphics frame today — `runtime/mc/modern/common/AGENTS.md` § *Open*.
