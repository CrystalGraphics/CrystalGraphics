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
| `LifecycleService1201` | `CgLifecycleService` | `CgGraphicsLifecycle.initContext/destroyContext/onResize` |
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

**No GL calls in constructors or static initializers anywhere in this package.**
