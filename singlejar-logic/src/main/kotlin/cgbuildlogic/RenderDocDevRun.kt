package cgbuildlogic

import net.neoforged.moddevgradle.dsl.RunModel
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import javax.tools.ToolProvider

/** The default install's library, which `-PcgRenderDoc` with no path loads. */
const val RENDERDOC_DEFAULT_DLL = "C:/Program Files/RenderDoc/renderdoc.dll"

/**
 * The RenderDoc library `-PcgRenderDoc` asks a dev client to load, or null without it. No value, or `true`, is the
 * default install's ([RENDERDOC_DEFAULT_DLL]); anything else is a path to `renderdoc.dll`.
 *
 * ```bash
 * ./gradlew :runtime:mc:modern:forge:1.20.1:runClient -PcgRenderDoc
 * ./gradlew :runtime:mc:modern:neoforge:26.2:runClient -PcgRenderDoc=D:/rd/renderdoc.dll
 * ```
 */
fun Project.renderDocLibrary(): File? {
    val value = findProperty("cgRenderDoc")?.toString() ?: return null
    val dll = File(if (value.isBlank() || value == "true") RENDERDOC_DEFAULT_DLL else value)
    if (!dll.isFile) throw GradleException("-PcgRenderDoc: no RenderDoc library at $dll; pass its path, -PcgRenderDoc=<renderdoc.dll>")
    return dll
}

/**
 * Loads RenderDoc into a ModDevGradle client [run] under `-PcgRenderDoc`: its JVM arguments and environment, and
 * `runClient` depending on the agent. Nothing without the flag.
 *
 * ```kotlin
 * runs.create("client") { client(); renderDocRun(this) }
 * ```
 */
fun Project.renderDocRun(run: RunModel) {
    run.jvmArguments.addAll(renderDocDevRun())
    run.environment.putAll(renderDocEnvironment())
}

/**
 * The client run's JVM arguments that load RenderDoc into the game before its window exists; registers the agent's
 * task and makes `runClient` depend on it. Empty without `-PcgRenderDoc`. [renderDocRun] for ModDevGradle; Loom:
 *
 * ```kotlin
 * loom.runs.named("client") {
 *     renderDocDevRun().forEach(::vmArg)
 *     renderDocEnvironment().forEach { (key, value) -> environmentVariable(key, value) }
 * }
 * ```
 *
 * - **A Java agent, not a mod**: Forge and NeoForge make the game's context in their early window, before any mod
 *   loads, and RenderDoc captures only a context it saw made. The agent's `premain` loads the library ahead of `main`.
 * - Captures: F12 or Print Screen in game, or RenderDoc's File > Attach to Running Instance, which lists them. They go
 *   to RenderDoc's temporary folder until saved from there.
 * - GL captures need a core context: Minecraft 1.17 on. Vulkan (26.x) goes through RenderDoc's own layer, which the
 *   install registers and the run's environment turns on.
 * - Every frame runs 2-4x slower attached: a run with it times nothing.
 */
fun Project.renderDocDevRun(): List<String> {
    val dll = renderDocLibrary() ?: return emptyList()
    val task = renderDocAgentTask()
    tasks.matching { it.name == "runClient" }.configureEach { dependsOn(task) }
    return listOf("-javaagent:${task.get().jar.get().asFile.absolutePath}=${dll.absolutePath}")
}

private const val RENDERDOC_AGENT_TASK = "renderDocAgent"

/** The environment a Vulkan device needs for RenderDoc's layer: empty without `-PcgRenderDoc`. */
fun Project.renderDocEnvironment(): Map<String, String> =
    if (renderDocLibrary() == null) emptyMap() else mapOf("ENABLE_VULKAN_RENDERDOC_CAPTURE" to "1")

private fun Project.renderDocAgentTask(): TaskProvider<RenderDocAgentJar> =
    if (RENDERDOC_AGENT_TASK in tasks.names) tasks.named(RENDERDOC_AGENT_TASK, RenderDocAgentJar::class.java)
    else tasks.register(RENDERDOC_AGENT_TASK, RenderDocAgentJar::class.java) {
        jar.set(layout.buildDirectory.file("renderdoc/cg-renderdoc-agent.jar"))
    }

/**
 * Builds the one-class agent jar: its `premain` loads the library its argument names. Compiled for Java 8, so one jar
 * serves every node's JVM.
 */
abstract class RenderDocAgentJar : DefaultTask() {

    @get:OutputFile
    abstract val jar: RegularFileProperty

    @TaskAction
    fun build() {
        val source = File(temporaryDir, "CgRenderDocAgent.java")
        source.writeText(AGENT_SOURCE)
        val javac = ToolProvider.getSystemJavaCompiler()
            ?: throw GradleException("renderDocAgent: Gradle's JVM has no compiler; run Gradle on a JDK")
        val classes = File(temporaryDir, "classes").apply { deleteRecursively(); mkdirs() }
        if (javac.run(null, null, null, "--release", "8", "-nowarn", "-d", classes.path, source.path) != 0) {
            throw GradleException("renderDocAgent: the agent did not compile")
        }
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name("Premain-Class")] = "CgRenderDocAgent"
        }
        val out = jar.get().asFile.apply { parentFile.mkdirs() }
        JarOutputStream(out.outputStream(), manifest).use { stream ->
            stream.putNextEntry(JarEntry("CgRenderDocAgent.class"))
            stream.write(File(classes, "CgRenderDocAgent.class").readBytes())
            stream.closeEntry()
        }
    }

    private companion object {
        val AGENT_SOURCE = """
            public final class CgRenderDocAgent {
                public static void premain(String library) {
                    System.load(library);
                    System.out.println("[renderdoc] attached: " + library + " (F12 captures; File > Attach to Running Instance lists them)");
                }
            }
        """.trimIndent()
    }
}
