# mc1201/common — Platform Package

`com.crystalgraphics.mc.platform` — MC 1.20.x shared platform implementation.
All files compile against MC 1.20.1 via `legacyForge` in `cg-mc1201-common.gradle.kts`.
No loader-specific types (Forge/NeoForge/Fabric) appear anywhere in this package.

## Entry Point

**`PlatformService1201`** — implements `CgPlatformService`; composes all six services.
Register it once at loader init: `CgPlatform.register(PlatformService1201.getInstance())`.

## Subpackages

### `gl/`

| Class | Implements | What it does |
|---|---|---|
| `Mc120xGLContext` | `CgGLContext` | Reads `GLCapabilities` from LWJGL 3's `GL.getCapabilities()`; call `probe()` once on the GL thread after context creation |
| `Mc120xGLBackend` | `CgGLBackend` | 3-tier routing: `RenderSystem` → `GlStateManager` → raw `GL*C`; throws on fixed-function paths (`GL_ALPHA_TEST`, matrix stack) removed from the core profile |

**3-Tier Routing Rule** (in `Mc120xGLBackend`):
- **Tier 1 — `RenderSystem`**: blend, depth test/mask/func, color mask, viewport, stencil, `activeTexture`, `texParameter`, `deleteTexture`
- **Tier 2 — `GlStateManager`**: FBO bind/gen/delete/blit, renderbuffer ops, `scissorBox`, `polygonMode`, `pixelStore`, `polygonOffset`
- **Tier 3 — raw `GL*C`**: VAOs, VBOs, shaders, texture upload, draw calls, sync, SSBO/TBO/UBO, anything MC doesn't track

Never use `GL11` / `GL20` (non-C suffix) — always `GL11C`, `GL20C`, etc. ARB extension classes (`ARBShaderObjects`, `ARBInstancedArrays`, `ARBSamplerObjects`) are allowed for their waterfall paths.

### `service/`

| Class | Implements | Delegates to |
|---|---|---|
| `LifecycleService1201` | `CgLifecycleService` | `CgGraphicsLifecycle.initContext/destroyContext/onResize/tickFrame` |
| `ReloadService1201` | `CgReloadService` | `CgAssetReloader.reload()` |
| `ResourceService1201` | `CgResourceService` | `Minecraft.getResourceManager()` — returns `null` on not-found, never throws |
| `RenderingService1201` | `CgRenderingService` | `CgRenderPipeline.execute(partialTick)` — legacy single-call path; superseded by the per-loader event handlers for the opaque/transparent split |

### `../mixin/` (sibling package `com.crystalgraphics.mc.mixin`)

| Class | Target | When it fires |
|---|---|---|
| `MixinGameRenderer` | `GameRenderer#render` | After `renderLevel()` returns — drives first-frame lazy init via `CgGraphicsLifecycle.onRenderFrame`; primary render path is now the per-loader stage events (`AFTER_BLOCK_ENTITIES` / `AFTER_PARTICLES`) |
| `MixinMinecraftShutdown` | `Minecraft#destroy` | HEAD — calls `CgGraphicsLifecycle.onShutdown` |

The mixin descriptor for `renderLevel` was verified against MC 1.20.1 source at `GameRenderer.java:1107`. NeoForge (MC 1.20.4) accepts the same descriptor; revisit if runtime issues arise.

Mixin classes must be registered in each loader's mixin JSON (Wave 4: T7/T8/T9).

## Lifecycle Ownership

First-frame lazy-init and resize detection live in `CgGraphicsLifecycle` (`core/`), not in this
package. `CgGraphicsLifecycle.onRenderFrame(partialTick, w, h)` handles first-frame init only:
- First call: `CgPlatform.gl().initContext()`, `CgPlatform.capabilities().probe()`,
  `initContext(w, h)`, then pipeline execute
- Resize: `onResize(w, h)`, then pipeline execute

The actual per-frame render passes are driven by loader-specific stage events:
- `onOpaquePass(partialTick, w, h, sourceFboId)` — called at `AFTER_BLOCK_ENTITIES` / `AFTER_ENTITIES`
- `onTransparentPass()` — called at `AFTER_PARTICLES` / `AFTER_TRANSLUCENT`

`CgGraphicsLifecycle.onShutdown()` calls `destroyContext()` exactly once.

**⚠️ TODO — `onFrameRendered()` native wiring not yet done for this loader family.**
`LifecycleService1201.onFrameRendered()` is implemented (delegates to
`CgGraphicsLifecycle.tickFrame()`), but nothing in forge/neoforge/fabric calls it yet.
mc1710 wires it via a dedicated mixin injection at `@At("TAIL")` of
`EntityRenderer.updateCameraAndRender` — a single per-call hook, verified to have no early
returns, that fires exactly once whether or not a world is loaded and whether or not a GUI
screen is open (see `CrystalGraphics/mc1710/.../CgRenderHook.java`'s class javadoc for the
full verification). This is deliberately a *different* injection point from the existing
`AFTER_BLOCK_ENTITIES`/`AFTER_PARTICLES` world-render-stage events that already call
`onOpaquePass(...)`/`onTransparentPass()` — those, and the demo-only `RenderGuiEvent.Post`
(Forge/NeoForge) / `HudRenderCallback` (Fabric) hooks used by `CgDemo*Events`, all live
**inside** `GameRenderer.render(...)`'s `if (renderLevel && this.minecraft.level != null)`
branch (verified against decompiled `GameRenderer.java` for both Forge and NeoForge) — none
of them fire on GUI-only/no-world screens (main menu, etc.), which instead take the sibling
`else if (this.minecraft.screen != null)` branch that calls
`ForgeHooksClient.drawScreen(...)` directly, bypassing `gui.render()` entirely. So wiring
`onFrameRendered()` to any of those would reproduce the exact gap mc1710 just closed.

**Confirmed correct hook for Forge and NeoForge** (verified against decompiled
`Minecraft.java`, both loaders): `TickEvent.RenderTickEvent` with `Phase.END`
(`net.minecraftforge.event.TickEvent.RenderTickEvent` / `net.neoforged.neoforge.event.TickEvent.RenderTickEvent`),
posted via `ForgeEventFactory.onRenderTickEnd(...)` / `EventHooks.onRenderTickEnd(...)`
immediately after `this.gameRenderer.render(partialTick, nanoTime, renderLevel)` returns in
`Minecraft.runTick(boolean)`, itself gated by `if (!this.noRender)`. This wraps the *entire*
`GameRenderer.render` call — both the world+GUI branch and the no-world+screen branch — so
it is the true per-loader equivalent of `updateCameraAndRender`'s `@At("TAIL")`, fires
natively (no mixin needed), and closes the known gap. Not yet wired — add a
`@SubscribeEvent`/`addListener` handler in `CgEngineForgeEvents`/`CgEngineNeoForgeEvents`
filtering to `Phase.END`, calling `CgPlatform.lifecycle().onFrameRendered()`.

**Fabric equivalent still unresearched.** Fabric API's per-game-tick events
(`ClientTickEvents.END_CLIENT_TICK`) run at the fixed 20/sec tick rate, decoupled from the
render frame rate, so they are not equivalent. Whether Fabric API exposes a native
once-per-render-frame event wrapping the same `GameRenderer.render` call (Forge/NeoForge's
`RenderTickEvent.END` equivalent) has not yet been confirmed — if none exists, a mixin
targeting the same `Minecraft.runTick`/`GameRenderer.render` tail may be unavoidable for
Fabric specifically (would still be the last-resort case per the project's mixin policy).

Until any of this lands, `CgGraphicsLifecycle.onOpaquePass()` still calls `tickFrame()`
directly as a temporary safety net (see its own code comment) — remove that once this is
wired, since it only covers the in-world case.

**No GL calls in constructors or static initializers anywhere in this package.**
