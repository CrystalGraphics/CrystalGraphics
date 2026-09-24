package cgbuildlogic

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.AbstractCopyTask
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.language.jvm.tasks.ProcessResources

/**
 * What the modern tree's nodes say about themselves, for the descriptors: one [Variant] per loader node.
 *
 * Every node ships its loader's classes RELOCATED into a package of its own, so two nodes of one loader
 * can share the merged jar ([nodePackage]); the loader's bootstrapper stays where it is, since it is
 * the one class that loader constructs whatever version is running. Entries are declared once, at their
 * SOURCE names, and each node declares its range and pack format in its own pins:
 *
 * ```properties
 * # runtime/mc/modern/fabric/versions/1.20.4/gradle.properties
 * variant.minecraft = [1.20.4,1.20.5)
 * variant.packFormat = 22
 * ```
 *
 * ```kotlin
 * // cg-descriptors
 * variants = listOf(Variant(loader = "fml1710", ...)) + modernVariants(project, mapOf(
 *     "forge" to LoaderEntries("com.example.mc.forge", common = "com.example.mc.forge.ExampleForge"),
 *     "fabric" to LoaderEntries("com.example.mc.fabric", common = "com.example.mc.fabric.ExampleCommon",
 *         fabricDepends = linkedMapOf("fabricloader" to ">=0.15.0", "fabric" to "*")),
 * ))
 *
 * // cg-modern-loader, for each source set whose classes a mod's entries live in
 * registerNodeVariants(descriptor)
 * ```
 *
 * - A loader node with no `variant.minecraft` or `variant.packFormat` fails configuration, naming it.
 * - Fabric's `minecraft` dependency is derived from the range; declaring one in [LoaderEntries] as well
 *   is overwritten.
 */
data class LoaderEntries(
    /** The package the loader's classes live in at source, and the one relocated per node. */
    val loaderPackage: String,
    val common: String? = null,
    val client: String? = null,
    val fabricDepends: Map<String, String> = emptyMap(),
)

/** Where a node's loader classes are shipped: the loader package plus `v<version digits>`. */
fun nodePackage(loaderPackage: String, version: String): String = "$loaderPackage.v${version.replace(".", "")}"

/** One [Variant] per node of each loader [entries] names, in [MODERN_LOADERS] order, oldest first. */
fun modernVariants(project: Project, entries: Map<String, LoaderEntries>): List<Variant> =
    MODERN_LOADERS.filter { it in entries }.flatMap { loader ->
        val e = entries.getValue(loader)
        modernNodes(project, loader).map { node ->
            fun pin(key: String): String = node.findProperty(key)?.toString()
                ?: throw GradleException("${node.path} pins no `$key` in its gradle.properties -- every loader " +
                    "node declares the Minecraft range it claims (variant.minecraft) and its pack format " +
                    "(variant.packFormat)")
            val range = pin("variant.minecraft")
            val shipped = nodePackage(e.loaderPackage, node.name)
            Variant(
                loader = loader, minecraft = range, era = "modern",
                commonEntry = e.common, clientEntry = e.client,
                mixinConfigs = if (node.hasProperty(MIXIN_PLUGIN)) listOf(nodeMixinConfig(shipped)) else emptyList(),
                packFormat = pin("variant.packFormat").toInt(),
                fabricDepends = if (loader != "fabric") emptyMap()
                    else LinkedHashMap(e.fabricDepends).apply { put("minecraft", McRange.parse(range).toFabricPredicate()) },
                node = node.path,
                relocation = e.loaderPackage to shipped,
            )
        }
    }

/**
 * The pin that gives a node mixins: the plugin gating its config, a Java 8 class the merge adds once.
 *
 * ```properties
 * # Forge 53 has no world-render event; its hook is a mixin
 * variant.mixinPlugin = com.crystalgraphics.mc.shared.CrystalGraphicsForgeMixins
 * ```
 *
 * The mixins themselves live in the branch's `mixin` package and are named by that plugin, never by
 * the config. @see registerNodeMixins
 */
const val MIXIN_PLUGIN = "variant.mixinPlugin"

/** The config a node ships its mixins under, named for its shipped package so no two nodes collide. */
fun nodeMixinConfig(shippedPackage: String): String = "mixins.$shippedPackage.json"

/**
 * Writes this node's mixin config into [jarTask] when its variant has one: the SHIPPED `mixin` package,
 * the pinned plugin, and empty lists.
 *
 * - Empty because Mixin parses every listed class before its plugin can refuse one, and the config is
 *   read on every loader the merged jar boots on. The plugin names the mixins. @see VariantMixins
 * - Into the shipped jar; and into `jar` and `shadowJar` at the source package, with every sibling
 *   config of the loader beside it inert, since a dev run reads the merged descriptor.
 * - `JAVA_8` whatever the node emits, because the merged jar is downgraded to 8 and every config in it
 *   is read on every loader: Mixin 0.8.4-0.8.5 (Forge through 1.20.x) know no level above `JAVA_18`,
 *   and a required config naming one stops the game before it writes an error.
 */
fun Project.registerNodeMixins(descriptor: ModDescriptor, jarTask: String) {
    val variant = descriptor.variants.single { it.node == path }
    val (sourcePackage, shippedPackage) = variant.relocation!!
    variant.mixinConfigs.singleOrNull()?.let { config ->
        val shipped = registerNodeMixinConfigs("generateNodeMixins", "node-mixins",
                mapOf(config to property(MIXIN_PLUGIN).toString()), shippedPackage)
        tasks.named(jarTask, AbstractCopyTask::class.java).configure { from(shipped) }
    }
    // A dev run reads the MERGED descriptor, which names every config of this loader: the unshaded jars
    // carry each at the source package -- this node's with its plugin, a sibling's inert.
    val siblings = descriptor.variants.filter { it.loader == variant.loader }.flatMap { v ->
        v.mixinConfigs.map { it to (if (v === variant) property(MIXIN_PLUGIN).toString() else null) }
    }.toMap()
    if (siblings.isEmpty()) return
    val dev = registerNodeMixinConfigs("generateDevNodeMixins", "node-mixins-dev", siblings, sourcePackage)
    tasks.matching { it.name == "jar" || it.name == "shadowJar" }.configureEach { (this as AbstractCopyTask).from(dev) }
}

/** Writes each config at `<loaderPackage>.mixin`, with its plugin or, for a null one, none. */
private fun Project.registerNodeMixinConfigs(task: String, dir: String, configs: Map<String, String?>,
                                             loaderPackage: String): TaskProvider<Task> {
    val files = configs.mapValues { (_, plugin) ->
        """
        |{
        |  "required": true,
        |  "minVersion": "0.8",
        |  "package": "$loaderPackage.mixin",
        |""".trimMargin() + (if (plugin == null) "" else "  \"plugin\": \"$plugin\",\n") + """
        |  "compatibilityLevel": "JAVA_8",
        |  "mixins": [],
        |  "client": [],
        |  "server": []
        |}
        |""".trimMargin()
    }
    val out = layout.buildDirectory.dir(dir)
    return tasks.register(task) {
        group = "build"
        description = "This node's mixin configs, at package $loaderPackage.mixin."
        inputs.property("files", files.toString())
        outputs.dir(out)
        doLast { files.forEach { (name, json) -> out.get().file(name).asFile.apply { parentFile.mkdirs() }.writeText(json) } }
    }
}

/** Every entry class [this] ships, as jar paths at their shipped names — for `requiredEntries`. */
fun ModDescriptor.shippedEntryPaths(): List<String> =
    variants.map { it.asShipped() }
        .flatMap { listOfNotNull(it.commonEntry, it.clientEntry) }
        .distinct()
        .map { it.replace('.', '/') + ".class" }

/**
 * Writes this node's own `META-INF/<modid>/variants.json` into [sourceSet]'s resources, at SOURCE names,
 * for its dev run — which loads the classes unrelocated, so the merged table's names would not resolve.
 *
 * Throws when [descriptor] has no variant for this node: a loader node every mod ships from must be in
 * that mod's declaration.
 */
fun Project.registerNodeVariants(descriptor: ModDescriptor, sourceSet: String = "main") {
    val variant = descriptor.variants.singleOrNull { it.node == path }
        ?: throw GradleException("$path has no ${descriptor.id} variant -- its loader is missing from the " +
            "LoaderEntries given to modernVariants")
    val table = VariantsJson.dev(descriptor, variant)
    val out = layout.buildDirectory.dir("node-variants/$sourceSet/${descriptor.id}")
    val suffix = if (sourceSet == "main") "" else sourceSet.replaceFirstChar(Char::uppercase)
    val generate = tasks.register("generate${suffix}NodeVariants") {
        group = "build"
        description = "This node's ${descriptor.id} variant table, at source names, for its dev run."
        inputs.property("table", table)
        outputs.dir(out)
        doLast {
            val file = out.get().file("META-INF/${descriptor.id}/variants.json").asFile
            file.parentFile.mkdirs()
            file.writeText(table)
        }
    }
    val processTask = extensions.getByType(SourceSetContainer::class.java).getByName(sourceSet).processResourcesTaskName
    tasks.named(processTask, ProcessResources::class.java).configure { from(generate) }
}
