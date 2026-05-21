# mc1201/forge — Agent Knowledge Base

## Target Versions

**MC 1.20.1 / MinecraftForge 47.x**

Uses ModDevGradle legacyForge (`net.neoforged.moddev.legacyforge`), which supports
MinecraftForge 1.17–1.20.1 and is Gradle 9 + JDK 25 compatible. See `build.gradle.kts`
for version pins under `mc1201.forge` / `mc1201.parchment.*` keys.

## Minecraft Source Location

Decompiled, Parchment-mapped MinecraftForge + MC 1.20.1 sources are extracted into two subdirectories:

| Path | Contents |
|---|---|
| `build/mc-src/java/` | MinecraftForge + Mojang Java sources, Parchment-mapped |
| `build/mc-src/resources/` | MC client assets (assets/, data/, *.json, *.mcmeta) from `client-extra-*.jar` |

These paths are gitignored and not committed. Generate them with:

```bash
./gradlew :mc1201:forge:extractMcSources
# or regenerate all three mc1201 loader modules at once:
./gradlew extractAllMcSources
```

Running `extractMcSources` will trigger `createMinecraftArtifacts` (the ModDevGradle task that
downloads and decompiles sources) if it has not run yet. Expect several minutes on first run.

## Key Source Files

After extraction, commonly referenced locations under `build/mc-src/java/`:

- `net/minecraft/client/Minecraft.java` — main game class
- `net/minecraft/client/renderer/` — rendering pipeline
- `net/minecraftforge/client/` — Forge client hooks and extensions
- `net/minecraft/resources/` — resource location / pack system

## Build

```bash
./gradlew :mc1201:forge:compileJava
./gradlew :mc1201:forge:shadowJar
```

## Plugin

Uses `net.neoforged.moddev.legacyforge` (ModDevGradle legacyForge). See `build.gradle.kts`
for version configuration.
