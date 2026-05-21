# mc1201/fabric — Agent Knowledge Base

## Target Versions

**MC 1.20.1 / Fabric**

Uses `fabric-loom 1.16.2`. See `build.gradle.kts` for version pins under `mc1201.*` keys.

## Minecraft Source Location

Decompiled, Parchment-mapped Fabric MC 1.20.1 sources are extracted into two subdirectories:

| Path | Contents |
|---|---|
| `build/mc-src/java/` | MC 1.20.1 Java sources, decompiled by Loom via Vineflower, Parchment-mapped |
| `build/mc-src/resources/` | MC client assets (assets/, data/, *.json, *.mcmeta) from the merged binary jar |

These paths are gitignored and not committed. Generate them with:

```bash
./gradlew :mc1201:fabric:extractMcSources
# or regenerate all three mc1201 loader modules at once:
./gradlew extractAllMcSources
```

**Note**: Generating sources triggers Loom's `genSourcesWithVineflower` task, which runs
Vineflower decompilation and may take **several minutes on first run**. Subsequent runs use
Loom's task output cache and are fast.

## Key Source Files

After extraction, commonly referenced locations under `build/mc-src/java/`:

- `net/minecraft/client/Minecraft.java` — main game class
- `net/minecraft/client/renderer/` — rendering pipeline
- `net/minecraft/resources/` — resource location / pack system
- `net/minecraft/world/` — world/level logic

## Build

```bash
./gradlew :mc1201:fabric:compileJava
./gradlew :mc1201:fabric:shadowJar
```

## Plugin

Uses `fabric-loom 1.16.2`. The `genSourcesWithVineflower` task is provided by Loom and
is what `extractMcSources` depends on to produce the sources jar.
