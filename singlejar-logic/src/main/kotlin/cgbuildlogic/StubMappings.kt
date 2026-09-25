package cgbuildlogic

import org.objectweb.asm.tree.ClassNode
import java.io.File

/**
 * A node's rename table cut down to the classes and members its stub holds — the names a stub build
 * renames its thin jar with, committed beside the `.sig`. About 1–2% of the full table, and it renames
 * identically, because nothing outside the stub is on a stub build's classpath to be renamed.
 *
 * ```kotlin
 * StubMappings.subset(file("namedToIntermediate.tsrg"), StubMappings.Format.TSRG, stub.classes, file("stub.tsrg"))
 * StubMappings.subset(loomMappings, StubMappings.Format.TINY, stub.classes, file("stub.tiny"))
 * ```
 *
 * - Members are matched by name, not descriptor: a table's descriptors may be in another namespace.
 * - [Format.TINY] reads the `named` namespace, which is what a stub's classes are named in.
 */
object StubMappings {

    enum class Format(val extension: String) { TSRG("tsrg"), TINY("tiny") }

    fun subset(full: File, format: Format, classes: Collection<ClassNode>, out: File) {
        val held = classes.associate { c -> c.name to (c.fields.map { it.name } + c.methods.map { it.name }).toSet() }
        val lines = full.readLines()
        val kept = when (format) {
            Format.TSRG -> tsrg(lines, held)
            Format.TINY -> tiny(lines, held)
        }
        out.parentFile.mkdirs()
        out.writeText(kept.joinToString("\n", postfix = "\n"))
    }

    /** TSRG v1 or v2: a class at column 0, its members one tab in, a member's parameters two. */
    private fun tsrg(lines: List<String>, held: Map<String, Set<String>>): List<String> {
        val out = mutableListOf<String>()
        var members: Set<String>? = null
        var member = false
        for (line in lines) {
            when {
                line.startsWith("tsrg2 ") -> out += line
                !line.startsWith("\t") -> {
                    members = held[line.substringBefore(' ')]
                    if (members != null) out += line
                }
                !line.startsWith("\t\t") -> {
                    val name = line.trim().substringBefore(' ')
                    member = members != null && (name in members || name == "<init>" || name == "<clinit>")
                    if (member) out += line
                }
                member -> out += line
            }
        }
        return out
    }

    /** Tiny v2: `c` lines, members one tab in, their parameters and comments deeper. */
    private fun tiny(lines: List<String>, held: Map<String, Set<String>>): List<String> {
        val namespaces = lines.first().split('\t').drop(3)
        val named = namespaces.indexOf("named").also { require(it >= 0) { "the mappings have no 'named' namespace: $namespaces" } }
        fun name(parts: List<String>, first: Int) = parts.getOrNull(first + named)?.ifEmpty { null } ?: parts[first]
        val out = mutableListOf(lines.first())
        var inClasses = false
        var members: Set<String>? = null
        var member = false
        for (line in lines.drop(1)) {
            val parts = line.split('\t')
            when {
                line.startsWith("c\t") -> {
                    inClasses = true
                    members = held[name(parts, 1)]
                    if (members != null) out += line
                }
                !inClasses -> out += line
                line.startsWith("\t") && !line.startsWith("\t\t") -> {
                    member = members != null && when (parts[1]) {
                        "m", "f" -> name(parts, 3) in members!!
                        else -> true
                    }
                    if (member) out += line
                }
                member -> out += line
            }
        }
        return out
    }
}
