package cgbuildlogic

import org.gradle.api.GradleException
import java.util.Properties

/**
 * One node singlejar-logic knows how to build: a loader at one Minecraft version, the range it claims,
 * and its pins -- the toolchain versions its build script reads with `property(...)`.
 *
 * @property tree    [Catalog.MODERN], [Catalog.LEGACY] or [Catalog.HOST_1710]
 * @property branch  `forge`, `neoforge`, `fabric`, or `common` for the modern tree's shared half
 * @property version the node's name, and the Minecraft it is built against
 */
data class CatalogNode(val tree: String, val branch: String, val version: String, val pins: Map<String, String>) {
    /** What the node's variant runs on: `variant.minecraft`. The common branch ships no variant. */
    val claims: McRange? = pins["variant.minecraft"]?.let(McRange::parse)

    override fun toString(): String = "$tree/$branch/$version"
}

/**
 * The pin catalog: every node this build logic has built, booted and tested, shipped inside it as
 * `cgbuildlogic/catalog/<tree>/<branch>/<version>.properties`. A build names the versions it targets
 * ([SingleJarSettings.targets]) and gets these nodes with these pins; it writes no pins of its own.
 *
 * - A node's own `versions/<version>/gradle.properties`, when a build keeps one, is for what differs per
 *   PROJECT -- its mixin plugin class -- and never repeats a catalog key.
 * - Adding a Minecraft version is a file here, a stub database entry, and a range that reaches it.
 */
object Catalog {
    const val MODERN = "modern"
    const val LEGACY = "legacy"
    const val HOST_1710 = "1710"

    val nodes: List<CatalogNode> by lazy {
        val index = resource("index.txt")
            ?: throw GradleException("singlejar-logic ships no catalog index; its build is broken")
        index.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { path ->
            val (tree, branch, file) = path.split('/')
            val pins = Properties().apply { load(resource(path)!!.reader()) }
            CatalogNode(tree, branch, file.removeSuffix(".properties"),
                pins.stringPropertyNames().associateWith { pins.getProperty(it).trim() })
        }.sortedWith(compareBy<CatalogNode>({ it.tree }, { it.branch }).thenComparator { a, b ->
            MinecraftVersionOrder.compare(a.version, b.version)
        })
    }

    private fun resource(path: String): String? =
        Catalog::class.java.getResourceAsStream("catalog/$path")?.use { it.readBytes().toString(Charsets.UTF_8) }
}
