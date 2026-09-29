

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
        // matches runtime/mc/modern/build-logic's net.neoforged:moddev-gradle:2.0.147. No
        // net.neoforged.moddev.repositories settings plugin pins them: nothing applies it here or in
        // CrystalGUI.
        id("net.neoforged.moddev") version("2.0.147")
        id("net.neoforged.moddev.legacyforge") version("2.0.147")

        // Applied once by the root build.gradle.kts; see there. Without it an IDE sync fails with
        // "Cannot add extension with name 'settings'".
        id("org.jetbrains.gradle.plugin.idea-ext") version("1.3")
    }

    // Supplies the 1.20.x convention plugins; without it every node fails at id("cg-modern-loader").
    includeBuild("runtime/mc/modern/build-logic")
    // The settings plugin below: the Minecraft nodes, from the versions targeted.
    includeBuild("singlejar-logic")

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
        maven("https://maven.wagyourtail.xyz/releases") { name = "Unimined" }
        maven("https://repo.spongepowered.org/repository/maven-public/") { name = "Sponge" }
        maven("https://maven.minecraftforge.net/") { name = "Forge" }
        maven("https://maven.neoforged.net/releases") { name = "NeoForge" }
    }
}

// The multi-version preprocessor the 1.20.x loaders are built with (J11). A SETTINGS plugin, so it
// needs a Java 21+ Gradle daemon in every build that includes this one -- Stonecutter's own floor.
plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
    id("com.crystalgraphics.singlejar")
}

rootProject.name = "CrystalGraphics"




// JNI bindings subproject (standalone Java library, not Minecraft mod)
include("freetype-msdfgen-harfbuzz-bindings")

// What every loader variant in the single jar shares: the mixin config plugin that decides whose
// mixins may apply, and the loader probe under it. Java 8, one dependency (Mixin, compileOnly), and
// no Minecraft type at all.
include("runtime:mc:shared")

// The one @Mod class every Forge constructs -- modern and legacy FML scan for the same annotation --
// compiled once, against stand-ins for both eras' Forge types (forge-stubs, never shipped).
include("runtime:mc:forge-stubs")
include("runtime:mc:forge-bootstrap")

// Tier 1 (CrystalGUI plan/crystalgui/platform-single-jar.md §12): the GL backend, the context and the
// input service per LWJGL family, with no Minecraft type in either. Compiled once, never remapped,
// one copy in the merged jar however many targets ship. What Minecraft caches and we must therefore
// tell it about is a T2 subclass in the target's own module, never a branch in here.
include("runtime:lwjgl:2")
include("runtime:lwjgl:3")
include("runtime:lwjgl:vulkan")

// Platform split subprojects (plain java-library, no gtnhconvention)
include(":core")
include(":platform")

// ── The Minecraft nodes ──────────────────────────────────────────────────────────────────────────
//
// The versions this build ships, resolved against singlejar-logic's pin catalog into the 1.7.10 host,
// the legacy tree and the modern tree. CrystalGUI builds a node of the same version on each (D1), so its
// ranges are these. Included by another build, only what that build can configure: the node its target
// needs. @see cgbuildlogic.SingleJarSettings
singlejar {
    targets {
        forge("1.7.10".."26.2")
        neoforge("1.20.2".."26.2")
        fabric("1.14.4".."26.2")
    }
}
