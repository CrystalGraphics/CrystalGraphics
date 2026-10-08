# demo — Platform-Agnostic Benchmarks

## What This Package Is

Standalone, platform-agnostic demo and benchmark utilities.  Classes here have **zero**
`net.minecraft.*` and **zero** `org.lwjgl.*` imports.  All GL calls go through
`CgPlatform.gl()`.  All GL constants come from `CgGL`.

## File Map

| File | Role |
|------|------|
| `CgFontDemo.java` | Font benchmark and atlas diagnostic viewer.  Renders two text draws per frame (a pose-scalable demo string and a fixed 2D label) plus a bottom-left atlas overlay showing bitmap and MSDF pages side by side. |
| `CgRenderDemo.java` | World demo, on unless `-Dcrystalgraphics.demo=false`: one scene at a time, N cycling them in game (Shift+N backwards). Every scene is built and warmed at install (`prepare()`: programs through `CgWorldRenderer.prepare`, looks through `CgVfxSystem.prepare`), and one switched away from is `clear()`ed, not deleted, so a switch builds and compiles nothing. `blasts` (`CgVfxBlasts`, `.demo.blasts=<n>`, 120), `beams` (`CgVfxShowcase`'s spheres and three beams under its sky; `.demo.sky=false` keeps the world's), `modules` (`CgVfxModules`); `-Dcrystalgraphics.demo=<name>` starts on one. **A new scene is a `Kind` line in `kinds`.** Top left, the harness HUD's lines and look (red, 16 px at a 600-pixel-high window): the scene, FPS, Shake [C] (`CgCameraShake.enabled`), Bloom [L], VFX sim [V] (which clears the scene, so nothing in flight stays on the old simulation), HDR scene [G] (`CgWorldRenderer.hdrScene`) and the particles drawn; keys are polled on `WORLD_TRANSPARENT` and ignored while `screenOpen()` or Ctrl is held, the HUD hidden with `guiHidden()`. Submitted to `CgWorldRenderer` from an `onFrame` listener, anchored ahead of the host's camera, standing on the world's ground once `CgWorldQueries` answers, and moved only when the scene or level changes or the camera jumps over 32 blocks in a frame, so a capture (`-Dcrystalgraphics.demo.capture=<png>`) shows whether the stage's view is the world's. |
| `CgVfxBlasts.java` | Blasts as GPU particles alone: the explosion kit without its billows and rings, `n` spots on a golden-angle disc `REACH` across each bursting every `CYCLE` seconds, in the waves' three palettes; each burst's debris lands on the world's ground under it (`CgWorldQueries.groundUnder`), else on the given floor. 120 keep about 360,000 particles alive. The harness's `vfx-blasts` and `CgRenderDemo`'s default. |
| `CgVfxModules.java` | X6's particle modules, a station each round an origin, each standing on the world's ground under it, else at the origin's height, its particles landing on the world's ground wherever it stands (`Ground`, or `CollideWorld` where a landing fires events or kills; a `CgVfxGround` per play for the CPU path); replayed every `PERIOD` seconds; labels when given a font. Frees its generated flipbook sheets on `delete`. The harness's `vfx-modules` and a `CgRenderDemo` scene. |
| `CgVfxShowcase.java` | Sixteen effect spheres on a 4×4 grid, one `.shader` each under `shaders/demo/vfx_*`, with additive glow shells standing in for bloom; `submitStage` adds a floor and sky for a host with no world (the harness's `vfx-spheres`), `submitSky` the sky alone over a host's world. `vfx_floor.shader` mirrors its grid and glow table. The sky is `vfx_sky.glsl`: `vfx_sky.shader` draws it at the far plane, and over a world `vfx_sky_horizon.shader` blends it over distant terrain ahead of Minecraft's terrain fog (estimated as a quarter of the far plane), first in the transparent stage so the glows stay over it, and `vfx_sky_seal.shader`, last, writes the nearest depth on open sky so clouds and weather drawn after the world stages stay off it. Where a host fires the transparent stage after its clouds (Forge before 1.18), they stay. `submit` also loops three `vfx.effect.beam.CgEnergyWave`s through its own `CgVfxSystem`, a lane each (`LANES`): the Kamehameha west and the Final Flash east, back to back, and the Galick Gun north behind the grid, staggered in time. Each fires a shot every `WAVE_CYCLE` seconds from beside the grid, straight out to its waypoint, then bends sharply toward one of its two targets in turn; it charges, releases with a flash and a ring, grows out, holds until `WAVE_HOLD`, runs out and bursts. `laneOf(effect)` names a wave's lane for the moments capture. The loop starts over when the grid moves. `CgVfxShowcase.stress(n)` fires `n` lanes instead (`beam00`...), from a ring round the grid outward, across it and round it, each first shot `STRESS_STAGGER` after the last so every beam holds at once: the harness's `vfx-spheres-stress`, a baseline for a frame full of effects. |

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
`-Dcrystalgraphics.demo=false`, and `destroyContext` calls `dispose()`. Installed, it submits its scene from a
`CgWorldRenderer.onFrame` listener and draws its HUD from `WORLD_TRANSPARENT` after the post stack's composite; with a
capture asked for, it also registers there just after the composite and reads the target back once. Both sit after the
composite because with the HDR scene on the host's target holds the world only from then.

## Key Rules

- `CgFontDemo` owns the frame counter; adapters carry no demo state.
- Dispose is called from the platform on context destroy (`CgFontDemo.INSTANCE.dispose()`).
- The diag atlas shader (`crystalgraphics:shader/diag_atlas.vert/frag`) must declare
  `a_pos` before `a_uv`; attrib locations 0 and 1 are hard-coded because `glGetAttribLocation`
  is not exposed by `CgGLBackend`.
- `CgRenderDemo` has no camera of its own: it draws under the host's, from the world stages.
