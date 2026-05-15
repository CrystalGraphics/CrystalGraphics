
pluginManagement {
    // Default plugin version so submodules can use 'id' without specifying version.
    // gtnhgradle is loaded into the settings classloader so all submodules share the
    // same RetroFuturaGradle classes — required by Gradle build services.
    plugins {
        id("com.gtnewhorizons.gtnhconvention") version("2.0.24")
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
    }
}



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

