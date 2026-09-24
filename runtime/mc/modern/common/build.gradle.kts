// The `common` branch: the platform bundle every 1.20.x loader registers, and nothing any one of them
// owns. Built once per Minecraft version the tree targets -- `:runtime:mc:modern:common:<version>` --
// and every loader node compiles against the common node of its own version. The toolchain and every
// pin come from the node (`versions/<version>/gradle.properties`, read by cg-modern-common).

import cgbuildlogic.usesLoomMinecraft
import net.fabricmc.loom.api.LoomGradleExtensionAPI

plugins {
    id("cg-modern-common")
    // Below 1.17 no ModDevGradle mode reaches Minecraft, and Loom supplies it vanilla with Mojang's
    // names. Declared here, and only applied on such a node, so it loads in this branch alone.
    id("fabric-loom") version "1.16.2" apply false
}

if (usesLoomMinecraft) {
    // A loader bundles common at Mojang's names and remaps the two together, as on every later node:
    // keep the unremapped jar in the outgoing variants and drop Loom's intermediary one.
    extra["fabric.loom.disableRemappedVariants"] = "true"
    apply(plugin = "fabric-loom")
    afterEvaluate {
        val remapped = tasks.named<AbstractArchiveTask>("remapJar").get().archiveFile.get().asFile
        for (variant in listOf("apiElements", "runtimeElements")) {
            configurations.getByName(variant).artifacts.removeIf { it.file == remapped }
        }
    }
    val loom = the<LoomGradleExtensionAPI>()
    dependencies {
        "minecraft"("com.mojang:minecraft:${property("mc.version")}")
        "mappings"(loom.officialMojangMappings())
        // @Nullable: ModDevGradle's Minecraft brings jsr305 with its libraries, and Loom's does not.
        "compileOnly"("com.google.code.findbugs:jsr305:3.0.2")
    }
}

base { archivesName.set("crystalgraphics-common-${project.name}") }

// freetype JNI bindings — compileOnly here because common's platform service
// implementation references freetype types directly. Each loader's shadowJar bundles
// the actual JAR; common never shades it.
dependencies {
    compileOnly(project(":freetype-msdfgen-harfbuzz-bindings"))
}
