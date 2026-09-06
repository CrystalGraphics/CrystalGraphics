
rootProject.name = "CrystalGraphics"

pluginManagement {
    // Default plugin version so submodules can use 'id' without specifying version.
    // gtnhgradle is loaded into the settings classloader so all submodules share the
    // same RetroFuturaGradle classes — required by Gradle build services.
    plugins {
        id("com.gtnewhorizons.gtnhconvention") version("2.0.20")
        id("com.gtnewhorizons.gtnhsettingsconvention") version("2.0.20")
        // Single version pin for all mc1201 loader subprojects.
        // com.gradleup.shadow is the maintained successor to com.github.johnrengelman.shadow.
        id("com.gradleup.shadow") version("9.2.2")

        // The mc1201 loader scripts request these with no version, so the pins live here; moddev
        // matches mc1201/build-logic's net.neoforged:moddev-gradle:2.0.141. (docs/BUILD_SETUP.md says a
        // net.neoforged.moddev.repositories settings plugin pins them; nothing applies it here or in
        // CrystalGUI.)
        id("net.neoforged.moddev") version("2.0.141")
        id("net.neoforged.moddev.legacyforge") version("2.0.141")

        // Applied once by the root build.gradle.kts; see there. Without it an IDE sync fails with
        // "Cannot add extension with name 'settings'".
        id("org.jetbrains.gradle.plugin.idea-ext") version("1.3")
    }

    // Supplies the mc1201 convention plugins; without it every mc1201 subproject fails at
    // id("cg-mc1201-loader").
    includeBuild("mc1201/build-logic")

    repositories {
        maven {
            // RetroFuturaGradle
            name = "GTNH Maven"
            url = uri("https://nexus.gtnewhorizons.com/repository/public/")
            mavenContent {
                includeGroup("com.gtnewhorizons")
                includeGroupByRegex("com\\.gtnewhorizons\\..+")
            }
        }
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
        // Loader plugin repos — registered without exclusiveContent to remain compatible
        // with loader plugins (ModDevGradle, Fabric Loom) that add their own
        // buildscript.repositories at configuration time, which Gradle 9 forbids when
        // exclusiveContent is active in pluginManagement.repositories.
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://repo.spongepowered.org/repository/maven-public/") { name = "Sponge" }
        maven("https://maven.minecraftforge.net/") { name = "Forge" }
        maven("https://maven.neoforged.net/releases") { name = "NeoForge" }
    }
}




// JNI bindings subproject (standalone Java library, not Minecraft mod)
include("freetype-msdfgen-harfbuzz-bindings")

// Platform split subprojects (plain java-library, no gtnhconvention)
include(":core")
include(":platform")
//
//// MC version subprojects (each applies gtnhconvention)
// THE 1.7.10 LOADER, ONLY WHEN SOMEBODY IS PLAUSIBLY BUILDING IT.
//
// Its GTNH convention plugin requires a JAVA 25 GRADLE DAEMON and pulls RetroFuturaGradle with it, so
// a third-party mod that merely consumes `:core` and `:platform` cannot configure this project at all:
// "Dependency requires at least JVM runtime version 25. This build uses a Java 17 JVM."
//
// NESTING DEPTH CANNOT ANSWER THIS. Gradle FLATTENS a composite -- an included build of an included
// build reports the root as its parent -- so `gradle.parent` is one level deep whether CrystalGUI
// included us or a third-party mod did, and both `settingsDir` and this build's own
// `startParameter.currentDir` are rewritten to our own directory. Measured, after both were tried.
//
// What does discriminate is WHICH BUILD WAS INVOKED: the root Gradle's currentDir is the directory the
// user ran the build in. That is this project when CrystalGraphics is worked on directly, and its
// parent when CrystalGUI is -- and anything else means somebody is consuming the libraries.
//
// Containment, not equality: currentDir is where Gradle was invoked, and IntelliJ runs a task from the
// SUBPROJECT directory. Under equality, :gl-debug-harness:runHarness dropped the loaders here while
// CrystalGUI still substituted com.crystalgraphics:crystalgraphics to :mc1710, failing every task with
// "Project with path ':mc1710' not found". Repro: cd gl-debug-harness && ../gradlew :gl-debug-harness:tasks
//
// So: anywhere inside this checkout, or inside the project containing it. The settings.gradle.kts probe
// accepts the parent only when the parent is itself a Gradle build.
val rootBuildDirectory = generateSequence(gradle) { it.parent }.last().startParameter.currentDir.canonicalFile
val ourCheckout = settingsDir.canonicalFile
val superProject = ourCheckout.parentFile

fun invokedUnder(dir: java.io.File?): Boolean =
    dir != null && rootBuildDirectory.toPath().startsWith(dir.toPath())

val loadersWanted = invokedUnder(ourCheckout) ||
    (superProject != null &&
        java.io.File(superProject, "settings.gradle.kts").isFile &&
        invokedUnder(superProject))

if (loadersWanted) include("mc1710")

//// Standalone GL debug harness (no Minecraft/Forge)
//if (file("gl-debug-harness").exists())
//    include(":gl-debug-harness")

// mc1201 subprojects. `common` holds the platform bundle, the three loaders are registration only.
// :mc1201:neoforge targets MC 1.20.4 -- NeoForge published no 20.1.x series at all, so `common`
// is compiled against 1.20.1 and consumed by a 1.20.4 module.
//
// CrystalGUI resolves :mc1201:common through a dependencySubstitution in its
// composite.settings.gradle.kts, which must name it in the same commit as these lines -- a
// substitution naming a missing project fails configuration for every task in both builds.
//
// Gated like :mc1710: @see loadersWanted.
// MC 1.20.1 Forge is included unconditionally: a 1.20.1 Forge mod consuming CrystalGraphics needs it
// on its run classpath to see CrystalGraphics in the mod list. Fabric (fabric-loom, Java 21 daemon)
// and NeoForge (MC 1.20.4) are not a 1.20.1 consumer's business.
include(":mc1201:common")
include(":mc1201:forge")

if (loadersWanted) {
    include(":mc1201:neoforge")
    include(":mc1201:fabric")
}
