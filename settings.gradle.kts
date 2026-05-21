
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
    }

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



plugins {
    id("net.neoforged.moddev.repositories") version "2.0.141"
}

includeBuild("mc1201/build-logic")

rootProject.name = "CrystalGraphics"

// JNI bindings subproject (standalone Java library, not Minecraft mod)
include("freetype-msdfgen-harfbuzz-bindings")

// Platform split subprojects (plain java-library, no gtnhconvention)
include(":core")
include(":platform")

// MC version subprojects (each applies gtnhconvention)
include(":mc1710")

// Standalone GL debug harness (no Minecraft/Forge)
if (file("gl-debug-harness").exists())
    include(":gl-debug-harness")

// mc1201 subprojects — nested under mc1201/
include(":mc1201:common")
include(":mc1201:neoforge")
include(":mc1201:fabric")
include(":mc1201:forge")

