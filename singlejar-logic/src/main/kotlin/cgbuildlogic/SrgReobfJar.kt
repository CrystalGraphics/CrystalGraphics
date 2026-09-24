package cgbuildlogic

import groovy.json.JsonSlurper
import net.neoforged.srgutils.IMappingFile
import net.neoforged.srgutils.IRenamer
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.CompileClasspath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.tasks.Jar
import org.gradle.process.ExecOperations
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.util.zip.ZipFile
import javax.inject.Inject

/**
 * A jar compiled against official names, renamed to what MinecraftForge 1.20.2–1.20.4 runs: Mojang's
 * class names with SRG members — and below 1.17, MCP's class names too. ModDevGradle's legacy mode does this up to 1.20.1 and cannot set up a
 * later Forge; this is the same renamer (AutoRenamingTool) over the same mapping, built here.
 *
 * ```kotlin
 * tasks.register<SrgReobfJar>("reobfThinShadowJar") {
 *     from(thinShadowJar.map { zipTree(it.archiveFile) })   // what to rename, like any Jar
 *     minecraft.set("1.20.4")                               // whose client mappings
 *     mcpConfig.from(mcpConfigZip)                          // de.oceanlabs.mcp:mcp_config:<v>@zip
 *     libraries.from(configurations["compileClasspath"])    // what the classes extend
 *     renamer.from(autoRenamingTool)                        // net.neoforged:AutoRenamingTool:<v>:all
 *     archiveClassifier.set("thin")
 * }
 * ```
 *
 * - Everything the classes inherit from must be in [libraries], or an override keeps its official name
 *   and silently never overrides anything at runtime.
 * - Forge 1.20.6+ runs official names; its jars ship as compiled. @see forgeRunsSrg
 */
abstract class SrgReobfJar @Inject constructor(private val exec: ExecOperations) : Jar() {

    @get:Input
    abstract val minecraft: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mcpConfig: ConfigurableFileCollection

    @get:CompileClasspath
    abstract val libraries: ConfigurableFileCollection

    @get:Classpath
    abstract val renamer: ConfigurableFileCollection

    @TaskAction
    override fun copy() {
        super.copy()
        val jar = archiveFile.get().asFile
        val official = File(temporaryDir, "official.jar")
        jar.copyTo(official, overwrite = true)
        val names = File(temporaryDir, "official-to-srg.tsrg")
        officialToSrg(names)
        File(temporaryDir, "renamer.log").outputStream().use { log ->
            renameTo(official, jar, names, log)
        }
    }

    private fun renameTo(official: File, jar: File, names: File, log: OutputStream) {
        exec.javaexec {
            standardOutput = log
            classpath(renamer)
            mainClass.set("net.neoforged.art.Main")
            args("--input", official.absolutePath, "--output", jar.absolutePath, "--names", names.absolutePath,
                "--disable-abstract-param", "--strip-sigs")
            libraries.forEach { args("--lib", it.absolutePath) }
        }
    }

    /** Official -> obfuscated (Mojang's client mappings) chained with obfuscated -> SRG (MCPConfig). */
    private fun officialToSrg(out: File) {
        val officialToObf = IMappingFile.load(clientMappings())
        val obfToSrg = ZipFile(mcpConfig.singleFile).use { zip ->
            zip.getInputStream(zip.getEntry("config/joined.tsrg")).use { IMappingFile.load(it) }
        }
        val chained = officialToObf.chain(obfToSrg)
        // Below 1.17 Forge runs MCPConfig's class names as well, so the chain's own are the right ones.
        val names = if (MinecraftVersionOrder.compare(minecraft.get(), "1.17") < 0) chained else chained.rename(KeepClassNames)
        names.write(out.toPath(), IMappingFile.Format.TSRG2, false)
    }

    /** Mojang's client mappings for [minecraft], fetched once and kept beside the task's scratch. */
    private fun clientMappings(): File {
        val version = minecraft.get()
        val cached = File(temporaryDir.parentFile, "client-mappings-$version.txt")
        if (cached.isFile) return cached
        val manifest = read("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
        val entry = (manifest["versions"] as List<*>).map { it as Map<*, *> }.single { it["id"] == version }
        val downloads = read(entry["url"].toString())["downloads"] as Map<*, *>
        val url = (downloads["client_mappings"] as Map<*, *>)["url"].toString()
        URI(url).toURL().openStream().use { input -> cached.outputStream().use { input.copyTo(it) } }
        return cached
    }

    private fun read(url: String): Map<*, *> =
        URI(url).toURL().openStream().use { JsonSlurper().parse(it) } as Map<*, *>

    /** From 1.17 Forge runs Mojang's class names; only members are SRG. */
    private object KeepClassNames : IRenamer {
        override fun rename(value: IMappingFile.IClass): String = value.original
    }
}
