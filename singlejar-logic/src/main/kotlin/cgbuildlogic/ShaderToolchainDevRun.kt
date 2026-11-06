package cgbuildlogic

import net.neoforged.moddevgradle.dsl.RunModel
import java.io.File

/** The system property a client reads the shader toolchain's folder from (`CgShaderToolchainFiles`). */
const val SHADER_TOOLCHAIN_DIR_PROPERTY = "crystalgraphics.shaderToolchain.dir"

/**
 * Where every dev client keeps the shader toolchain's jars: one folder per user, so the hundreds of nodes, in every
 * worktree, fetch them once rather than once per run folder. Each jar is still checked against its pin.
 */
val SHADER_TOOLCHAIN_DEV_DIR = File(System.getProperty("user.home"), ".crystalgraphics/shader-toolchain")

/** The folder a dev client is given: `-Dcrystalgraphics.shaderToolchain.dir` on the Gradle command line, else [SHADER_TOOLCHAIN_DEV_DIR]. */
private fun shaderToolchainDevDir(): String =
    System.getProperty(SHADER_TOOLCHAIN_DIR_PROPERTY) ?: SHADER_TOOLCHAIN_DEV_DIR.absolutePath

/**
 * Points a ModDevGradle client [run] at the shared shader toolchain folder.
 *
 * ```kotlin
 * runs.create("client") { client(); renderDocRun(this); shaderToolchainRun(this) }
 * ```
 */
fun shaderToolchainRun(run: RunModel) {
    run.systemProperty(SHADER_TOOLCHAIN_DIR_PROPERTY, shaderToolchainDevDir())
}

/**
 * The same for a Loom client run, as VM arguments.
 *
 * ```kotlin
 * loom.runs.named("client") { shaderToolchainVmArgs().forEach(::vmArg) }
 * ```
 */
fun shaderToolchainVmArgs(): List<String> = listOf("-D$SHADER_TOOLCHAIN_DIR_PROPERTY=${shaderToolchainDevDir()}")
