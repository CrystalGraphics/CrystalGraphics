import cgbuildlogic.ModDescriptor
import cgbuildlogic.configureStubs
import cgbuildlogic.legacyNodePackage
import cgbuildlogic.nodeJava
import cgbuildlogic.registerNodeVariants
import cgbuildlogic.useNodeCoordinates

plugins { id("cg-java") }

// ── A legacy node: `:runtime:mc:legacy:forge:<version>` ──────────────────────────────────────────
//
// Forge on LaunchWrapper, 1.8 to 1.12.2, at MCP names. The branch script puts Minecraft on the classpath
// (Unimined, real mode only) and renames the thin jar; this is what every legacy node does alike.
// @see cgbuildlogic.LegacyTree
useNodeCoordinates()
base { archivesName.set("crystalgraphics-legacy-${project.name}") }

repositories {
    mavenCentral()
    maven("https://maven.minecraftforge.net/") { name = "Forge" }
    maven("https://repo.spongepowered.org/repository/maven-public/") { name = "Sponge" }
}

dependencies {
    // compileOnly: the merge adds each of these once, at the root.
    "compileOnly"(project(":platform"))
    "compileOnly"(project(":core"))
    "compileOnly"(project(":runtime:mc:shared"))
}

// ── The thin jar ─────────────────────────────────────────────────────────────────────────────────
//
// This node's classes and nothing else, moved from `com.crystalgraphics.mc.legacy` to the node's own
// `com.crystalgraphics.mc.v<digits>` so three nodes share the merged jar. Unlike the modern tree there is
// no common node to carry, and no bootstrapper to spare: Forge's is its own module for every Forge.
val sourcePackage = "com.crystalgraphics.mc.legacy"

val thinShadowJar = tasks.register<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("thinShadowJar") {
    group = "build"
    description = "This legacy node's classes, relocated -- the merge's input, before the SRG rename."
    archiveClassifier.set("thin-dev")
    configurations = emptyList()
    from(sourceSets["main"].output)
    // The dev run's table: the merge writes the shipped one once.
    exclude("META-INF/*/variants.json")
    relocate(sourcePackage, legacyNodePackage(sourcePackage, project.name))
}

tasks.register<cgbuildlogic.CheckThinJar>("checkThinJar") {
    allowedPrefixes.set(listOf("com/crystalgraphics/mc/"))
    maxClassMajor.set(nodeJava + 44)
    forbiddenPrefixes.set(listOf(
        "com/crystalgraphics/core/", "com/crystalgraphics/api/", "com/crystalgraphics/gl/",
        "com/crystalgraphics/text/", "com/crystalgraphics/platform/", "org/joml/",
        "com/fasterxml/", "de/javagl/", "natives/",
    ))
    logTag.set("cg")
}
tasks.named("check") { dependsOn("checkThinJar") }

@Suppress("UNCHECKED_CAST")
val modDescriptors = rootProject.extra["cgModDescriptors"] as Map<String, ModDescriptor>
registerNodeVariants(modDescriptors.getValue("main"))

// Last, once every source set exists: the stub on the classpath, or the tasks that write and check it.
configureStubs()
