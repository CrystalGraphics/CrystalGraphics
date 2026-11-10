import cgbuildlogic.abstractModule
import java.io.File as JFile

// runtime/lwjgl/3 — §12 tier 1 for LWJGL3: the GL device's driver, the input service and the clipboard,
// written against LWJGL 3 and nothing else.
//
// One copy in the merged jar, compiled once, never remapped, shared by every 1.13+ target. Every new Minecraft
// version on this LWJGL family adds nothing here.
//
// PINNED TO 3.2.2 (F1), which is what MC 1.13–1.16 ship: compiling against the OLDEST LWJGL3 in the
// supported range is what stops a symbol added in 3.3 reaching a client that has no such method.
// 1.20.x runs 3.3.x, and a 3.2.2 call is forward-compatible; the reverse is not.
//
// An abstract module like `platform` and `core`: Java 25, and a consumer below that resolves the Java 8
// copy. The merged jar's major-52 ceiling is met by `singleJar` rewriting the whole jar on the way in.

plugins {
    `java-library`
}

group = providers.gradleProperty("modGroup").orElse("com.crystalgraphics").get()
version = providers.gradleProperty("modVersion").orElse("1.0.0").get()
base { archivesName.set("crystalgraphics-lwjgl3") }

// An abstract module: Java 25, with a Java 8 copy for every consumer below it. @see cgbuildlogic.abstractModule
abstractModule("com/crystalgraphics/jvmdg/lwjgl3")

repositories {
    mavenCentral()
}

// See dep.lwjgl3.tier1 in gradle.properties for why this is not the newest, before raising it.
val lwjgl3Version = providers.gradleProperty("dep.lwjgl3.tier1").getOrElse("3.2.2")

dependencies {
    compileOnly(project(":platform"))

    // compileOnly: the game supplies LWJGL. See the version note above before raising this.
    compileOnly("org.lwjgl:lwjgl:$lwjgl3Version")
    compileOnly("org.lwjgl:lwjgl-glfw:$lwjgl3Version")
    compileOnly("org.lwjgl:lwjgl-opengl:$lwjgl3Version")
    // The audio output: only what LWJGL 3.1.6's OpenAL already has (Minecraft 1.13).
    compileOnly("org.lwjgl:lwjgl-openal:$lwjgl3Version")
    // The Ogg Vorbis decoder: stb_vorbis, which every LWJGL 3 client ships.
    compileOnly("org.lwjgl:lwjgl-stb:$lwjgl3Version")
}

// The tier boundary, enforced rather than described (F4).
tasks.named<JavaCompile>("compileJava") {
    val srcRoot: String = layout.projectDirectory.dir("src/main/java").asFile.absolutePath
    doLast {
        val violations = JFile(srcRoot).walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .filter { f ->
                f.readLines().any { line ->
                    val trimmed = line.trimStart()
                    trimmed.startsWith("import ") && (
                        trimmed.contains("net.minecraft") ||
                        trimmed.contains("com.mojang") ||
                        trimmed.contains("cpw.mods.fml") ||
                        trimmed.contains("net.minecraftforge") ||
                        trimmed.contains("net.neoforged") ||
                        trimmed.contains("net.fabricmc")
                    )
                }
            }
            .toList()
        if (violations.isNotEmpty()) {
            error("Minecraft or loader imports found in runtime/lwjgl/3/ - tier 1 is LWJGL and " +
                "the JDK only:\n" +
                violations.joinToString("\n") { "  ${it.relativeTo(JFile(srcRoot))}" })
        }
    }
}
