# runtime/mc/modern/common — Platform Package

`com.crystalgraphics.mc.modern.platform` — MC 1.20.x shared platform implementation.
All files compile against MC 1.20.1 via `legacyForge` in `cg-mc1201-common.gradle.kts`.
No loader-specific types (Forge/NeoForge/Fabric) appear anywhere in this package.

## Entry Point

**`PlatformServiceModern`** — implements `CgPlatformService`; composes all six services.
Register it once at loader init: `CgPlatform.register(PlatformServiceModern.getInstance())`.

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
| `LifecycleService` | `CgLifecycleService` | `CgGraphicsLifecycle.initContext/destroyContext/onResize/tickFrame` |
| `ReloadService` | `CgReloadService` | `CgAssetReloader.reload()` |
| `ResourceService` | `CgResourceService` | `Minecraft.getResourceManager()` — returns `null` on not-found, never throws |
| `RenderingService` | `CgRenderingService` | `CgRenderPipeline.execute(partialTick)` — legacy single-call path; superseded by the per-loader event handlers for the opaque/transparent split |

## Lifecycle Ownership

First-frame lazy-init and resize detection live in `CgGraphicsLifecycle` (`core/`), not in this
package. `CgGraphicsLifecycle.onRenderFrame(partialTick, w, h)` handles first-frame init only:
- First call: `CgPlatform.gl().initContext()`, `CgPlatform.capabilities().probe()`,
  `initContext(w, h)`, then pipeline execute
- Resize: `onResize(w, h)`, then pipeline execute

The actual per-frame render passes are driven by loader-specific stage events:
- `onOpaquePass(partialTick, w, h, sourceFboId)` — called at `AFTER_BLOCK_ENTITIES` / `AFTER_ENTITIES`
- `onTransparentPass()` — called at `AFTER_PARTICLES` / `AFTER_TRANSLUCENT`

At game shutdown the loaders call `CgGraphicsLifecycle.shutdown()`, which stops the engine and frees
nothing: Minecraft dispatches render stages for a frame or two after its shutdown signal, and freeing
early leaves the engine half-dead while frames are still arriving. `destroyContext()` is the other
call, for a host where rendering has definitively stopped.

**`onFrameRendered()` is not wired on 1201.** The hook to use, why the world-render-stage
events are not it, and where Fabric stands: see `runtime/mc/modern/common/AGENTS.md`.
