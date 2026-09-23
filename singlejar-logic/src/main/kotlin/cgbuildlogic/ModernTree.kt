package cgbuildlogic

import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File

/**
 * The Stonecutter tree at `:runtime:mc:modern` — one BRANCH per loader, one NODE per Minecraft version
 * that loader targets — and the only place that knows how to find a node. Shared by every build laid
 * out this way: CrystalGraphics and everything built on it.
 *
 * A node is `:runtime:mc:modern:<branch>:<version>`, so on a node `project.name` is the VERSION and the
 * branch is the loader (or `common`). Adding a version is a `versions(...)` line in settings plus that
 * node's `versions/<version>/gradle.properties`; nothing that reads the tree through here needs an edit.
 *
 * ```kotlin
 * project.modernLoader              // "forge", on :runtime:mc:modern:forge:1.20.1
 * project.commonNode                // :runtime:mc:modern:common:1.20.1, for any 1.20.1 node
 * modernNodes(project, "fabric")    // every fabric node, oldest version first
 * modernLoaderNodes(project)        // every node that ships a thin jar
 * ```
 *
 * A build built ON another reaches the other build's node of the SAME loader and version:
 *
 * ```kotlin
 * project.sameVersionNodePath("common")                        // a composite task path
 * project.sameVersionNodeDir(graphicsBuildDir, "fabric")       // its directory, for build outputs
 * project.sameVersionNodeCoordinate("com.crystalgraphics", "common")   // what a substitution maps
 * ```
 *
 * - `commonNode` throws for a version `common` has no node for. A loader node must never borrow a
 *   common compiled against another Minecraft: it compiles, and tests nothing.
 * - Reading another project's TASKS at configuration time needs it evaluated first —
 *   `evaluationDependsOn(commonNode.path)`. Listing nodes does not: the hierarchy exists before any
 *   project is configured.
 */
const val MODERN_TREE = ":runtime:mc:modern"

/** The loaders that ship a thin jar, in merge order. `common` is a branch and not a loader. */
val MODERN_LOADERS = listOf("forge", "neoforge", "fabric")

/** The branch this node belongs to: its loader, or `common`. */
val Project.modernLoader: String
    get() = parent?.takeIf { it.parent?.path == MODERN_TREE }?.name
        ?: throw GradleException("$path is not a node of $MODERN_TREE")

/** The common node built against this node's Minecraft. */
val Project.commonNode: Project
    get() = rootProject.findProject("$MODERN_TREE:common:$name")
        ?: throw GradleException(
            "$path has no common node: add \"$name\" to the common branch in settings.gradle.kts. "
                + "A loader node may not compile against a common built for another Minecraft.")

/** Every node of one branch, oldest Minecraft first. Empty when the branch is absent (embedded builds). */
fun modernNodes(project: Project, branch: String): List<Project> =
    project.rootProject.findProject("$MODERN_TREE:$branch")?.childProjects?.values
        ?.sortedWith(compareBy(MinecraftVersionOrder) { it.name })
        .orEmpty()

/** Every node that ships a thin jar: each loader's nodes, in [MODERN_LOADERS] order. */
fun modernLoaderNodes(project: Project): List<Project> = MODERN_LOADERS.flatMap { modernNodes(project, it) }

/**
 * A node's Maven coordinates: group per BRANCH, name per version — `<modGroup>.mc.modern.<branch>`.
 *
 * Load-bearing, not tidy. Every node of a version shares a project name, so with one group for the
 * whole tree `forge:1.20.1` and `common:1.20.1` are one coordinate, Gradle resolves the loader's
 * dependency on common to the loader itself, and the build fails on `compileJava` depending on
 * `compileJava`.
 */
fun Project.useNodeCoordinates() {
    group = "${property("modGroup")}.mc.modern.$modernLoader"
    version = property("modVersion").toString()
}

/** The path of [branch]'s node at this node's version, in any build with this layout. */
fun Project.sameVersionNodePath(branch: String): String = "$MODERN_TREE:$branch:$name"

/** That node's directory, under [buildRoot] — the root of the build that owns it. */
fun Project.sameVersionNodeDir(buildRoot: File, branch: String): File =
    File(buildRoot, "runtime/mc/modern/$branch/versions/$name")

/**
 * That node's coordinates in the build whose `modGroup` is [groupRoot], per [useNodeCoordinates] —
 * versionless, because it only ever resolves through a composite substitution.
 */
fun Project.sameVersionNodeCoordinate(groupRoot: String, branch: String): String =
    "$groupRoot.mc.modern.$branch:$name"

/** `1.20.1` < `1.20.4` < `1.21`: numeric per component, a missing component counting as zero. */
object MinecraftVersionOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val left = a.split('.').map { it.toIntOrNull() ?: 0 }
        val right = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(left.size, right.size)) {
            val c = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}
