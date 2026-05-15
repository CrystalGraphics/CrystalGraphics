// Root project — pure build coordinator. No source lives here.
//
// MC version subprojects (each applies gtnhconvention and owns its own build logic):
//   :mc1710  — Minecraft 1.7.10 + Forge 10.13.4 (LWJGL 2)
//
// Platform-agnostic subprojects:
//   :core                             — rendering engine (LWJGL as compileOnly)
//   :platform                         — SPI interfaces
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
