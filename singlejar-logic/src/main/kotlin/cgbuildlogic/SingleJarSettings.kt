package cgbuildlogic

import dev.kikugie.stonecutter.settings.StonecutterSettingsExtension
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle
import java.io.File
import javax.inject.Inject

/** Where a consumer names the ONE target it includes this build for: `loader:minecraft`, e.g. `forge:1.20.1`. */
const val CHECKOUT_TARGET = "singlejar.checkout.target"

/** `com.crystalgraphics.singlejar`: a build's Minecraft nodes, from the versions it declares. @see SingleJarSettings */
class SingleJarSettingsPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        settings.extensions.create("singlejar", SingleJarSettings::class.java, settings)
        // A build that does not hold singlejar-logic still compiles its nodes against these stubs.
        stubDatabaseBeside()?.let { settings.gradle.extensions.extraProperties.set(STUB_DATABASE, it) }
    }
}

/**
 * The `singlejar` block of a settings script: the Minecraft versions a build targets, which become its
 * nodes -- the Stonecutter trees, the 1.7.10 host, and every node's pins from the [Catalog].
 *
 * ```kotlin
 * // settings.gradle.kts
 * pluginManagement { includeBuild("CrystalGraphics/singlejar-logic") }
 * plugins {
 *     id("dev.kikugie.stonecutter") version "0.9.8"
 *     id("com.crystalgraphics.singlejar")
 * }
 * singlejar {
 *     targets {
 *         forge("1.7.10".."1.21.11")      // 1.7.10's host, the legacy tree and the modern forge branch
 *         neoforge("1.20.2".."1.21.11")
 *         fabric("1.20.1", "1.21.11")     // single versions: the node claiming each
 *     }
 * }
 * ```
 *
 * The same block in a Groovy script takes a range as `forge(between('1.16.5', '1.21.11'))`.
 *
 * - A range selects every node whose claimed range touches it; a single version selects the node claiming
 *   it, and fails naming what is claimed when none does.
 * - The modern tree's `common` branch is derived: one common node per selected loader node's version.
 * - Nodes live at `runtime/mc/<tree>/<branch>/versions/<version>/`, created when missing; a
 *   `gradle.properties` there adds per-project keys; one repeating a pin wins over the catalog.
 * - When another build includes this one, only what it can configure is selected: the node claiming the
 *   target it names in [CHECKOUT_TARGET], else the modern forge node claiming 1.20.1.
 * - Declared once, before anything reads the nodes -- [modernNodes] is empty until then.
 * - Nodes compile against the stub database shipped beside this build logic (@see stubMode), so a build
 *   holding no copy of singlejar-logic still needs no Minecraft toolchain to build its jar.
 */
abstract class SingleJarSettings @Inject constructor(private val settings: Settings) {

    /** The modern tree by branch, `common` first: what this build has. Empty before [targets]. */
    var modernNodes: Map<String, List<String>> = emptyMap()
        private set

    /** Resolves [action]'s versions against the catalog and creates the nodes. */
    fun targets(action: Action<Targets>) {
        if (declared) throw GradleException("singlejar: targets { } may be declared once")
        declared = true
        configure(select(Targets().also(action::execute).resolve()))
    }

    private var declared = false

    private fun select(declared: List<CatalogNode>): List<CatalogNode> {
        if (invokedHere()) return declared
        val target = System.getProperty(CHECKOUT_TARGET)
        if (target != null) {
            val loader = target.substringBefore(':')
            val minecraft = target.substringAfter(':')
            val node = declared.firstOrNull { it.branch == loader && it.claims?.contains(minecraft) == true }
                ?: throw GradleException("singlejar: ${settings.rootProject.name} declares no $loader node claiming " +
                    "Minecraft $minecraft, which the build including it targets")
            settings.gradle.extensions.extraProperties.set(CONSUMER_CHECKOUT, true)
            return listOf(node)
        }
        return declared.filter { it.tree == Catalog.MODERN && it.branch == "forge" && it.claims?.contains("1.20.1") == true }
    }

    /**
     * Whether this build is the one being worked on: invoked from inside it, or from inside a build that
     * contains it -- CrystalGUI, for CrystalGraphics. Nesting depth cannot tell: Gradle flattens a
     * composite, so every included build reports the root as its parent.
     */
    private fun invokedHere(): Boolean {
        if (settings.gradle.parent == null) return true
        val invoked = generateSequence(settings.gradle, Gradle::getParent).last().startParameter.currentDir.canonicalFile
        val ours = settings.settingsDir.canonicalFile
        val parent = ours.parentFile
        fun under(dir: File) = invoked.toPath().startsWith(dir.toPath())
        return under(ours) || parent != null && File(parent, "settings.gradle.kts").isFile && under(parent)
    }

    private fun configure(selected: List<CatalogNode>) {
        val loaders = MODERN_LOADERS.associateWith { branch ->
            selected.filter { it.tree == Catalog.MODERN && it.branch == branch }.map { it.version }
        }.filterValues { it.isNotEmpty() }
        val common = loaders.values.flatten().distinct().sortedWith(MinecraftVersionOrder).onEach { version ->
            if (Catalog.nodes.none { it.tree == Catalog.MODERN && it.branch == "common" && it.version == version })
                throw GradleException("singlejar: the catalog has no common node for $version")
        }
        modernNodes = if (common.isEmpty()) emptyMap() else linkedMapOf("common" to common) + loaders
        val legacy = selected.filter { it.tree == Catalog.LEGACY }.map { it.version }

        val nodes = modernNodes.flatMap { (branch, versions) -> versions.map { Triple(Catalog.MODERN, branch, it) } } +
            legacy.map { Triple(Catalog.LEGACY, "forge", it) }
        nodes.forEach { (tree, branch, version) ->
            File(settings.settingsDir, "runtime/mc/$tree/$branch/versions/$version").mkdirs()
        }

        val stonecutter = settings.extensions.getByType(StonecutterSettingsExtension::class.java)
        if (modernNodes.isNotEmpty()) stonecutter.create("runtime:mc:modern") {
            // Every branch declares its versions and the tree none: a tree-level version is inherited by a
            // branch naming none, and is also a node of the tree itself, with no script, building nothing.
            modernNodes.forEach { (branch, versions) -> branch(branch) { versions(versions) } }
        }
        if (legacy.isNotEmpty()) stonecutter.create("runtime:mc:legacy") {
            branch("forge") { versions(legacy) }
        }
        if (selected.any { it.tree == Catalog.HOST_1710 }) settings.include(HOST_1710.removePrefix(":"))

        val pins = nodes.associate { (tree, branch, version) ->
            ":runtime:mc:$tree:$branch:$version" to Catalog.nodes.single {
                it.tree == tree && it.branch == branch && it.version == version
            }.pins
        }
        settings.gradle.projectsLoaded {
            rootProject.allprojects {
                pins[path]?.forEach { (key, value) ->
                    if (!hasProperty(key)) extensions.extraProperties.set(key, value)
                }
            }
        }
    }
}

/**
 * The versions a build targets, by loader. Each call adds; the nodes are the union.
 *
 * @see SingleJarSettings
 */
class Targets {
    private val requests = mutableListOf<Pair<String, Any>>()

    /** The Forge node claiming each version: 1.7.10's host, a legacy plateau, or a modern node. */
    fun forge(vararg versions: String) { versions.forEach { requests += "forge" to it } }

    /** Every Forge node whose claimed range touches [range]. */
    fun forge(range: ClosedRange<String>) { requests += "forge" to range }

    fun neoforge(vararg versions: String) { versions.forEach { requests += "neoforge" to it } }
    fun neoforge(range: ClosedRange<String>) { requests += "neoforge" to range }
    fun fabric(vararg versions: String) { versions.forEach { requests += "fabric" to it } }
    fun fabric(range: ClosedRange<String>) { requests += "fabric" to range }

    /** A range, for a script without Kotlin's `..`: `forge(between('1.16.5', '1.21.11'))`. */
    fun between(from: String, to: String): ClosedRange<String> = from..to

    internal fun resolve(): List<CatalogNode> = requests.flatMap { (loader, request) ->
        val candidates = Catalog.nodes.filter { it.branch == loader && it.claims != null }
        when (request) {
            is String -> listOf(candidates.firstOrNull { it.claims!!.contains(request) }
                ?: throw GradleException("singlejar: no $loader node claims Minecraft $request. Claimed: " +
                    candidates.joinToString { "${it.claims}" }))
            else -> {
                @Suppress("UNCHECKED_CAST")
                val range = request as ClosedRange<String>
                val wanted = McRange.parse("[${range.start},${range.endInclusive}]")
                candidates.filter { it.claims!!.overlaps(wanted) }.ifEmpty {
                    throw GradleException("singlejar: no $loader node claims anything in $wanted")
                }
            }
        }
    }.distinct()
}
