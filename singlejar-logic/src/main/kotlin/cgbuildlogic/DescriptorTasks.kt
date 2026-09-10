package cgbuildlogic

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register
import java.io.File

/**
 * Registers `generate<Name>Descriptors`, and — for a mod that also ships per-loader descriptors —
 * `checkDescriptorsAgree` and `checkDescriptors`.
 *
 * <p>The merged jar carries one `fabric.mod.json`, one `mods.toml` and one `mcmod.info` between them
 * describing every loader. Hand-writing files that have to agree is how a version gets bumped in three
 * of them; they are printed from one {@link ModDescriptor} instead.</p>
 *
 * <pre>{@code
 * registerDescriptorTasks(hostDescriptor, "cgui")                             // generateMergedDescriptors
 * registerDescriptorTasks(langDescriptor, "cgui-lang", name = "language",     // generateLanguageDescriptors
 *         checkShipped = false)
 * }</pre>
 *
 * <p>The per-loader descriptors under each module's `src/main/resources` are still what those modules
 * ship, and `checkDescriptorsAgree` is what stops them drifting from the declaration. A second mod
 * built from the same source tree ships none — its descriptors exist only in its merged jar — so it
 * passes {@code checkShipped = false} and gets the generator alone.</p>
 *
 * <p><b>The check assumes the module layout</b> this build shares — `mc1710/`, `mc1201/{forge,neoforge,
 * fabric}/` — because that is what makes it a fixed list rather than another thing to declare. A
 * project laid out differently wants its own copy of this function, not a parameter.</p>
 *
 * @param descriptor   what this mod says about itself, in every format
 * @param logTag       prefix for the two log lines, e.g. `cgui`
 * @param name         names the generator and its output directory; `merged` gives
 *                     `generateMergedDescriptors`, which is what {@link SingleJarSpec} defaults to
 * @param checkShipped whether this mod also ships per-loader descriptors to be checked against
 * @param taskGroup    the group these land in — the same folder as the pipeline that consumes them
 */
fun Project.registerDescriptorTasks(
    descriptor: ModDescriptor,
    logTag: String,
    name: String = "merged",
    checkShipped: Boolean = true,
    taskGroup: String = "single jar",
) {

    val titled = name.replaceFirstChar { it.uppercase() }
    val descriptorDir = layout.buildDirectory.dir("descriptors/$name")

    val generate = tasks.register("generate${titled}Descriptors") {
        group = taskGroup
        description = "Writes the descriptors the merged jar carries, one per format, from one declaration."
        val out = descriptorDir
        outputs.dir(out)
        inputs.property("descriptor", descriptor.toString())
        doLast {
            val root = out.get().asFile
            root.resolve("META-INF").mkdirs()
            root.resolve("fabric.mod.json").writeText(FabricModJson.merged(descriptor))
            root.resolve("mcmod.info").writeText(McmodInfo.merged(descriptor))
            root.resolve("META-INF/mods.toml").writeText(ForgeModsToml.merged(descriptor))
            root.resolve("pack.mcmeta").writeText(PackMcmeta.merged(descriptor))
            logger.lifecycle("[{}] merged descriptors for {} variants -> {}",
                    logTag, descriptor.variants.size, root)
        }
    }

    if (!checkShipped) return

    // What it does NOT compare is as deliberate as what it does: the merged mods.toml says
    // loaderVersion="[1,)" where a per-loader one names its own, carries both `mandatory` and `type`
    // where each loader writes only its own spelling, and drops the forge/neoforge dependency row
    // entirely -- a required dependency on a mod the other loader does not have is a refusal to load.
    // Those three are the whole reason one file can serve both loaders.
    val checkAgree = tasks.register("checkDescriptorsAgree") {
        group = taskGroup
        description = "Fails if a per-loader descriptor disagrees with the one declaration."
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

            // mcmod.info is a GTNH template: ${modId} is expanded by processResources, so the literal
            // is what a source file legitimately holds.
            require(mcmod, "\${modId}", "keep the modId placeholder GTNH expands")

            if (problems.isNotEmpty()) {
                throw GradleException(
                    "A shipped descriptor disagrees with the declaration in cg-descriptors.gradle.kts:\n"
                        + problems.joinToString("\n") { "  $it" })
            }
            logger.lifecycle("[{}] shipped descriptors agree with the declaration", logTag)
        }
    }

    tasks.register("checkDescriptors") {
        group = taskGroup
        description = "Generates the merged descriptors and checks the shipped ones against them."
        dependsOn(generate, checkAgree)
    }
}
