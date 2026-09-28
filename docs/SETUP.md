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

The API on `compileOnly`, the mod on the dev run through your toolchain's own remapping:

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
    compileOnly("com.crystalgraphics:core:1.0.0")
}
```

| Toolchain | The dev-run line |
|---|---|
| ModDevGradle `neoForge` | `runtimeOnly("com.crystalgraphics:crystalgraphics:1.0.0") { isTransitive = false }` |
| ModDevGradle `legacyForge` | `obfuscation.createRemappingConfiguration(configurations.runtimeOnly.get())`, then `"modRuntimeOnly"(…)` as above |
| Loom | `modLocalRuntime("com.crystalgraphics:crystalgraphics:1.0.0") { isTransitive = false }` |

These are the routes CrystalGUI's plugin takes, verified on Forge 1.20.1, NeoForge 1.21.1 and Fabric
1.20.1 dev clients. Below 1.19.3 add `crystalgraphics-joml` the same way. On Fabric, CrystalGraphics
requires Fabric API on the run.

Your descriptor declares the dependency:

```toml
# META-INF/mods.toml -- neoforge.mods.toml says type = "required" instead of mandatory
[[dependencies.yourmod]]
modId = "crystalgraphics"
mandatory = true
versionRange = "[1.0.0,)"
ordering = "AFTER"
side = "BOTH"
```

```json
"depends": { "crystalgraphics": ">=1.0.0" }
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
pluginManagement { includeBuild("<path>/CrystalGraphics/singlejar-logic") }
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
