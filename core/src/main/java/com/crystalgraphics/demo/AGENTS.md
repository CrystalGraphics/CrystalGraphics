# demo — Platform-Agnostic Benchmarks

## What This Package Is

Standalone, platform-agnostic demo and benchmark utilities.  Classes here have **zero**
`net.minecraft.*` and **zero** `org.lwjgl.*` imports.  All GL calls go through
`CgPlatform.gl()`.  All GL constants come from `CgGL`.

## File Map

| File | Role |
|------|------|
| `CgFontDemo.java` | Font benchmark and atlas diagnostic viewer.  Renders two text draws per frame (a pose-scalable demo string and a fixed 2D label) plus a bottom-left atlas overlay showing bitmap and MSDF pages side by side. |
| `CgRenderDemo.java` | World demo, on unless `-Dcrystalgraphics.demo=false`. The VFX showcase submitted to `CgWorldRenderer` from an `onFrame` listener, anchored once on whole blocks ahead of the host's camera, under the showcase's sky (`-Dcrystalgraphics.demo.sky=false` keeps the world's), so a capture (`-Dcrystalgraphics.demo.capture=<png>`) shows whether the stage's view is the world's. |
| `CgVfxShowcase.java` | Sixteen effect spheres on a 4×4 grid, one `.shader` each under `shaders/demo/vfx_*`, with additive glow shells standing in for bloom; `submitStage` adds a floor and sky for a host with no world (the harness's `vfx-spheres`), `submitSky` the sky alone over a host's world. `vfx_floor.shader` mirrors its grid and glow table. The sky is `vfx_sky.glsl`: `vfx_sky.shader` draws it at the far plane, and over a world `vfx_sky_horizon.shader` blends it over distant terrain ahead of Minecraft's terrain fog (estimated as a quarter of the far plane), first in the transparent stage so the glows stay over it, and `vfx_sky_seal.shader`, last, writes the nearest depth on open sky so clouds and weather drawn after the world stages stay off it. Where a host fires the transparent stage after its clouds (Forge before 1.18), they stay. |

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

No host names it. `CgGraphicsLifecycle.initContext` calls `install()`, which does nothing when
`-Dcrystalgraphics.demo=false`, and `destroyContext` calls `dispose()`. Installed, it submits the showcase from a
`CgWorldRenderer.onFrame` listener; with a capture asked for, it also registers on `WORLD_TRANSPARENT` just
after the world renderer and reads the target back once.

## Key Rules

- `CgFontDemo` owns the frame counter; adapters carry no demo state.
- Dispose is called from the platform on context destroy (`CgFontDemo.INSTANCE.dispose()`).
- The diag atlas shader (`crystalgraphics:shader/diag_atlas.vert/frag`) must declare
  `a_pos` before `a_uv`; attrib locations 0 and 1 are hard-coded because `glGetAttribLocation`
  is not exposed by `CgGLBackend`.
- `CgRenderDemo` has no camera of its own: it draws under the host's, from the world stages.
