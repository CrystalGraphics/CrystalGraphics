// Root project — pure build coordinator. No source lives here.
//
// MC version subprojects:
//   :mc1710                — Minecraft 1.7.10 + Forge 10.13.4  (LWJGL 2, gtnhconvention)
//   :mc1201:common         — Shared 1.20.1 source (plain Java 17 library)
//   :mc1201:fabric         — MC 1.20.1 Fabric loader  (fabric-loom 1.16.2)
//   :mc1201:forge          — MC 1.20.1 MinecraftForge (ModDevGradle legacyForge)
//   :mc1201:neoforge       — MC 1.20.4 NeoForge       (ModDevGradle — see neoforge/build.gradle.kts)
//
// Platform-agnostic subprojects:
//   :core                             — rendering engine (LWJGL as compileOnly)
//   :platform                         — SPI interfaces (zero MC/LWJGL imports)
//   :freetype-msdfgen-harfbuzz-bindings — JNI font/shape bindings
//   :gl-debug-harness                 — standalone GL test harness (no Minecraft)

plugins {
    idea
}

// IntelliJ IDEA triggers 'processIdeaSettings' on the root project during Gradle sync.
// gtnhconvention only registers this task on subprojects that apply it, so register a
// no-op here to prevent "task not found" errors during IDEA sync.
tasks.register("processIdeaSettings") {
    group = "ide"
    description = "No-op task for IntelliJ IDEA Gradle sync compatibility"
}

// gtnhsettingsconvention (applied in settings.gradle.kts) injects spotless and other
// plugins onto this project's buildscript classpath. Declare buildscript repositories
// here so those plugins can resolve even though root applies no MC convention plugin.
buildscript {
    repositories {
        maven {
            name = "GTNH Maven"
            url = uri("https://nexus.gtnewhorizons.com/repository/public/")
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

repositories {
    maven {
        name = "GTNH Maven"
        url = uri("https://nexus.gtnewhorizons.com/repository/public/")
    }
    gradlePluginPortal()
    mavenCentral()
}
