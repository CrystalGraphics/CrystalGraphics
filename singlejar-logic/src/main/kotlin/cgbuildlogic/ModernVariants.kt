package cgbuildlogic

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
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
 *         fabricDepends = linkedMapOf("fabricloader" to ">=0.15.0", "fabric-api" to "*")),
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
            Variant(
                loader = loader, minecraft = range, era = "modern",
                commonEntry = e.common, clientEntry = e.client,
                packFormat = pin("variant.packFormat").toInt(),
                fabricDepends = if (loader != "fabric") emptyMap()
                    else LinkedHashMap(e.fabricDepends).apply { put("minecraft", McRange.parse(range).toFabricPredicate()) },
                node = node.path,
                relocation = e.loaderPackage to nodePackage(e.loaderPackage, node.name),
            )
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
