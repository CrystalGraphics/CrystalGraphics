import cgbuildlogic.abstractModule
import java.io.File as JFile

// runtime/lwjgl/vulkan — tier 1 for Vulkan, beside runtime/lwjgl/2 and /3: what the tracked backend needs from
// LWJGL's Vulkan bindings, with no Minecraft type. Today the GLSL compiler: shaderc and SPIRV-Cross, the way
// Minecraft 26.2 compiles its own shaders.
//
// PINNED TO dep.lwjgl3.vulkan (3.4.1), the oldest LWJGL a 26.2+ client ships, as runtime/lwjgl/3 is pinned to
// the oldest its clients ship: compileOnly, since hosted it runs on Minecraft's copy.

plugins {
    `java-library`
}

group = providers.gradleProperty("modGroup").orElse("com.crystalgraphics").get()
version = providers.gradleProperty("modVersion").orElse("1.0.0").get()
base { archivesName.set("crystalgraphics-vulkan") }

// An abstract module: Java 25, with a Java 8 copy for every consumer below it. @see cgbuildlogic.abstractModule
abstractModule("com/crystalgraphics/jvmdg/vulkan")

repositories {
    mavenCentral()
}

val lwjglVulkan = providers.gradleProperty("dep.lwjgl3.vulkan").getOrElse("3.4.1")
val natives = System.getProperty("os.name").lowercase().let { os ->
    when {
        os.contains("win") -> "natives-windows"
        os.contains("mac") -> if (System.getProperty("os.arch") == "aarch64") "natives-macos-arm64" else "natives-macos"
        else -> "natives-linux"
    }
}

dependencies {
    compileOnly(project(":platform"))
    compileOnly("org.lwjgl:lwjgl:$lwjglVulkan")
    compileOnly("org.lwjgl:lwjgl-shaderc:$lwjglVulkan")
    compileOnly("org.lwjgl:lwjgl-spvc:$lwjglVulkan")

    testImplementation(project(":platform"))
    // The engine itself, for EngineOnTrackedBackendTest. Its own tests carry LWJGL 2, which this cannot.
    testImplementation(project(":core"))
    testImplementation("org.joml:joml:${rootProject.properties["dep.joml"]}")
    testRuntimeOnly("org.apache.logging.log4j:log4j-core:2.26.1")
    testImplementation("junit:junit:${rootProject.properties["dep.junit"]}")
    for (module in listOf("lwjgl", "lwjgl-shaderc", "lwjgl-spvc")) {
        testImplementation("org.lwjgl:$module:$lwjglVulkan")
        testRuntimeOnly("org.lwjgl:$module:$lwjglVulkan:$natives")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// The natives load through System.load, which JDK 25 warns about unless native access is granted.
tasks.test { jvmArgs("--enable-native-access=ALL-UNNAMED") }

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
            error("Minecraft or loader imports found in runtime/lwjgl/vulkan/ - tier 1 is LWJGL and the JDK only:\n" +
                violations.joinToString("\n") { "  ${it.relativeTo(JFile(srcRoot))}" })
        }
    }
}
