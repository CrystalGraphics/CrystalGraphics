package cgbuildlogic

import net.neoforged.moddevgradle.dsl.NeoForgeExtension
import net.neoforged.moddevgradle.legacyforge.dsl.LegacyForgeExtension
import net.neoforged.nfrtgradle.CreateMinecraftArtifacts
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import java.io.File

/**
 * What every build laid out as [MODERN_TREE] does the same way, so no build decides it twice.
 *
 * ```kotlin
 * // a `common` node's convention plugin
 * useNodeCoordinates()
 * useModernMinecraft()          // Minecraft on the classpath, toolchain chosen by the node's pins
 * guardLoaderImports()          // after the source sets exist
 *
 * // a loader node's
 * registerCheckDescriptorsNameNoCommon(listOf("com.example.mc.modern"))
 *
 * // the root
 * registerCheckAllTargets()
 * ```
 */

/**
 * Puts this `common` node's Minecraft on its classpath, choosing the ModDevGradle mode from the node's
 * own pins — because no single mode reaches every version:
 *
 * - `neoform.version`: NeoForm mode, Minecraft alone at official names and no loader at all. Exists
 *   from 1.20.2; NeoForm published no 1.20.1 artifact.
 * - `forge.version`: legacyForge, Forge's userdev — the one ModDevGradle route to 1.17–1.20.1. It puts
 *   Forge on compileOnly, which is what [guardLoaderImports] is for.
 * - both, on a Forge node from 1.20.2: NeoForm wins, and [useForgeApi] adds Forge's jars.
 * - `minecraft.loom` or `minecraft.unimined`, below 1.17: nothing here; the branch script owns it.
 *
 * Throws when the node pins neither, naming it.
 */
fun Project.useModernMinecraft() {
    val mcVersion = property("mc.version").toString()
    val neoFormPin = findProperty("neoform.version")?.toString()
    val forgePin = findProperty("forge.version")?.toString()
    // Below 1.17 neither ModDevGradle mode reaches: the branch script applies Loom (a common node) or
    // Unimined (a Forge node) itself, since only that branch's classloader may carry either.
    if (usesLoomMinecraft || usesUniminedMinecraft) return
    when {
        neoFormPin != null -> {
            pluginManager.apply("net.neoforged.moddev")
            extensions.configure(NeoForgeExtension::class.java) { setNeoFormVersion(neoFormPin) }
        }
        forgePin != null -> {
            pluginManager.apply("net.neoforged.moddev.legacyforge")
            extensions.configure(LegacyForgeExtension::class.java) { setVersion("$mcVersion-$forgePin") }
            // ModDevGradle runs its decompile tools on Minecraft's own Java, which for 1.17 is 16 and is
            // not installed; 17 runs them the same.
            if (MinecraftVersionOrder.compare(mcVersion, "1.18") < 0) {
                val java17 = extensions.getByType(JavaToolchainService::class.java)
                    .launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }
                    .map { it.executablePath.asFile.absolutePath }
                afterEvaluate {
                    tasks.withType(CreateMinecraftArtifacts::class.java).configureEach { toolsJavaExecutable.set(java17) }
                }
            }
        }
        else -> throw GradleException(
            "$path pins neither neoform.version nor forge.version in its gradle.properties, so there is "
                + "no toolchain to put Minecraft $mcVersion on its classpath.")
    }
}

/** Pinned `minecraft.loom = true`: a common node below 1.17, vanilla through Loom. */
val Project.usesLoomMinecraft: Boolean
    get() = findProperty("minecraft.loom")?.toString() == "true"

/** Pinned `minecraft.unimined = true`: a Forge node below 1.17, through Unimined. */
val Project.usesUniminedMinecraft: Boolean
    get() = findProperty("minecraft.unimined")?.toString() == "true"

/**
 * Mojang-shaped names for a Minecraft Mojang published none for (1.13.2, 1.14.3), generated from 1.14.4's through
 * SRG ids by `runtime/mc/modern/mappings/backport_mojmap.py`; null where Mojang's own exist.
 *
 * ```kotlin
 * mappings { backportedMojmap()?.let { mapping(it, "mojmap") { requires("official"); provides("mojmap" to true) } } ?: mojmap() }
 * ```
 */
fun Project.backportedMojmap(): File? {
    val path = "runtime/mc/modern/mappings/mojmap-${property("mc.version")}.tsrg"
    return listOf(rootDir.resolve(path), rootDir.resolve("CrystalGraphics/$path")).firstOrNull { it.isFile }
}

/**
 * The Java this node builds for: its Minecraft's -- 17 up to 1.20.4, 21 from 1.20.5 -- pinned as
 * `java.version` in the node's gradle.properties, 17 when unpinned.
 *
 * ```kotlin
 * options.release.set(nodeJava)                   // what javac emits
 * maxClassMajor.set(nodeJava + 44)                // the class-file major a thin jar may carry
 * ```
 */
val Project.nodeJava: Int
    get() = findProperty("java.version")?.toString()?.toInt() ?: 17

/** Loader packages, none of which a `common` node may import. */
val LOADER_PACKAGES = listOf("net.minecraftforge.", "net.neoforged.", "net.fabricmc.", "cpw.mods.fml.")

/**
 * Fails `compileJava` when a source javac compiled imports a loader. `common` is shared by every
 * loader, so a Forge import compiles on a legacyForge node and throws NoClassDefFoundError on the
 * other two.
 *
 * It reads the task's OWN sources — what javac was handed for this node, directives applied. A path
 * under the project directory is a node's `versions/<v>/src`, which does not exist, so a guard reading
 * it passes having read nothing.
 */
fun Project.guardLoaderImports() {
    val owner = path
    tasks.named("compileJava", JavaCompile::class.java).configure {
        val sources = source
        doLast {
            val java: List<java.io.File> = sources.files.filter { it.extension == "java" }
            if (java.isEmpty()) throw GradleException("$owner compiled no Java, so the loader-import guard read nothing")
            val violations: List<Pair<String, String>> = java.mapNotNull { file ->
                file.readLines().map { it.trimStart() }
                    .firstOrNull { line -> line.startsWith("import ") && LOADER_PACKAGES.any { line.contains(it) } }
                    ?.let { hit -> file.name to hit }
            }
            if (violations.isNotEmpty()) throw GradleException(
                "$owner imports a loader, and every loader shares it -- it may name net.minecraft.*, " +
                    "com.mojang.* and org.lwjgl.* but nothing from Forge, NeoForge or Fabric:\n" +
                    violations.joinToString("\n") { (name, line) -> "  $name\n      $line" })
        }
    }
}

/**
 * Registers `checkDescriptorsNameNoCommon` and hangs it on `check`: fails when a descriptor or service
 * file names one of [relocatedPackages].
 *
 * The thin jar relocates the common node's classes, and relocation rewrites class references but not a
 * name sitting in `mods.toml`, `fabric.mod.json` or `META-INF/services` — so such a name points at a
 * class that no longer exists under that spelling, on every loader, silently.
 */
fun Project.registerCheckDescriptorsNameNoCommon(relocatedPackages: List<String>) {
    val resources = extensions.getByType(SourceSetContainer::class.java).getByName("main").resources
    val check = tasks.register("checkDescriptorsNameNoCommon") {
        group = "verification"
        description = "Fails if a descriptor or service file names a class that the thin jar relocates."
        // The SOURCE SET's resources, never a path under the project directory -- see guardLoaderImports.
        inputs.files(resources).withPropertyName("resources")
        outputs.upToDateWhen { true }
        doLast {
            val hits: List<Pair<String, String>> = resources.files.flatMap { file ->
                val text = runCatching { file.readText() }.getOrDefault("")
                relocatedPackages.filter { text.contains(it) }.map { pkg -> file.name to pkg }
            }
            if (hits.isNotEmpty()) throw GradleException(
                "A descriptor or service file names a package the thin jar relocates, so the name will be " +
                    "wrong on every loader:\n" + hits.joinToString("\n") { (name, pkg) -> "  $name  names  $pkg" })
        }
    }
    tasks.named("check").configure { dependsOn(check) }
}

/**
 * Registers `checkAllTargets` on the root: every node of every branch compiled, every source set.
 *
 * The rule it enforces is that a change is compiled against EVERY Minecraft version before it is
 * committed, not only the active node's — a break that holds for the active version alone is invisible
 * until somebody else builds. Reads the tree, which configures no node.
 */
fun Project.registerCheckAllTargets() {
    val nodes = (listOf("common") + MODERN_LOADERS).flatMap { modernNodes(this, it) }
    tasks.register("checkAllTargets") {
        group = "verification"
        description = "Compiles every node of $MODERN_TREE -- every Minecraft version, every loader."
        // Every compile task a node has, whichever source sets that build gives its nodes.
        dependsOn(nodes.map { node -> node.tasks.withType(JavaCompile::class.java) })
    }
}
