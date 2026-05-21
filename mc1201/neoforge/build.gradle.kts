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
        }
    }
}

// Merge platform, core, mc1201:common into this loader JAR — mirrors mc1710 pattern exactly.
tasks.shadowJar {
    dependsOn(":platform:jar", ":core:jar", ":mc1201:common:jar")
    configurations = listOf()  // no runtime classpath shadowing — only explicit inclusions below
}

afterEvaluate {
    tasks.shadowJar.configure {
        from(zipTree(project(":platform").tasks.named<Jar>("jar").get().archiveFile.get()))
        from(zipTree(project(":core").tasks.named<Jar>("jar").get().archiveFile.get()))
        from(zipTree(project(":mc1201:common").tasks.named<Jar>("jar").get().archiveFile.get()))
    }
}

tasks.assemble { dependsOn(tasks.shadowJar) }
