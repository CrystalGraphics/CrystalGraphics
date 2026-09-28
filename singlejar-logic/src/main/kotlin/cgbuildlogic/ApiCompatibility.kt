package cgbuildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.io.File

/**
 * The packages no consumer may name, so they are not API however public their classes are: the per-loader
 * hosts, relocated per variant, whose package differs per loader.
 */
val INTERNAL_PACKAGES = listOf("com/crystalgui/mc/", "com/crystalgraphics/mc/")

/**
 * A published module's public API, kept as a committed baseline and checked on every build: removing a
 * public declaration fails `check` until the version's major number moves.
 *
 * ```
 * ./gradlew :core:apiCheck     # part of :core:check -- fails on a removal against api/core.api
 * ./gradlew :core:apiDump      # rewrite the baseline: at a release, or after a deliberate major break
 * ```
 *
 * - The baseline is the API as last RELEASED, so an addition never fails: a method added since, and
 *   removed again before a release, broke nobody.
 * - Strict about changes: an existing declaration whose access, type or supertypes changed counts as
 *   removed. A widening (protected to public) is reported the same way; re-dump if it was meant.
 * - Registered by [publishedModule]; nothing to call.
 */
fun Project.apiCompatibility(artifactId: String) {
    val main = extensions.getByType<SourceSetContainer>()["main"]
    val baseline = layout.projectDirectory.file("api/$artifactId.api")
    val dump = tasks.register<ApiDump>("apiDump") {
        group = "verification"
        description = "Writes this module's public API to api/$artifactId.api, the baseline apiCheck compares with."
        classes.from(main.output.classesDirs)
        coordinate.set("${project.group}:$artifactId:${project.version}")
        file.set(baseline)
    }
    val check = tasks.register<ApiCheck>("apiCheck") {
        group = "verification"
        description = "Fails when a public declaration of api/$artifactId.api is gone without a major version bump."
        classes.from(main.output.classesDirs)
        version.set(project.version.toString())
        this.baseline.from(baseline)
        mustRunAfter(dump)
    }
    tasks.named("check") { dependsOn(check) }
}

/** The public API of compiled classes, one sorted line per fact, grouped by class. */
object PublicApi {

    /** Every class's facts, by class name. */
    fun read(roots: Iterable<File>): Map<String, List<String>> {
        val nodes = roots.filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.map { file ->
                ClassNode().also { ClassReader(file.readBytes()).accept(it, ClassReader.SKIP_CODE) }
            }.toList()
        }.associateBy { it.name }
        return nodes.values.filter { isPublic(it, nodes) }.sortedBy { it.name }
            .associate { it.name to facts(it) }
    }

    private fun isPublic(c: ClassNode, all: Map<String, ClassNode>): Boolean {
        if (INTERNAL_PACKAGES.any { c.name.startsWith(it) } || c.access and Opcodes.ACC_SYNTHETIC != 0) return false
        // A nested class's own access is in its InnerClasses row; a local or anonymous one has no outer.
        val self = c.innerClasses.firstOrNull { it.name == c.name }
            ?: return c.access and Opcodes.ACC_PUBLIC != 0
        val outer = self.outerName?.let { all[it] } ?: return false
        return self.access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) != 0 && isPublic(outer, all)
    }

    private fun facts(c: ClassNode): List<String> {
        val subclassable = c.access and Opcodes.ACC_FINAL == 0
        fun visible(access: Int) = access and Opcodes.ACC_SYNTHETIC == 0 &&
            (access and Opcodes.ACC_PUBLIC != 0 || subclassable && access and Opcodes.ACC_PROTECTED != 0)
        val lines = mutableListOf("access ${flags(c.access, CLASS_FLAGS)}")
        c.superName?.let { lines += "extends $it" }
        c.interfaces.forEach { lines += "implements $it" }
        c.fields.filter { visible(it.access) }
            .forEach { lines += "field ${flags(it.access, MEMBER_FLAGS)} ${it.name} ${it.desc}" }
        c.methods.filter { visible(it.access) && it.access and Opcodes.ACC_BRIDGE == 0 }
            .forEach { lines += "method ${flags(it.access, MEMBER_FLAGS)} ${it.name} ${it.desc}" }
        return lines.sorted()
    }

    private val CLASS_FLAGS = listOf(Opcodes.ACC_PUBLIC to "public", Opcodes.ACC_PROTECTED to "protected",
        Opcodes.ACC_FINAL to "final", Opcodes.ACC_ABSTRACT to "abstract", Opcodes.ACC_INTERFACE to "interface",
        Opcodes.ACC_ENUM to "enum", Opcodes.ACC_ANNOTATION to "annotation", Opcodes.ACC_RECORD to "record")
    private val MEMBER_FLAGS = listOf(Opcodes.ACC_PUBLIC to "public", Opcodes.ACC_PROTECTED to "protected",
        Opcodes.ACC_STATIC to "static", Opcodes.ACC_FINAL to "final", Opcodes.ACC_ABSTRACT to "abstract")

    private fun flags(access: Int, known: List<Pair<Int, String>>) =
        known.filter { access and it.first != 0 }.joinToString(",") { it.second }.ifEmpty { "-" }

    /** The file [ApiDump] writes: a header, then each class and its facts, indented. */
    fun write(coordinate: String, api: Map<String, List<String>>): String = buildString {
        append("# The public API of ").append(coordinate)
            .append(" as last released. Written by apiDump; apiCheck fails on a removal.\n")
        api.forEach { (name, facts) ->
            append(name).append('\n')
            facts.forEach { append("  ").append(it).append('\n') }
        }
    }

    /** The inverse of [write]: the coordinate's version, and every fact as `class | fact`. */
    fun parse(text: String): Pair<String, Set<String>> {
        val lines = text.lines()
        val version = lines.first().substringAfter("The public API of ").substringBefore(" as last").substringAfterLast(':')
        val facts = mutableSetOf<String>()
        var current = ""
        for (line in lines.drop(1).filter { it.isNotBlank() }) {
            if (line.startsWith("  ")) facts += "$current | ${line.trim()}" else current = line.trim().also { facts += "$it | class" }
        }
        return version to facts
    }

    fun flatten(api: Map<String, List<String>>): Set<String> =
        api.flatMap { (name, facts) -> listOf("$name | class") + facts.map { "$name | $it" } }.toSet()
}

/** Writes a module's public API as the baseline [ApiCheck] compares with. */
abstract class ApiDump : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classes: ConfigurableFileCollection

    @get:Input
    abstract val coordinate: Property<String>

    @get:OutputFile
    abstract val file: RegularFileProperty

    @TaskAction
    fun dump() {
        file.get().asFile.apply { parentFile.mkdirs() }
            .writeText(PublicApi.write(coordinate.get(), PublicApi.read(classes)))
    }
}

/** Fails when a declaration of the baseline is gone and the major version is the baseline's. */
abstract class ApiCheck : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classes: ConfigurableFileCollection

    @get:Input
    abstract val version: Property<String>

    /** One file; a collection so that a missing baseline is this task's failure message, not an input error. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val baseline: ConfigurableFileCollection

    @TaskAction
    fun check() {
        val file = baseline.singleFile
        if (!file.isFile) {
            throw GradleException("$path: no API baseline at $file. Run ${path.removeSuffix("apiCheck")}apiDump and commit it.")
        }
        val (released, before) = PublicApi.parse(file.readText())
        val now = PublicApi.flatten(PublicApi.read(classes))
        val removed = (before - now).sorted()
        if (removed.isEmpty()) return
        if (major(version.get()) > major(released)) {
            logger.lifecycle("$path: ${removed.size} declarations removed since $released, allowed by $version.")
            return
        }
        throw GradleException(buildString {
            append("$path: ${removed.size} public declarations of $released are gone or changed, and ")
            append("${version.get()} is not a new major version. A consumer compiled against $released breaks:\n")
            removed.take(40).forEach { append("  ").append(it).append('\n') }
            if (removed.size > 40) append("  ... and ${removed.size - 40} more\n")
            append("Restore them, or bump the major version and run apiDump.")
        })
    }

    private fun major(version: String) = version.substringBefore('.').toIntOrNull() ?: 0
}
