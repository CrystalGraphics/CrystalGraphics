# demo — Platform-Agnostic Benchmarks

## What This Package Is

Standalone, platform-agnostic demo and benchmark utilities.  Classes here have **zero**
`net.minecraft.*` and **zero** `org.lwjgl.*` imports.  All GL calls go through
`CgPlatform.gl()`.  All GL constants come from `CgGL`.

## File Map

| File | Role |
|------|------|
| `CgFontDemo.java` | Font benchmark and atlas diagnostic viewer.  Renders two text draws per frame (a pose-scalable demo string and a fixed 2D label) plus a bottom-left atlas overlay showing bitmap and MSDF pages side by side. |

## Platform Wiring

Each platform provides a thin adapter that calls `CgFontDemo.INSTANCE.render(w, h)` once
per overlay frame and `CgFontDemo.INSTANCE.onMouseWheel(delta)` on scroll input.

| Platform | Location |
|----------|----------|
| MC 1.7.10 / Forge | `mc1710/.../integration/CrystalGraphicsFontDemo.java` |
| MC 1.20.1 / Fabric | `mc1201/fabric/.../CrystalGraphics1201Fabric.java` (`HudRenderCallback`) |
| MC 1.20.1 / Forge  | `mc1201/forge/.../CrystalGraphics1201Forge.java` (`RenderGuiOverlayEvent.Post`) |
| MC 1.20.4 / NeoForge | `mc1201/neoforge/.../CrystalGraphics1201NeoForge.java` (`RenderGuiEvent.Post`) |

## Key Rules

- `CgFontDemo` owns the frame counter; adapters carry no demo state.
- Dispose is called from the platform on context destroy (`CgFontDemo.INSTANCE.dispose()`).
- The diag atlas shader (`crystalgraphics:shader/diag_atlas.vert/frag`) must declare
  `a_pos` before `a_uv`; attrib locations 0 and 1 are hard-coded because `glGetAttribLocation`
  is not exposed by `CgGLBackend`.
