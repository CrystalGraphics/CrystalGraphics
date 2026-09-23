package cgbuildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.AbstractArchiveTask

/**
 * Loader nodes no ModDevGradle mode sets up, built from parts: MinecraftForge from 1.20.2, and NeoForge
 * 20.2-20.3 ([useNeoForgeApi]).
 *
 * MinecraftForge from 1.20.2, where ModDevGradle's legacy mode stops. A Forge node there compiles like
 * a `common` node -- vanilla Minecraft at official names through NeoForm -- with Forge's own jars on
 * compileOnly, and pins both:
 *
 * ```properties
 * neoform.version = 1.20.4-20240627.114801   # Minecraft, through useModernMinecraft
 * forge.version = 49.2.9                     # what the loader classes compile against
 * mcp.version = 1.20.4-20231207.112700       # SRG names, below 1.20.6 only (@see forgeRunsSrg)
 * ```
 *
 * ```kotlin
 * useModernMinecraft()
 * useForgeApi()
 * val thin = if (forgeRunsSrg(name)) registerSrgReobf("thinShadowJar", "thin", compileClasspath)
 *            else tasks.named<AbstractArchiveTask>("thinShadowJar")
 * ```
 *
 * - Such a node has no dev run: that comes from Forge's userdev, which nothing on Gradle 9 sets up
 *   past 1.20.1. prodSmoke is its runtime check.
 */

/** The renamer ModDevGradle's legacy reobfuscation runs, and [SrgReobfJar] runs the same one. */
const val AUTO_RENAMING_TOOL = "net.neoforged:AutoRenamingTool:2.0.17:all"

/**
 * Forge's own jars, compileOnly on `main` and on `lang` when the node has one.
 *
 * - Without ASM: Forge 53+ asks for a newer one than NeoForm pins strictly, and Minecraft brings its own.
 */
fun Project.useForgeApi() {
    repositories.maven { name = "Forge"; setUrl("https://maven.minecraftforge.net/") }
    val forge = "${property("mc.version")}-${property("forge.version")}"
    val api = listOf(
        "net.minecraftforge:forge:$forge:universal",
        "net.minecraftforge:fmlcore:$forge",
        "net.minecraftforge:fmlloader:$forge",
        "net.minecraftforge:javafmllanguage:$forge",
    )
    for (configuration in listOf("compileOnly", "langCompileOnly")) {
        if (configurations.findByName(configuration) == null) continue
        api.forEach { (dependencies.add(configuration, it) as ModuleDependency).exclude(mapOf("group" to "org.ow2.asm")) }
    }
}

/**
 * NeoForge's own jars compileOnly, for a NeoForge node ModDevGradle cannot set up (20.2, 20.3): its
 * `universal` jar, FancyModLoader and the event bus, at the versions its POM names. Such a node pins
 * them beside `neoform.version`, and runs Mojang's names, so nothing is reobfuscated.
 *
 * ```properties
 * neoforge.version = 20.2.93
 * neoforge.fml = 1.0.16
 * neoforge.bus = 7.2.0
 * ```
 *
 * - Non-transitive: the POM also lists Minecraft's libraries at versions NeoForm pins strictly (slf4j
 *   2.0.9 against 2.0.7), which one classpath cannot hold.
 */
fun Project.useNeoForgeApi() {
    val fml = property("neoforge.fml")
    val api = listOf(
        "net.neoforged:neoforge:${property("neoforge.version")}:universal@jar",
        "net.neoforged.fancymodloader:loader:$fml",
        "net.neoforged.fancymodloader:core:$fml",
        "net.neoforged.fancymodloader:language-java:$fml",
        "net.neoforged.fancymodloader:events:$fml",
        "net.neoforged:bus:${property("neoforge.bus")}",
    )
    for (configuration in listOf("compileOnly", "langCompileOnly")) {
        if (configurations.findByName(configuration) == null) continue
        api.forEach { (dependencies.add(configuration, it) as ModuleDependency).isTransitive = false }
    }
}

/**
 * Registers `reobf<ShadowTask>`: [shadowTask]'s jar at SRG members. [libraries] is what its classes
 * extend -- the source set's compile classpath.
 */
fun Project.registerSrgReobf(shadowTask: String, classifier: String, libraries: FileCollection): TaskProvider<SrgReobfJar> {
    val mcp = configurations.detachedConfiguration(
        dependencies.create("de.oceanlabs.mcp:mcp_config:${property("mcp.version")}@zip"))
    val renamer = configurations.detachedConfiguration(dependencies.create(AUTO_RENAMING_TOOL))
    val source = tasks.named(shadowTask, AbstractArchiveTask::class.java)
    val minecraftVersion = property("mc.version").toString()
    return tasks.register("reobf" + shadowTask.replaceFirstChar(Char::uppercaseChar), SrgReobfJar::class.java) {
        group = "build"
        description = "$shadowTask renamed to the SRG members Forge $minecraftVersion runs."
        from(source.map { zipTree(it.archiveFile) })
        exclude("META-INF/MANIFEST.MF")
        minecraft.set(minecraftVersion)
        mcpConfig.from(mcp)
        this.libraries.from(libraries)
        this.renamer.from(renamer)
        archiveClassifier.set(classifier)
    }
}
