# runtime/mc/modern/common — Agent Knowledge Base

The modern-era code that is no loader's: the `common` branch of the Stonecutter tree, built once per
Minecraft version (`:runtime:mc:modern:common:<version>`). Each loader node compiles against the common
node of its own version through the `commonOutput` configuration; `cg-modern-common.gradle.kts` picks
the toolchain from the node's pins (`docs/BUILD.md` § *Nodes and toolchains*).

**No loader type appears here** — a Forge, NeoForge or Fabric import compiles against one loader and is
used by three.

| Package | AGENTS.md | What it contains |
|---|---|---|
| `com.crystalgraphics.mc.modern.platform` | [platform/AGENTS.md](src/main/java/com/crystalgraphics/mc/modern/platform/AGENTS.md) | `PlatformServiceModern`, `Blaze3dGLBackend`, `HostStateVerifier`, the services, and `LifecycleModern` — the one class a loader talks to |

The world-render hooks are loader events wherever one exists; the exceptions are node mixins in the
loader branches (Forge 1.21.3+, Fabric 1.14.4–1.15.2), never here.

## Key design points

- **No GL in constructors.** GL work waits for the first frame that owns the render context.
- **Mixin AP comes from the toolchain.** A second `annotationProcessor` for Mixin produces duplicate-AP
  SRG mapping errors.

## Open: a GUI-only frame never ticks

`CgGraphicsLifecycle.tickFrame()` runs from `FrameHooks.endFrame()`, which `LifecycleModern` calls at the
end of the transparent pass — inside `GameRenderer.render`'s world branch. A title-screen or GUI-only
frame takes the sibling screen branch and never ends a CrystalGraphics frame. 1.7.10 and legacy Forge
close this with a `TAIL` injection on `updateCameraAndRender`.

The modern equivalents, measured with `singlejar-logic/mcapi.py`: `GameRenderer.render` TAIL on every node
(two signatures, split at 1.21.1); Forge's `TickEvent.RenderTickEvent` at `END` (1.13.2+); NeoForge's
`TickEvent.RenderTickEvent` (1.20.2–1.20.4) and `RenderFrameEvent.Post` (1.20.6+). Fabric has no frame
event, so it needs the mixin. Owned by device-seam milestone D1 (the frame ring's fence).
