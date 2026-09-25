# singlejar-logic — one jar for every Minecraft loader

Build logic for shipping **a single artifact that installs unchanged on MC 1.7.10 Forge, 1.20.1 Forge,
1.20.4 NeoForge and 1.20.1 Fabric**. CrystalGUI and CrystalGraphics both ship this way; this directory
is what they share, and what a third project wires itself into.

It lives in CrystalGraphics because CrystalGraphics is the parent of everything that uses it — but
**nothing here is CrystalGraphics-specific**. No package, module, mod id or loader list is baked in.

📄 **[STUBS.md](STUBS.md)** — how every Minecraft version builds from one 16 MB `stubs.zip` instead of
its real toolchain (`-PcgStubs`), and what to regenerate when a node is added.

---

## Why one jar is possible at all

Three facts, and the whole design rests on them:

| | |
|---|---|
| **F1 — a class file is inert until something DEFINES it** | Every loader's scanner reads candidate classes with ASM and defines only what it wants. A jar carrying a Forge entry point, a NeoForge one, a Fabric one and an FML 1.7.10 one costs each loader nothing for the three it ignores — their references to a Minecraft that isn't running are never resolved |
| **F2 — FML 1.7.10 reads EVERY class in EVERY jar with asm-debug-all-5.0.3** | Which throws on anything above major 52. So the *whole* merged jar is downgraded to Java 8, and `META-INF/versions/` is no escape: FML doesn't honour it and scans those entries too. A jar that violates this dies before any mod loads, with nothing of yours in the log |
| **F3 — Mixin asks `shouldApplyMixin` BEFORE looking up the target class** | So one config plugin can decline another loader's mixins without their targets existing |

Each loader also spells its entry point differently (`cpw.mods.fml.common.Mod`,
`net.minecraftforge…`, `net.neoforged…`, `fabric.mod.json`), so no bootstrapper is needed while there
is one variant per loader.

---

## The shape of a build

```
:runtime:mc:1710             ──┐
:runtime:mc:modern:forge     ──┤
:runtime:mc:modern:neoforge  ──┼─→ singleShadowJar ─→ downgradeSingleJar ─→ shadeSingleJar ─→ singleJar
:runtime:mc:modern:fabric    ──┤          ↑              (to Java 8)         (jvmdg stubs)        ↓
                               │                                                              checkSingleJar
   :core, :language, … ────────┘
   libraries, descriptors, services
```

A **thin jar** is one loader's own classes plus the shared host relocated under
`com.<you>.mc.<loader>.common` — three remapped copies cannot share a name. Everything else — the
engine, its libraries, the descriptors — enters **once, at the root**.

**The order is not interchangeable.** jvmdg rewrites bytecode and adds references to its own stubs, so
the stubs are shaded in *after* the rewrite. Remapping already happened per thin jar, which is the one
inversion from a fat chain and is safe because jvmdg does not read names.

---

## Wiring a project in

### 1. Include the build

```kotlin
// <your project>/<your build-logic>/settings.gradle.kts
rootProject.name = "my-build-logic"
includeBuild("../../CrystalGraphics/singlejar-logic")   // wherever CrystalGraphics sits
```

```kotlin
// <your build-logic>/build.gradle.kts
dependencies {
    implementation("com.crystalgraphics.build:singlejar-logic")
}
```

A composite matches an included build to a dependency by **group and name**, so the path above is the
only thing that changes per consumer.

### 2. Declare the merge

Your own precompiled script plugin keeps the `plugins { }` block — a shared function cannot supply one
— and hands over a `SingleJarSpec`:

```kotlin
import cgbuildlogic.SingleJarSpec
import cgbuildlogic.registerSingleJarPipeline

plugins {
    java                                    // not `base`: jvmdg's DowngradeJar reads sourceSets
    id("com.gradleup.shadow")
    id("xyz.wagyourtail.jvmdowngrader")
}

tasks.named<Jar>("jar") { enabled = false } // nothing compiles at the root
group = property("modGroup").toString()
version = property("modVersion").toString()

jvmdg.defaultShadeTask { enabled = false }  // jvmdg's conventions are for a module that compiles
jvmdg.defaultTask { enabled = false }
jvmdg.multiReleaseVersions.set(emptySet<JavaVersion>())
jvmdg.multiReleaseOriginal.set(false)

repositories { mavenCentral() }

val modId = property("modId").toString()

registerSingleJarPipeline(SingleJarSpec(
    modId = modId,
    fileName = "$modId-${project.version}.jar",
    shadePath = "com/myproject/shadow",     // see "Traps" — never let this default

    thinJars = listOf(
        ":runtime:mc:1710" to "reobfThinJar",
        ":runtime:mc:modern:forge" to "reobfThinShadowJar",
        ":runtime:mc:modern:neoforge" to "thinShadowJar",
        ":runtime:mc:modern:fabric" to "remapThinJar",
    ),
    libraryProjects = listOf(":core", ":runtime:mc:shared"),
    serviceOwners = listOf(":core"),

    relocations = listOf("org.joml" to "com.myproject.shadow.org.joml"),

    manifest = mapOf(
        "FMLCorePluginContainsFMLMod" to true,
        "ForceLoadAsMod" to true,
        "TweakClass" to "org.spongepowered.asm.launch.MixinTweaker",
        "MixinConfigs" to "mixins.myproject.json",
        "Implementation-Version" to project.version.toString(),
        "Automatic-Module-Name" to modId,
    ),
    fabricThinJar = ":runtime:mc:modern:fabric" to "remapThinJar",

    extraContent = {                        // anything else this project bundles
        exclude("module-info.class", "kotlin/**")
    },
    configureCheck = {                      // what you expect of the result
        forbiddenPrefixes.set(listOf("META-INF/versions/", "org/joml/"))
        expectSingle.set(listOf("com/myproject/engine/"))
        relocatedClasses.set(mapOf("com/myproject/mc/Host.class" to 3))
        requiredEntries.set(listOf("META-INF/mods.toml", "fabric.mod.json", "mcmod.info"))
    },
))

dependencies {
    "singleJarLibs"("org.joml:joml-jdk8:1.10.1")   // the configuration the pipeline creates
}
```

### 3. Declare what the mod says about itself

Four descriptor formats, one declaration:

```kotlin
import cgbuildlogic.*

val descriptor = ModDescriptor(
    id = "myproject", name = "MyProject",
    version = property("modVersion").toString(),
    description = "…", license = "LGPL-3.0-or-later",
    dependencies = listOf(Dependency("crystalgraphics", "[1.0.0,)", ordering = Ordering.AFTER)),
    variants = listOf(
        Variant(loader = "fml1710", minecraft = "[1.7.10]", era = "1710",
                commonEntry = "com.myproject.MyProject",
                mixinConfigs = listOf("mixins.myproject.json"), packFormat = 1),
        Variant(loader = "forge", minecraft = "[1.20.1,1.21)", era = "modern",
                commonEntry = "com.myproject.mc.forge.MyProjectForge", packFormat = 15),
        Variant(loader = "neoforge", minecraft = "[1.20.4,1.21)", era = "modern",
                commonEntry = "com.myproject.mc.neoforge.MyProjectNeoForge", packFormat = 22),
        Variant(loader = "fabric", minecraft = "[1.20.1,1.21)", era = "modern",
                commonEntry = "com.myproject.mc.fabric.MyProjectFabricCommon",
                clientEntry = "com.myproject.mc.fabric.MyProjectFabric",
                fabricDepends = linkedMapOf("fabricloader" to ">=0.15.0", "minecraft" to "~1.20.1"),
                packFormat = 15),
    ),
)

registerDescriptorTasks(descriptor, "myproject")
```

### 4. Guard each thin jar

Register once for all your 1.20.x loaders — what a thin jar may contain is the *project's* answer:

```kotlin
tasks.register<cgbuildlogic.CheckThinJar>("checkThinJar") {
    allowedPrefixes.set(listOf("com/myproject/mc/"))
    forbiddenPrefixes.set(listOf("com/myproject/engine/", "org/joml/"))  // what the ROOT merges
    logTag.set("myproject")
}
tasks.named("check") { dependsOn("checkThinJar") }
```

…and in each loader, supply only its jar:

```kotlin
tasks.named<cgbuildlogic.CheckThinJar>("checkThinJar") { jar.set(reobfThinJar.flatMap { it.archiveFile }) }
```

---

## What you get

| Task | Does |
|---|---|
| `singleJar` | The artifact, at `build/libs/<fileName>` |
| `checkSingleJar` | Asserts the jar is what four loaders need. Deletes it on failure, so a bad jar is never installable |
| `checkSingle` | Build it and check it |
| `generateMergedDescriptors` | The four formats, from one declaration |
| `checkDescriptorsAgree` | Fails if a shipped per-loader descriptor has drifted from that declaration |
| `checkThinJar` | Per loader: this loader and nothing the root merges |

### More than one jar from one project

Every name above is derived from `SingleJarSpec.name`, which defaults to `single`. Register the
pipeline twice to ship a second mod from the same build — an optional half nobody should have to
download, say:

```kotlin
registerSingleJarPipeline(SingleJarSpec(name = "single",   modId = "myproject",      …))
registerSingleJarPipeline(SingleJarSpec(name = "language", modId = "myproject_lang", …))
```

The second gives `languageJar`, `languageShadowJar`, `checkLanguageJar`, `languageJarLibs` and its own
`build/language-jar/` staging directory, so the two never touch. (A mod id with an underscore, not a
hyphen: Forge's id grammar allows no hyphen; Fabric allows both.)

**Its descriptors need a name too.** `registerDescriptorTasks` defaults to `merged`, which is what
`SingleJarSpec.descriptorsTask` defaults to; a second mod passes its own, and `checkShipped = false`
because it ships no per-loader descriptor to be checked against:

```kotlin
registerDescriptorTasks(langDescriptor, "myproject-lang", name = "language", checkShipped = false)
registerSingleJarPipeline(SingleJarSpec(name = "language", descriptorsTask = "generateLanguageDescriptors", …))
```

**Where the second mod's classes come from is a SOURCE SET, not a module.** Each module that already
faces a loader gains a `lang` (or whatever the second mod is) source set beside `main`, and the loader
modules gain one entry class each. A module per loader per era doubles the module count with every era
added, for twenty lines of registration apiece.

The rule that makes the split hold is one line of Gradle: the optional half's library goes on **that
source set's** `compileOnly` and not on `main`'s.

```kotlin
val lang: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets["main"].compileClasspath + sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].runtimeClasspath + sourceSets["main"].output
}
dependencies { "langCompileOnly"(project(":language")) }
```

`main` is on `lang`'s compile classpath and not the reverse, so a `main` class naming the optional half
is a **compile error** rather than something an import guard notices afterwards. Give the source set its
own package — the two halves end up in two jars, and two jars sharing a package is a split package that
fails module resolution on Forge and NeoForge.

**Registration inverts with the split.** The host must not call into the optional jar, so the optional
jar's own entry point installs itself through seams the shared library owns, and the host only reports
which tier it has. Where the host needs to run something the other jar provides — a test probe, say —
it publishes a seam and the other jar registers into it:

```java
CgUiAutoTest.onFrame(5, MyProbe::runOnce);   // from the optional mod's entry, at init
```

**Cross-jar `ServiceLoader` works on all four loaders** — measured, not assumed. One `LaunchClassLoader`
on 1.7.10 and one Knot on Fabric make it obvious; on Forge and NeoForge the two jars are two automatic
modules in the game layer and the lookup still resolves. A `META-INF/services` file in the second jar
reaches a `ServiceLoader` call in the first.

**Except under ModLauncher 5 (Forge 29-31)**, whose classloader never lists a resource inside a mod file,
so `ServiceLoader` finds nothing in any of them. CrystalGUI discovers through `Providers.forEach`, and the
Forge 1.15 host fills its `Providers.Copies` slot from FML's own mod list.

`checkSingleJar` catches, specifically: any class above the major ceiling; a relocated class that
appears once instead of once per variant; a required entry or manifest key missing; a `META-INF/services`
file that lost a provider; a forbidden prefix shipping unrelocated.

---

## Traps this build exists to prevent

Each of these was paid for once. None of them fails loudly on its own.

- **A relocated library must not appear in a signature another jar defines.** Relocation renames *your*
  copy; a foreign jar still names the original, and the two stop being the same type. CrystalGUI and
  CrystalGraphics relocate JOML **identically** for this reason.
- **Never relocate tree-sitter or anything with JNI.** A JNI symbol is named after the mangled package;
  renaming it renames a symbol the `.dll` does not export, and the first call throws `UnsatisfiedLinkError`.
- **A remapper rewrites compiled references, never strings.** `getMethod("loadLevel", …)` reaches a
  Forge jar running SRG members and a Fabric jar running intermediary unchanged, and matches nothing —
  while resolving on NeoForge, which runs official names. Call Minecraft members in *compiled* code.
- **`shadePath` must be a legal package path.** jvmdg defaults it to the archive base name; a
  hyphenated one is not an identifier and the module system rejects the jar before the early display.
- **A 1.7.10 mixin config plugin must compile against the SHADED ASM spelling**
  (`org.spongepowered.asm.lib.tree.ClassNode`). One written against vanilla Mixin's `org.objectweb.asm`
  loads, is transformed, has `getMixins()` called — then kills the game at the first applied mixin with
  a `NoSuchMethodError` naming a constructor that is present.
- **`Multi-Release` must not reach the manifest.** It puts modern classes where FML 1.7.10's scanner
  reads them and calls the jar corrupt.
- **ASM must be relocated, and unrelocated it fails in three different-looking ways.** ModLauncher runs
  on ASM, so a game-layer jar exporting `org.objectweb.asm` is a split package against the boot layer:
  Forge and NeoForge die straight after *"Initialized transformers"* with **nothing in any log**, Fabric
  reports a Knot/app loader-constraint violation on `ClassNode`, and 1.7.10 works fine, having no
  modules. Put the prefix in `forbiddenPrefixes` so the jar is refused instead.
- **A library a fat jar declared as a shaded dependency reaches no merged jar.** The merge takes *thin*
  jars, which carry no dependencies at all, so anything a per-loader chain pulled in that way has to be
  declared into `<name>JarLibs` explicitly. Symptom: nothing, until the one code path that needs it runs
  on an installed client.

---

## Verifying it actually runs

A green build proves nothing about a jar: a dev run resolves classes from source-set *directories*, so
it cannot see relocation, remapping, downgrading or a merged manifest — the four things most likely to
be wrong. Drive real clients instead. CrystalGUI's `ProdSmoke` does this for every installed instance
(boot, load a world, photograph, quit; ~75s for four) and lives in the top consumer rather than here,
because one run covers every mod in the pack.

If you write your own: **a capture is not a paint.** A screenshot proves a frame was read back, not
that your engine drew it — with no live GL context a screen's `render` returns at once, and outside a
level Minecraft never clears the colour buffer, so the frame still holds the previous screen. Have the
game state whether it painted, and fail on the answer.

---

## Many Minecraft versions: the modern tree

A loader is built once per Minecraft version it targets, from ONE source tree, with
[Stonecutter](https://stonecutter.kikugie.dev/)'s comment directives. The tree is **branched**:

```
runtime/mc/modern/
  stonecutter.gradle.kts                  the controller: which node is ACTIVE, and nothing else
  common/  forge/  neoforge/  fabric/     a BRANCH each: the shared src/ and one build script
    <branch>/versions/<version>/          a NODE: gradle.properties (its pins), and its build/ and runs/
```

```kotlin
// settings.gradle.kts -- the only place a node is declared
plugins { id("dev.kikugie.stonecutter") version "0.9.8" }
stonecutter {
    create("runtime:mc:modern") {
        branch("common") { versions("1.20.1", "1.20.4") }
        branch("forge") { versions("1.20.1") }
    }
}
```

A node is the project `:runtime:mc:modern:<branch>:<version>`, so **on a node `project.name` is the
version**. `ModernTree` answers everything else and is the only thing that should: `modernLoader`,
`commonNode` (the common node of the same version — a loader never borrows another's), `modernNodes`,
`modernLoaderNodes`, and, for a project built on another, `sameVersionNodePath` / `sameVersionNodeDir` /
`sameVersionNodeCoordinate`. `ModernConventions` holds what every such build does alike:
`useNodeCoordinates`, `useModernMinecraft` (NeoForm where it exists, 1.20.2 onward; Forge's userdev
through legacyForge from 1.17; Loom or Unimined below that — chosen by the node's own pins), `guardLoaderImports`,
`registerCheckDescriptorsNameNoCommon` and `registerCheckAllTargets`.

**Two nodes of one loader share the merged jar** because every node ships its loader's classes under
a package of its own — `nodePackage`: `<loader package>.v<version digits>`, with common beneath it as
`.common` — and only the loader's **bootstrapper** stays at its source name, the one class that loader
constructs whatever version runs. `modernVariants` reads each loader node's variant off the tree, so a
descriptor declares its entry classes once, at SOURCE names, in `LoaderEntries`; the merged
`variants.json` names them relocated, and `registerNodeVariants` gives each node's dev run a table of its
own at source names, since a dev run loads the classes unrelocated. `shippedEntryPaths` is the list
`requiredEntries` checks.

**Adding a version** — the parent first, since a project built on another compiles each node against
the parent's node of the same version:

1. the version on the loader's branch in `settings.gradle.kts`, **and on `common`** if it is absent;
2. `versions/<version>/gradle.properties` for each: the toolchain pins, plus `variant.minecraft` (the
   range the node claims, narrowing a neighbour's if they would overlap — `ModDescriptor` refuses the
   overlap), `variant.packFormat`, and `java.version = 21` from 1.20.5 on (`nodeJava`: what the node
   emits, and the ceiling its thin jar is checked against; 17 when unpinned);
3. `//? if` directives where the API differs — `checkAllTargets` finds every one.
4. regenerate `stubs.zip` and commit it with the node — [STUBS.md](STUBS.md) § *Regenerating*.
    Until then the new node builds real.

The thin-jar lists, the relocation counts, the descriptors (including `neoforge.mods.toml`, the only
file NeoForge 1.20.5+ reads), the variant tables and `requiredEntries` all follow the tree, so none of
them is edited.

**A project built on another may call into the parent's common node** — CrystalGUI uses
CrystalGraphics' `ResourceIds` rather than a copy. The parent ships that common relocated per node, so
the child's thin jar relocates its REFERENCES the same way: `relocate(<parent common package>,
nodePackage(<parent loader package>, version) + ".common…")`. Nothing of the parent's is bundled; only
the names in the child's bytecode move. Dev runs need nothing, since both load at source names.

**MinecraftForge past 1.20.1 has no Gradle 9 toolchain** — ModDevGradle's legacy mode stops at 1.20.1
and ForgeGradle is Gradle 8 — so a Forge node from 1.20.2 is built from parts (`ModernForge`). It pins
`neoform.version` beside `forge.version`: Minecraft comes through NeoForm, `useForgeApi` puts Forge's
own jars on compileOnly, and there is no dev run, so prodSmoke is its runtime check. What it ships
depends on the names Forge runs, which `forgeRunsSrg` answers:

```properties
# forge/versions/1.20.4 -- Forge 49 runs SRG members: the thin jar is reobfuscated
neoform.version = 1.20.4-20240627.114801
forge.version = 49.2.9
mcp.version = 1.20.4-20231207.112700   # the SRG table, as Forge's userdev names it
```

```properties
# forge/versions/1.21.1 -- Forge 52 runs Mojang's names: the thin jar ships as compiled
neoform.version = 1.21.1-20240808.144430
forge.version = 52.1.16
```

`registerSrgReobf` does the reobfuscation with the renamer legacy mode uses, over Mojang's names
chained with MCPConfig's SRG table; `thinJarTask` names each node's production step for the merge.

**NeoForge 20.2 and 20.3 are the same case** — ModDevGradle does not set them up — and simpler: they
run Mojang's names, so `useNeoForgeApi` puts the jars on compileOnly and nothing is renamed. It lists
them non-transitively (`neoforge.fml`, `neoforge.bus`), because NeoForge's POM also names Minecraft's
libraries at versions NeoForm pins strictly.

**Below 1.17 ModDevGradle reaches no Minecraft at all**, so a pin picks another toolchain: Loom on
`common` (vanilla at Mojang's names) and Unimined on Forge (Forge 29-31's userdev). Fabric is Loom
already. Neither has a dev run on Forge, so prodSmoke is its runtime check.

```properties
# common/versions/1.15.2
minecraft.loom = true
```

```properties
# forge/versions/1.15.2 -- Forge 31 runs MCP class names as well as SRG members
minecraft.unimined = true
forge.version = 31.2.62
mcp.version = 1.15.2-20200515.085601
```

`registerSrgReobf` renames classes too below 1.17; from 1.17 Forge runs Mojang's class names.

**A node may ship mixins** when its loader has no event for what it needs — Forge 53 (1.21.3) dropped
the world-render event with 1.21.2's frame graph. It pins the plugin that gates them, and the mixins
live in the branch's `mixin` package:

```properties
# forge/versions/1.21.3
variant.mixinPlugin = com.crystalgraphics.mc.shared.CrystalGraphicsForgeMixins
```

`registerNodeMixins` writes the node's config into its thin jar at the shipped package, and the
variant table and descriptors carry it: the manifest's `MixinConfigs` for FML 1.7.10 and Forge,
`[[mixins]]` for NeoForge, `fabric.mod.json` for Fabric. Every loader that reads a list reads all of
it, so the plugin — a `VariantMixins` in the unrelocated shared module — applies a config only on the
variant whose package holds it, and names the mixins itself: the config's lists stay empty, because
Mixin parses a listed class before its plugin can refuse it, and 1.7.10's ASM cannot read Java 21.

What bites:

1. **A node's group is its branch's** (`useNodeCoordinates`). Nodes of one version share a project
   name, so one group for the whole tree makes `forge:1.20.1` and `common:1.20.1` one coordinate and
   the loader's dependency on common resolves to itself: `compileJava` depending on `compileJava`.
2. **Switching the active node rewrites the branches' `src/` in place.** Switch back to the controller's
   version before committing, or the diff carries directive noise rather than the change.
3. **Directives live in the branch `src/`**, never under a node's `build/generated/stonecutter/`. The
   active node compiles `src/` directly; every other node compiles the generated copy.
4. **Nothing may read `src/` relative to the project directory.** On a node that is
   `versions/<version>/src`, which does not exist, so a check reading it passes having read nothing.
5. **The settings plugin needs a Java 21+ Gradle daemon** in every build that includes one of these.
6. **A bootstrapper may name no Minecraft class** — one copy serves every node of its loader, so the
   merge keeps whichever node's arrived first. Loader API that moved between its versions is read
   reflectively for the same reason (`FmlVersion`, `FmlSide`).
7. **Loom reads a mod jar while the build is configured.** A node's first dev run on Fabric can have no
   parent mod yet; the build says so, and the next run finds it. A CHANGED parent lags the same way.
8. **An old NeoForm can be unusable.** ModDevGradle needs the `neoform-dependencies` capability, which
   NeoForm builds from 2023 lack; 1.20.2 resolves only through its December 2024 republish.
9. **A node mixin's target may not be on its compile classpath.** A Forge node compiles against
   vanilla Minecraft, so a method Forge's patches add — `ParticleEngine.render(..., Frustum)` — is a
   Mixin processor warning at build time and resolves only in the game. prodSmoke is what proves the
   injection bound; each injector says `require = 1` so a miss is a crash, not a silent no-op.
10. **A rename is a controller replacement, not a directive.** `ResourceLocation` → `Identifier`
    (1.21.11) touches every file that names one; `stonecutter.gradle.kts` swaps the string on nodes from
    that version (`replacements.string(current.parsed >= "1.21.11")`), and `src/` keeps the old name.
11. **ModDevGradle refuses `additionalRuntimeClasspath` from 1.21.10.** A dev-run library goes on
    `runtimeOnly` there; `devRunLibraries` names the configuration for a node.
12. **ModDevGradle runs its decompile tools on the Minecraft's own Java**, which for 1.17 is 16 and
    usually not installed. `useModernMinecraft` points them at 17 there. A node's `java.version` never
    needs to drop below 17: the merged jar is downgraded to Java 8 whatever the nodes emit.
13. **Fabric API was mod id `fabric` through its 1.19.1 builds** and `fabric-api` after, still
    providing `fabric`. Depend on `fabric`, or a 1.17-1.19.1 client refuses the jar.
14. **Minecraft ships no JOML below 1.19.3.** A shipped jar may not carry it (1.19.3+ has it as a named
    module, and a second copy is a split package), so those instances take a companion jar and a dev
    run takes JOML as a library.
15. **Loom's main artifact is the remapped jar.** A Loom `common` publishes its unremapped one instead
    (`fabric.loom.disableRemappedVariants`): the loader node bundles common at Mojang's names and remaps
    the two together. An intermediary common reaches a dev run as a `NoSuchMethodError`.
16. **Unimined puts Minecraft on the source set's classpath, not the `compileClasspath` configuration.**
    Hand `registerSrgReobf` `sourceSets.main.get().compileClasspath`, or the renamer sees no Minecraft
    and dies on the first inherited method.
17. **Fabric API has no world-render event below 1.16**, so a 1.14-1.15 Fabric node hooks the level
    render with a node mixin, naming the method both ways since the dev run is Mojang-named and
    production is intermediary. A dev run reads the merged descriptor, which names every node's config,
    so `registerNodeMixins` writes each into `processResources` at the source package -- the node's own
    with its plugin, a sibling's inert -- and the thin jar excludes them (`devNodeMixinConfigs`).
18. **Forge below 1.17 needs Java 8, and has no dev run.** An instance for it pins a Java 8 runtime; the
    merged jar is downgraded to 8 already. The dev classes are not -- major 61 with `NestHost`, which a
    Java 8 JVM cannot define and Forge 25's ASM6 scanner cannot read -- so `serverSmoke` and `runClient`
    do not run there, and prodSmoke is the check.
19. **A node's mixin configs belong to one mod.** A second mod built from the same nodes (CrystalGUI's
    language stack) passes `LoaderEntries(mixins = false)`: one config name in two mods is a Fabric
    refusal at launch, and it shows as a client that stops right after the Mixin banner, logging nothing.
20. **A `replacements.string` runs in reverse on every node its condition is false for.** Its target
    must therefore never occur in the sources: `GlStateManager.` for `GlStateManager._` would have turned
    every `GlStateManager.class` into `_class` on 1.15+. 1.14's un-prefixed names are a same-package shim
    instead.
21. **Mojang published no names before 1.14.4**, so the tree compiles 1.13.2 and 1.14.3 against
    generated ones: `runtime/mc/modern/mappings/mojmap-<version>.tsrg`, 1.14.4's Mojang names carried back
    through SRG ids by `backport_mojmap.py <version>`. `backportedMojmap()` finds the file for a node; Unimined reads it as the
    `mojmap` namespace and `SrgReobfJar` reverses it where it would read Mojang's `client.txt`. Where an
    id changed in 1.14 the generator needs telling (`HINTS`, `MEMBER_ALIASES`), and where Forge adds a
    member of the name it would give, it must give none (`MEMBER_SKIP`) or the remap refuses.
22. **Loom and Unimined cannot share a plugin classloader**, and Gradle shares one between sibling
    scripts only when their plugin requests match. Unimined carries its own copies of Loom's classes, so
    a `common` requesting both breaks Loom in every fabric node; a Loom `common` beside a Loom `fabric`
    works only because the two request the same set. The 1.13 `common` applies Unimined from a script
    plugin (`unimined-vanilla.gradle.kts`), whose `buildscript {}` is a classloader of its own.
23. **LWJGL 3.1 has no core-profile `GLxxC` classes**, and Minecraft 1.13 ships 3.1.6. Tier 1 keeps
    them; `Lwjgl31GLBackend` is generated from it with the plain `GLxx` classes, and the 1.13 backend
    extends that instead. Anything else a 1.13 node calls names `GLxx`.

---

## What is shared, and what is not

| Shared | Why |
|---|---|
| `registerSingleJarPipeline` | The merge: thin jars in, services unioned, relocations, downgrade, shade, artifact, check |
| `registerDescriptorTasks` | Four descriptor formats from one declaration, plus the drift check |
| `CheckSingleJar`, `CheckThinJar` | What a finished jar and a thin jar must be |
| `ModDescriptor` | The model the formats are printed from |
| `ModernTree`, `ModernConventions`, `ModernVariants` | Finding a node, its coordinates, its toolchain, its checks, its variant and its relocated names — the modern tree above |

| Not shared | Why |
|---|---|
| The `plugins { }` block, group, version, jvmdg conventions | A shared function cannot supply a `plugins` block; ~20 lines stay in each consumer |
| The mixin config plugin | 69% of its lines differ; each names its own configs and owner map |
| `ProdSmoke` | Drives clients carrying *every* mod — one copy in the top consumer, not one per project |
| **`runtime/mc/shared`** (`LoaderProbe`, `CrashVariant`) | **Shared since 2026-09-11 — a project that depends on CrystalGraphics uses its copy.** The one per-project part is the crash-report heading, so it is a parameter: `CrashVariant.label("MyMod")`. This row used to argue for duplication, on two grounds. Version skew turning into a hard crash does not apply to a consumer that already requires CrystalGraphics, which fails on any skew anyway. `LoaderProbe` running at 1.7.10 mixin bootstrap does resolve, since both jars are tweaker jars on the LaunchWrapper classpath before a config plugin loads — **measured 2026-09-11**: an installed 1.7.10 client whose CrystalGUI jar carries no `LoaderProbe` printed `CrystalGuiMixins.onLoad`'s `loader is fml1710 (by mixin service)`, and `prodSmoke` drew all four clients. A project that does not depend on CrystalGraphics still carries its own copy |

The rule behind that table: **share the mechanism, never the constants.** Anything that differs
between two projects belongs to those projects. It is why the merge is shared and the merge *script*
is not — measured, 56% of those lines differed, and every difference was a constant.
