

pluginManagement {
    // Default plugin version so submodules can use 'id' without specifying version.
    // gtnhgradle is loaded into the settings classloader so all submodules share the
    // same RetroFuturaGradle classes — required by Gradle build services.
    plugins {
        id("com.gtnewhorizons.gtnhconvention") version("2.0.20")
        id("com.gtnewhorizons.gtnhsettingsconvention") version("2.0.20")
        // Single version pin for every 1.20.x loader node.
        // com.gradleup.shadow is the maintained successor to com.github.johnrengelman.shadow.
        id("com.gradleup.shadow") version("9.2.2")

        // The 1.20.x loader scripts request these with no version, so the pins live here; moddev
        // matches runtime/mc/modern/build-logic's net.neoforged:moddev-gradle:2.0.141. (docs/BUILD_SETUP.md says a
        // net.neoforged.moddev.repositories settings plugin pins them; nothing applies it here or in
        // CrystalGUI.)
        id("net.neoforged.moddev") version("2.0.141")
        id("net.neoforged.moddev.legacyforge") version("2.0.141")

        // Applied once by the root build.gradle.kts; see there. Without it an IDE sync fails with
        // "Cannot add extension with name 'settings'".
        id("org.jetbrains.gradle.plugin.idea-ext") version("1.3")
    }

    // Supplies the 1.20.x convention plugins; without it every node fails at id("cg-modern-loader").
    includeBuild("runtime/mc/modern/build-logic")

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

// The multi-version preprocessor the 1.20.x loaders are built with (J11). A SETTINGS plugin, so it
// needs a Java 21+ Gradle daemon in every build that includes this one -- Stonecutter's own floor.
plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
}

rootProject.name = "CrystalGraphics"




// JNI bindings subproject (standalone Java library, not Minecraft mod)
include("freetype-msdfgen-harfbuzz-bindings")

// What every loader variant in the single jar shares: the mixin config plugin that decides whose
// mixins may apply, and the loader probe under it. Java 8, one dependency (Mixin, compileOnly), and
// no Minecraft type at all.
include("runtime:mc:shared")

// Tier 1 (CrystalGUI plan/crystalgui/platform-single-jar.md §12): the GL backend, the context and the
// input service per LWJGL family, with no Minecraft type in either. Compiled once, never remapped,
// one copy in the merged jar however many targets ship. What Minecraft caches and we must therefore
// tell it about is a T2 subclass in the target's own module, never a branch in here.
include("runtime:lwjgl:2")
include("runtime:lwjgl:3")

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
// CrystalGUI still substituted com.crystalgraphics:crystalgraphics to :runtime:mc:1710, failing every task with
// "Project with path ':runtime:mc:1710' not found". Repro: cd gl-debug-harness && ../gradlew :gl-debug-harness:tasks
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

if (loadersWanted) include("runtime:mc:1710")

//// Standalone GL debug harness (no Minecraft/Forge)
//if (file("gl-debug-harness").exists())
//    include(":gl-debug-harness")

// ── The MC 1.20.x loaders: one source tree, a node per Minecraft version (J11) ───────────────────
//
// Stonecutter, BRANCHED. Each loader is a branch -- `runtime/mc/modern/<loader>/` holds the shared
// `src/` and one build script -- and each Minecraft version it targets is a NODE,
// `:runtime:mc:modern:<loader>:<version>`, in `<loader>/versions/<version>/` with its own pins. `common`
// holds the platform bundle and is versioned too: every loader node compiles against the common node of
// its own version, so NeoForge (1.20.4) has a 1.20.4 common rather than borrowing 1.20.1's.
//
// Nothing else in the build names a node -- cgbuildlogic.ModernTree finds them, for this build and for
// every build on top of it. CrystalGUI substitutes each of its common nodes to the one here of the same
// version, so a version it has must exist here first (D1).
//
// Gated like :runtime:mc:1710 (@see loadersWanted), except that common and forge at 1.20.1 are always
// present: a 1.20.1 Forge mod consuming CrystalGraphics needs the forge node on its run classpath to
// see CrystalGraphics in the mod list. Fabric (fabric-loom, Java 21 daemon) and 1.20.4 are not a
// 1.20.1 consumer's business.
val modernNodes: Map<String, List<String>> =
    if (!loadersWanted) linkedMapOf("common" to listOf("1.20.1"), "forge" to listOf("1.20.1"))
    else linkedMapOf(
        "common" to listOf("1.20.1", "1.20.4", "1.20.6", "1.21.1"),
        "forge" to listOf("1.20.1"),
        "neoforge" to listOf("1.20.4", "1.20.6", "1.21.1"),
        "fabric" to listOf("1.20.1", "1.20.4", "1.20.6", "1.21.1"),
    )

stonecutter {
    create("runtime:mc:modern") {
        // EVERY branch declares its own versions and the tree declares none: a tree-level `versions`
        // is inherited by a branch that names none, and also creates a node on the tree itself.
        modernNodes.forEach { (branchName, nodeVersions) ->
            branch(branchName) { versions(*nodeVersions.toTypedArray()) }
        }
    }
}
