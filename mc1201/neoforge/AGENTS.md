# mc1201/neoforge — Agent Knowledge Base

## Target Versions

**IMPORTANT**: Despite the `mc1201/` directory name, this module targets **MC 1.20.4 / NeoForge 20.4.x**.
NeoForge never published a stable 1.20.1 series — the earliest available stable series is 20.4.x (MC 1.20.4).
The directory name `mc1201/neoforge/` is retained for continuity. Version pins live in `gradle.properties`
under `mc1204.*` keys.

## Minecraft Source Location

Decompiled, Parchment-mapped NeoForge + MC 1.20.4 sources are extracted into two subdirectories:

| Path | Contents |
|---|---|
| `build/mc-src/java/` | NeoForge + Mojang Java sources, Parchment-mapped |
| `build/mc-src/resources/` | MC client assets (assets/, data/, *.json, *.mcmeta) from `client-extra-*.jar` |

These paths are gitignored and not committed. Generate them with:

```bash
./gradlew :mc1201:neoforge:extractMcSources
# or regenerate all three mc1201 loader modules at once:
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
./gradlew :mc1201:neoforge:compileJava
./gradlew :mc1201:neoforge:shadowJar
```

## Plugin

Uses `net.neoforged.moddev` (ModDevGradle). See `build.gradle.kts` for version pins.
