package cgbuildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.CompileClasspath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.tasks.Jar
import org.gradle.process.ExecOperations
import java.io.File
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.inject.Inject

/**
 * A jar compiled against Mojang's names, renamed to intermediary by tiny-remapper — what Loom's
 * `remapJar` does, without Loom: for a stub build, whose classpath is the stub.
 *
 * ```kotlin
 * tasks.register<TinyRemapJar>("remapThinJar") {
 *     from(thinShadowJar.map { zipTree(it.archiveFile) })
 *     stubDatabase.set(file("singlejar-logic/stubs.zip")); stubNode.set("fabric:1.20.1")   // its names
 *     libraries.from(sourceSets.main.get().compileClasspath)
 *     remapper.from(configurations.detachedConfiguration(dependencies.create(TINY_REMAPPER)))
 *     manifest.attributes(StubDatabase.manifest(database, "fabric:1.20.1").toMap())
 *     archiveClassifier.set("thin")
 * }
 * ```
 */
abstract class TinyRemapJar @Inject constructor(private val exec: ExecOperations) : Jar() {

    /** The database the node's named -> intermediary table is cut from, for this run alone. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val stubDatabase: RegularFileProperty

    @get:Input
    abstract val stubNode: Property<String>

    @get:CompileClasspath
    abstract val libraries: ConfigurableFileCollection

    @get:Classpath
    abstract val remapper: ConfigurableFileCollection

    @TaskAction
    override fun copy() {
        super.copy()
        val jar = archiveFile.get().asFile
        val named = File(temporaryDir, "named.jar")
        jar.copyTo(named, overwrite = true)
        jar.delete()
        val mappings = File(temporaryDir, "names.tiny").apply { writeText(StubDatabase.names(stubDatabase.get().asFile, stubNode.get())) }
        File(temporaryDir, "remapper.log").outputStream().use { log ->
            exec.javaexec {
                standardOutput = log
                classpath(remapper)
                mainClass.set("net.fabricmc.tinyremapper.Main")
                args(named.absolutePath, jar.absolutePath, mappings.absolutePath, "named", "intermediary")
                libraries.filter { it.exists() }.forEach { args(it.absolutePath) }
            }
        }
        mappings.delete()
        named.delete()
    }
}

/** What Loom 1.16 renames with, run from a detached configuration by [TinyRemapJar]. */
const val TINY_REMAPPER = "net.fabricmc:tiny-remapper:0.13.0"

/**
 * Fails when a stub build's output differs from the real one: [expected] against [actual], each a
 * classes directory or a jar, entry by entry, manifests compared by attribute.
 *
 * ```kotlin
 * tasks.register<CompareOutputs>("checkStubEquivalenceCompileJava") {
 *     expected.from(compileJava.flatMap { it.destinationDirectory })
 *     actual.from(stubCheckCompileJava.flatMap { it.destinationDirectory })
 * }
 * ```
 */
abstract class CompareOutputs : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val expected: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val actual: ConfigurableFileCollection

    @get:Internal
    val report: File get() = File(temporaryDir, "differences.txt")

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun compare() {
        val left = entries(expected.singleFile)
        val right = entries(actual.singleFile)
        val problems = mutableListOf<String>()
        (left.keys - right.keys).sorted().forEach { problems += "only in the real build: $it" }
        (right.keys - left.keys).sorted().forEach { problems += "only in the stub build: $it" }
        for (name in (left.keys intersect right.keys).sorted()) {
            val same = if (name == "META-INF/MANIFEST.MF") manifest(left.getValue(name)) == manifest(right.getValue(name))
                else left.getValue(name).contentEquals(right.getValue(name))
            if (!same) problems += "differs: $name"
        }
        report.writeText(problems.joinToString("\n"))
        if (problems.isNotEmpty()) throw GradleException(
            "${left.size} entries in ${expected.singleFile.name}, and the stub build differs in ${problems.size}:\n  " +
                problems.take(20).joinToString("\n  ") + (if (problems.size > 20) "\n  ... all in $report" else ""))
        logger.lifecycle("[stubs] {}: {} entries identical", path, left.size)
    }

    private fun entries(root: File): Map<String, ByteArray> = if (root.isDirectory) {
        root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes() }
    } else ZipFile(root).use { zip ->
        zip.entries().asSequence().filterNot(ZipEntry::isDirectory).associate { it.name to zip.getInputStream(it).readBytes() }
    }

    private fun manifest(bytes: ByteArray): Map<String, String> =
        Manifest(bytes.inputStream()).mainAttributes.entries.associate { it.key.toString() to it.value.toString() }
}
