import java.io.File as JFile

// mc-lwjgl3 — §12 tier 1 for LWJGL3: the GL backend, the context, the input service and the
// clipboard, written against LWJGL 3 and nothing else.
//
// One copy in the merged jar, compiled once, never remapped, shared by every 1.13+ target. What
// Minecraft caches and we must therefore tell it about is `Blaze3dGLBackend extends
// Lwjgl3GLBackend` in `runtime/mc/modern/common` — the override list is contracts C5. Every new Minecraft
// version on this LWJGL family adds nothing here.
//
// PINNED TO 3.2.2 (F1), which is what MC 1.13–1.16 ship: compiling against the OLDEST LWJGL3 in the
// supported range is what stops a symbol added in 3.3 reaching a client that has no such method.
// 1.20.x runs 3.3.x, and a 3.2.2 call is forward-compatible; the reverse is not.
//
// JAVA 17, like `platform` and `core` here — a Java 8 consumer cannot resolve a Java 17 producer at
// all, since Gradle matches a JVM-version attribute before any class is read. The merged jar's
// major-52 ceiling is met by `downgradeSingleJar` rewriting the whole jar on the way in.

plugins {
    `java-library`
}

group = providers.gradleProperty("modGroup").orElse("com.crystalgraphics").get()
version = providers.gradleProperty("modVersion").orElse("1.0.0").get()
base { archivesName.set("crystalgraphics-mc-lwjgl3") }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

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
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
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
            error("Minecraft or loader imports found in runtime/lwjgl/3/ - tier 1 is LWJGL and the JDK only:\n" +
                violations.joinToString("\n") { "  ${it.relativeTo(JFile(srcRoot))}" })
        }
    }
}
