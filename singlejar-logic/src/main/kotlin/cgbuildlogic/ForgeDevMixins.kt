package cgbuildlogic

import org.gradle.api.Project
import java.io.File
import java.util.Properties

/**
 * The program arguments that apply mixin [configs] in a MinecraftForge dev run.
 *
 * ```kotlin
 * runs { create("client") { client(); programArguments.addAll(forgeDevMixinArgs(listOf("mixins.mymod.json"))) } }
 * ```
 *
 * - Forge takes a mod's configs from its jar manifest's `MixinConfigs` alone, and a dev run's mod is folders:
 *   without these its mixins never apply. NeoForge and Fabric read their descriptors, so empty off Forge.
 */
fun Project.forgeDevMixinArgs(configs: List<String>): List<String> =
    if (modernLoader != "forge") emptyList() else configs.flatMap { listOf("--mixin.config", it) }

/**
 * CrystalGraphics' mixin config for this node's Forge dev run, for a build that puts CrystalGraphics on it.
 *
 * ```kotlin
 * // CrystalGraphics' own node
 * runs.configureEach { programArguments.addAll(graphicsDevMixinArgs(rootDir)) }
 * // a consumer, CrystalGraphics included as a build
 * runs.configureEach { programArguments.addAll(graphicsDevMixinArgs(gradle.includedBuild("CrystalGraphics").projectDir)) }
 * tasks.named<JavaExec>("runClient") { args(graphicsDevMixinArgs(graphicsRoot)) }   // Unimined
 * ```
 *
 * - [graphicsRoot] is CrystalGraphics' build root; its node of this node's loader and version is read.
 * - Empty off Forge, and for a node whose CrystalGraphics node pins no `variant.mixinPlugin`.
 */
fun Project.graphicsDevMixinArgs(graphicsRoot: File): List<String> {
    val pins = Properties()
    File(sameVersionNodeDir(graphicsRoot, modernLoader), "gradle.properties").takeIf { it.isFile }?.reader()?.use(pins::load)
    if (pins.getProperty(MIXIN_PLUGIN) == null) return emptyList()
    return forgeDevMixinArgs(listOf(nodeMixinConfig(nodePackage("com.crystalgraphics.mc.modern.$modernLoader", name))))
}
