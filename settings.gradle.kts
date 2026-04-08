
pluginManagement {
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

plugins {
    id("com.gtnewhorizons.gtnhsettingsconvention") version("2.0.24")
}

rootProject.name = "CrystalGraphics"

// JNI bindings subproject (standalone Java library, not Minecraft mod)
include("freetype-msdfgen-harfbuzz-bindings")

// Standalone GL debug harness (no Minecraft/Forge)
include("gl-debug-harness")
