// The `neoforge` branch — NeoForge through ModDevGradle, one node per Minecraft version
// (`versions/<version>/`, whose gradle.properties pins mc.version, neoforge.version and Parchment).
// Its first node is 1.20.2: NeoForge published no 20.1.x series.
//
// A node that also pins `neoform.version` (1.20.2, 1.20.3 -- NeoForge 20.2/20.3, which ModDevGradle does
// not set up) is built from parts: NeoForm's Minecraft, NeoForge's jars compileOnly, and no dev run.
// @see cgbuildlogic.useNeoForgeApi

import cgbuildlogic.commonNode
import cgbuildlogic.useNeoForgeApi

plugins {
    id("cg-modern-loader")
    id("net.neoforged.moddev")
    id("com.gradleup.shadow")
}

// ADHOC: Re-declare two Maven repos that net.neoforged.moddev.repositories (settings plugin)
// should provide at project level. The cg-modern-loader convention plugin's repositories block
// runs at project configuration time and takes precedence over settings-level repos in Gradle 9,
// causing moddev's NeoForge/Mojang repos to go missing during dependency resolution.
//
// Removal condition: if ModDevGradle changes its settings plugin to use
// DependencyResolutionManagement (exclusive, settings-owned) instead of per-project repos,
// these declarations can be removed and the dependency resolution failure will confirm it.
repositories {
    maven("https://maven.neoforged.net/mojang-meta/") { name = "NeoForge Mojang Meta" }
    maven("https://libraries.minecraft.net/") {
        name = "MC Libraries"
        metadataSources { mavenPom() }
    }
}

val fromParts = findProperty("neoform.version") != null
if (fromParts) useNeoForgeApi()

neoForge {
    if (fromParts) neoFormVersion = property("neoform.version").toString()
    else version = property("neoforge.version").toString()

    parchment {
        minecraftVersion = property("parchment.mc").toString()
        mappingsVersion = property("parchment.version").toString()
    }

    if (!fromParts) runs {
        create("client") {
            client()
            // Forward every -Dcrystalgraphics.* from the Gradle invocation into the game's JVM, the
            // way the debug harness does. Without it the diagnostic flags this project ships --
            // host.verify, state.verify, state.noDedup -- are unreachable from a dev run, which is
            // the one place a developer would reach for them.
            System.getProperties().stringPropertyNames()
                    .filter { it.startsWith("crystalgraphics.") }
                    .forEach { systemProperty(it, System.getProperty(it)) }
        }
    }

    if (!fromParts) mods {
        create("crystalgraphics") {
            sourceSet(sourceSets.main.get())
            // Dev-run classpath: platform, core, and the common node are compileOnly for production
            // (shadowJar bundles them via from(zipTree(...))), but ModDevGradle dev runs only see
            // what's declared in this mods{} block. Adding their source sets here puts their
            // compiled classes in the mod's virtual JAR, making them visible to ModuleClassLoader
            // and resolving ClassNotFoundException: com/crystalgraphics/platform/CgPlatformService.
            sourceSet(project(":platform").extensions.getByType<SourceSetContainer>()["main"])
            sourceSet(project(":core").extensions.getByType<SourceSetContainer>()["main"])
            sourceSet(project.commonNode.extensions.getByType<SourceSetContainer>()["main"])
            sourceSet(project(":freetype-msdfgen-harfbuzz-bindings").extensions.getByType<SourceSetContainer>()["main"])
        }
    }
}

// Merge platform, core, the common node into this loader JAR — mirrors mc1710 pattern exactly.
val platformJar = project(":platform").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val coreJar     = project(":core").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val commonJar   = project.commonNode.tasks.named<Jar>("jar").flatMap { it.archiveFile }
val freetypeJar = project(":freetype-msdfgen-harfbuzz-bindings").tasks.named<Jar>("jar").flatMap { it.archiveFile }

tasks.shadowJar {
    configurations = listOf()  // no runtime classpath shadowing — only explicit inclusions below
    from(zipTree(platformJar))
    from(zipTree(coreJar))
    from(zipTree(commonJar))
    from(zipTree(freetypeJar))
}

// Not on `assemble` (J7): the merged single jar is the shipping artifact, and the fat per-loader jar
// nothing installs was the most expensive thing in this build. `./gradlew shadowJar` still builds one.

// Extracts this node's NeoForge + Minecraft sources and resources into build/mc-src for local navigation.
// Sync (not Copy) removes stale files when the source jar changes between toolchain version bumps.
val extractMcSources by tasks.registering(Sync::class) {
    description = "Extracts this node's NeoForge + Minecraft sources and resources into build/mc-src for local navigation."
    group = "crystalgraphics"

    // dependsOn (not mustRunAfter) — mustRunAfter only orders tasks already scheduled; it does not
    // cause createMinecraftArtifacts to run, so the jar would be absent on a clean checkout.
    dependsOn("createMinecraftArtifacts")

    // Lazy providers resolved at execution time — never at configuration time (Gradle 9 rule).
    // fileTree scan is the fallback because ModDevGradle does not expose a public typed output
    // property for the sources or client-extra jars.
    val sourcesJar = layout.buildDirectory.dir("moddev/artifacts").map { dir ->
        dir.asFileTree.matching { include("*-sources.jar") }.singleFile
    }
    val resourcesJar = layout.buildDirectory.dir("moddev/artifacts").map { dir ->
        // `client-extra-<v>.jar` on 1.20.x, `<loader>-<v>-client-extra-aka-minecraft-resources.jar` on 1.21.
        dir.asFileTree.matching { include("*client-extra*.jar") }.singleFile
    }

    from(zipTree(sourcesJar)) { into("java") }
    from(zipTree(resourcesJar)) { into("resources") }
    into(layout.buildDirectory.dir("mc-src"))
}

// extractMcSources is cheap (unzips an already-present jar — createMinecraftArtifacts ran first).
// Wire it into classes so build/mc-src/ is always populated after a normal compile.
tasks.named("classes") { dependsOn(extractMcSources) }

// -- The thin jar (J1) ----------------------------------------------------------------------------
//
// NO REMAPPING STEP, and that is the difference from forge rather than an omission: NeoForge runs
// official Minecraft names, so `thinShadowJar` already IS the production artifact. It therefore
// takes the `thin` classifier directly rather than the `thin-dev` the other two carry until mapped.
tasks.named<AbstractArchiveTask>("thinShadowJar") { archiveClassifier.set("thin") }

// Registered by cg-modern-loader with what a CrystalGraphics thin jar may contain; only the jar is ours.
tasks.named<cgbuildlogic.CheckThinJar>("checkThinJar") {
    jar.set(tasks.named<AbstractArchiveTask>("thinShadowJar").flatMap { it.archiveFile })
}
tasks.named("assemble") { dependsOn("thinShadowJar") }
