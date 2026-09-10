# runtime/mc/modern/neoforge — Agent Knowledge Base

## Target Versions

**IMPORTANT**: Despite the `runtime/mc/modern/` directory name, this module targets **MC 1.20.4 / NeoForge 20.4.x**.
NeoForge never published a stable 1.20.1 series — the earliest available stable series is 20.4.x (MC 1.20.4).
The directory name `runtime/mc/modern/neoforge/` is retained for continuity. Version pins live in `gradle.properties`
under `mc1204.*` keys.

## The loader is registration only

One `@Mod` class, `CrystalGraphicsNeoForge`. Its `Events` inner class registers the render
stages and the shutdown signal on `NeoForge.EVENT_BUS` from the constructor, and holds the MOD-bus
reload listener.

Which event, and which stage of it. What the engine then does — bind the main render target, run the
opaque or transparent pass, forward a reload, stop at shutdown — is `:runtime:mc:modern:common`'s `LifecycleModern`,
shared by all three.

## Minecraft Source Location

Decompiled, Parchment-mapped NeoForge + MC 1.20.4 sources are extracted into two subdirectories:

| Path | Contents |
|---|---|
| `build/mc-src/java/` | NeoForge + Mojang Java sources, Parchment-mapped |
| `build/mc-src/resources/` | MC client assets (assets/, data/, *.json, *.mcmeta) from `client-extra-*.jar` |

These paths are gitignored and not committed. Generate them with:

```bash
./gradlew :runtime:mc:modern:neoforge:extractMcSources
# or regenerate all three 1.20.x loader modules at once:
./gradlew extractAllMcSources
```

Running `extractMcSources` will trigger `createMinecraftArtifacts` (the ModDevGradle task that
downloads and decompiles sources) if it has not run yet. Expect several minutes on first run.

## Key Source Files

After extraction, commonly referenced locations under `build/mc-src/java/`:

- `net/minecraft/client/Minecraft.java` — main game class
- `net/minecraft/client/renderer/` — rendering pipeline
- `net/minecraft/resources/` — resource location / pack system
- `net/minecraft/world/` — world/level logic

## Build

```bash
./gradlew :runtime:mc:modern:neoforge:compileJava
./gradlew :runtime:mc:modern:neoforge:shadowJar
```

## Plugin

Uses `net.neoforged.moddev` (ModDevGradle). See `build.gradle.kts` for version pins.
