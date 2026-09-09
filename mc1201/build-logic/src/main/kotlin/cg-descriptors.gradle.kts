import cgbuildlogic.Dependency
import cgbuildlogic.FabricModJson
import cgbuildlogic.ForgeModsToml
import cgbuildlogic.McmodInfo
import cgbuildlogic.ModDescriptor
import cgbuildlogic.PackMcmeta
import cgbuildlogic.Variant

// ── What this mod says about itself, once (J3) ───────────────────────────────────────────────────
//
// The merged jar carries one fabric.mod.json, one mods.toml and one mcmod.info between them
// describing four loaders. Hand-writing four files that have to agree is how a version gets bumped in
// three of them; these are printed from the declaration below instead.
//
// The per-loader descriptors under each module's src/main/resources are still what those modules
// ship, and `checkDescriptorsAgree` is what stops them drifting from this.

val cgDescriptor = ModDescriptor(
    id = "crystalgraphics",
    name = "CrystalGraphics",
    version = providers.gradleProperty("modVersion").orElse("1.0.0").get(),
    description = "Rendering engine library for Minecraft mods.",
    license = "LGPL-3.0-or-later",
    dependencies = emptyList(),
    variants = listOf(
        Variant(
            loader = "fml1710", minecraft = "[1.7.10]", era = "1710",
            commonEntry = "com.crystalgraphics.CrystalGraphics",
            mixinConfigs = listOf("mixins.crystalgraphics.json"),
            packFormat = 1,
        ),
        Variant(
            loader = "forge", minecraft = "[1.20.1,1.21)", era = "modern",
            commonEntry = "com.crystalgraphics.mc.forge.CrystalGraphics1201Forge",
            packFormat = 15,
        ),
        Variant(
            loader = "neoforge", minecraft = "[1.20.4,1.21)", era = "modern",
            commonEntry = "com.crystalgraphics.mc.neoforge.CrystalGraphics1201NeoForge",
            packFormat = 22,
        ),
        Variant(
            loader = "fabric", minecraft = "[1.20.1,1.21)", era = "modern",
            commonEntry = "com.crystalgraphics.mc.fabric.CrystalGraphics1201FabricCommon",
            clientEntry = "com.crystalgraphics.mc.fabric.CrystalGraphics1201Fabric",
            fabricDepends = linkedMapOf(
                "fabricloader" to ">=0.15.0",
                "minecraft" to "~1.20.1",
                "fabric-api" to "*",
            ),
            packFormat = 15,
        ),
    ),
)

val cgDescriptorDir = layout.buildDirectory.dir("descriptors/merged")

val generateMergedDescriptors = tasks.register("generateMergedDescriptors") {
    group = "build"
    description = "Writes the descriptors the merged jar carries, one per format, from one declaration."
    val out = cgDescriptorDir
    val descriptor = cgDescriptor
    outputs.dir(out)
    inputs.property("descriptor", descriptor.toString())
    doLast {
        val root = out.get().asFile
        root.resolve("META-INF").mkdirs()
        root.resolve("fabric.mod.json").writeText(FabricModJson.merged(descriptor))
        root.resolve("mcmod.info").writeText(McmodInfo.merged(descriptor))
        root.resolve("META-INF/mods.toml").writeText(ForgeModsToml.merged(descriptor))
        root.resolve("pack.mcmeta").writeText(PackMcmeta.merged(descriptor))
        logger.lifecycle("[cg] merged descriptors for {} variants -> {}", descriptor.variants.size, root)
    }
}

/**
 * Fails when a shipped per-loader descriptor and the declaration above disagree.
 *
 * What it does NOT compare is as deliberate as what it does: the merged `mods.toml` says
 * `loaderVersion="[1,)"` where a per-loader one names its own, carries both `mandatory` and `type`
 * where each loader writes only its own spelling, and drops the `forge`/`neoforge` dependency row
 * entirely -- a required dependency on a mod the other loader does not have is a refusal to load.
 */
val checkDescriptorsAgree = tasks.register("checkDescriptorsAgree") {
    group = "verification"
    description = "Fails if a per-loader descriptor disagrees with the one declaration."
    val descriptor = cgDescriptor
    val fabricJson = layout.projectDirectory.file("mc1201/fabric/src/main/resources/fabric.mod.json").asFile
    val forgeToml = layout.projectDirectory.file("mc1201/forge/src/main/resources/META-INF/mods.toml").asFile
    val neoToml = layout.projectDirectory.file("mc1201/neoforge/src/main/resources/META-INF/mods.toml").asFile
    val mcmod = layout.projectDirectory.file("mc1710/src/main/resources/mcmod.info").asFile
    inputs.files(fabricJson, forgeToml, neoToml, mcmod).withPropertyName("shippedDescriptors")
    inputs.property("descriptor", descriptor.toString())
    outputs.upToDateWhen { true }
    doLast {
        val problems = mutableListOf<String>()

        fun require(file: File, needle: String, why: String) {
            if (!file.isFile) {
                problems += "${file.name} is missing"
            } else if (!file.readText().contains(needle)) {
                problems += "${file.name} does not $why (looked for: $needle)"
            }
        }

        require(fabricJson, "\"id\": \"${descriptor.id}\"", "declare the mod id")
        descriptor.variants.single { it.loader == "fabric" }.let { fabric ->
            require(fabricJson, fabric.commonEntry!!, "name the common entrypoint")
            require(fabricJson, fabric.clientEntry!!, "name the client entrypoint")
            fabric.fabricDepends.forEach { (id, range) ->
                require(fabricJson, "\"$id\": \"$range\"", "declare its $id dependency")
            }
        }

        require(forgeToml, "modId = \"${descriptor.id}\"", "declare the mod id")
        require(neoToml, "modId = \"${descriptor.id}\"", "declare the mod id")
        require(forgeToml, descriptor.variants.single { it.loader == "forge" }.minecraft,
                "declare its own Minecraft range")
        require(neoToml, descriptor.variants.single { it.loader == "neoforge" }.minecraft,
                "declare its own Minecraft range")

        // mcmod.info is a GTNH template: ${modId} is expanded by processResources, so the literal is
        // what a source file legitimately holds.
        require(mcmod, "\${modId}", "keep the modId placeholder GTNH expands")

        if (problems.isNotEmpty()) {
            throw GradleException(
                "A shipped descriptor disagrees with the declaration in cg-descriptors.gradle.kts:\n"
                    + problems.joinToString("\n") { "  $it" })
        }
        logger.lifecycle("[cg] four shipped descriptors agree with the declaration")
    }
}

tasks.register("checkDescriptors") {
    group = "verification"
    description = "Generates the merged descriptors and checks the shipped ones against them."
    dependsOn(generateMergedDescriptors, checkDescriptorsAgree)
}
