package cgbuildlogic

import groovy.lang.Closure
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.tasks.Jar
import xyz.wagyourtail.jvmdg.ClassDowngrader
import xyz.wagyourtail.jvmdg.compile.ApiShader
import xyz.wagyourtail.jvmdg.compile.ZipDowngrader
import xyz.wagyourtail.jvmdg.gradle.flags.ShadeFlags
import xyz.wagyourtail.jvmdg.gradle.flags.convention
import xyz.wagyourtail.jvmdg.gradle.flags.toFlags
import xyz.wagyourtail.jvmdg.gradle.task.ShadeJar
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject

/**
 * jvmdg's `DowngradeJar` and `ShadeJar` as ONE task: downgrades [inputFile], shades in the jvmdg runtime
 * stubs the downgrade made it reference, and writes only the finished jar. The two stages' jars live in
 * this task's temporary directory and are deleted once it is written.
 *
 * ```kotlin
 * tasks.register<DowngradeShadeJar>("singleJar") {
 *     inputFile.set(singleShadowJar.flatMap { it.archiveFile })
 *     classpath.from(sourceSets["main"].compileClasspath)   // what the downgrade resolves supertypes against
 *     downgradeTo.set(JavaVersion.VERSION_1_8)
 *     shadePath.set { "com/myproject/shadow" }              // a PACKAGE path, never the jvmdg default
 *     destinationDirectory.set(layout.buildDirectory.dir("libs")); archiveFileName.set("myproject-1.0.0.jar")
 * }
 * ```
 *
 * - Downgrade before shade: the shade bundles what the rewritten bytecode references.
 * - The output is what the two jvmdg tasks in sequence produced, byte for byte: the same calls, the same
 *   manifest (the input's), repacked by the same reproducible `Jar`.
 */
abstract class DowngradeShadeJar @Inject constructor(private val archive: ArchiveOperations) : Jar(), ShadeFlags {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputFile: RegularFileProperty

    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    init {
        convention(this, project.gradle.sharedServices.registrations.getByName("${project.path}:jvmdgDefaultFlags").parameters as ShadeFlags)
    }

    override fun shadePath(action: Closure<String>) {
        shadePath.set { action.call(it) }
    }

    // THE Jar action, overridden rather than joined: a second @TaskAction runs beside Jar's own `copy`,
    // which would then re-read the stage jars this deletes.
    @TaskAction
    override fun copy() {
        val downgraded = File(temporaryDir, "downgraded.jar").apply { delete() }
        val shaded = File(temporaryDir, "shaded.jar").apply { delete() }
        ClassDowngrader.downgradeTo(toFlags()).use {
            ZipDowngrader.downgradeZip(it, inputFile.get().asFile.toPath(), classpath.files.map { f -> f.toURI().toURL() }.toSet(), downgraded.toPath())
        }
        ApiShader.shadeApis(toFlags(), shadePath.get().invoke(archiveFileName.get()), downgraded, shaded, downgradedApis())

        val manifestFile = File(temporaryDir, "input-manifest.MF")
        // The DOWNGRADED jar's manifest, as jvmdg's own tasks take it: the downgrade adds JvmDowngrader-Version.
        ZipFile(downgraded).use { zip ->
            zip.getEntry("META-INF/MANIFEST.MF")?.let { entry -> zip.getInputStream(entry).use { manifestFile.writeBytes(it.readBytes()) } }
        }
        if (manifestFile.isFile) manifest { from(manifestFile) }
        from(archive.zipTree(shaded))
        super.copy()
        downgraded.delete()
        shaded.delete()
    }

    /** jvmdg's API jars, downgraded once beside themselves in Gradle's cache, as its ShadeJar keeps them. */
    private fun downgradedApis(): Set<File> = apiJar.get().map { path ->
        val version = ShadeJar::class.java.`package`.implementationVersion ?: "0.7.0"
        val downgraded = path.resolveSibling(path.nameWithoutExtension + "-downgraded-$version.jar")
        if (!downgraded.exists()) {
            ClassDowngrader.downgradeTo(toFlags()).use { ZipDowngrader.downgradeZip(it, path.toPath(), emptySet(), downgraded.toPath()) }
        }
        downgraded
    }.toSet()
}
