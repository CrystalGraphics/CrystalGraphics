# singlejar-logic — one jar for every Minecraft loader

Build logic for shipping **a single artifact that installs unchanged on MC 1.7.10 Forge, 1.20.1 Forge,
1.20.4 NeoForge and 1.20.1 Fabric**. CrystalGUI and CrystalGraphics both ship this way; this directory
is what they share, and what a third project wires itself into.

It lives in CrystalGraphics because CrystalGraphics is the parent of everything that uses it — but
**nothing here is CrystalGraphics-specific**. No package, module, mod id or loader list is baked in.

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
:mc1710      thin jar ─┐
:mc1201:forge     ─────┤
:mc1201:neoforge  ─────┼─→ singleShadowJar ─→ downgradeSingleJar ─→ shadeSingleJar ─→ singleJar
:mc1201:fabric    ─────┘         ↑                   (to Java 8)      (jvmdg stubs)      ↓
                                 │                                                 checkSingleJar
   :core, :language, … ──────────┘
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
        ":mc1710" to "reobfThinJar",
        ":mc1201:forge" to "reobfThinShadowJar",
        ":mc1201:neoforge" to "thinShadowJar",
        ":mc1201:fabric" to "remapThinJar",
    ),
    libraryProjects = listOf(":core", ":mc-shared"),
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
    fabricThinJar = ":mc1201:fabric" to "remapThinJar",

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

## What is shared, and what is not

| Shared | Why |
|---|---|
| `registerSingleJarPipeline` | The merge: thin jars in, services unioned, relocations, downgrade, shade, artifact, check |
| `registerDescriptorTasks` | Four descriptor formats from one declaration, plus the drift check |
| `CheckSingleJar`, `CheckThinJar` | What a finished jar and a thin jar must be |
| `ModDescriptor` | The model the formats are printed from |

| Not shared | Why |
|---|---|
| The `plugins { }` block, group, version, jvmdg conventions | A shared function cannot supply a `plugins` block; ~20 lines stay in each consumer |
| The mixin config plugin | 69% of its lines differ; each names its own configs and owner map |
| `ProdSmoke` | Drives clients carrying *every* mod — one copy in the top consumer, not one per project |
| **`mc-shared`** (`LoaderProbe`, `CrashVariant`) | **Deliberately duplicated.** Runtime code shipped *inside* a jar, answering "which jar am I?". Sharing it would make one mod's entry-point constructor throw `NoClassDefFoundError` beside an older copy of the other, turning benign version skew into a hard crash — and `LoaderProbe` runs at mixin bootstrap on 1.7.10, before mods load |

The rule behind that table: **share the mechanism, never the constants.** Anything that differs
between two projects belongs to those projects. It is why the merge is shared and the merge *script*
is not — measured, 56% of those lines differed, and every difference was a constant.
