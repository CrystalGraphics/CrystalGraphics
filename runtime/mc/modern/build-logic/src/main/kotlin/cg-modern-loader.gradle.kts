import cgbuildlogic.ModDescriptor
import cgbuildlogic.commonNode
import cgbuildlogic.modernLoader
import cgbuildlogic.nodeJava
import cgbuildlogic.nodePackage
import cgbuildlogic.registerNodeVariants
import cgbuildlogic.registerCheckDescriptorsNameNoCommon
import cgbuildlogic.useNodeCoordinates

plugins { id("cg-java") }

// ── A loader node: `:runtime:mc:modern:<loader>:<version>` ───────────────────────────────────────
//
// `project.name` is the VERSION here and the loader is the branch, so neither is spelled out below:
// `modernLoader` and `commonNode` say which is which. @see cgbuildlogic.ModernTree
useNodeCoordinates()
base { archivesName.set("crystalgraphics-$modernLoader-${project.name}") }

/** The common node of THIS node's Minecraft -- never another version's. */
val common: Project = project.commonNode

// Its jar task is read below at configuration time, which needs the project configured first.
evaluationDependsOn(common.path)

// LWJGL 3.3.1 -- what MC 1.20.1 ships -- predates Java 21 and does not recognise its JNI version. It
// patches the JNIEnv function table on a guessed layout anyway; under a debugger's JVMTI agent that
// table is instrumented, so the write lands past it and the process dies with a native fail-fast
// (0xC0000409 on Windows) before the window opens.
//
// A dev run here is ALWAYS on Java 21: cg-java raises the toolchain to 21 so javac can read :core's
// v65 classes, and ModDevGradle takes the run JVM from the toolchain. So the client runs fine and
// cannot be debugged -- which reads as an IDE fault rather than a library one.
//
// 3.3.3 knows the version and uses the right layout. Dev runs only; nothing shipped resolves LWJGL.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.lwjgl") {
            useVersion("3.3.3")
            because("LWJGL 3.3.1 corrupts the JNIEnv table on Java 21 under a debugger")
        }
    }
}


repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases") { name = "NeoForge" }
    maven("https://maven.parchmentmc.org/") { name = "ParchmentMC" }
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    maven("https://repo.spongepowered.org/repository/maven-public/") { name = "Sponge" }
    maven("https://maven.minecraftforge.net/") { name = "Forge" }
}

dependencies {
    // compileOnly: shadowJar bundles these manually (see each loader's build.gradle.kts).
    // runtimeOnly: picked up by Fabric/Loom dev runs via Gradle's standard runtimeClasspath.
    // ModDevGradle (Forge/NeoForge) dev runs ignore runtimeClasspath and instead use the
    // mods{} sourceSet declarations in each loader's build.gradle.kts.
    "compileOnly"(project(common.path))
    "compileOnly"(project(":platform"))

    // compileOnly and NOT bundled: the merge adds :runtime:mc:shared once, under a package no variant
    // relocates, so all four hosts share the one copy.
    "compileOnly"(project(":runtime:mc:shared"))
    // AND ON THE RUNTIME CLASSPATH since J11.0: it carries the variant selector a bootstrapper calls,
    // so a dev run that cannot see it dies in the entry point. Loom takes this from runtimeClasspath;
    // ModDevGradle ignores that and needs `additionalRuntimeClasspath`, at the foot of this file.
    "runtimeOnly"(project(":runtime:mc:shared"))
    "compileOnly"(project(":core"))
    "compileOnly"(project(":freetype-msdfgen-harfbuzz-bindings"))
    "runtimeOnly"(project(common.path))
    "runtimeOnly"(project(":platform"))
    "runtimeOnly"(project(":core"))
    // Fabric/Loom dev runs pick this up from runtimeClasspath.
    // ModDevGradle (Forge/NeoForge) dev runs need it in the mods{} sourceSet block instead.
    "runtimeOnly"(project(":freetype-msdfgen-harfbuzz-bindings"))
    // Mixin compileOnly — loaders bundle it at runtime
    "compileOnly"("org.spongepowered:mixin:${property("modern.mixin")}")
    "annotationProcessor"("org.spongepowered:mixin:${property("modern.mixin")}:processor")
    "compileOnly"("io.github.llamalad7:mixinextras-common:${property("modern.mixinextras")}")
}

// ── The thin jar (J1) ────────────────────────────────────────────────────────────────────────────
//
// One input to the single-jar merge: this loader's own classes and resources, plus its common node,
// and NOTHING else. The renderer, its SPI, the font bindings and JOML enter the merge once at the
// root; a copy here would ship three times over.
//
// `common` has to be relocated because the single jar carries THREE remapped copies of it -- SRG on
// Forge, official on NeoForge, intermediary on Fabric -- and three classes cannot share a name.
//
// THE PACKAGE IS MOVED, NOT ITS PARENT: relocating `com.crystalgraphics.mc` would rewrite this
// loader's own `com.crystalgraphics.mc.<loader>` too. `platform` keeps its leaf name under the new
// root, so `mc.platform.LifecycleModern` becomes `mc.forge.common.platform.LifecycleModern`.
//
// EVERYTHING A NODE SHIPS LIVES UNDER ITS OWN PACKAGE, `...mc.modern.<loader>.v<version>` (J11.1b), so
// two nodes of one loader can share the merged jar: common goes to `<node>.common`, the loader's own
// classes to `<node>`. EXCEPT THE BOOTSTRAPPER, the one class its loader constructs whatever version is
// running -- every node ships it at one name and the merge keeps a single copy. It names no Minecraft
// class, which is what makes one copy right for every node. The variant table names the relocated
// entries (cgbuildlogic.ModernVariants).
val loaderPackage = "com.crystalgraphics.mc.modern.$modernLoader"
val nodeRoot = nodePackage(loaderPackage, project.name)
val cgThinRoot = "$nodeRoot.common"
val loaderTitle = mapOf("forge" to "Forge", "neoforge" to "NeoForge", "fabric" to "Fabric").getValue(modernLoader)
val bootstrapper = "$loaderPackage.${loaderTitle}Bootstrap"

/** The descriptor the merged jar is printed from, and the variant this node's dev run reads. */
@Suppress("UNCHECKED_CAST")
val modDescriptors = rootProject.extra["cgModDescriptors"] as Map<String, ModDescriptor>

val thinShadowJar = tasks.register<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("thinShadowJar") {
    group = "build"
    description = "This loader plus its common node, relocated -- the merge's input, before remapping."
    // DEV NAMES STILL. Forge reobfuscates this, Fabric remaps it, NeoForge ships it as it is.
    archiveClassifier.set("thin-dev")
    configurations = emptyList()
    from(sourceSets["main"].output)
    // The dev run's descriptors: the merge writes its own copy once.
    exclude("META-INF/mods.toml", "fabric.mod.json", "mcmod.info", "pack.mcmeta", "META-INF/*/variants.json")
    val commonJar = common.tasks.named<Jar>("jar")
    dependsOn(commonJar)
    from(commonJar.map { zipTree(it.archiveFile) })
    relocate("com.crystalgraphics.mc.modern.platform", "$cgThinRoot.platform")
    relocate(loaderPackage, nodeRoot) { exclude(bootstrapper) }
}

// Nothing in the common node may be NAMED from a descriptor or a service file: the relocation above
// rewrites class references, never a name in mods.toml, fabric.mod.json or META-INF/services.
registerCheckDescriptorsNameNoCommon(listOf("com.crystalgraphics.mc.modern.platform"))

// ── The thin-jar check, registered once for every 1.20.x loader ──────────────────────────────────
//
// WHAT A THIN JAR MAY CONTAIN IS THE PROJECT'S ANSWER, not each loader's, so it is stated here rather
// than in all three. A loader supplies only its own jar. The task is shared with every project on
// this build — ../../singlejar-logic.
tasks.register<cgbuildlogic.CheckThinJar>("checkThinJar") {
    allowedPrefixes.set(listOf("com/crystalgraphics/mc/"))
    // The node's own Java, never above it: a thin jar is this node's Minecraft's bytecode until the merge
    // downgrades everything to 52. Java N is class-file major N + 44.
    maxClassMajor.set(nodeJava + 44)
    // What CrystalGraphics merges at the ROOT, and so must not be here.
    forbiddenPrefixes.set(listOf(
        "com/crystalgraphics/core/", "com/crystalgraphics/api/", "com/crystalgraphics/gl/",
        "com/crystalgraphics/text/", "com/crystalgraphics/platform/", "org/joml/",
        "com/fasterxml/", "de/javagl/", "natives/",
    ))
    logTag.set("cg")
}

tasks.named("check") { dependsOn("checkThinJar") }

// A DEV RUN HAS TO SEE A VARIANT TABLE (J11.0), because the bootstrapper its descriptor names reads one
// -- and it must be THIS NODE'S, at source names: a dev run (and a consumer's dev run, which takes this
// node's classes unrelocated) would not resolve the merged table's names. On Fabric it also takes the
// merged fabric.mod.json, which names only the bootstrapper and ORs every node's range.
registerNodeVariants(modDescriptors.getValue("main"))
// NeoForge the same way: its merged mods.toml AND neoforge.mods.toml, the only file NeoForge 20.5+ reads.
// Forge takes the merged mods.toml too, whose hull covers every Forge node.
val mergedDevDescriptors = mapOf(
    "fabric" to listOf("fabric.mod.json"),
    "forge" to listOf("META-INF/mods.toml"),
    "neoforge" to listOf("META-INF/mods.toml", "META-INF/neoforge.mods.toml"),
)[modernLoader]
if (mergedDevDescriptors != null) {
    tasks.named<ProcessResources>("processResources") {
        val descriptors = rootProject.tasks.named("generateMergedDescriptors")
        dependsOn(descriptors)
        from(descriptors) { include(mergedDevDescriptors) }
    }
}

// :runtime:mc:shared IS A LIBRARY ON A DEV RUN, NOT A MOD (J11.0).
//
// It carries the variant selector the bootstrapper calls, and nothing in it is annotated, so it has to
// be on the run's classpath without being scanned as a mod. `additionalRuntimeClasspath` is
// ModDevGradle's own channel for exactly that.
//
// afterEvaluate, and not `plugins.withId`: ModDevGradle creates this configuration while the
// legacyForge/neoForge EXTENSION is configured, not when its plugin is applied, so a hook at apply
// time fails with "Configuration with name 'additionalRuntimeClasspath' not found". Loom never has
// one, which is what `findByName` answers for.
afterEvaluate {
    configurations.findByName("additionalRuntimeClasspath")?.let { runtime ->
        dependencies.add(runtime.name, project(":runtime:mc:shared"))
    }
}
