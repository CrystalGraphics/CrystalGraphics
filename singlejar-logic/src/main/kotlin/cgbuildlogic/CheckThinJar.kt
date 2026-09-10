package cgbuildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import java.util.zip.ZipFile

/**
 * Fails unless a thin jar contains this loader and nothing else.
 *
 * <p>A thin jar is one input to the single-jar merge: the loader's own classes plus the shared
 * vanilla host, relocated. Everything a project ships beside those — its engine, its libraries, its
 * bundled resources — enters the merge <b>once, at the root</b>, so a copy here would be shipped once
 * per loader and would defeat the whole exercise. Nothing at runtime would notice, which is why this
 * is a build failure rather than a review note.</p>
 *
 * <pre>
 * tasks.register&lt;CheckThinJar&gt;("checkThinJar") {
 *     jar.set(reobfThinJar.flatMap { it.archiveFile })
 *     allowedPrefixes.set(listOf("com/myproject/mc/"))
 *     // What must NOT be here. Project-specific by nature: it names what THIS project merges at the
 *     // root, so there is no useful default and an empty list only checks the class prefixes above.
 *     forbiddenPrefixes.set(listOf("com/myproject/engine/", "org/joml/", "assets/myproject/big/"))
 * }
 * </pre>
 *
 * Class-file versions are checked too: the merged jar is downgraded as a whole, but a thin jar above
 * the ceiling means the loader itself was compiled wrong, which is cheaper to catch here than after
 * FML 1.7.10's ASM 5 scanner calls the jar "probably a corrupt zip".
 */
abstract class CheckThinJar : DefaultTask() {

    /** The production-mapped thin jar. */
    @get:InputFile
    abstract val jar: RegularFileProperty

    /** Entry prefixes every class in the jar must start with. */
    @get:Input
    abstract val allowedPrefixes: ListProperty<String>

    /**
     * Entry prefixes that must not appear at all — what this project merges at the root.
     *
     * <p>No default, because a useful one cannot exist here: it would have to name another project's
     * libraries. Empty means only {@link #allowedPrefixes} is enforced, which catches stray CLASSES
     * and not stray resources.</p>
     */
    @get:Input
    abstract val forbiddenPrefixes: ListProperty<String>

    /** Prefix for this task's own log line, e.g. `cgui`. */
    @get:Input
    abstract val logTag: Property<String>

    /** Highest class-file major version permitted. 52 is Java 8, 61 is Java 17. */
    @get:Input
    abstract val maxClassMajor: Property<Int>

    init {
        group = "verification"
        description = "Fails unless the thin jar holds this loader and the relocated host, and nothing else."
        forbiddenPrefixes.convention(emptyList())
        logTag.convention("singlejar")
        maxClassMajor.convention(61)
    }

    @TaskAction
    fun check() {
        val file = jar.get().asFile
        val allowed = allowedPrefixes.get()
        val forbidden = forbiddenPrefixes.get()
        val ceiling = maxClassMajor.get()

        val stray = mutableListOf<String>()
        val banned = mutableListOf<String>()
        val tooNew = mutableListOf<String>()
        var classes = 0

        ZipFile(file).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                val name = entry.name
                if (forbidden.any { name.startsWith(it) }) banned += name
                if (!name.endsWith(".class")) continue
                classes++
                if (allowed.none { name.startsWith(it) }) stray += name
                val head = ByteArray(8)
                zip.getInputStream(entry).use { it.read(head) }
                val major = ((head[6].toInt() and 0xFF) shl 8) or (head[7].toInt() and 0xFF)
                if (major > ceiling) tooNew += "$name (major $major)"
            }
        }

        if (stray.isNotEmpty() || banned.isNotEmpty() || tooNew.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("${file.name} is not a thin jar.")
                    if (banned.isNotEmpty()) {
                        appendLine("  ${banned.size} entries belong to the root merge, not to a loader:")
                        banned.take(8).forEach { appendLine("      $it") }
                    }
                    if (stray.isNotEmpty()) {
                        appendLine("  ${stray.size} classes are outside $allowed:")
                        stray.take(8).forEach { appendLine("      $it") }
                    }
                    if (tooNew.isNotEmpty()) {
                        appendLine("  ${tooNew.size} classes are above major $ceiling:")
                        tooNew.take(8).forEach { appendLine("      $it") }
                    }
                }
            )
        }
        logger.lifecycle("[{}] {}: {} classes, all under {}", logTag.get(), file.name, classes, allowed)
    }
}
