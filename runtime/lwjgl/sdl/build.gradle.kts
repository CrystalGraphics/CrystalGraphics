import cgbuildlogic.abstractModule
import java.io.File as JFile

// runtime/lwjgl/sdl — tier 1 for a host windowed by SDL3 rather than GLFW: key and mouse state, the clipboard
// and the cursor, with no Minecraft type. Minecraft 26.3 ships SDL3 and no GLFW at all, so this is what a 26.3
// node registers where an older one registers runtime/lwjgl/3's GLFW services. The GL backend is unaffected:
// Lwjgl3GLBackend's calls are GL's, not GLFW's.
//
// PINNED TO dep.lwjgl3.sdl (3.4.3), the oldest LWJGL a client with SDL3 ships, as runtime/lwjgl/vulkan is
// pinned to the oldest with Vulkan: compileOnly, since hosted it runs on Minecraft's copy.

plugins {
    `java-library`
}

group = providers.gradleProperty("modGroup").orElse("com.crystalgraphics").get()
version = providers.gradleProperty("modVersion").orElse("1.0.0").get()
base { archivesName.set("crystalgraphics-sdl") }

// An abstract module: Java 25, with a Java 8 copy for every consumer below it. @see cgbuildlogic.abstractModule
abstractModule("com/crystalgraphics/jvmdg/sdl")

repositories {
    mavenCentral()
}

val lwjglSdl = providers.gradleProperty("dep.lwjgl3.sdl").getOrElse("3.4.3")

dependencies {
    compileOnly(project(":platform"))
    compileOnly("org.lwjgl:lwjgl:$lwjglSdl")
    compileOnly("org.lwjgl:lwjgl-sdl:$lwjglSdl")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// The tier boundary, as in runtime/lwjgl/3.
tasks.named<JavaCompile>("compileJava") {
    val srcRoot: String = layout.projectDirectory.dir("src/main/java").asFile.absolutePath
    doLast {
        val violations = JFile(srcRoot).walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .filter { f ->
                f.readLines().any { line ->
                    val trimmed = line.trimStart()
                    trimmed.startsWith("import ") && listOf("net.minecraft", "com.mojang", "cpw.mods.fml",
                        "net.minecraftforge", "net.neoforged", "net.fabricmc").any { trimmed.contains(it) }
                }
            }
            .toList()
        if (violations.isNotEmpty()) {
            error("Minecraft or loader imports found in runtime/lwjgl/sdl/ - tier 1 is LWJGL and the JDK only:\n" +
                violations.joinToString("\n") { "  ${it.relativeTo(JFile(srcRoot))}" })
        }
    }
}
