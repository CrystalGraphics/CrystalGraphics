// mc1201/forge — MinecraftForge 1.20.1 loader subproject.
// Uses ModDevGradle legacyForge plugin (net.neoforged.moddev.legacyforge), which explicitly
// supports MinecraftForge 1.17–1.20.1 and is Gradle 9 + JDK 25 compatible.
//
// Previously used dev.architectury.loom:1.14.473, replaced because:
//   - Architectury-loom's Forge mode eagerly resolves a detachedConfiguration inside the
//     jvmArguments property getter, which is a Gradle 9 hard error.
//   - No fix exists upstream (1.14.473 is the last published build, March 2026).
//   - There is no Gradle 9 property to suppress the exclusive-lock requirement.
//
// The legacyForge plugin version is inherited from settings.gradle.kts where
// net.neoforged.moddev.repositories:2.0.141 is applied — that settings plugin pins
// all three net.neoforged.moddev.* plugins to the same version automatically.

plugins {
    id("cg-mc1201-loader")
    id("net.neoforged.moddev.legacyforge")
    id("com.gradleup.shadow")
}

group = rootProject.properties["modGroup"] as String
version = rootProject.properties["modVersion"] as String
base { archivesName.set("crystalgraphics-mc1201-forge") }

legacyForge {
    // MinecraftForge artifact ID format: "<mcVersion>-<forgeVersion>"
    version = "1.20.1-${rootProject.properties["mc1201.forge"]}"

    parchment {
        minecraftVersion = rootProject.properties["mc1201.parchment.mc"] as String
        mappingsVersion = rootProject.properties["mc1201.parchment"] as String
    }

    runs {
        create("client") {
            client()
            gameDirectory = project.file("runs/client")
        }
        create("server") {
            server()
            gameDirectory = project.file("runs/server")
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

// Merge platform, core, mc1201:common — same pattern as mc1710 and mc1201/neoforge.
val platformJar = project(":platform").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val coreJar     = project(":core").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val commonJar   = project(":mc1201:common").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val freetypeJar = project(":freetype-msdfgen-harfbuzz-bindings").tasks.named<Jar>("jar").flatMap { it.archiveFile }

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    configurations = listOf()
    from(zipTree(platformJar))
    from(zipTree(coreJar))
    from(zipTree(commonJar))
    from(zipTree(freetypeJar))
}
tasks.assemble { dependsOn(tasks.named("shadowJar")) }

// Extracts MinecraftForge 1.20.1 sources and resources into build/mc-src for local navigation.
// Sync (not Copy) removes stale files when the source jar changes between toolchain version bumps.
val extractMcSources by tasks.registering(Sync::class) {
    description = "Extracts MinecraftForge 1.20.1 sources and resources into build/mc-src for local navigation."
    group = "crystalgraphics"

    // dependsOn (not mustRunAfter) — mustRunAfter does not cause this task to run on a clean checkout.
    dependsOn("createMinecraftArtifacts")

    // Lazy providers resolved at execution time — never at configuration time (Gradle 9 rule).
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
