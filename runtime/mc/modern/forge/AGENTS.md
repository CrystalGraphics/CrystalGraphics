# runtime/mc/modern/forge — Agent Knowledge Base

## Target versions

**MC 1.13.2–1.21.11 / MinecraftForge 25–61**: a node each for 1.13.2, 1.14.3 (also 1.14.2), 1.14.4,
1.15.2 (also 1.15, 1.15.1), 1.16.5 (also 1.16.1–1.16.4), 1.17.1, 1.18.2 (also 1.18, 1.18.1), 1.19.2
(also 1.19, 1.19.1), 1.19.3, 1.19.4, 1.20.1, 1.20.2, 1.20.4, 1.20.6, 1.21.1, 1.21.3, 1.21.4, 1.21.5,
1.21.6 (also 1.21.7), 1.21.8, 1.21.10 (also 1.21.9) and 1.21.11. Forge 1.21 is refused: Forge 51 has no
HUD event. Pins and toolchains: `docs/BUILD.md` § *Nodes and toolchains*.

**1.13.2–1.16.5 are built by Unimined**, since ModDevGradle reaches nothing below 1.17, and their thin
jar is reobfuscated to MCP class names as well as SRG members. Forge 31 registers reload listeners on
the resource manager itself and crash callables through `CrashReportExtender`. 1.13 has no preparation
stage: its listener is a plain `ResourceManagerReloadListener`. Mojang named nothing before 1.14.4, so
1.13.2 and 1.14.3 compile against names carried back from it — `../mappings/` and
`singlejar-logic/README.md`.

**Below 1.19.3 the world passes land where Forge can reach.** Forge 44 (1.19.3) added
`AFTER_BLOCK_ENTITIES`; Forge 40–43 stop at `AFTER_CUTOUT_BLOCKS`, ahead of entities; Forge 37–39 have no
stage event, so both passes run at the end of the level (`RenderLevelLastEvent`,
`RenderWorldLastEvent`). Forge 38–39 share the 1.18.2 node, which picks at runtime.

**From 1.21.3 the world-render hook is a node mixin** (`mixin/OpaquePassHook`, `mixin/TransparentPassHook`,
gated by `CrystalGraphicsForgeMixins`): Forge 53 removed `RenderLevelStageEvent` and nothing replaced
it. They run Mojang names, so they need no refmap. **From 1.21.6 Forge is EventBus 7**: an event carries
its own `BUS`, a mod-bus event hands out one per `getModBusGroup()`, and a cancelling listener is a
`Predicate`.

## The loader is registration only

`CrystalGraphicsForge` is a `VariantEntry`, not a `@Mod`: one `@Mod` class serves every Forge from 1.8
(`runtime/mc/forge-bootstrap`), and picks this node's entry by the running version. Its `Events` inner
class registers listeners with `addListener` — the mod bus for the reload listener, the Forge bus for
the render stages and the shutdown signal — because an `@EventBusSubscriber` would be scanned in every
variant the jar carries. What the engine then does is the common branch's `LifecycleModern`.

## Minecraft sources

```bash
./gradlew :runtime:mc:modern:forge:<version>:extractMcSources   # into versions/<version>/build/mc-src/{java,resources}
```

Gitignored. The task makes that node real, so the first run sets up its toolchain (several minutes).
