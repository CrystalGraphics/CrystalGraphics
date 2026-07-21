# mc1201/common — Agent Knowledge Base

Shared MC 1.20.x platform implementation. Compiles against MC 1.20.1 + MinecraftForge 47.2.0
via `legacyForge` in `cg-mc1201-common.gradle.kts`. The compiled JAR is consumed by all three
loader subprojects (`forge`, `neoforge`, `fabric`) via the `commonOutput` configuration.

## Build

```bash
./gradlew :mc1201:common:compileJava   # compiles shared sources only
```

No loader-specific types (Forge/NeoForge/Fabric APIs) appear in this module.

## Package Guide

| Package | AGENTS.md | What it contains |
|---|---|---|
| `com.crystalgraphics.mc.platform` | [platform/AGENTS.md](src/main/java/com/crystalgraphics/mc/platform/AGENTS.md) | GL backend, services, lifecycle bridge, `PlatformService1201` |
| `com.crystalgraphics.mc.mixin` | ↑ same file | `MixinGameRenderer`, `MixinMinecraftShutdown` |

## Key Design Points

- **No GL calls in constructors** — all GL work deferred to first `CgGraphicsLifecycle.onOpaquePass` call (lazy init via `onRenderFrame`)
- **Mixin AP**: provided by `legacyForge`; do NOT add a second `annotationProcessor` for Mixin in this module — it causes duplicate-AP SRG mapping errors
- **`legacyForge` not `neoForge`**: NeoForm 1.20.1 was never published; `legacyForge{version="1.20.1-47.2.0"}` is the only ModDevGradle path


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
