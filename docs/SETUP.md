# Setting up a project on CrystalGraphics

How to build a mod that renders through CrystalGraphics: what to depend on, what to declare, how to run
it. Building CrystalGraphics itself is [`BUILD.md`](BUILD.md). A mod using CrystalGUI as well follows
CrystalGUI's [`docs/CGUI_SETUP.md`](../../docs/CGUI_SETUP.md) instead: its plugin does all of this.

---

## Which setup

| Your mod ships | Setup |
|---|---|
| one jar per Minecraft version, built by your usual toolchain | [**One version**](#one-version): two dependencies |
| one jar for several loaders and versions | [**Many versions**](#many-versions): `singlejar-logic`'s `targets {}` |

The player installs CrystalGraphics as a mod of its own; yours depends on it and bundles none of it.

## Requirements

- Everything is published to `https://dl.cloudsmith.io/public/crystalgraphics/crystalgraphics/maven/`
  (below). An unreleased build: `./gradlew publishToMavenLocal` in a clone of
  [CrystalGraphics](https://github.com/CrystalGraphics/CrystalGraphics) (`git clone --recursive`), and
  `mavenLocal()` ahead of it.
- The current release is **0.0.1**, the version every snippet here names; the badge on the README shows
  the latest.
- For many versions: JDK 25, and Gradle running on it (`toolchainVersion=25` in
  `gradle/gradle-daemon-jvm.properties`).

| Coordinate | What |
|---|---|
| `com.crystalgraphics:core` | the API, with `platform` — sources and javadoc included, and a Java 8 copy Gradle picks below Java 25 |
| `com.crystalgraphics:crystalgraphics` | the shipped mod, for a dev run |
| `com.crystalgraphics:crystalgraphics-joml` | JOML as a mod, for Minecraft below 1.19.3 only (1.19.3+ ships its own) |
| `com.crystalgraphics:mc-shared` | the variant selector a many-version mod's bootstrappers call |

---

## One version

The API on `compileOnly`, the mod on the dev run through your toolchain's own remapping. In
`build.gradle.kts` — or, if your settings set `FAIL_ON_PROJECT_REPOS`, the same `exclusiveContent` in
`dependencyResolutionManagement.repositories`:

```kotlin
repositories {
    exclusiveContent {
        forRepository {
            maven {
                name = "CrystalGraphics"
                url = uri("https://dl.cloudsmith.io/public/crystalgraphics/crystalgraphics/maven/")
            }
        }
        filter { includeGroup("com.crystalgraphics") }
    }
}
dependencies {
    compileOnly("com.crystalgraphics:core:0.0.1")
}
```

| Toolchain | The dev-run line |
|---|---|
| ModDevGradle `neoForge` | `runtimeOnly("com.crystalgraphics:crystalgraphics:0.0.1") { isTransitive = false }` |
| ModDevGradle `legacyForge` | `obfuscation.createRemappingConfiguration(configurations.runtimeOnly.get())`, then `"modRuntimeOnly"(…)` as above |
| Loom | `modLocalRuntime("com.crystalgraphics:crystalgraphics:0.0.1") { isTransitive = false }` |

These are the routes CrystalGUI's plugin takes, verified on Forge 1.20.1, NeoForge 1.21.1 and Fabric
1.20.1 dev clients. Below 1.19.3 add `crystalgraphics-joml` the same way. On Fabric, CrystalGraphics
requires Fabric API on the run.

Your descriptor declares the dependency:

```toml
# META-INF/mods.toml -- neoforge.mods.toml says type = "required" instead of mandatory
[[dependencies.yourmod]]
modId = "crystalgraphics"
mandatory = true
versionRange = "[0.0.1,)"
ordering = "AFTER"
side = "BOTH"
```

```json
"depends": { "crystalgraphics": ">=0.0.1" }
```

- Name nothing in `com.crystalgraphics.mc` but `com.crystalgraphics.mc.shared`: the rest is the per-loader
  host, in a different package on every loader, so nothing compiled against it links.
- On 1.7.10, depend with `required-after:crystalgraphics` in `mcmod.info`.

---

## Many versions

One jar that installs on every loader and version you target, built by
[`singlejar-logic`](../singlejar-logic/README.md). CrystalGUI's
[`samples/fieldnotes`](../../samples/fieldnotes/README.md) is a complete project of this shape — without
CrystalGUI, drop its `com.crystalgui` plugin lines and put CrystalGraphics on each dev run with the
one-version line above.

```kotlin
// settings.gradle.kts
pluginManagement {
    includeBuild("<path>/CrystalGraphics/singlejar-logic")   // a clone: the build logic is not published
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.neoforged.net/releases")
    }
}
plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
    id("com.crystalgraphics.singlejar")
}
singlejar {
    targets {
        forge("1.20.1")
        neoforge("1.20.2".."1.21.11")   // every node whose range touches it
        fabric("1.20.1", "1.21.11")     // single versions: the node claiming each
    }
}
```

A version is a line there: its node, toolchain pins and stub come from singlejar-logic's catalog. A range
reaching below 1.13 also brings the 1.7.10 host and the Forge 1.8–1.12.2 tree, whose modules you write
yourself — CrystalGraphics' own are the pattern ([`singlejar-logic/README.md`](../singlejar-logic/README.md)).

```bash
./gradlew checkSingle            # the jar, checked; no Minecraft toolchain needed
./gradlew checkAllTargets        # every node compiled -- before every commit
./gradlew :runtime:mc:modern:fabric:1.20.1:runClient   # a dev client; that node builds real
```

| Rule | Why |
|---|---|
| Each loader has one **bootstrapper** at a fixed name, naming no Minecraft class | The loader constructs it whatever version runs; `VariantBootstrap` / `ForgeStart` pick the variant |
| Variant entries implement `VariantEntry` and carry no `@Mod` | Two annotated variants are two mods of one id |
| Each node's classes are relocated into its own package (`nodePackage`), the bootstrapper excepted | Several nodes share one jar |
| The thin jar excludes `DEV_DESCRIPTORS` | The merge writes the descriptors once |
| A Maven library a node compiles against is a `nodeLibrary(...)` | Otherwise the stub check takes it for the toolchain's |
| `registerNodeDevRun(descriptor, bundled)` on each loader node | Its dev client and server: ModDevGradle and Loom nodes (NeoForge, Forge to 1.20.1, Fabric) |
| Call Minecraft in compiled code, never by string | A remapper renames references, not strings |

---

## Drawing from your mod

Your renderer draws at a **render stage**: a point in Minecraft's frame that a host fires, where every registered
renderer records into one frame on the game's target, executed there and then.

| Stage | Fired | Seen from |
|---|---|---|
| `CgRenderStage.WORLD_OPAQUE` | the opaque world drawn, translucent terrain not yet | the world's camera |
| `CgRenderStage.WORLD_TRANSPARENT` | translucent terrain and particles drawn | the world's camera |
| `UiStages.SCREEN`, `UiStages.HUD` | over a screen, over the HUD -- **CrystalGUI's**, fired only where it is installed | CrystalGUI's logical pixels |
| your own, `CgRenderStage.define("mymod:after_sky")` | from a hook of yours, by `stage.fire()` | what you set in `stage.host()` |

```java
// Meshes in the world: CgWorldRenderer sorts, culls and instances them at both world stages
CgWorldRenderer world = CgWorldRenderer.get();
world.onFrame(view -> world.draw(mesh, material).at(x, y, z).submit());

// Anything else at a stage: record into its frame
CgRenderStage.Registration drawing = CgRenderStage.WORLD_OPAQUE.register(0, frame -> {
    CgRasterPass pass = frame.pass(frame.constants(), CgOrder.SORTED);   // the host's camera
    // ... chunks ...
    pass.end();
});
drawing.close();   // stops it
```

- Every host fires the world stages, on every version; a stage whose renderers record nothing costs one empty frame.
- Register from any thread; a renderer runs on the render thread, in ascending `order`, ties in registration order.
- `frame.callback(name, body)` runs `body` at its place in the stage with GL state restored after, for drawing not
  yet written as recorded chunks.
- `CgRenderStage` and `CgWorldRenderer`'s javadoc carry the rest; CrystalGUI's stages are its
  [`docs/CGUI_BUILDING_UIS.md`](../../docs/CGUI_BUILDING_UIS.md) § *Drawing under or over the UI*.
