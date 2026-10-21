import cgbuildlogic.abstractModule
import java.io.File as JFile

// runtime/lwjgl/vulkan — tier 1 for Vulkan, beside runtime/lwjgl/2 and /3: what the tracked backend needs from
// LWJGL's Vulkan bindings, with no Minecraft type. The GLSL compiler (shaderc and SPIRV-Cross, the way Minecraft
// 26.2 compiles its own shaders) and CgVulkanDevice with its hosts.
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
    // HostedVulkanHost's warnings. compileOnly, as in :platform: every host supplies its own log4j.
    compileOnly("org.apache.logging.log4j:log4j-api:${rootProject.properties["dep.log4j"]}")
    compileOnly("org.lwjgl:lwjgl:$lwjglVulkan")
    compileOnly("org.lwjgl:lwjgl-shaderc:$lwjglVulkan")
    compileOnly("org.lwjgl:lwjgl-spvc:$lwjglVulkan")
    compileOnly("org.lwjgl:lwjgl-vulkan:$lwjglVulkan")
    compileOnly("org.lwjgl:lwjgl-vma:$lwjglVulkan")
    // OwnedVulkanHost's window surface; hosted, the host has made its own.
    compileOnly("org.lwjgl:lwjgl-glfw:$lwjglVulkan")

    testImplementation(project(":platform"))
    // The engine itself, for EngineOnTrackedBackendTest. Its own tests carry LWJGL 2, which this cannot.
    testImplementation(project(":core"))
    testImplementation("org.joml:joml:${rootProject.properties["dep.joml"]}")
    testRuntimeOnly("org.apache.logging.log4j:log4j-core:2.26.1")
    // Asset files are YAML (CgFormats.YAML): core's consumerApi does not reach this test classpath.
    testRuntimeOnly("org.yaml:snakeyaml:2.7")
    testImplementation("junit:junit:${rootProject.properties["dep.junit"]}")
    for (module in listOf("lwjgl", "lwjgl-shaderc", "lwjgl-spvc")) {
        testImplementation("org.lwjgl:$module:$lwjglVulkan")
        testRuntimeOnly("org.lwjgl:$module:$lwjglVulkan:$natives")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// -PkitPorts=<namespace>=<dir>;...: each dir's files as <namespace>:shaders/..., where a kit's importer puts them.
val kitPorts: Map<String, String> = providers.gradleProperty("kitPorts").orNull.orEmpty().split(";")
    .map { it.trim() }.filter { it.isNotEmpty() }
    .associate { entry ->
        val at = entry.indexOf('=')
        require(at > 0) { "-PkitPorts takes <namespace>=<dir>, not '$entry'" }
        entry.substring(0, at) to entry.substring(at + 1)
    }
val kitPortsRoot = layout.buildDirectory.dir("kit-ports")
val stageKitPorts = tasks.register<Sync>("stageKitPorts") {
    into(kitPortsRoot)
    kitPorts.forEach { (namespace, dir) -> from(dir) { into("assets/$namespace/shaders") } }
}

// The natives load through System.load, which JDK 25 warns about unless native access is granted.
tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // Each class installs its own backend under the engine's static buffers and fences: one JVM a class.
    forkEvery = 1
    // A kit's ported programs, linked when asked (KitProgramsLinkTest): staged under their namespace, as a kit loads them.
    if (kitPorts.isNotEmpty()) {
        dependsOn(stageKitPorts)
        systemProperty("crystalgraphics.kits.ports", kitPorts.keys.joinToString(";"))
        systemProperty("crystalgraphics.kits.portsRoot", kitPortsRoot.get().asFile.absolutePath)
        systemProperty("crystalgraphics.resourceOverrideDirs", kitPortsRoot.get().asFile.absolutePath)
    }
}

// -Ptest.bytecode=8: the tests against core's and platform's Java 8 copies, the bytecode the shipped jars carry,
// on the same JVM: what an allocation proof must also pass on.
(findProperty("test.bytecode") as String?)?.toInt()?.let { level ->
    configurations.named("testRuntimeClasspath") {
        attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, level) }
    }
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
            error("Minecraft or loader imports found in runtime/lwjgl/vulkan/ - tier 1 is LWJGL and the JDK only:\n" +
                violations.joinToString("\n") { "  ${it.relativeTo(JFile(srcRoot))}" })
        }
    }
}
