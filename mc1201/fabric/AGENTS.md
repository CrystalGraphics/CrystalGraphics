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

## Multi-Project Dev Run Classpath (CRITICAL — do not repeat this mistake)

Fabric Loom uses the **remapped JAR** (`build/libs/*.jar`, produced by `remapJar` from `tasks.jar`)
as the mod's classpath when Knot classloader loads mod code in dev runs.

### Why `tasks.jar` bundling is the correct approach

Knot does NOT load `com.crystalgraphics.*` classes from the JVM system classpath (i.e., plain
`runtimeOnly` dependencies). It only loads them from the mod JAR it finds via `fabric.mod.json`.
Therefore every sub-project whose classes need to be accessible at runtime — `platform`, `core`,
`mc1201:common`, `freetype-msdfgen-harfbuzz-bindings` — must be bundled directly into `tasks.jar`:

```kotlin
tasks.jar {
    from(zipTree(platformJar))
    from(zipTree(coreJar))
    from(zipTree(commonJar))
    from(zipTree(freetypeJar))
}
```

Loom's `remapJar` picks up this bundled JAR and remaps it → `build/libs/*.jar`. That remapped
JAR is what lands on Knot's classpath.

### Why `loom.mods { sourceSet(crossProject) }` does NOT work

When you call `sourceSet(project(":some-subproject")...)` in `loom.mods {}`, Loom 1.16.2 tries
to apply the `net.fabricmc.fabric-loom-companion` plugin to that cross-project. Sub-projects that
don't apply Loom (`platform`, `core`, `freetype-msdfgen-harfbuzz-bindings`) will fail with:

```
Plugin with id 'net.fabricmc.fabric-loom-companion' not found.
```

**Do not add cross-project source sets to `loom.mods {}`**. Only `sourceSet(sourceSets.main.get())`
(this project's own main source set) is safe if you need the block at all. For multi-project mods,
use JAR bundling instead.
