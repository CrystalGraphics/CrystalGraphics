// NOTE: Despite living under mc1201/, this subproject targets MC 1.20.4 / NeoForge 20.4.x.
// NeoForge 20.1.x (MC 1.20.1) was never published to the NeoForge Maven; the earliest
// available stable series is 20.4.x (MC 1.20.4). Version pins live in gradle.properties
// under mc1204.* keys. The directory name mc1201/neoforge/ is retained for continuity.

plugins {
    id("cg-mc1201-loader")
    id("net.neoforged.moddev")
    id("com.gradleup.shadow")
}

group = rootProject.properties["modGroup"] as String
version = rootProject.properties["modVersion"] as String
base { archivesName.set("crystalgraphics-mc1201-neoforge") }

// ADHOC: Re-declare two Maven repos that net.neoforged.moddev.repositories (settings plugin)
// should provide at project level. The cg-mc1201-loader convention plugin's repositories block
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

neoForge {
    version = rootProject.properties["mc1204.neoforge"] as String

    parchment {
        minecraftVersion = rootProject.properties["mc1204.parchment.mc"] as String
        mappingsVersion = rootProject.properties["mc1204.parchment"] as String
    }

    runs {
        create("client") {
            client()
        }
    }

    mods {
        create("crystalgraphics") {
            sourceSet(sourceSets.main.get())
            // Dev-run classpath: platform, core, and mc1201:common are compileOnly for production
            // (shadowJar bundles them via from(zipTree(...))), but ModDevGradle dev runs only see
            // what's declared in this mods{} block. Adding their source sets here puts their
            // compiled classes in the mod's virtual JAR, making them visible to ModuleClassLoader
            // and resolving ClassNotFoundException: com/crystalgraphics/platform/CgPlatformService.
            sourceSet(project(":platform").extensions.getByType<SourceSetContainer>()["main"])
            sourceSet(project(":core").extensions.getByType<SourceSetContainer>()["main"])
            sourceSet(project(":mc1201:common").extensions.getByType<SourceSetContainer>()["main"])
            sourceSet(project(":freetype-msdfgen-harfbuzz-bindings").extensions.getByType<SourceSetContainer>()["main"])
        }
    }
}

// Merge platform, core, mc1201:common into this loader JAR — mirrors mc1710 pattern exactly.
val platformJar = project(":platform").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val coreJar     = project(":core").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val commonJar   = project(":mc1201:common").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val freetypeJar = project(":freetype-msdfgen-harfbuzz-bindings").tasks.named<Jar>("jar").flatMap { it.archiveFile }

tasks.shadowJar {
    configurations = listOf()  // no runtime classpath shadowing — only explicit inclusions below
    from(zipTree(platformJar))
    from(zipTree(coreJar))
    from(zipTree(commonJar))
    from(zipTree(freetypeJar))
}

tasks.assemble { dependsOn(tasks.shadowJar) }

// Extracts NeoForge + MC 1.20.4 sources and resources into build/mc-src for local navigation.
// Sync (not Copy) removes stale files when the source jar changes between toolchain version bumps.
val extractMcSources by tasks.registering(Sync::class) {
    description = "Extracts NeoForge + MC 1.20.4 sources and resources into build/mc-src for local navigation."
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
        dir.asFileTree.matching { include("client-extra-*.jar") }.singleFile
    }

    from(zipTree(sourcesJar)) { into("java") }
    from(zipTree(resourcesJar)) { into("resources") }
    into(layout.buildDirectory.dir("mc-src"))
}

// extractMcSources is cheap (unzips an already-present jar — createMinecraftArtifacts ran first).
// Wire it into classes so build/mc-src/ is always populated after a normal compile.
tasks.named("classes") { dependsOn(extractMcSources) }
