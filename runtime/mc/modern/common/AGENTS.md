# runtime/mc/modern/common — Agent Knowledge Base

Shared MC 1.20.x platform implementation. Compiles against MC 1.20.1 + MinecraftForge 47.2.0
via `legacyForge` in `cg-mc1201-common.gradle.kts`. The compiled JAR is consumed by all three
loader subprojects (`forge`, `neoforge`, `fabric`) via the `commonOutput` configuration.

## Build

```bash
./gradlew :runtime:mc:modern:common:compileJava   # compiles shared sources only
```

No loader-specific types (Forge/NeoForge/Fabric APIs) appear in this module.

## Package Guide

| Package | AGENTS.md | What it contains |
|---|---|---|
| `com.crystalgraphics.mc.modern.platform` | [platform/AGENTS.md](src/main/java/com/crystalgraphics/mc/modern/platform/AGENTS.md) | The GL backend, the platform services, `PlatformServiceModern`, and `LifecycleModern` — the one class a loader talks to |

Each loader declares a mixin config naming `com.crystalgraphics.mc.mixin`, and all three are empty:
the 1.20.x hooks are native loader events, and a mixin here would be the last resort the project's
mixin policy describes.

## Key Design Points

- **No GL calls in constructors** — all GL work deferred to first `CgGraphicsLifecycle.onOpaquePass` call (lazy init via `onRenderFrame`)
- **Mixin AP**: provided by `legacyForge`; do NOT add a second `annotationProcessor` for Mixin in this module — it causes duplicate-AP SRG mapping errors
- **`legacyForge` not `neoForge`**: NeoForm 1.20.1 was never published; `legacyForge{version="1.20.1-47.2.0"}` is the only ModDevGradle path


## Open: `onFrameRendered()` is not wired on 1201

`LifecycleService.onFrameRendered()` delegates to `CgGraphicsLifecycle.tickFrame()`, and no loader
calls it. Until it is wired, `onOpaquePass` calls `tickFrame()` itself as a stand-in — which only
covers frames that render a world.

**The world-render-stage events are not the hook.** `AFTER_BLOCK_ENTITIES` and `AFTER_PARTICLES` sit
inside `GameRenderer.render`'s `if (renderLevel && minecraft.level != null)` branch, so they never fire
on a GUI-only frame — the main menu takes the sibling `else if (minecraft.screen != null)` branch
instead. Wiring the per-frame tick to them would leave exactly the gap 1.7.10 closed with a
`@At("TAIL")` injection on `EntityRenderer.updateCameraAndRender`.

**Forge and NeoForge have a native equivalent**, verified against decompiled `Minecraft.java` for both:
`TickEvent.RenderTickEvent` at `Phase.END`, posted immediately after
`gameRenderer.render(partialTick, nanoTime, renderLevel)` returns in `Minecraft.runTick(boolean)`. It
wraps the whole call — world branch and screen branch alike — so it needs no mixin. Add a handler in
`CrystalGraphicsForge.Events` / `CrystalGraphicsNeoForge.Events` filtering on `Phase.END`.

**Fabric has no confirmed equivalent.** Its `ClientTickEvents.END_CLIENT_TICK` runs at the fixed 20 Hz
tick, decoupled from the frame rate. Whether Fabric API exposes a once-per-render-frame event over the
same call is unresearched; if none exists, a mixin on the same tail may be unavoidable there, and
would be the last-resort case the project's mixin policy describes.
