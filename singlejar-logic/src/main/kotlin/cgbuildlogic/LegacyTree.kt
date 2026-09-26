package cgbuildlogic

import org.gradle.api.GradleException
import org.gradle.api.Project

/**
 * The Stonecutter tree at `:runtime:mc:legacy` — Forge on LaunchWrapper, 1.8 to 1.12.2 — and what its
 * nodes say about themselves. One branch, `forge`, one node per SRG plateau; a node ships its classes
 * under `…mc.v<version digits>`, the package 1.7.10 already takes at `…mc.v1710`.
 *
 * ```properties
 * # runtime/mc/legacy/forge/versions/1.12.2/gradle.properties
 * mc.version = 1.12.2
 * forge.version = 14.23.5.2859
 * mcp.version = 1.12.2                  # joined.srg, for the SRG rename
 * mcp.mappings = stable:39-1.12         # the MCP names the node compiles against
 * variant.minecraft = [1.12,1.13)
 * variant.packFormat = 3
 * ```
 *
 * ```kotlin
 * // cg-descriptors
 * variants = ... + legacyVariants(project, LegacyEntries("com.example.mc.legacy",
 *     common = "com.example.mc.legacy.ExampleLegacy"))
 *
 * // cg-single-jar
 * thinJars = ... + legacyNodes(project).map { it.path to "reobfThinShadowJar" }
 * ```
 *
 * - Every node is `fml1122` to the bootstrapper, whatever its Minecraft: `LoaderProbe` answers by
 *   launcher, and the range picks the node.
 * - The source package's parent is where the shipped one goes, so [LegacyEntries.sourcePackage] must
 *   be `<root>.mc.legacy`: `com.example.mc.legacy` ships as `com.example.mc.v1122`.
 */
const val LEGACY_TREE = ":runtime:mc:legacy"

/** Every legacy node, oldest Minecraft first. Empty when the tree is absent. */
fun legacyNodes(project: Project): List<Project> =
    project.rootProject.findProject("$LEGACY_TREE:forge")?.childProjects?.values
        ?.sortedWith(compareBy(MinecraftVersionOrder) { it.name })
        .orEmpty()

/** Where a legacy node's classes ship: [sourcePackage]'s parent plus `v<version digits>`. */
fun legacyNodePackage(sourcePackage: String, version: String): String =
    nodePackage(sourcePackage.substringBeforeLast('.'), version)

/** A mod's legacy entry classes, at source names. */
data class LegacyEntries(
    /** `<root>.mc.legacy`: the package every legacy class lives in at source, and the one relocated. */
    val sourcePackage: String,
    val common: String? = null,
    val client: String? = null,
    /** Whether this mod ships its nodes' `variant.mixinPlugin` configs; off for a second mod of the same nodes. */
    val mixins: Boolean = true,
)

/** One `fml1122` [Variant] per legacy node, oldest first, from each node's `variant.*` pins. */
fun legacyVariants(project: Project, entries: LegacyEntries): List<Variant> =
    legacyNodes(project).map { node ->
        fun pin(key: String): String = node.findProperty(key)?.toString()
            ?: throw GradleException("${node.path} pins no `$key` in its gradle.properties -- every legacy " +
                "node declares the Minecraft range it claims (variant.minecraft) and its pack format " +
                "(variant.packFormat)")
        val shipped = legacyNodePackage(entries.sourcePackage, node.name)
        Variant(
            loader = "fml1122", minecraft = pin("variant.minecraft"), era = "legacy",
            commonEntry = entries.common, clientEntry = entries.client,
            mixinConfigs = if (entries.mixins && node.hasProperty(MIXIN_PLUGIN)) listOf(nodeMixinConfig(shipped)) else emptyList(),
            packFormat = pin("variant.packFormat").toInt(),
            node = node.path,
            relocation = entries.sourcePackage to shipped,
        )
    }
