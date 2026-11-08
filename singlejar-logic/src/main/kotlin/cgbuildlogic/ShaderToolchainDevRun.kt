package cgbuildlogic

import net.neoforged.moddevgradle.dsl.RunModel
import org.gradle.api.Project
import java.io.File

/** The system property a client reads the shader toolchain's folder from (`CgShaderToolchainFiles`). */
const val SHADER_TOOLCHAIN_DIR_PROPERTY = "crystalgraphics.shaderToolchain.dir"

/**
 * Where every dev client of this checkout keeps the shader toolchain's jars: `CrystalGraphics/build/shader-toolchain`,
 * so the hundreds of nodes, CrystalGUI's and CrystalGraphics' alike, fetch them once rather than once per run folder.
 * Each jar is still checked against its pin; a `clean` fetches them again.
 *
 * ```kotlin
 * val dir = shaderToolchainDevDir()   // from any project of either build
 * ```
 *
 * - `-Dcrystalgraphics.shaderToolchain.dir` on the Gradle command line names another folder.
 */
fun Project.shaderToolchainDevDir(): File {
    System.getProperty(SHADER_TOOLCHAIN_DIR_PROPERTY)?.let { return File(it) }
    // CrystalGraphics as CrystalGUI's included build, or the root of its own
    val graphics = gradle.includedBuilds.firstOrNull { it.name == "CrystalGraphics" }?.projectDir ?: rootProject.projectDir
    return File(graphics, "build/shader-toolchain")
}

/**
 * Points a ModDevGradle client [run] at the shared shader toolchain folder.
 *
 * ```kotlin
 * runs.create("client") { client(); renderDocRun(this); shaderToolchainRun(this) }
 * ```
 */
fun Project.shaderToolchainRun(run: RunModel) {
    run.systemProperty(SHADER_TOOLCHAIN_DIR_PROPERTY, shaderToolchainDevDir().absolutePath)
}

/**
 * The same for a Loom client run, or any JVM a task starts, as VM arguments.
 *
 * ```kotlin
 * loom.runs.named("client") { shaderToolchainVmArgs().forEach(::vmArg) }
 * ```
 */
fun Project.shaderToolchainVmArgs(): List<String> =
    listOf("-D$SHADER_TOOLCHAIN_DIR_PROPERTY=${shaderToolchainDevDir().absolutePath}")
