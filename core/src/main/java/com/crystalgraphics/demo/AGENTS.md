# demo — Platform-Agnostic Benchmarks

## What This Package Is

Standalone, platform-agnostic demo and benchmark utilities.  Classes here have **zero**
`net.minecraft.*` and **zero** `org.lwjgl.*` imports.  All GL calls go through
`CgPlatform.gl()`.  All GL constants come from `CgGL`.

## File Map

| File | Role |
|------|------|
| `CgFontDemo.java` | Font benchmark and atlas diagnostic viewer.  Renders two text draws per frame (a pose-scalable demo string and a fixed 2D label) plus a bottom-left atlas overlay showing bitmap and MSDF pages side by side. |
| `CgRenderDemo.java` | 3D render pipeline demo. Renders a 4×4 rainbow-tinted cube grid via `CgRenderPipeline` with an auto-orbiting internal camera. Exercises depth prepass, opaque forward pass, and `cg_DepthBuffer` binding. Uses `crystalgraphics:shaders/demo_render.shader`. |

## Platform Wiring — CgFontDemo

Each platform provides a thin adapter that calls `CgFontDemo.INSTANCE.render(w, h)` once
per overlay frame and `CgFontDemo.INSTANCE.onMouseWheel(delta)` on scroll input.

| Platform | Location |
|----------|----------|
| MC 1.7.10 / Forge | `runtime/mc/1710/.../integration/CrystalGraphicsFontDemo.java` |
| MC 1.20.1 / Fabric | `runtime/mc/modern/fabric/.../CrystalGraphicsFabric.java` (`HudRenderCallback`) |
| MC 1.20.1 / Forge  | `runtime/mc/modern/forge/.../CrystalGraphicsForge.java` (`RenderGuiOverlayEvent.Post`) |
| MC 1.20.4 / NeoForge | `runtime/mc/modern/neoforge/.../CrystalGraphicsNeoForge.java` (`RenderGuiEvent.Post`) |

## Platform Wiring — CgRenderDemo

`CgRenderDemo` drives the full render cycle internally — two hooks per platform replace the
platform's direct `executeOpaquePass` / `executeTransparentPass` / `endFrame` calls:

| Hook | Call | When |
|------|------|------|
| Pre-translucent | `CgRenderDemo.INSTANCE.renderOpaque(partialTick, w, h, sourceFboId)` | `AFTER_BLOCK_ENTITIES` / 1.7.10 `onBeforeTranslucentBlocks` |
| Post-translucent | `CgRenderDemo.INSTANCE.renderTransparent()` | `AFTER_PARTICLES` / 1.7.10 `onAfterTranslucentContent` |
| Mouse scroll | `CgRenderDemo.INSTANCE.onMouseWheel(delta)` | same scroll hook as `CgFontDemo` |
| Context destroy | `CgRenderDemo.INSTANCE.dispose()` | same destroy hook as `CgFontDemo` |

## Key Rules

- `CgFontDemo` owns the frame counter; adapters carry no demo state.
- Dispose is called from the platform on context destroy (`CgFontDemo.INSTANCE.dispose()`).
- The diag atlas shader (`crystalgraphics:shader/diag_atlas.vert/frag`) must declare
  `a_pos` before `a_uv`; attrib locations 0 and 1 are hard-coded because `glGetAttribLocation`
  is not exposed by `CgGLBackend`.
- `CgRenderDemo` sets its own `CgFrameData` (orbit camera) on every `renderOpaque` call.
  This overrides any previously set view/proj — expected behaviour for a standalone demo.
