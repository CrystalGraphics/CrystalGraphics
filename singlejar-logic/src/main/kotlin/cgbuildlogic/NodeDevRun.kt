package cgbuildlogic

import net.neoforged.moddevgradle.dsl.ModDevExtension
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.language.jvm.tasks.ProcessResources

/**
 * What a node's dev run reads that its thin jar must not carry: the merge writes these once, for every node.
 *
 * ```kotlin
 * tasks.register<ShadowJar>("thinShadowJar") { from(main.output); exclude(DEV_DESCRIPTORS) }
 * ```
 */
val DEV_DESCRIPTORS = listOf("META-INF/mods.toml", "META-INF/neoforge.mods.toml", "fabric.mod.json",
    "mcmod.info", "pack.mcmeta", "META-INF/*/variants.json")

/**
 * A loader node's dev client and server, in the node's own `runs/`: the mod is this node, its common node
 * and [bundled]; its descriptors are the merged ones plus this node's variant table at source names.
 *
 * ```kotlin
 * // a loader node, after its toolchain
 * registerNodeDevRun(descriptor, bundled = listOf(project(":core")))
 * ```
 * ```bash
 * ./gradlew :runtime:mc:modern:fabric:1.20.1:runClient
 * ./gradlew :runtime:mc:modern:forge:1.20.1:runClient -Dcrystalgui.autotest=true   # forwarded to the game
 * ```
 *
 * - ModDevGradle (NeoForge; Forge to 1.20.1) and Loom (Fabric). A node built from parts (Forge from
 *   1.20.2, NeoForge 20.2 and 20.3) or through Unimined has no dev run; prodSmoke is its runtime check.
 * - Registers nothing in stub mode: requesting a run task is what makes a node real.
 * - Every `-D` system property on the Gradle command line starting with one of [forwardProperties] reaches
 *   the game.
 * - The mods it depends on are the caller's to put on the run: `com.crystalgui` puts CrystalGUI's and
 *   CrystalGraphics' there.
 * - The thin jar excludes [DEV_DESCRIPTORS], or a node's dev copy wins over the merged one.
 */
fun Project.registerNodeDevRun(descriptor: ModDescriptor, bundled: List<Project> = emptyList(),
                               descriptorsTask: String = "generateMergedDescriptors",
                               forwardProperties: List<String> = listOf("crystalgui.", "crystalgraphics.")) {
    if (stubMode) return
    val forwarded = System.getProperties().stringPropertyNames()
        .filter { name -> forwardProperties.any(name::startsWith) }
        .associateWith { System.getProperty(it) }
    val main = extensions.getByType(SourceSetContainer::class.java).getByName("main")

    registerNodeVariants(descriptor)
    tasks.named(main.processResourcesTaskName, ProcessResources::class.java).configure {
        val merged = rootProject.tasks.named(descriptorsTask)
        dependsOn(merged)
        from(merged) { include("META-INF/mods.toml", "META-INF/neoforge.mods.toml", "fabric.mod.json", "pack.mcmeta") }
    }

    val loom = extensions.findByName("loom")
    if (loom != null) {
        // Knot loads what the run's classpath holds, so the rest of the mod rides on runtimeOnly.
        (listOf(commonNode) + bundled).forEach { dependencies.add("runtimeOnly", it) }
        @Suppress("UNCHECKED_CAST")
        val runs = loom.javaClass.getMethod("getRuns").invoke(loom) as NamedDomainObjectContainer<Any>
        runs.configureEach {
            javaClass.getMethod("runDir", String::class.java).invoke(this, "runs/${javaClass.getMethod("getName").invoke(this)}")
            forwarded.forEach { (key, value) ->
                javaClass.getMethod("property", String::class.java, String::class.java).invoke(this, key, value)
            }
        }
        return
    }

    val moddev = extensions.findByType(ModDevExtension::class.java) ?: return
    val fromParts = findProperty("neoform.version") != null
    if (fromParts) return
    moddev.mods.create(descriptor.id) {
        sourceSet(main)
        sourceSet(devRunSourceSet(commonNode))
        bundled.forEach { sourceSet(devRunSourceSet(it)) }
    }
    for (side in listOf("client", "server")) {
        moddev.runs.create(side) {
            if (side == "client") client() else server()
            gameDirectory.set(layout.projectDirectory.dir("runs/$side"))
            forwarded.forEach { (key, value) -> systemProperty(key, value) }
        }
    }
}
