# runtime/mc/modern/forge — Agent Knowledge Base

## Target Versions

**MC 1.13.2–1.21.11 / MinecraftForge 25–61** (a node each for 1.13.2, 1.14.3 (also 1.14.2), 1.14.4, 1.15.2 (also 1.15, 1.15.1), 1.16.5 (also 1.16.1-1.16.4), 1.17.1, 1.18.2 (also 1.18, 1.18.1), 1.19.2
(also 1.19, 1.19.1), 1.19.3, 1.19.4, 1.20.1, 1.20.2, 1.20.4, 1.20.6, 1.21.1, 1.21.3, 1.21.4, 1.21.5, 1.21.6
(also 1.21.7), 1.21.8, 1.21.10 (also 1.21.9), 1.21.11; Forge 1.21 is refused: Forge 51 has no HUD event)

**The 1.13.2, 1.14.3, 1.14.4, 1.15.2 and 1.16.5 nodes are built by Unimined**, since ModDevGradle reaches nothing below 1.17, and its thin jar is
reobfuscated to MCP class names as well as SRG members. Forge 31 registers reload listeners on the
resource manager itself and crash callables through `CrashReportExtender`. 1.13 has no preparation
stage: its listener is a plain `ResourceManagerReloadListener`. Mojang named nothing before 1.14.4, so
1.13.2 and 1.14.3 compile against names carried back from it -- see `../mappings/` and `singlejar-logic/README.md`.

**Below 1.19.3 the world passes land where Forge can reach.** Forge 44 (1.19.3) added
`AFTER_BLOCK_ENTITIES`; Forge 40-43 stop at `AFTER_CUTOUT_BLOCKS`, ahead of entities; Forge 38-39 and 37
have no stage event, so both passes run at the end of the level (`RenderLevelLastEvent`,
`RenderWorldLastEvent`). Forge 38-39 share the 1.18.2 node, which picks at runtime.

**From 1.21.3 the world-render hook is a mixin** (`mixin/`, gated by `CrystalGraphicsForgeMixins`):
Forge 53 removed `RenderLevelStageEvent` and nothing replaced it. **From 1.21.6 Forge is EventBus 7**:
an event carries its own `BUS`, a mod-bus event hands out one per `getModBusGroup()`, and a cancelling
listener is a `Predicate`.

The 1.20.1 node uses ModDevGradle legacyForge (`net.neoforged.moddev.legacyforge`), which supports
MinecraftForge 1.17–1.20.1 and is Gradle 9 + JDK 25 compatible. From 1.20.2 legacyForge sets up nothing, so those nodes pin `neoform.version` too and are built
from parts: NeoForm's Minecraft, Forge's jars compileOnly, no dev run, and `SrgReobfJar` below 1.20.6.
See CrystalGraphics' `singlejar-logic/README.md` § Many Minecraft versions. Version pins are per
node in `versions/<version>/gradle.properties`.

## The loader is registration only

One `@Mod` class, `CrystalGraphicsForge`. Its `Events` inner class holds the two
`@Mod.EventBusSubscriber` buses: MOD for the reload listener, FORGE+CLIENT for the two render stages
and the shutdown signal.

Which event, and which stage of it. What the engine then does — bind the main render target, run the
opaque or transparent pass, forward a reload, stop at shutdown — is the common branch's `LifecycleModern`,
shared by all three.

## Minecraft Source Location

Decompiled, Parchment-mapped MinecraftForge + MC 1.20.1 sources are extracted into two subdirectories:

| Path | Contents |
|---|---|
| `versions/1.20.1/build/mc-src/java/` | MinecraftForge + Mojang Java sources, Parchment-mapped |
| `versions/1.20.1/build/mc-src/resources/` | MC client assets (assets/, data/, *.json, *.mcmeta) from `client-extra-*.jar` |

These paths are gitignored and not committed. Generate them with:

```bash
./gradlew :runtime:mc:modern:forge:1.20.1:extractMcSources
# or regenerate all three 1.20.x loader modules at once:
./gradlew extractAllMcSources
```

Running `extractMcSources` will trigger `createMinecraftArtifacts` (the ModDevGradle task that
downloads and decompiles sources) if it has not run yet. Expect several minutes on first run.

## Key Source Files

After extraction, commonly referenced locations under `versions/1.20.1/build/mc-src/java/`:

- `net/minecraft/client/Minecraft.java` — main game class
- `net/minecraft/client/renderer/` — rendering pipeline
- `net/minecraftforge/client/` — Forge client hooks and extensions
- `net/minecraft/resources/` — resource location / pack system

## Build

```bash
./gradlew :runtime:mc:modern:forge:1.20.1:compileJava
./gradlew :runtime:mc:modern:forge:1.20.1:shadowJar
```

## Plugin

Uses `net.neoforged.moddev.legacyforge` (ModDevGradle legacyForge). See `build.gradle.kts`
for version configuration.
